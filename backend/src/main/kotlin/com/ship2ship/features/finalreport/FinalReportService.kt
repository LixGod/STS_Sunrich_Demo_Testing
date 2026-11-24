package com.ship2ship.features.finalreport

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.jobs.JobLookup
import com.ship2ship.core.jobs.JobTechnicalRef
import com.ship2ship.core.persistence.UuidV7
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
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.Locale
import java.util.UUID

/** Job access for the final report: the job must be visible (JOB.READ) and the report permission held for its branch. */
@Component
class ReportAccess(private val jobs: JobLookup, private val policy: AccessPolicy) {
    fun job(id: UUID, permission: String): JobTechnicalRef {
        val job = jobs.technical(id) ?: throw NotFoundException("Job")
        policy.requireBranch(Permissions.JOB_READ, job.branchId, "JOB", id)
        policy.requireBranch(permission, job.branchId, "JOB", id)
        if (!permission.endsWith(".READ")) jobs.requireWritable(id)
        return job
    }

    fun can(job: JobTechnicalRef, permission: String) = policy.currentUser().scopeFor(permission).coversBranch(job.branchId)

    fun actor(): UUID = policy.currentUser().id
}

/**
 * Reads the job's records for the report (job, vessels, cargo, Q88 references, 4b events, cargo readings, delays,
 * incidents, the issued SOF) with SQL only, so no other feature is imported.
 */
@Component
class ReportRecords(private val jdbc: JdbcClient, private val references: ReferenceLookup, private val clock: Clock) {

    fun snapshot(job: JobTechnicalRef, content: ReportContent): ReportSnapshot {
        val branchZone = jdbc.sql("SELECT timezone FROM branch WHERE id=:id").param("id", job.branchId.bytes())
            .query(String::class.java).single()
        val zone = ZoneId.of(branchZone)
        val clientId = jdbc.sql("SELECT client_id FROM sts_job WHERE id=:id").param("id", job.id.bytes()).query { rs, _ -> rs.getBytes(1).uuid() }.single()
        val client = clientId?.let { references.clients(listOf(it))[it]?.legalName }
        val vesselRefs = references.vessels(job.vessels.map { it.vesselId })
        val vesselTypes = job.vessels.associate { v ->
            v.vesselId to jdbc.sql("SELECT vessel_type FROM vessel WHERE id=:id").param("id", v.vesselId.bytes()).query(String::class.java)
                .optional().orElse(null)
        }
        val vesselName = job.vessels.associate { it.id to (vesselRefs[it.vesselId]?.name ?: it.role) }
        val ordered = job.vessels.sortedBy { if (it.role == "MOTHER") 0 else 1 }
        val grades = references.cargoGrades(job.cargo.map { it.cargoGradeId })
        val units = references.units(job.cargo.map { it.unitId })
        return ReportSnapshot(
            jobNumber = job.jobNumber, client = client, timezone = zone.id,
            operationType = job.operationType,
            vessels = ordered.map { SnapVessel(it.role, vesselRefs[it.vesselId]?.name, vesselRefs[it.vesselId]?.imo, vesselTypes[it.vesselId]) },
            cargo = job.cargo.sortedBy { it.lineNo }.map {
                SnapCargo(grades[it.cargoGradeId]?.name, grades[it.cargoGradeId]?.code, it.quantity.plain(), units[it.unitId]?.code)
            },
            q88 = ordered.mapNotNull { v -> q88(v.id, v.vesselId)?.let { (name, at) -> SnapQ88(vesselRefs[v.vesselId]?.name, vesselRefs[v.vesselId]?.imo, name, at) } },
            events = events(job, vesselName),
            grades = grades(job),
            delays = delays(job),
            incidents = incidents(job),
            sof = sof(job),
            attachments = attachments(job, content.attachmentDocumentIds),
            takenAt = clock.instant().truncatedTo(ChronoUnit.MICROS),
        )
    }

    /** Time of the latest change to the job's events (to warn when the issued SOF predates it). */
    fun lastEventChange(job: JobTechnicalRef): Instant? =
        jdbc.sql("SELECT MAX(updated_at) FROM operation_event WHERE job_id=:job").param("job", job.id.bytes())
            .query { rs, _ -> rs.getObject(1, LocalDateTime::class.java).instant() }.list().firstOrNull()

