package com.ship2ship.features.technical

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.jobs.JobTechnicalRef
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.core.resources.ResourceAllocationLookup
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.security.ReferenceLookup
import com.ship2ship.core.security.UserDirectory
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.BusinessRuleException
import com.ship2ship.core.web.ConflictException
import com.ship2ship.core.web.FieldError
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.Versioning
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Joint Plan of Operation (FR-TECH-02): one JPO per job with revisions. A revision is prepared from the job's data
 * (vessels, cargo, verified voyage values, checklists) plus structured entries, submitted when nothing blocks it,
 * approved by someone other than the submitter, issued with an immutable PDF, and accepted by the masters and the STS
 * Superintendent. Job data is frozen at submission so the approved plan does not change underneath the approver.
 * Sections whose modules do not exist yet (mooring plan 3d, operating limits 3e, equipment and support craft in
 * Phase 4) are entered on the JPO and marked MANUAL. The app never fills in limits, equipment or marine values.
 */
@Service
class JpoService(
    private val jdbc: JdbcClient,
    private val access: JobTechnicalAccess,
    private val policy: AccessPolicy,
    private val references: ReferenceLookup,
    private val users: UserDirectory,
    private val voyage: JobVoyageService,
    private val annex: AnnexTemplateService,
    private val output: JpoOutput,
    private val mooring: MooringPlanService,
    private val limits: OperatingLimitService,
    private val weather: WeatherReportService,
    private val resources: ResourceAllocationLookup,
    private val json: JsonMapper,
    private val audit: AuditService,
    private val clock: Clock,
) {

    // ---------- reads ----------

    @Transactional(readOnly = true)
    fun overview(jobId: UUID): JpoOverviewDto {
        val job = access.job(jobId, Permissions.TECH_JPO_READ)
        val revisions = revisionsOf(jobId)
        return JpoOverviewDto(jobId, job.jobNumber,
            revisions.map { JpoRevisionSummaryDto(it.id, it.revisionNo, it.status, it.issuedAt, it.acceptances.size) },
            canCreate = can(job, Permissions.TECH_JPO_WRITE) && revisions.none { it.status in OPEN })
    }

    @Transactional(readOnly = true)
    fun get(jobId: UUID, revisionId: UUID): JpoRevisionDto {
        val job = access.job(jobId, Permissions.TECH_JPO_READ)
        return toDto(job, row(jobId, revisionId))
    }

    @Transactional(readOnly = true)
    fun pdf(jobId: UUID, revisionId: UUID): Pair<String, ByteArray> {
        val job = access.job(jobId, Permissions.TECH_JPO_READ)
        val revision = row(jobId, revisionId)
        val stored = if (revision.status in FROZEN_PDF) storedPdf(revisionId) else null
        val bytes = stored ?: output.pdf(document(job, revision, draft = true))
        return "${job.jobNumber.replace('/', '-')}-JPO-r${revision.revisionNo}${if (stored == null) "-draft" else ""}.pdf" to bytes
    }

    @Transactional(readOnly = true)
    fun xlsx(jobId: UUID, revisionId: UUID): Pair<String, ByteArray> {
        val job = access.job(jobId, Permissions.TECH_JPO_READ)
        val revision = row(jobId, revisionId)
        return "${job.jobNumber.replace('/', '-')}-JPO-r${revision.revisionNo}.xlsx" to
            output.xlsx(document(job, revision, draft = revision.status !in FROZEN_PDF))
    }

    // ---------- preparing ----------

    /** A new draft revision: a copy of the newest revision's entries, or a first draft pre-filled from the job. */
    @Transactional
    fun create(jobId: UUID): JpoRevisionDto {
        val job = access.job(jobId, Permissions.TECH_JPO_WRITE)
        lockJob(jobId)
        val jpoId = jdbc.sql("SELECT id FROM jpo WHERE job_id=:job").param("job", jobId.bytes())
            .query { rs, _ -> rs.getBytes(1).uuid()!! }.optional().orElse(null) ?: UuidV7.generate().also { id ->
            jdbc.sql("INSERT INTO jpo (id,job_id,created_at,created_by,updated_at,updated_by,version) VALUES (:id,:job,:t,:a,:t,:a,0)")
                .param("id", id.bytes()).param("job", jobId.bytes()).param("t", now()).param("a", actor().bytes()).update()
        }
        val existing = revisionsOf(jobId)
        if (existing.any { it.status in OPEN }) {
            throw ConflictException("JPO_REVISION_OPEN", "Finish the open JPO revision first.")
        }
        val content = existing.firstOrNull()?.content ?: firstDraft(job)
        val id = UuidV7.generate()
        val number = (existing.maxOfOrNull { it.revisionNo } ?: 0) + 1
        jdbc.sql("""INSERT INTO jpo_revision (id,jpo_id,revision_no,status,content_json,created_at,created_by,updated_at,updated_by,version)
            VALUES (:id,:jpo,:no,'DRAFT',:content,:t,:a,:t,:a,0)""")
            .param("id", id.bytes()).param("jpo", jpoId.bytes()).param("no", number).param("content", json.writeValueAsString(content))
            .param("t", now()).param("a", actor().bytes()).update()
        audit.record(AuditEntry(AuditAction.CREATE, ENTITY, id, job.branchId,
            mapOf("jobId" to jobId, "revisionNo" to number, "copiedFrom" to existing.firstOrNull()?.revisionNo)))
        return toDto(job, row(jobId, id))
    }

    @Transactional
    fun update(jobId: UUID, revisionId: UUID, expectedVersion: Long, content: JpoContent): JpoRevisionDto {
        val job = access.job(jobId, Permissions.TECH_JPO_WRITE)
        val revision = locked(jobId, revisionId, expectedVersion)
        requireStatus(revision, JpoStatus.DRAFT)
        val clean = validate(job, content)
        jdbc.sql("UPDATE jpo_revision SET content_json=:content,updated_at=:t,updated_by=:a,version=version+1 WHERE id=:id")
            .param("content", json.writeValueAsString(clean)).param("t", now()).param("a", actor().bytes()).param("id", revisionId.bytes()).update()
        audit.record(AuditEntry(AuditAction.UPDATE, ENTITY, revisionId, job.branchId, mapOf("revisionNo" to revision.revisionNo)))
        return toDto(job, row(jobId, revisionId))
    }

    @Transactional
    fun submit(jobId: UUID, revisionId: UUID, expectedVersion: Long): JpoRevisionDto {
        val job = access.job(jobId, Permissions.TECH_JPO_WRITE)
        val revision = locked(jobId, revisionId, expectedVersion)
        requireStatus(revision, JpoStatus.DRAFT)
        val snapshot = snapshot(job)
        val wording = annex.currentForJpo()
        val blockers = blockers(revision.content, snapshot)
        if (blockers.isNotEmpty()) {
            throw BadRequestException("JPO_INCOMPLETE", "Complete the JPO before marking it done.",
                blockers.map { FieldError(it.code, "required", it.message) })
        }
        val frozen = snapshot.copy(annexVersion = wording?.versionNo,
            annexSections = wording?.sections.orEmpty().filter { it.body.isNotBlank() && applies(it.appliesWhen, revision.content) })
        jdbc.sql("""UPDATE jpo_revision SET status='DONE',snapshot_json=:snapshot,annex_template_id=NULL,submitted_by=:a,
            submitted_at=:t,issued_by=:a,issued_at=:t,returned_reason=NULL,updated_at=:t,updated_by=:a,version=version+1 WHERE id=:id""")
            .param("snapshot", json.writeValueAsString(frozen))
            .param("a", actor().bytes()).param("t", now()).param("id", revisionId.bytes()).update()
        val done = row(jobId, revisionId)
        val bytes = output.pdf(document(job, done, draft = false))
        val sha = JpoOutput.sha256(bytes)
        jdbc.sql("UPDATE jpo_revision SET pdf=:pdf,pdf_sha256=:sha WHERE id=:id").param("pdf", bytes).param("sha", sha)
            .param("id", revisionId.bytes()).update()
        revisionsOf(jobId).filter { it.id != revisionId && it.status in setOf(JpoStatus.DONE, JpoStatus.ISSUED) }.forEach { older ->
            jdbc.sql("UPDATE jpo_revision SET status='SUPERSEDED',updated_at=:t,version=version+1 WHERE id=:id")
                .param("t", now()).param("id", older.id.bytes()).update()
            status(job, older, older.status, JpoStatus.SUPERSEDED, "Superseded by revision ${revision.revisionNo}")
        }
        status(job, revision, JpoStatus.DRAFT, JpoStatus.DONE, null, mapOf("pdfSha256" to sha))
        return toDto(job, row(jobId, revisionId))
    }

    // ---------- approval ----------

    @Transactional
    fun approve(jobId: UUID, revisionId: UUID, expectedVersion: Long): JpoRevisionDto {
        val job = access.job(jobId, Permissions.TECH_JPO_APPROVE)
        val revision = locked(jobId, revisionId, expectedVersion)
        requireStatus(revision, JpoStatus.SUBMITTED)
        if (revision.submittedBy == actor()) {
            throw BusinessRuleException("SELF_APPROVAL_FORBIDDEN", "A JPO cannot be approved by the person who submitted it.")
        }
        jdbc.sql("UPDATE jpo_revision SET status='APPROVED',decided_by=:a,decided_at=:t,updated_at=:t,version=version+1 WHERE id=:id")
            .param("a", actor().bytes()).param("t", now()).param("id", revisionId.bytes()).update()
        status(job, revision, JpoStatus.SUBMITTED, JpoStatus.APPROVED, null)
        return toDto(job, row(jobId, revisionId))
    }

    /** Back to draft with a reason; the frozen job data is released and taken again at the next submission. */
    @Transactional
    fun returnForCorrection(jobId: UUID, revisionId: UUID, expectedVersion: Long, request: JpoReturnRequest): JpoRevisionDto {
        val job = access.job(jobId, Permissions.TECH_JPO_APPROVE)
        val revision = locked(jobId, revisionId, expectedVersion)
        if (revision.status !in setOf(JpoStatus.SUBMITTED, JpoStatus.APPROVED)) {
            throw BusinessRuleException("INVALID_TRANSITION", "Only a submitted or approved JPO can be returned.")
        }
        val reason = request.reason.trim()
        if (reason.isEmpty()) throw BadRequestException("REASON_REQUIRED", "A reason is required for this change.", listOf(FieldError("reason", "required", null)))
        jdbc.sql("""UPDATE jpo_revision SET status='DRAFT',returned_reason=:reason,snapshot_json=NULL,annex_template_id=NULL,
            decided_by=NULL,decided_at=NULL,updated_at=:t,version=version+1 WHERE id=:id""")
            .param("reason", reason.take(500)).param("t", now()).param("id", revisionId.bytes()).update()
        status(job, revision, revision.status, JpoStatus.DRAFT, reason)
        return toDto(job, row(jobId, revisionId))
    }

    // ---------- issue and acceptance ----------

    @Transactional
    fun issue(jobId: UUID, revisionId: UUID, expectedVersion: Long): JpoRevisionDto {
        val job = access.job(jobId, Permissions.TECH_JPO_WRITE)
        val revision = locked(jobId, revisionId, expectedVersion)
        requireStatus(revision, JpoStatus.APPROVED)
        jdbc.sql("UPDATE jpo_revision SET status='ISSUED',issued_by=:a,issued_at=:t,updated_at=:t,version=version+1 WHERE id=:id")
            .param("a", actor().bytes()).param("t", now()).param("id", revisionId.bytes()).update()
        val issued = row(jobId, revisionId)
        val bytes = output.pdf(document(job, issued, draft = false))
        val sha = JpoOutput.sha256(bytes)
        jdbc.sql("UPDATE jpo_revision SET pdf=:pdf,pdf_sha256=:sha WHERE id=:id").param("pdf", bytes).param("sha", sha)
            .param("id", revisionId.bytes()).update()
        revisionsOf(jobId).filter { it.id != revisionId && it.status == JpoStatus.ISSUED }.forEach { older ->
            jdbc.sql("UPDATE jpo_revision SET status='SUPERSEDED',updated_at=:t,version=version+1 WHERE id=:id")
                .param("t", now()).param("id", older.id.bytes()).update()
            status(job, older, JpoStatus.ISSUED, JpoStatus.SUPERSEDED, "Superseded by revision ${revision.revisionNo}")
        }
        status(job, revision, JpoStatus.APPROVED, JpoStatus.ISSUED, null, mapOf("pdfSha256" to sha))
        return toDto(job, row(jobId, revisionId))
    }

    /** Records one party's acceptance of the issued plan (discharging master, receiving master, STS Superintendent). */
    @Transactional
    fun accept(jobId: UUID, revisionId: UUID, request: JpoAcceptanceRequest): JpoRevisionDto {
        val job = access.job(jobId, Permissions.TECH_JPO_WRITE)
        val revision = locked(jobId, revisionId, null)
        if (revision.status !in setOf(JpoStatus.DONE, JpoStatus.ISSUED)) {
            throw BusinessRuleException("INVALID_TRANSITION", "Only a completed JPO can record acceptances.")
        }
        val errors = mutableListOf<FieldError>()
        if (request.party !in PARTIES) errors += FieldError("party", "allowed", "Choose the accepting party.")
        if (request.personName.isBlank() || request.personName.length > 150) errors += FieldError("personName", "required", "Enter the name (max 150).")
        val acceptedAt = request.acceptedAt
        if (acceptedAt == null) errors += FieldError("acceptedAt", "required", null)
        else {
            if (acceptedAt.isAfter(clock.instant().plus(5, ChronoUnit.MINUTES))) errors += FieldError("acceptedAt", "future", "Cannot be in the future.")
            if (revision.issuedAt != null && acceptedAt.isBefore(revision.issuedAt.truncatedTo(ChronoUnit.MINUTES))) {
                errors += FieldError("acceptedAt", "beforeIssue", "Cannot be before the JPO was issued.")
            }
        }
        request.signedDocumentId?.let { if (!isJobDocument(job, it)) errors += FieldError("signedDocumentId", "unknown", "Choose a document of this job.") }
        if (errors.isNotEmpty()) throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", errors)
        if (revision.acceptances.any { it.party == request.party }) {
            throw ConflictException("ALREADY_RECORDED", "This party's acceptance is already recorded.")
        }
        jdbc.sql("""INSERT INTO jpo_acceptance (id,revision_id,party,person_name,accepted_at,signed_document_id,recorded_by,recorded_at)
            VALUES (:id,:rev,:party,:name,:at,:doc,:a,:t)""")
            .param("id", UuidV7.generate().bytes()).param("rev", revisionId.bytes()).param("party", request.party)
            .param("name", request.personName.trim()).param("at", Uuids.toTimestamp(acceptedAt!!.truncatedTo(ChronoUnit.MICROS)))
            .param("doc", request.signedDocumentId?.bytes()).param("a", actor().bytes()).param("t", now()).update()
        jdbc.sql("UPDATE jpo_revision SET version=version+1 WHERE id=:id").param("id", revisionId.bytes()).update()
        audit.record(AuditEntry(AuditAction.UPDATE, ENTITY, revisionId, job.branchId,
            mapOf("acceptance" to request.party, "personName" to request.personName.trim(), "signedDocumentId" to request.signedDocumentId)))
        return toDto(job, row(jobId, revisionId))
    }

    // ---------- job data ----------

    /** Job facts as they are now: vessels, cargo, latest verified voyage values, checklists and review warnings. */
    internal fun snapshot(job: JobTechnicalRef): JpoSnapshot {
        val vesselRefs = references.vessels(job.vessels.map { it.vesselId })
        val grades = references.cargoGrades(job.cargo.map { it.cargoGradeId })
        val units = references.units(job.cargo.map { it.unitId })
        val voyageIds = job.vessels.associate { v ->
            v.role to jdbc.sql("""SELECT id FROM job_voyage_version WHERE job_vessel_id=:v AND status='VERIFIED' ORDER BY version_no DESC LIMIT 1""")
                .param("v", v.id.bytes()).query { rs, _ -> rs.getBytes(1).uuid()!! }.optional().orElse(null)
        }
        val voyageValues = job.vessels.flatMap { v ->
            val id = voyageIds[v.role] ?: return@flatMap emptyList()
            jdbc.sql("""SELECT f.code,f.label,f.unit,x.value_text,x.source_document_id,x.source_page FROM job_voyage_value x
                JOIN voyage_field f ON f.code=x.field_code WHERE x.version_id=:id AND x.verified=TRUE ORDER BY f.sort_order""")
                .param("id", id.bytes()).query { rs, _ ->
                    SnapshotVoyageValue(v.role, rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBytes(5).uuid(),
                        rs.getInt(6).takeUnless { rs.wasNull() })
                }.list()
        }
        val roleOf = job.vessels.associate { it.id to it.role }
        val checklists = jdbc.sql("""SELECT r.job_vessel_id,t.code,t.name,r.status FROM job_checklist_response r
            JOIN checklist_template t ON t.id=r.template_id WHERE r.job_id=:job ORDER BY t.code,r.job_vessel_id""")
            .param("job", job.id.bytes()).query { rs, _ ->
                SnapshotChecklist(rs.getBytes(1).uuid()?.let(roleOf::get), rs.getString(2), rs.getString(3), rs.getString(4))
            }.list()
        val warnings = voyage.review(job.id).warnings.map { w -> listOfNotNull(w.vesselRole?.let { roleName(it) }, w.message).joinToString(": ") } +
            job.vessels.flatMap { v ->
                val present = voyageValues.filter { it.role == v.role }.map { it.fieldCode }.toSet()
                val missing = JPO_VOYAGE_FIELDS.filter { it !in present }
                if (voyageIds[v.role] != null && missing.isNotEmpty()) {
                    listOf("${roleName(v.role)}: verified values missing for ${missing.joinToString { it.lowercase().replace('_', ' ') }}.")
                } else emptyList()
            } +
            checklists.filter { it.status != "RECORDED" }.map { "Checklist ${it.name}${it.role?.let { r -> " (${roleName(r)})" } ?: ""} is not recorded yet." } +
            weather.warningMessages(job)
        val approvedLimits = limits.approved(job.id)
        return JpoSnapshot(
            jobNumber = job.jobNumber,
            operationType = job.operationType,
            vessels = job.vessels.sortedBy { if (it.role == "MOTHER") 0 else 1 }.map {
                SnapshotVessel(it.id, it.role, vesselRefs[it.vesselId]?.name, vesselRefs[it.vesselId]?.imo, it.etaUtc, it.etaTimezone)
            },
            cargo = job.cargo.map { SnapshotCargo(grades[it.cargoGradeId]?.code, grades[it.cargoGradeId]?.name, it.quantity, units[it.unitId]?.code) },
            voyage = voyageValues,
            voyageVersionIds = voyageIds,
            checklists = checklists,
            warnings = warnings,
            takenAt = clock.instant().truncatedTo(ChronoUnit.MICROS),
            linkedMooring = mooring.issuedForJpo(job.id),
            operatingLimits = approvedLimits?.second,
            operatingLimitsRevision = approvedLimits?.first,
            weatherReports = weather.acceptedReports(job.id),
            allocatedResources = resources.activeForJob(job.id),
        )
    }

    /** What still blocks submission; nothing here is a safety judgement, only missing information. */
    internal fun blockers(content: JpoContent, snapshot: JpoSnapshot): List<JpoBlocker> = buildList {
        fun need(ok: Boolean, code: String, message: String) { if (!ok) add(JpoBlocker(code, message)) }
        need(content.operationDate != null, "operationDate", "Enter the date of the operation.")
        need(content.mooringDiagramDocumentId != null || snapshot.linkedMooring != null, "mooringDiagramDocumentId", "Attach a job mooring diagram or issue a mooring plan.")
        need(!content.shipHandling.isNullOrBlank(), "shipHandling", "Choose the agreed method of ship handling.")
        need(!content.approach.isNullOrBlank(), "approach", "Choose the vessel approach.")
        need(content.operatingLimits.isNotEmpty() || !snapshot.operatingLimits.isNullOrEmpty(), "operatingLimits",
            "Approve the job's operating limits, or enter the agreed limits for separation on the JPO.")
        need(!content.securityLevel.isNullOrBlank(), "securityLevel", "Choose the security level.")
        need(!content.contingency.isNullOrBlank(), "contingency", "Enter the contingency plans.")
        need(!content.personnelTransfer.isNullOrBlank(), "personnelTransfer", "Enter the personnel transfer method.")
        need(content.equipment.any { it.category == "CARGO_HOSE" && (it.number ?: 0) > 0 } ||
            snapshot.allocatedResources.any { it.kind == "ASSET" && it.category == "CARGO_HOSE" },
            "equipment.CARGO_HOSE", "Enter or allocate the cargo hoses supplied.")
        need(content.equipment.any { it.category == "PRIMARY_FENDER" && (it.number ?: 0) > 0 } ||
            snapshot.allocatedResources.any { it.kind == "ASSET" && it.category == "PRIMARY_FENDER" },
            "equipment.PRIMARY_FENDER", "Enter or allocate the primary fenders supplied.")
        snapshot.vessels.forEach { v ->
            need(snapshot.voyageVersionIds[v.role] != null, "voyage.${v.role}", "${roleName(v.role)}: no verified voyage-condition values (drafts and freeboards).")
        }
    }

    // ---------- helpers ----------

    internal data class Revision(
        val id: UUID, val revisionNo: Int, val status: JpoStatus, val content: JpoContent, val snapshot: JpoSnapshot?,
        val annexTemplateId: UUID?, val submittedBy: UUID?, val submittedAt: Instant?, val decidedBy: UUID?, val decidedAt: Instant?,
        val returnedReason: String?, val issuedBy: UUID?, val issuedAt: Instant?, val pdfSha256: String?, val acceptances: List<JpoAcceptanceRow>,
        val updatedAt: Instant?, val version: Long,
    )

    internal data class JpoAcceptanceRow(val party: String, val personName: String, val acceptedAt: Instant, val signedDocumentId: UUID?,
        val recordedBy: UUID, val recordedAt: Instant)

    private fun toDto(job: JobTechnicalRef, r: Revision): JpoRevisionDto {
        val snapshot = r.snapshot ?: snapshot(job)
        val annexVersion = r.annexTemplateId?.let(annex::version) ?: if (r.status == JpoStatus.DRAFT) annex.currentForJpo() else null
        val names = users.names(listOfNotNull(r.submittedBy, r.decidedBy, r.issuedBy) + r.acceptances.map { it.recordedBy })
        val me = actor()
        val canWrite = can(job, Permissions.TECH_JPO_WRITE)
        val canApprove = can(job, Permissions.TECH_JPO_APPROVE)
        val actions = buildList {
            when (r.status) {
                JpoStatus.DRAFT -> if (canWrite) addAll(listOf(JpoAction.EDIT, JpoAction.SUBMIT))
                JpoStatus.SUBMITTED -> if (canApprove) {
                    if (r.submittedBy != me) add(JpoAction.APPROVE)
                    add(JpoAction.RETURN)
                }
                JpoStatus.APPROVED -> {
                    if (canWrite) add(JpoAction.ISSUE)
                    if (canApprove) add(JpoAction.RETURN)
                }
                JpoStatus.DONE, JpoStatus.ISSUED -> if (canWrite && r.acceptances.size < PARTIES.size) add(JpoAction.ACCEPT)
                JpoStatus.SUPERSEDED -> Unit
            }
        }
        return JpoRevisionDto(
            id = r.id, revisionNo = r.revisionNo, status = r.status, content = r.content, snapshot = snapshot,
            snapshotFrozen = r.snapshot != null,
            sources = SOURCES + ("mooringDiagram" to if (snapshot.linkedMooring != null) SectionSource.LINKED else SectionSource.MANUAL) +
                ("operatingLimits" to if (snapshot.operatingLimits != null) SectionSource.LINKED else SectionSource.MANUAL) +
                ("equipment" to if (snapshot.allocatedResources.any { it.kind == "ASSET" && it.category in EQUIPMENT })
                    SectionSource.LINKED else SectionSource.MANUAL) +
                ("supportCraft" to if (snapshot.allocatedResources.any { it.kind == "CRAFT" }) SectionSource.LINKED else SectionSource.MANUAL),
            blockers = if (r.status == JpoStatus.DRAFT) blockers(r.content, snapshot) else emptyList(),
            warnings = snapshot.warnings,
            annexTemplateVersion = snapshot.annexVersion ?: annexVersion?.versionNo,
            annexSections = if (r.status == JpoStatus.DRAFT || r.annexTemplateId != null)
                annexVersion?.sections.orEmpty().filter { it.body.isNotBlank() && applies(it.appliesWhen, r.content) } else snapshot.annexSections,
            submittedByName = r.submittedBy?.let(names::get), submittedAt = r.submittedAt,
            decidedByName = r.decidedBy?.let(names::get), decidedAt = r.decidedAt, returnedReason = r.returnedReason,
            issuedByName = r.issuedBy?.let(names::get), issuedAt = r.issuedAt, pdfSha256 = r.pdfSha256,
            acceptances = r.acceptances.map { JpoAcceptanceDto(it.party, it.personName, it.acceptedAt, it.signedDocumentId, names[it.recordedBy], it.recordedAt) },
            allowedActions = actions, updatedAt = r.updatedAt, version = r.version,
        )
    }

    /** Printable view of a revision (PDF and Excel use the same model). */
    private fun document(job: JobTechnicalRef, r: Revision, draft: Boolean): JpoDocument {
        val snapshot = r.snapshot ?: snapshot(job)
        val annexVersion = r.annexTemplateId?.let(annex::version) ?: if (r.status == JpoStatus.DRAFT) annex.currentForJpo() else null
        val diagrams = listOfNotNull(r.content.mooringDiagramDocumentId, r.content.unmooringDiagramDocumentId)
        val documentFiles = documentFiles(diagrams + r.acceptances.mapNotNull { it.signedDocumentId })
        val names = users.names(listOfNotNull(r.decidedBy))
        return JpoDocument.build(
            jobNumber = job.jobNumber, revisionNo = r.revisionNo, status = r.status, content = r.content, snapshot = snapshot,
            annexSections = if (r.status == JpoStatus.DRAFT || r.annexTemplateId != null)
                annexVersion?.sections.orEmpty().filter { applies(it.appliesWhen, r.content) && it.body.isNotBlank() } else snapshot.annexSections,
            annexVersion = snapshot.annexVersion ?: annexVersion?.versionNo,
            approvedBy = r.decidedBy?.let(names::get), approvedAt = r.decidedAt, issuedAt = r.issuedAt,
            acceptances = r.acceptances, documentFiles = documentFiles, draft = draft,
            zone = ZoneId.of(jdbc.sql("SELECT timezone FROM branch WHERE id=:id").param("id", job.branchId.bytes())
                .query(String::class.java).single()),
        )
    }

    /** Job documents by id → file id and name, for diagrams and signed copies. */
    private fun documentFiles(ids: List<UUID>): Map<UUID, Pair<UUID, String>> =
        ids.distinct().mapNotNull { id ->
            jdbc.sql("SELECT d.file_id,f.original_name FROM document_version d JOIN stored_file f ON f.id=d.file_id WHERE d.id=:id")
                .param("id", id.bytes()).query { rs, _ -> id to (rs.getBytes(1).uuid()!! to rs.getString(2)) }.optional().orElse(null)
        }.toMap()

    private fun validate(job: JobTechnicalRef, c: JpoContent): JpoContent {
        val errors = mutableListOf<FieldError>()
        fun text(field: String, value: String?, max: Int = 2000) { if (value != null && value.length > max) errors += FieldError(field, "size", "At most $max characters.") }
        fun choice(field: String, value: String?, allowed: Set<String>) { if (value != null && value !in allowed) errors += FieldError(field, "allowed", null) }
        fun number(field: String, value: BigDecimal?, max: BigDecimal) {
            if (value != null && (value.signum() < 0 || value > max || value.scale() > 2)) errors += FieldError(field, "range", "Between 0 and $max, at most 2 decimals.")
        }
        listOf("purpose" to c.purpose, "mooringNotes" to c.mooringNotes, "constantHeadingCourseSpeed" to c.constantHeadingCourseSpeed,
            "manoeuvringCourseSpeed" to c.manoeuvringCourseSpeed, "rudderType" to c.rudderType, "constantHeadingDeadSlow" to c.constantHeadingDeadSlow,
            "speedMonitoring" to c.speedMonitoring, "rendezvous" to c.rendezvous, "transferLocation" to c.transferLocation,
            "approachNotes" to c.approachNotes, "supportCraft" to c.supportCraft, "additionalRequirements" to c.additionalRequirements,
            "mooringRopeFailure" to c.mooringRopeFailure, "contingency" to c.contingency, "personnelTransfer" to c.personnelTransfer,
            "oilBoom" to c.oilBoom, "unmooringNotes" to c.unmooringNotes).forEach { (f, v) -> text(f, v) }
        choice("shipHandling", c.shipHandling, setOf("HELM_ORDERS", "COURSE_ORDERS"))
        choice("handedness", c.handedness, setOf("LEFT", "RIGHT"))
        choice("constantHeadingPropulsion", c.constantHeadingPropulsion, PROPULSION)
        choice("manoeuvringPropulsion", c.manoeuvringPropulsion, PROPULSION)
        choice("constantHeadingDpMode", c.constantHeadingDpMode, DP_MODES)
        choice("manoeuvringDpMode", c.manoeuvringDpMode, DP_MODES)
        choice("manoeuvringDeadSlowCondition", c.manoeuvringDeadSlowCondition, setOf("BALLAST", "LADEN"))
        choice("approach", c.approach, setOf("AT_ANCHOR", "UNDERWAY", "WITH_TUGS"))
        choice("securityLevel", c.securityLevel, setOf("LEVEL_1", "LEVEL_2", "LEVEL_3"))
        number("maxRudderAngleDeg", c.maxRudderAngleDeg, BigDecimal(90))
        number("manoeuvringDeadSlowKn", c.manoeuvringDeadSlowKn, BigDecimal(50))
        if (c.operatingLimits.size > 30) errors += FieldError("operatingLimits", "size", "At most 30 rows.")
        c.operatingLimits.forEachIndexed { i, l ->
            if (l.parameter.isBlank() || l.parameter.length > 100) errors += FieldError("operatingLimits[$i].parameter", "required", null)
            if (l.limit.isBlank() || l.limit.length > 100) errors += FieldError("operatingLimits[$i].limit", "required", null)
            if ((l.unit?.length ?: 0) > 20) errors += FieldError("operatingLimits[$i].unit", "size", null)
            if (l.action.isBlank() || l.action.length > 300) errors += FieldError("operatingLimits[$i].action", "required", null)
        }
        val grades = references.cargoGrades(job.cargo.map { it.cargoGradeId }).values.map { it.code }.toSet()
        c.cargoRates.forEachIndexed { i, rate ->
            if (rate.cargoGradeCode !in grades) errors += FieldError("cargoRates[$i].cargoGradeCode", "unknown", "Not a cargo grade of this job.")
            number("cargoRates[$i].maxRatePerLine", rate.maxRatePerLine, BigDecimal(1_000_000))
            text("cargoRates[$i].rateUnit", rate.rateUnit, 20)
        }
        if (c.equipment.size > 20) errors += FieldError("equipment", "size", "At most 20 rows.")
        c.equipment.forEachIndexed { i, e ->
            choice("equipment[$i].category", e.category, EQUIPMENT)
            if (e.number != null && e.number !in 0..999) errors += FieldError("equipment[$i].number", "range", null)
            if (e.strings != null && e.strings !in 0..99) errors += FieldError("equipment[$i].strings", "range", null)
            text("equipment[$i].dimensions", e.dimensions, 200)
        }
        if (c.ancillary.size > 30) errors += FieldError("ancillary", "size", "At most 30 rows.")
        c.ancillary.forEachIndexed { i, a ->
            if (a.item.isBlank() || a.item.length > 100) errors += FieldError("ancillary[$i].item", "required", null)
            text("ancillary[$i].value", a.value, 200)
        }
        listOf("mooringDiagramDocumentId" to c.mooringDiagramDocumentId, "unmooringDiagramDocumentId" to c.unmooringDiagramDocumentId).forEach { (f, id) ->
            if (id != null && !isJobDocument(job, id)) errors += FieldError(f, "unknown", "Choose a document of this job.")
        }
        if (errors.isNotEmpty()) throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", errors)
        fun String?.clean() = this?.trim()?.ifBlank { null }
        return c.copy(
            purpose = c.purpose.clean(), mooringNotes = c.mooringNotes.clean(), constantHeadingCourseSpeed = c.constantHeadingCourseSpeed.clean(),
            manoeuvringCourseSpeed = c.manoeuvringCourseSpeed.clean(), rudderType = c.rudderType.clean(),
            constantHeadingDeadSlow = c.constantHeadingDeadSlow.clean(), speedMonitoring = c.speedMonitoring.clean(),
            rendezvous = c.rendezvous.clean(), transferLocation = c.transferLocation.clean(), approachNotes = c.approachNotes.clean(),
            supportCraft = c.supportCraft.clean(), additionalRequirements = c.additionalRequirements.clean(),
            mooringRopeFailure = c.mooringRopeFailure.clean(), contingency = c.contingency.clean(),
            personnelTransfer = c.personnelTransfer.clean(), oilBoom = c.oilBoom.clean(), unmooringNotes = c.unmooringNotes.clean(),
            operatingLimits = c.operatingLimits.map { it.copy(parameter = it.parameter.trim(), limit = it.limit.trim(), unit = it.unit.clean(), action = it.action.trim()) },
            ancillary = c.ancillary.map { it.copy(item = it.item.trim(), value = it.value.clean()) },
        )
    }

    /** First draft from the job: date from the mother vessel's ETA (local), its cargo grades, and the empty equipment rows. */
    private fun firstDraft(job: JobTechnicalRef): JpoContent {
        val mother = job.vessels.firstOrNull { it.role == "MOTHER" }
        val date = mother?.etaUtc?.let { eta -> eta.atZone(mother.etaTimezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of("UTC")).toLocalDate() }
        val grades = references.cargoGrades(job.cargo.map { it.cargoGradeId })
        return JpoContent(
            operationDate = date,
            cargoRates = job.cargo.mapNotNull { grades[it.cargoGradeId]?.code }.map { CargoRate(it) },
            equipment = EQUIPMENT_ORDER.map { EquipmentRow(it) },
            ancillary = ANCILLARY_ITEMS.map { AncillaryRow(it) },
        )
    }

    private fun isJobDocument(job: JobTechnicalRef, documentId: UUID): Boolean {
        val owner = jdbc.sql("SELECT owner_type,owner_id FROM document_version WHERE id=:id").param("id", documentId.bytes())
            .query { rs, _ -> rs.getString(1) to rs.getBytes(2).uuid() }.optional().orElse(null) ?: return false
        return (owner.first == "JOB" && owner.second == job.id) || (owner.first == "JOB_VESSEL" && job.vessels.any { it.id == owner.second })
    }

    private fun revisionsOf(jobId: UUID): List<Revision> =
        jdbc.sql("SELECT r.id FROM jpo_revision r JOIN jpo j ON j.id=r.jpo_id WHERE j.job_id=:job ORDER BY r.revision_no DESC")
            .param("job", jobId.bytes()).query { rs, _ -> rs.getBytes(1).uuid()!! }.list().map { load(it) }

    private fun row(jobId: UUID, revisionId: UUID): Revision {
        val owner = jdbc.sql("SELECT j.job_id FROM jpo_revision r JOIN jpo j ON j.id=r.jpo_id WHERE r.id=:id").param("id", revisionId.bytes())
            .query { rs, _ -> rs.getBytes(1).uuid() }.optional().orElse(null)
        if (owner != jobId) throw NotFoundException("JPO revision")
        return load(revisionId)
    }

    /** Locks the revision row, then checks the version the user edited (null = no If-Match for this action). */
    private fun locked(jobId: UUID, revisionId: UUID, expectedVersion: Long?): Revision {
        row(jobId, revisionId)
        jdbc.sql("SELECT id FROM jpo_revision WHERE id=:id FOR UPDATE").param("id", revisionId.bytes()).query { rs, _ -> rs.getBytes(1) }.single()
        val revision = load(revisionId)
        expectedVersion?.let { Versioning.checkCurrent(it, revision.version) }
        return revision
    }

    private fun load(id: UUID): Revision {
        val acceptances = jdbc.sql("""SELECT party,person_name,accepted_at,signed_document_id,recorded_by,recorded_at FROM jpo_acceptance
            WHERE revision_id=:id ORDER BY recorded_at""").param("id", id.bytes()).query { rs, _ ->
            JpoAcceptanceRow(rs.getString(1), rs.getString(2), ts(rs.getObject(3, LocalDateTime::class.java))!!, rs.getBytes(4).uuid(),
                rs.getBytes(5).uuid()!!, ts(rs.getObject(6, LocalDateTime::class.java))!!)
        }.list()
        return jdbc.sql("""SELECT revision_no,status,content_json,snapshot_json,annex_template_id,submitted_by,submitted_at,decided_by,
            decided_at,returned_reason,issued_by,issued_at,pdf_sha256,updated_at,version FROM jpo_revision WHERE id=:id""")
            .param("id", id.bytes()).query { rs, _ ->
                Revision(id, rs.getInt(1), JpoStatus.valueOf(rs.getString(2)), json.readValue(rs.getString(3), JpoContent::class.java),
                    rs.getString(4)?.let { json.readValue(it, JpoSnapshot::class.java) }, rs.getBytes(5).uuid(), rs.getBytes(6).uuid(),
                    ts(rs.getObject(7, LocalDateTime::class.java)), rs.getBytes(8).uuid(), ts(rs.getObject(9, LocalDateTime::class.java)),
                    rs.getString(10), rs.getBytes(11).uuid(), ts(rs.getObject(12, LocalDateTime::class.java)), rs.getString(13), acceptances,
                    ts(rs.getObject(14, LocalDateTime::class.java)), rs.getLong(15))
            }.optional().orElseThrow { NotFoundException("JPO revision") }
    }

    private fun storedPdf(id: UUID): ByteArray? =
        jdbc.sql("SELECT pdf FROM jpo_revision WHERE id=:id").param("id", id.bytes()).query { rs, _ -> rs.getBytes(1) }.optional().orElse(null)

    private fun requireStatus(r: Revision, expected: JpoStatus) {
        if (r.status != expected) throw BusinessRuleException("INVALID_TRANSITION", "A JPO revision in status ${r.status} cannot do this.")
    }

    private fun status(job: JobTechnicalRef, r: Revision, from: JpoStatus, to: JpoStatus, reason: String?, extra: Map<String, Any?> = emptyMap()) =
        audit.record(AuditEntry(AuditAction.STATUS_CHANGE, ENTITY, r.id, job.branchId,
            mapOf("jobId" to job.id, "revisionNo" to r.revisionNo, "from" to from.name, "to" to to.name, "reason" to reason) + extra))

    private fun lockJob(jobId: UUID) {
        jdbc.sql("SELECT id FROM sts_job WHERE id=:id FOR UPDATE").param("id", jobId.bytes()).query { rs, _ -> rs.getBytes(1) }.single()
    }

    private fun can(job: JobTechnicalRef, permission: String) = policy.currentUser().scopeFor(permission).coversBranch(job.branchId)

    private fun actor() = policy.currentUser().id

    private fun now() = Uuids.toTimestamp(clock.instant().truncatedTo(ChronoUnit.MICROS))

    private fun ts(value: LocalDateTime?): Instant? = value?.let(Uuids::fromTimestamp)

    companion object {
        private const val ENTITY = "JPO_REVISION"
        val OPEN = setOf(JpoStatus.DRAFT, JpoStatus.SUBMITTED, JpoStatus.APPROVED)
        val FROZEN_PDF = setOf(JpoStatus.DONE, JpoStatus.ISSUED, JpoStatus.SUPERSEDED)
        val PARTIES = listOf("DISCHARGING_MASTER", "RECEIVING_MASTER", "STS_SUPERINTENDENT")
        val PROPULSION = setOf("CPP", "TELEGRAPH")
        val DP_MODES = setOf("NOT_DP", "DP", "SEAGOING")
        val EQUIPMENT_ORDER = listOf("CARGO_HOSE", "VAPOUR_HOSE", "PRIMARY_FENDER", "SECONDARY_FENDER")
        val EQUIPMENT = EQUIPMENT_ORDER.toSet()
        /** Ancillary equipment rows of the JPO workbook (page 4). */
        val ANCILLARY_ITEMS = listOf("Gaskets", "SS bolts and nuts", "Rope protectors", "Quick-release hooks", "Portable chocks",
            "Torque wrench", "Reducers", "Insulation block (LPG only)")
        /** Voyage values page 3 shows: drafts and freeboards at arrival and departure. */
        val JPO_VOYAGE_FIELDS = listOf("ARRIVAL_DRAFT_FWD", "ARRIVAL_DRAFT_AFT", "DEPARTURE_DRAFT_FWD", "DEPARTURE_DRAFT_AFT",
            "ARRIVAL_FREEBOARD_FWD", "ARRIVAL_FREEBOARD_AFT", "DEPARTURE_FREEBOARD_FWD", "DEPARTURE_FREEBOARD_AFT")
        /** Sections entered on the JPO until their own module exists. */
        val SOURCES = mapOf(
            "mooringDiagram" to SectionSource.MANUAL, // Mooring Plan Designer, 3d
            "operatingLimits" to SectionSource.MANUAL, // weather and operating limits, 3e
            "equipment" to SectionSource.MANUAL, // resource allocation, Phase 4
            "supportCraft" to SectionSource.MANUAL, // resource allocation, Phase 4
        )

        fun applies(condition: String, content: JpoContent): Boolean = when (condition) {
            "REVERSE_LIGHTERING" -> content.reverseLightering
            "SIMILAR_LOA" -> content.similarLoaBerthing
            else -> true
        }

        fun roleName(role: String) = if (role == "MOTHER") "Discharging vessel" else if (role == "DAUGHTER") "Receiving vessel" else role
    }
}