    fun isJobDocument(job: JobTechnicalRef, documentId: UUID): Boolean {
        val owner = jdbc.sql("SELECT owner_type,owner_id FROM document_version WHERE id=:id").param("id", documentId.bytes())
            .query { rs, _ -> rs.getString(1) to rs.getBytes(2).uuid() }.optional().orElse(null) ?: return false
        return (owner.first == "JOB" && owner.second == job.id) || (owner.first == "JOB_VESSEL" && job.vessels.any { it.id == owner.second })
    }

    private fun q88(jobVesselId: UUID, vesselId: UUID): Pair<String, Instant>? =
        jdbc.sql("""SELECT COALESCE(d.title, f.original_name), d.uploaded_at FROM document_version d
            JOIN document_type t ON t.id=d.document_type_id JOIN stored_file f ON f.id=d.file_id
            WHERE t.code='Q88' AND ((d.owner_type='VESSEL' AND d.owner_id=:vessel) OR (d.owner_type='JOB_VESSEL' AND d.owner_id=:jv))
            ORDER BY d.uploaded_at DESC LIMIT 1""").param("vessel", vesselId.bytes()).param("jv", jobVesselId.bytes())
            .query { rs, _ -> rs.getString(1) to rs.getObject(2, LocalDateTime::class.java).instant()!! }.optional().orElse(null)

    private fun events(job: JobTechnicalRef, vesselName: Map<UUID, String>): List<SnapEvent> =
        jdbc.sql("""SELECT t.code,t.phase,t.label,e.job_vessel_id,e.occurred_at,e.started_at,e.ended_at,e.remarks FROM operation_event e
            JOIN operation_event_type t ON t.code=e.type_code WHERE e.job_id=:job AND e.status='ACTIVE'
            ORDER BY COALESCE(e.occurred_at,e.started_at), e.recorded_at""").param("job", job.id.bytes()).query { rs, _ ->
            SnapEvent(rs.getString(2), rs.getString(3), rs.getBytes(4).uuid()?.let(vesselName::get), rs.getObject(5, LocalDateTime::class.java).instant(),
                rs.getObject(6, LocalDateTime::class.java).instant(), rs.getObject(7, LocalDateTime::class.java).instant(), rs.getString(8),
                code = rs.getString(1))
        }.list()

    private fun grades(job: JobTechnicalRef): List<SnapGrade> {
        val lines = jdbc.sql("SELECT id,cargo_grade_id,quantity,unit_id FROM job_cargo WHERE job_id=:job ORDER BY line_no").param("job", job.id.bytes())
            .query { rs, _ -> listOf(rs.getBytes(1).uuid()!!, rs.getBytes(2).uuid()!!, rs.getBigDecimal(3), rs.getBytes(4).uuid()!!) }.list()
        val grades = references.cargoGrades(lines.map { it[1] as UUID })
        val units = references.units(lines.map { it[3] as UUID })
        return lines.map { l ->
            val rows = jdbc.sql("SELECT read_at,cumulative,kind,remarks FROM cargo_reading WHERE job_cargo_id=:id ORDER BY read_at, recorded_at")
                .param("id", (l[0] as UUID).bytes()).query { rs, _ ->
                    Triple(rs.getObject(1, LocalDateTime::class.java).instant()!!, rs.getBigDecimal(2).plain(), rs.getString(3) to rs.getString(4))
                }.list()
            val readings = rows.mapIndexed { i, (at, value, kindRemarks) ->
                val previous = rows.getOrNull(i - 1)
                val since = previous?.let { value - it.second }
                val hours = previous?.let { BigDecimal(Duration.between(it.first, at).toMinutes()).divide(BigDecimal(60), 6, RoundingMode.HALF_UP) }
                val rate = if (since != null && hours != null && hours.signum() > 0 && kindRemarks.first != "CORRECTION") {
                    since.divide(hours, 3, RoundingMode.HALF_UP).plain()
                } else null
                SnapReading(at, value, kindRemarks.first, since?.plain(), rate, kindRemarks.second)
            }
            val grade = grades[l[1] as UUID]
            SnapGrade(grade?.name, grade?.code, units[l[3] as UUID]?.code, (l[2] as BigDecimal).plain(), readings.lastOrNull()?.cumulative,
                readings.lastOrNull()?.kind == "FINAL", readings)
        }
    }

    private fun delays(job: JobTechnicalRef): List<SnapDelay> =
        jdbc.sql("""SELECT c.label,d.started_at,d.ended_at,d.description FROM job_delay d JOIN delay_category c ON c.code=d.category_code
            WHERE d.job_id=:job AND d.status='ACTIVE' ORDER BY d.started_at""").param("job", job.id.bytes()).query { rs, _ ->
            SnapDelay(rs.getString(1), rs.getObject(2, LocalDateTime::class.java).instant()!!, rs.getObject(3, LocalDateTime::class.java).instant(), rs.getString(4))
        }.list()

    private fun incidents(job: JobTechnicalRef): List<SnapIncident> =
        jdbc.sql("""SELECT c.label,s.label,i.occurred_at,i.description,i.status,i.closing_note FROM job_incident i
            JOIN incident_category c ON c.code=i.category_code JOIN incident_severity s ON s.code=i.severity_code
            WHERE i.job_id=:job ORDER BY i.occurred_at""").param("job", job.id.bytes()).query { rs, _ ->
            SnapIncident(rs.getString(1), rs.getString(2), rs.getObject(3, LocalDateTime::class.java).instant()!!, rs.getString(4), rs.getString(5), rs.getString(6))
        }.list()

    private fun sof(job: JobTechnicalRef): SnapSof? =
        jdbc.sql("SELECT revision_no,issued_at,pdf_sha256 FROM sof_revision WHERE job_id=:job AND status='ISSUED' ORDER BY revision_no DESC LIMIT 1")
            .param("job", job.id.bytes()).query { rs, _ -> SnapSof(rs.getInt(1), rs.getObject(2, LocalDateTime::class.java).instant(), rs.getString(3)) }
            .optional().orElse(null)

    private fun attachments(job: JobTechnicalRef, ids: List<UUID>): List<SnapAttachment> = ids.distinct().mapNotNull { id ->
        jdbc.sql("""SELECT COALESCE(d.title, f.original_name), t.name FROM document_version d JOIN stored_file f ON f.id=d.file_id
            JOIN document_type t ON t.id=d.document_type_id WHERE d.id=:id""").param("id", id.bytes())
            .query { rs, _ -> SnapAttachment(id, rs.getString(1), rs.getString(2)) }.optional().orElse(null)
    }
}

/**
 * Final operation report of a job (FR-REP-01): revisions prepared from the job's approved records plus entered content,
 * submitted when nothing blocks them, approved by someone other than the submitter, issued as an immutable PDF.
 * The generated summary states only what the records show; it never claims "no incidents" unless the logs hold none.
 */
@Service
class FinalReportService(
    private val jdbc: JdbcClient,
    private val access: ReportAccess,
    private val records: ReportRecords,
    private val users: UserDirectory,
    private val output: FinalReportOutput,
    private val json: JsonMapper,
    private val audit: AuditService,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun overview(jobId: UUID): ReportOverviewDto {
        val job = access.job(jobId, Permissions.REPORT_FINAL_READ)
        val revisions = revisionsOf(jobId)
        return ReportOverviewDto(jobId, job.jobNumber, revisions.map { ReportRevisionSummaryDto(it.id, it.revisionNo, it.status, it.issuedAt) },
            access.can(job, Permissions.REPORT_FINAL_WRITE) && revisions.none { it.status in OPEN })
    }

    @Transactional(readOnly = true)
    fun get(jobId: UUID, id: UUID): ReportRevisionDto {
        val job = access.job(jobId, Permissions.REPORT_FINAL_READ)
        return toDto(job, row(jobId, id))
    }

    @Transactional
    fun create(jobId: UUID): ReportRevisionDto {
        val job = access.job(jobId, Permissions.REPORT_FINAL_WRITE)
        jdbc.sql("SELECT id FROM sts_job WHERE id=:id FOR UPDATE").param("id", jobId.bytes()).query { rs, _ -> rs.getBytes(1) }.single()
        val reportId = jdbc.sql("SELECT id FROM final_report WHERE job_id=:job").param("job", jobId.bytes()).query { rs, _ -> rs.getBytes(1).uuid()!! }
            .optional().orElse(null) ?: UuidV7.generate().also {
            jdbc.sql("INSERT INTO final_report (id,job_id,created_at,created_by) VALUES (:id,:job,:t,:a)").param("id", it.bytes())
                .param("job", jobId.bytes()).param("t", now()).param("a", access.actor().bytes()).update()
        }
        val existing = revisionsOf(jobId)
        if (existing.any { it.status in OPEN }) throw ConflictException("REPORT_REVISION_OPEN", "Finish the open report revision first.")
        val content = existing.firstOrNull()?.content ?: ReportContent().let { it.copy(summary = summary(records.snapshot(job, it))) }
        val id = UuidV7.generate()
        val no = (existing.maxOfOrNull { it.revisionNo } ?: 0) + 1
        jdbc.sql("""INSERT INTO final_report_revision (id,report_id,revision_no,status,template_version,content_json,created_at,created_by,updated_at,
            updated_by,version) VALUES (:id,:report,:no,'DRAFT',:tpl,:content,:t,:a,:t,:a,0)""")
            .param("id", id.bytes()).param("report", reportId.bytes()).param("no", no).param("tpl", TEMPLATE_VERSION)
            .param("content", json.writeValueAsString(content)).param("t", now()).param("a", access.actor().bytes()).update()
        audit.record(AuditEntry(AuditAction.CREATE, ENTITY, id, job.branchId,
            mapOf("jobId" to jobId, "revisionNo" to no, "copiedFrom" to existing.firstOrNull()?.revisionNo)))
        return toDto(job, row(jobId, id))
    }

    @Transactional
    fun update(jobId: UUID, id: UUID, expectedVersion: Long, content: ReportContent): ReportRevisionDto {
        val job = access.job(jobId, Permissions.REPORT_FINAL_WRITE)
        val revision = locked(jobId, id, expectedVersion)
        requireStatus(revision, ReportStatus.DRAFT)
        val clean = validate(job, content)
        jdbc.sql("UPDATE final_report_revision SET content_json=:c,updated_at=:t,updated_by=:a,version=version+1 WHERE id=:id")
            .param("c", json.writeValueAsString(clean)).param("t", now()).param("a", access.actor().bytes()).param("id", id.bytes()).update()
        audit.record(AuditEntry(AuditAction.UPDATE, ENTITY, id, job.branchId, mapOf("revisionNo" to revision.revisionNo)))
        return toDto(job, row(jobId, id))
    }

    /** Replaces the summary with a new draft from the current records (draft revisions only). */
    @Transactional
    fun regenerateSummary(jobId: UUID, id: UUID, expectedVersion: Long): ReportRevisionDto {
        val job = access.job(jobId, Permissions.REPORT_FINAL_WRITE)
        val revision = locked(jobId, id, expectedVersion)
        requireStatus(revision, ReportStatus.DRAFT)
        val content = revision.content.copy(summary = summary(records.snapshot(job, revision.content)))
        jdbc.sql("UPDATE final_report_revision SET content_json=:c,updated_at=:t,updated_by=:a,version=version+1 WHERE id=:id")
            .param("c", json.writeValueAsString(content)).param("t", now()).param("a", access.actor().bytes()).param("id", id.bytes()).update()
        audit.record(AuditEntry(AuditAction.UPDATE, ENTITY, id, job.branchId, mapOf("summary" to "regenerated")))
        return toDto(job, row(jobId, id))
    }

    @Transactional
    fun submit(jobId: UUID, id: UUID, expectedVersion: Long): ReportRevisionDto {
        val job = access.job(jobId, Permissions.REPORT_FINAL_WRITE)
        val revision = locked(jobId, id, expectedVersion)
        requireStatus(revision, ReportStatus.DRAFT)
        val snapshot = records.snapshot(job, revision.content)
        val blockers = blockers(revision.content, snapshot)
        if (blockers.isNotEmpty()) {
            throw BadRequestException("REPORT_INCOMPLETE", "Complete the report before submitting it.", blockers.map { FieldError(it.code, "required", it.message) })
        }
        jdbc.sql("""UPDATE final_report_revision SET status='SUBMITTED',snapshot_json=:s,submitted_by=:a,submitted_at=:t,returned_reason=NULL,
            updated_at=:t,updated_by=:a,version=version+1 WHERE id=:id""").param("s", json.writeValueAsString(snapshot))
            .param("a", access.actor().bytes()).param("t", now()).param("id", id.bytes()).update()
        status(job, revision, ReportStatus.DRAFT, ReportStatus.SUBMITTED, null)
        return toDto(job, row(jobId, id))
    }

    @Transactional
    fun decide(jobId: UUID, id: UUID, expectedVersion: Long, request: ReportDecision): ReportRevisionDto {
        val job = access.job(jobId, Permissions.REPORT_FINAL_APPROVE)
        val revision = locked(jobId, id, expectedVersion)
        when (request.decision) {
            "APPROVE" -> {
                requireStatus(revision, ReportStatus.SUBMITTED)
                if (revision.submittedBy == access.actor()) {
                    throw BusinessRuleException("SELF_APPROVAL_FORBIDDEN", "A report cannot be approved by the person who submitted it.")
                }
                jdbc.sql("UPDATE final_report_revision SET status='APPROVED',decided_by=:a,decided_at=:t,updated_at=:t,version=version+1 WHERE id=:id")
                    .param("a", access.actor().bytes()).param("t", now()).param("id", id.bytes()).update()
                status(job, revision, ReportStatus.SUBMITTED, ReportStatus.APPROVED, null)
            }
            "RETURN" -> {
                if (revision.status !in setOf(ReportStatus.SUBMITTED, ReportStatus.APPROVED)) {
                    throw BusinessRuleException("INVALID_TRANSITION", "Only a submitted or approved report can be returned.")
                }
                val reason = request.reason?.trim().orEmpty()
                if (reason.isEmpty()) throw BadRequestException("REASON_REQUIRED", "A reason is required for this change.", listOf(FieldError("reason", "required", null)))
                jdbc.sql("""UPDATE final_report_revision SET status='DRAFT',returned_reason=:r,snapshot_json=NULL,decided_by=NULL,decided_at=NULL,
                    updated_at=:t,version=version+1 WHERE id=:id""").param("r", reason.take(500)).param("t", now()).param("id", id.bytes()).update()
                status(job, revision, revision.status, ReportStatus.DRAFT, reason)
            }
            else -> throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", listOf(FieldError("decision", "allowed", "APPROVE or RETURN.")))
        }
        return toDto(job, row(jobId, id))
    }

    @Transactional
    fun issue(jobId: UUID, id: UUID, expectedVersion: Long): ReportRevisionDto {
        val job = access.job(jobId, Permissions.REPORT_FINAL_WRITE)
        val revision = locked(jobId, id, expectedVersion)
        requireStatus(revision, ReportStatus.APPROVED)
        jdbc.sql("UPDATE final_report_revision SET status='ISSUED',issued_by=:a,issued_at=:t,updated_at=:t,version=version+1 WHERE id=:id")
            .param("a", access.actor().bytes()).param("t", now()).param("id", id.bytes()).update()
        val issued = row(jobId, id)
        val pdf = output.pdf(document(job, issued, draft = false))
        val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf))
        jdbc.sql("UPDATE final_report_revision SET pdf=:pdf,pdf_sha256=:sha WHERE id=:id").param("pdf", pdf).param("sha", sha).param("id", id.bytes()).update()
        revisionsOf(jobId).filter { it.id != id && it.status == ReportStatus.ISSUED }.forEach { older ->
            jdbc.sql("UPDATE final_report_revision SET status='SUPERSEDED',updated_at=:t,version=version+1 WHERE id=:id")
                .param("t", now()).param("id", older.id.bytes()).update()
            status(job, older, ReportStatus.ISSUED, ReportStatus.SUPERSEDED, "Superseded by revision ${revision.revisionNo}")
        }
        status(job, revision, ReportStatus.APPROVED, ReportStatus.ISSUED, null)
        return toDto(job, row(jobId, id))
    }

    @Transactional(readOnly = true)
    fun pdf(jobId: UUID, id: UUID): Pair<String, ByteArray> {
        val job = access.job(jobId, Permissions.REPORT_FINAL_READ)
        val revision = row(jobId, id)
        val stored = if (revision.status in FROZEN) jdbc.sql("SELECT pdf FROM final_report_revision WHERE id=:id").param("id", id.bytes())
            .query { rs, _ -> rs.getBytes(1) }.optional().orElse(null) else null
        val name = "${job.jobNumber.replace('/', '-')}-final-report-r${revision.revisionNo}${if (stored == null) "-draft" else ""}.pdf"
        return name to (stored ?: output.pdf(document(job, revision, draft = true)))
    }

    // ---------- rules ----------

    /** What still blocks submission: missing information only, never a judgement. */
    internal fun blockers(c: ReportContent, s: ReportSnapshot): List<ReportIssue> = buildList {
        if (c.summary.isNullOrBlank()) add(ReportIssue("summary", "Enter the executive summary."))
        if (c.attendance.isEmpty()) add(ReportIssue("attendance", "Enter the supervision and attendance (at least one person)."))
        val open = s.grades.filter { !it.completed }.mapNotNull { it.gradeName ?: it.gradeCode }
        if (open.isNotEmpty()) add(ReportIssue("cargo", "Record the final reading for: ${open.joinToString()}."))
        if (s.sof == null) add(ReportIssue("sof", "Issue the Statement of Facts first."))
    }

    internal fun warnings(job: JobTechnicalRef, s: ReportSnapshot, frozen: Boolean): List<ReportIssue> = buildList {
        val open = s.incidents.count { it.status != "CLOSED" }
        if (open > 0) add(ReportIssue("incidentsOpen", "$open incident(s) not closed yet; they are printed as open."))
        val ongoing = s.delays.count { it.endedAt == null }
        if (ongoing > 0) add(ReportIssue("delaysOngoing", "$ongoing delay(s) still ongoing."))
        if (!frozen) {
            val last = records.lastEventChange(job)
            if (s.sof?.issuedAt != null && last != null && last.isAfter(s.sof.issuedAt)) {
                add(ReportIssue("sofOutdated", "Events changed after the issued Statement of Facts; consider issuing a new SOF revision."))
            }
        }
    }

    /** A factual draft from the records; edited by people before approval. */
    internal fun summary(s: ReportSnapshot): String {
        val zone = ZoneId.of(s.timezone)
        val format = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH).withZone(zone)
        fun at(code: String) = s.events.firstOrNull { it.code == code }?.let { (it.occurredAt ?: it.startedAt)?.let(format::format) }
        val lines = mutableListOf<String>()
        lines += listOfNotNull("STS operation", s.client?.let { "for $it" }).joinToString(" ") + "."
        val mother = s.vessels.firstOrNull { it.role == "MOTHER" }?.name
        val daughter = s.vessels.firstOrNull { it.role == "DAUGHTER" }?.name
        if (mother != null || daughter != null) lines += "Discharging vessel: ${mother ?: "—"}; receiving vessel: ${daughter ?: "—"}."
        if (s.cargo.isNotEmpty()) lines += "Cargo nominated: " + s.cargo.joinToString { "${it.quantity.toPlainString()} ${it.unit ?: ""} ${it.gradeName ?: it.gradeCode ?: ""}".trim() } + "."
        at("ALL_FAST")?.let { lines += "All fast at $it (${zone.id})." }
        val commenced = at("CARGO_COMMENCED")
        val completed = at("CARGO_COMPLETED")
        when {
            commenced != null && completed != null -> lines += "Cargo transfer commenced at $commenced and was completed at $completed."
            commenced != null -> lines += "Cargo transfer commenced at $commenced."
        }
        val finals = s.grades.filter { it.completed && it.finalQuantity != null }
        if (finals.isNotEmpty()) lines += "Final quantities: " + finals.joinToString { "${it.gradeName ?: it.gradeCode} ${it.finalQuantity!!.toPlainString()} ${it.unit ?: ""}".trim() } + "."
        at("CAST_OFF")?.let { lines += "All lines cast off at $it." }
        lines += if (s.incidents.isEmpty() && s.delays.isEmpty()) "No incidents or delays were recorded in the operations log."
        else "${s.incidents.size} incident(s) and ${s.delays.size} delay(s) were recorded in the operations log; see the sections below."
        return lines.joinToString("\n")
    }

    private fun validate(job: JobTechnicalRef, c: ReportContent): ReportContent {
        val errors = mutableListOf<FieldError>()
        if ((c.vhfChannel?.length ?: 0) > 50) errors += FieldError("vhfChannel", "size", "At most 50 characters.")
        if ((c.summary?.length ?: 0) > 8000) errors += FieldError("summary", "size", "At most 8000 characters.")
        if ((c.remarks?.length ?: 0) > 8000) errors += FieldError("remarks", "size", "At most 8000 characters.")
        if (c.equipment.size > 30) errors += FieldError("equipment", "size", "At most 30 rows.")
        c.equipment.forEachIndexed { i, e ->
            if (e.category !in EQUIPMENT) errors += FieldError("equipment[$i].category", "allowed", null)
            if (e.description.isBlank() || e.description.length > 200) errors += FieldError("equipment[$i].description", "required", null)
            if (e.quantity != null && e.quantity !in 0..999) errors += FieldError("equipment[$i].quantity", "range", null)
        }
        if (c.attendance.size > 30) errors += FieldError("attendance", "size", "At most 30 rows.")
        c.attendance.forEachIndexed { i, a ->
            if (a.capacity.isBlank() || a.capacity.length > 100) errors += FieldError("attendance[$i].capacity", "required", null)
            if (a.name.isBlank() || a.name.length > 150) errors += FieldError("attendance[$i].name", "required", null)
        }
        if (c.attachmentDocumentIds.size > 30) errors += FieldError("attachmentDocumentIds", "size", "At most 30 attachments.")
        c.attachmentDocumentIds.forEachIndexed { i, id ->
            if (!records.isJobDocument(job, id)) errors += FieldError("attachmentDocumentIds[$i]", "unknown", "Choose a document of this job.")
        }
        if (errors.isNotEmpty()) throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", errors)
        fun String?.clean() = this?.trim()?.ifEmpty { null }
        return c.copy(vhfChannel = c.vhfChannel.clean(), summary = c.summary.clean(), remarks = c.remarks.clean(),
            equipment = c.equipment.map { it.copy(description = it.description.trim()) },
            attendance = c.attendance.map { it.copy(capacity = it.capacity.trim(), name = it.name.trim()) },
            attachmentDocumentIds = c.attachmentDocumentIds.distinct())
    }

    // ---------- persistence ----------

    private data class Revision(
        val id: UUID, val revisionNo: Int, val status: ReportStatus, val templateVersion: Int, val content: ReportContent, val snapshot: ReportSnapshot?,
        val submittedBy: UUID?, val submittedAt: Instant?, val decidedBy: UUID?, val decidedAt: Instant?, val returnedReason: String?,
        val issuedBy: UUID?, val issuedAt: Instant?, val pdfSha256: String?, val updatedAt: Instant?, val version: Long,
    )

    private fun toDto(job: JobTechnicalRef, r: Revision): ReportRevisionDto {
        val snapshot = r.snapshot ?: records.snapshot(job, r.content)
        val names = users.names(listOfNotNull(r.submittedBy, r.decidedBy, r.issuedBy))
        val me = access.actor()
        val write = access.can(job, Permissions.REPORT_FINAL_WRITE)
        val approve = access.can(job, Permissions.REPORT_FINAL_APPROVE)
        val actions = buildList {
            when (r.status) {
                ReportStatus.DRAFT -> if (write) addAll(listOf(ReportAction.EDIT, ReportAction.SUBMIT))
                ReportStatus.SUBMITTED -> if (approve) { if (r.submittedBy != me) add(ReportAction.APPROVE); add(ReportAction.RETURN) }
                ReportStatus.APPROVED -> { if (write) add(ReportAction.ISSUE); if (approve) add(ReportAction.RETURN) }
                else -> Unit
            }
        }
        return ReportRevisionDto(
            id = r.id, revisionNo = r.revisionNo, reference = "${job.jobNumber} · FR${r.revisionNo}", documentId = r.id, templateVersion = r.templateVersion,
            status = r.status, content = r.content, snapshot = snapshot, snapshotFrozen = r.snapshot != null,
            blockers = if (r.status == ReportStatus.DRAFT) blockers(r.content, snapshot) else emptyList(),
            warnings = warnings(job, snapshot, r.snapshot != null), sources = SOURCES,
            submittedByName = r.submittedBy?.let(names::get), submittedAt = r.submittedAt, decidedByName = r.decidedBy?.let(names::get),
            decidedAt = r.decidedAt, returnedReason = r.returnedReason, issuedByName = r.issuedBy?.let(names::get), issuedAt = r.issuedAt,
            pdfSha256 = r.pdfSha256, allowedActions = actions, updatedAt = r.updatedAt, version = r.version,
        )
    }

    private fun document(job: JobTechnicalRef, r: Revision, draft: Boolean): ReportDocument {
        val snapshot = r.snapshot ?: records.snapshot(job, r.content)
        val names = users.names(listOfNotNull(r.decidedBy))
        return ReportDocument.build("${job.jobNumber} · FR${r.revisionNo}", r.id, r.templateVersion, r.content, snapshot,
            r.decidedBy?.let(names::get), r.decidedAt, r.issuedAt, draft)
    }

    private fun revisionsOf(jobId: UUID): List<Revision> =
        jdbc.sql("SELECT r.id FROM final_report_revision r JOIN final_report f ON f.id=r.report_id WHERE f.job_id=:job ORDER BY r.revision_no DESC")
            .param("job", jobId.bytes()).query { rs, _ -> rs.getBytes(1).uuid()!! }.list().map(::load)

    private fun row(jobId: UUID, id: UUID): Revision {
        val owner = jdbc.sql("SELECT f.job_id FROM final_report_revision r JOIN final_report f ON f.id=r.report_id WHERE r.id=:id")
            .param("id", id.bytes()).query { rs, _ -> rs.getBytes(1).uuid() }.optional().orElse(null)
        if (owner != jobId) throw NotFoundException("Final report revision")
        return load(id)
    }

    private fun locked(jobId: UUID, id: UUID, expectedVersion: Long): Revision {
        row(jobId, id)
        jdbc.sql("SELECT id FROM final_report_revision WHERE id=:id FOR UPDATE").param("id", id.bytes()).query { rs, _ -> rs.getBytes(1) }.single()
        return load(id).also { Versioning.checkCurrent(expectedVersion, it.version) }
    }

    private fun load(id: UUID): Revision =
        jdbc.sql("""SELECT revision_no,status,template_version,content_json,snapshot_json,submitted_by,submitted_at,decided_by,decided_at,returned_reason,
            issued_by,issued_at,pdf_sha256,updated_at,version FROM final_report_revision WHERE id=:id""").param("id", id.bytes()).query { rs, _ ->
            fun t(i: Int) = rs.getObject(i, LocalDateTime::class.java).instant()
            Revision(id, rs.getInt(1), ReportStatus.valueOf(rs.getString(2)), rs.getInt(3), json.readValue(rs.getString(4), ReportContent::class.java),
                rs.getString(5)?.let { json.readValue(it, ReportSnapshot::class.java) }, rs.getBytes(6).uuid(), t(7), rs.getBytes(8).uuid(), t(9),
                rs.getString(10), rs.getBytes(11).uuid(), t(12), rs.getString(13), t(14), rs.getLong(15))
        }.optional().orElseThrow { NotFoundException("Final report revision") }

    private fun requireStatus(r: Revision, expected: ReportStatus) {
        if (r.status != expected) throw BusinessRuleException("INVALID_TRANSITION", "A report revision in status ${r.status} cannot do this.")
    }

    private fun status(job: JobTechnicalRef, r: Revision, from: ReportStatus, to: ReportStatus, reason: String?) =
        audit.record(AuditEntry(AuditAction.STATUS_CHANGE, ENTITY, r.id, job.branchId,
            mapOf("jobId" to job.id, "revisionNo" to r.revisionNo, "from" to from.name, "to" to to.name, "reason" to reason)))

    private fun now() = clock.instant().truncatedTo(ChronoUnit.MICROS).stamp()

    companion object {
        private const val ENTITY = "FINAL_REPORT_REVISION"
        const val TEMPLATE_VERSION = 1
        val OPEN = setOf(ReportStatus.DRAFT, ReportStatus.SUBMITTED, ReportStatus.APPROVED)
        val FROZEN = setOf(ReportStatus.ISSUED, ReportStatus.SUPERSEDED)
        val EQUIPMENT = setOf("FENDER", "HOSE", "OTHER")
        /** Entered on the report until 4a's allocations and personnel are committed. */
        val SOURCES = mapOf("equipment" to "MANUAL", "attendance" to "MANUAL")
    }
}
