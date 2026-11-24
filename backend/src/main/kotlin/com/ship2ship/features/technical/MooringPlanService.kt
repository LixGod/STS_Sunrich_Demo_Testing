package com.ship2ship.features.technical

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.audit.Uuids
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
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

@Service
class MooringPlanService(
    private val jdbc: JdbcClient,
    private val access: JobTechnicalAccess,
    private val policy: AccessPolicy,
    private val references: ReferenceLookup,
    private val users: UserDirectory,
    private val validator: MooringPlanValidator,
    private val output: MooringPlanOutput,
    private val json: JsonMapper,
    private val audit: AuditService,
    private val clock: Clock,
) {
    internal data class Row(
        val id: UUID, val number: Int, val status: MooringStatus, val model: MooringModel, val snapshot: MooringSnapshot?,
        val submittedBy: UUID?, val submittedAt: Instant?, val decidedBy: UUID?, val decidedAt: Instant?,
        val returnedReason: String?, val issuedBy: UUID?, val issuedAt: Instant?, val pdfSha256: String?, val version: Long,
    )

    @Transactional(readOnly = true)
    fun overview(jobId: UUID): MooringOverviewDto {
        val job = access.job(jobId, Permissions.TECH_MOORING_READ)
        val revisions = revisions(jobId)
        return MooringOverviewDto(jobId, job.jobNumber,
            revisions.map { MooringRevisionSummary(it.id, it.number, it.status, it.issuedAt) },
            can(job, Permissions.TECH_MOORING_WRITE) && revisions.none { it.status in OPEN })
    }

    @Transactional(readOnly = true)
    fun get(jobId: UUID, revisionId: UUID): MooringRevisionDto {
        val job = access.job(jobId, Permissions.TECH_MOORING_READ)
        return dto(job, row(jobId, revisionId))
    }

    /** Called only after the JPO has already checked this job's scope. */
    @Transactional(readOnly = true)
    fun issuedForJpo(jobId: UUID): LinkedMooringPlan? = revisions(jobId)
        .firstOrNull { it.status == MooringStatus.ISSUED }
        ?.let { r -> r.snapshot?.let { LinkedMooringPlan(r.id, r.number, r.model, it) } }

    @Transactional(readOnly = true)
    fun pdf(jobId: UUID, revisionId: UUID): Pair<String, ByteArray> {
        val job = access.job(jobId, Permissions.TECH_MOORING_READ)
        val r = row(jobId, revisionId)
        val stored = if (r.status in setOf(MooringStatus.ISSUED, MooringStatus.SUPERSEDED))
            jdbc.sql("SELECT pdf FROM mooring_plan_revision WHERE id=:id").param("id", r.id.bytes())
                .query { rs, _ -> rs.getBytes(1) }.optional().orElse(null) else null
        val names = users.names(listOfNotNull(r.decidedBy, r.issuedBy))
        val bytes = stored ?: output.pdf(job.jobNumber, r.number, r.status, r.model, r.snapshot ?: snapshot(job, r.id, r.model),
            r.decidedBy?.let(names::get), r.decidedAt, r.issuedBy?.let(names::get), r.issuedAt)
        return "${job.jobNumber.replace('/', '-')}-Mooring-r${r.number}${if (stored == null) "-draft" else ""}.pdf" to bytes
    }

    @Transactional
    fun create(jobId: UUID): MooringRevisionDto {
        val job = access.job(jobId, Permissions.TECH_MOORING_WRITE)
        jdbc.sql("SELECT id FROM sts_job WHERE id=:id FOR UPDATE").param("id", jobId.bytes()).query { rs, _ -> rs.getBytes(1) }.single()
        val planId = jdbc.sql("SELECT id FROM mooring_plan WHERE job_id=:id").param("id", jobId.bytes())
            .query { rs, _ -> rs.getBytes(1).uuid()!! }.optional().orElse(null) ?: UuidV7.generate().also { id ->
            jdbc.sql("""INSERT INTO mooring_plan (id,job_id,created_at,created_by,updated_at,updated_by,version)
                VALUES (:id,:job,:t,:actor,:t,:actor,0)""")
                .param("id", id.bytes()).param("job", jobId.bytes()).param("t", now()).param("actor", actor().bytes()).update()
        }
        val prior = revisions(jobId)
        if (prior.any { it.status in OPEN }) throw ConflictException("MOORING_REVISION_OPEN", "Finish the open mooring revision first.")
        val model = prior.firstOrNull()?.model ?: MooringModel(
            vessels = job.vessels.map { MooringVessel(it.id) },
            layers = job.vessels.mapNotNull { vessel ->
                jdbc.sql("""SELECT d.id FROM document_version d
                    JOIN document_type t ON t.id=d.document_type_id
                    JOIN stored_file f ON f.id=d.file_id
                    WHERE ((d.owner_type='JOB_VESSEL' AND d.owner_id=:jobVessel)
                        OR (d.owner_type='VESSEL' AND d.owner_id=:masterVessel)) AND t.code='GA-PLAN'
                    AND f.content_type IN ('image/png','image/jpeg')
                    ORDER BY CASE WHEN d.owner_type='JOB_VESSEL' THEN 0 ELSE 1 END, d.version_no DESC LIMIT 1""")
                    .param("jobVessel", vessel.id.bytes()).param("masterVessel", vessel.vesselId.bytes())
                    .query { rs, _ -> rs.getBytes(1).uuid()!! }
                    .optional().orElse(null)?.let { MooringLayer(UuidV7.generate(), vessel.id, it) }
            },
        )
        val id = UuidV7.generate()
        val no = (prior.maxOfOrNull { it.number } ?: 0) + 1
        jdbc.sql("""INSERT INTO mooring_plan_revision
            (id,plan_id,revision_no,status,model_json,created_at,created_by,updated_at,updated_by,version)
            VALUES (:id,:plan,:no,'DRAFT',:model,:t,:actor,:t,:actor,0)""")
            .param("id", id.bytes()).param("plan", planId.bytes()).param("no", no)
            .param("model", json.writeValueAsString(model)).param("t", now()).param("actor", actor().bytes()).update()
        audit.record(AuditEntry(AuditAction.CREATE, ENTITY, id, job.branchId,
            mapOf("jobId" to jobId, "revisionNo" to no)))
        return dto(job, row(jobId, id))
    }

    @Transactional
    fun update(jobId: UUID, revisionId: UUID, expected: Long, raw: JsonNode): MooringRevisionDto {
        val job = access.job(jobId, Permissions.TECH_MOORING_WRITE)
        val r = locked(jobId, revisionId, expected)
        requireStatus(r, MooringStatus.DRAFT)
        val model = validator.parse(raw, json)
        validator.validate(job, model)
        validateSources(job, model)
        // Verification is tied to the exact value and source. Stale verification records are removed on every edit.
        verified(r.id).forEach { (key, hash) ->
            if (valueHash(model, key) != hash) jdbc.sql("DELETE FROM mooring_plan_verified_value WHERE revision_id=:id AND value_key=:key")
                .param("id", r.id.bytes()).param("key", key).update()
        }
        jdbc.sql("UPDATE mooring_plan_revision SET model_json=:model,updated_at=:t,updated_by=:actor,version=version+1 WHERE id=:id")
            .param("model", json.writeValueAsString(model)).param("t", now()).param("actor", actor().bytes())
            .param("id", revisionId.bytes()).update()
        audit.record(AuditEntry(AuditAction.UPDATE, ENTITY, r.id, job.branchId,
            mapOf("jobId" to jobId, "revisionNo" to r.number)))
        return dto(job, row(jobId, revisionId))
    }

    @Transactional
    fun verifyValue(jobId: UUID, revisionId: UUID, expected: Long, request: MooringVerifyValueRequest): MooringRevisionDto {
        val job = access.job(jobId, Permissions.TECH_MOORING_APPROVE)
        val r = locked(jobId, revisionId, expected)
        requireStatus(r, MooringStatus.DRAFT)
        val key = request.key.trim()
        val hash = valueHash(r.model, key)
            ?: throw BadRequestException("VALIDATION_FAILED", "Choose a sourced plan value.", listOf(FieldError("key", "unknown", null)))
        val preparer = jdbc.sql("SELECT updated_by FROM mooring_plan_revision WHERE id=:id").param("id", r.id.bytes())
            .query { rs, _ -> rs.getBytes(1).uuid() }.single()
        if (preparer == actor()) throw BusinessRuleException("SELF_VERIFICATION_FORBIDDEN", "A second person must verify plan values.")
        jdbc.sql("""INSERT INTO mooring_plan_verified_value (revision_id,value_key,value_sha256,verified_by,verified_at)
            VALUES (:id,:key,:hash,:actor,:t) ON DUPLICATE KEY UPDATE value_sha256=:hash,verified_by=:actor,verified_at=:t""")
            .param("id", r.id.bytes()).param("key", key).param("hash", hash)
            .param("actor", actor().bytes()).param("t", now()).update()
        jdbc.sql("UPDATE mooring_plan_revision SET updated_at=:t,version=version+1 WHERE id=:id")
            .param("t", now()).param("id", r.id.bytes()).update()
        audit.record(AuditEntry(AuditAction.UPDATE, ENTITY, r.id, job.branchId,
            mapOf("verificationKey" to key)))
        return dto(job, row(jobId, revisionId))
    }

    @Transactional
    fun submit(jobId: UUID, revisionId: UUID, expected: Long): MooringRevisionDto {
        val job = access.job(jobId, Permissions.TECH_MOORING_WRITE)
        val r = locked(jobId, revisionId, expected)
        requireStatus(r, MooringStatus.DRAFT)
        val snapshot = snapshot(job, r.id, r.model)
        val blockers = blockers(r.model, snapshot)
        if (blockers.isNotEmpty()) throw BadRequestException("MOORING_INCOMPLETE", "Complete the mooring plan before submission.",
            blockers.map { FieldError(it.code, "required", it.message) })
        jdbc.sql("""UPDATE mooring_plan_revision SET status='SUBMITTED',snapshot_json=:snapshot,submitted_by=:actor,
            submitted_at=:t,returned_reason=NULL,updated_at=:t,updated_by=:actor,version=version+1 WHERE id=:id""")
            .param("snapshot", json.writeValueAsString(snapshot)).param("actor", actor().bytes()).param("t", now())
            .param("id", r.id.bytes()).update()
        status(job, r, MooringStatus.SUBMITTED)
        return dto(job, row(jobId, revisionId))
    }

    @Transactional
    fun decide(jobId: UUID, revisionId: UUID, expected: Long, request: MooringDecisionRequest): MooringRevisionDto {
        val job = access.job(jobId, Permissions.TECH_MOORING_APPROVE)
        val r = locked(jobId, revisionId, expected)
        if (request.decision == "APPROVE") {
            requireStatus(r, MooringStatus.SUBMITTED)
            if (r.submittedBy == actor()) throw BusinessRuleException("SELF_APPROVAL_FORBIDDEN", "A second person must approve the plan.")
            if (r.snapshot == null || blockers(r.model, r.snapshot).isNotEmpty())
                throw BadRequestException("MOORING_INCOMPLETE", "The frozen plan is incomplete.")
            jdbc.sql("UPDATE mooring_plan_revision SET status='APPROVED',decided_by=:actor,decided_at=:t,updated_at=:t,version=version+1 WHERE id=:id")
                .param("actor", actor().bytes()).param("t", now()).param("id", r.id.bytes()).update()
            status(job, r, MooringStatus.APPROVED)
        } else if (request.decision == "RETURN") {
            if (r.status !in setOf(MooringStatus.SUBMITTED, MooringStatus.APPROVED))
                throw BusinessRuleException("INVALID_TRANSITION", "Only a submitted or approved plan may be returned.")
            val reason = request.reason?.trim().orEmpty()
            if (reason.isEmpty()) throw BadRequestException("REASON_REQUIRED", "A return reason is required.",
                listOf(FieldError("reason", "required", null)))
            jdbc.sql("""UPDATE mooring_plan_revision SET status='DRAFT',snapshot_json=NULL,decided_by=NULL,decided_at=NULL,
                returned_reason=:reason,updated_at=:t,version=version+1 WHERE id=:id""")
                .param("reason", reason.take(500)).param("t", now()).param("id", r.id.bytes()).update()
            status(job, r, MooringStatus.DRAFT, reason)
        } else throw BadRequestException("VALIDATION_FAILED", "Choose APPROVE or RETURN.", listOf(FieldError("decision", "allowed", null)))
        return dto(job, row(jobId, revisionId))
    }

    @Transactional
    fun issue(jobId: UUID, revisionId: UUID, expected: Long): MooringRevisionDto {
        val job = access.job(jobId, Permissions.TECH_MOORING_WRITE)
        val r = locked(jobId, revisionId, expected)
        requireStatus(r, MooringStatus.APPROVED)
        val frozen = r.snapshot ?: throw BusinessRuleException("MOORING_INCOMPLETE", "The submitted snapshot is missing.")
        val names = users.names(listOfNotNull(r.decidedBy, actor()))
        val issueTime = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val bytes = output.pdf(job.jobNumber, r.number, MooringStatus.ISSUED, r.model, frozen,
            r.decidedBy?.let(names::get), r.decidedAt, names[actor()], issueTime)
        val hash = sha(bytes)
        jdbc.sql("""UPDATE mooring_plan_revision SET status='ISSUED',issued_by=:actor,issued_at=:t,
            pdf=:pdf,pdf_sha256=:hash,updated_at=:t,version=version+1 WHERE id=:id""")
            .param("actor", actor().bytes()).param("t", Uuids.toTimestamp(issueTime)).param("pdf", bytes).param("hash", hash)
            .param("id", r.id.bytes()).update()
        revisions(jobId).filter { it.id != r.id && it.status == MooringStatus.ISSUED }.forEach { old ->
            jdbc.sql("UPDATE mooring_plan_revision SET status='SUPERSEDED',updated_at=:t,version=version+1 WHERE id=:id")
                .param("t", now()).param("id", old.id.bytes()).update()
            status(job, old, MooringStatus.SUPERSEDED)
        }
        status(job, r, MooringStatus.ISSUED)
        return dto(job, row(jobId, revisionId))
    }

    @Transactional(readOnly = true)
    fun columns(): List<MooringCapacityColumn> {
        policy.requireAny(Permissions.MASTER_TECHNICAL_READ)
        return capacityColumns()
    }

    @Transactional
    fun defineColumn(code: String, expected: Long, request: MooringColumnRequest): MooringCapacityColumn {
        policy.requireAny(Permissions.MASTER_TECHNICAL_WRITE)
        val before = capacityColumns().firstOrNull { it.code == code } ?: throw NotFoundException("Capacity column")
        Versioning.checkCurrent(expected, before.version)
        if (request.definition.isBlank() || request.unit.isBlank() || request.evidenceRequirement.isBlank())
            throw BadRequestException("VALIDATION_FAILED", "Define meaning, unit and source requirement.")
        jdbc.sql("""UPDATE mooring_capacity_column SET definition=:definition,unit=:unit,evidence_requirement=:evidence,
            status='ACTIVE',updated_at=:t,updated_by=:actor,version=version+1 WHERE code=:code""")
            .param("definition", request.definition.trim().take(500)).param("unit", request.unit.trim().take(30))
            .param("evidence", request.evidenceRequirement.trim().take(500)).param("t", now())
            .param("actor", actor().bytes()).param("code", code).update()
        audit.record(AuditEntry(AuditAction.UPDATE, "MOORING_CAPACITY_COLUMN", null, null, mapOf("code" to code)))
        return capacityColumns().first { it.code == code }
    }

    @Transactional(readOnly = true)
    fun rule(): MooringRuleDto {
        policy.requireAny(Permissions.MASTER_TECHNICAL_READ)
        return loadRule()
    }

    @Transactional
    fun updateRule(expected: Long, request: MooringRuleRequest): MooringRuleDto {
        policy.requireAny(Permissions.MASTER_TECHNICAL_WRITE)
        Versioning.checkCurrent(expected, loadRule().version)
        if (request.active && (request.toleranceM == null || request.toleranceM.signum() < 0 || request.toleranceM.scale() > 3))
            throw BadRequestException("VALIDATION_FAILED", "Enter a non-negative tolerance in metres.",
                listOf(FieldError("toleranceM", "range", null)))
        jdbc.sql("""UPDATE mooring_plan_rule SET tolerance_m=:value,active=:active,updated_at=:t,
            updated_by=:actor,version=version+1 WHERE code='MANIFOLD_LENGTH'""")
            .param("value", request.toleranceM).param("active", request.active)
            .param("t", now()).param("actor", actor().bytes()).update()
        audit.record(AuditEntry(AuditAction.UPDATE, "MOORING_RULE", null, null,
            mapOf("code" to "MANIFOLD_LENGTH", "active" to request.active, "toleranceM" to request.toleranceM)))
        return loadRule()
    }

    private fun loadRule(): MooringRuleDto = jdbc.sql("SELECT tolerance_m,active,version FROM mooring_plan_rule WHERE code='MANIFOLD_LENGTH'")
        .query { rs, _ -> MooringRuleDto(rs.getBigDecimal(1), rs.getBoolean(2), rs.getLong(3)) }.single()

    private fun dto(job: JobTechnicalRef, r: Row): MooringRevisionDto {
        val snapshot = r.snapshot ?: snapshot(job, r.id, r.model)
        val names = users.names(listOfNotNull(r.submittedBy, r.decidedBy, r.issuedBy))
        val write = can(job, Permissions.TECH_MOORING_WRITE)
        val approve = can(job, Permissions.TECH_MOORING_APPROVE)
        val actions = buildList {
            when (r.status) {
                MooringStatus.DRAFT -> {
                    if (write) addAll(listOf(MooringAction.EDIT, MooringAction.SUBMIT))
                    if (approve) add(MooringAction.VERIFY_VALUE)
                }
                MooringStatus.SUBMITTED -> if (approve) {
                    if (r.submittedBy != actor()) add(MooringAction.APPROVE)
                    add(MooringAction.RETURN)
                }
                MooringStatus.APPROVED -> {
                    if (write) add(MooringAction.ISSUE)
                    if (approve) add(MooringAction.RETURN)
                }
                else -> Unit
            }
        }
        return MooringRevisionDto(r.id, r.number, r.status, r.model, snapshot, r.snapshot != null,
            if (r.status == MooringStatus.DRAFT) blockers(r.model, snapshot) else emptyList(), warnings(r.model, snapshot),
            r.submittedBy?.let(names::get), r.submittedAt, r.decidedBy?.let(names::get), r.decidedAt,
            r.returnedReason, r.issuedBy?.let(names::get), r.issuedAt, r.pdfSha256, actions, r.version)
    }

    private fun snapshot(job: JobTechnicalRef, revisionId: UUID, model: MooringModel): MooringSnapshot {
        val refs = references.vessels(job.vessels.map { it.vesselId })
        val vessels = job.vessels.map { jv ->
            val versionId = jdbc.sql("""SELECT id FROM vessel_particulars_version WHERE vessel_id=:vessel AND status='VERIFIED'
                ORDER BY version_no DESC LIMIT 1""").param("vessel", jv.vesselId.bytes())
                .query { rs, _ -> rs.getBytes(1).uuid()!! }.optional().orElse(null)
            val values = if (versionId == null) emptyMap() else jdbc.sql("""SELECT field_code,value_number,source_document_id,source_page,verified
                FROM vessel_particular_value WHERE version_id=:id""").param("id", versionId.bytes())
                .query { rs, _ -> rs.getString(1) to MooringDimension(jv.id, rs.getString(1), rs.getBigDecimal(2), "m",
                    versionId, rs.getBytes(3).uuid(), rs.getObject(4) as? Int, rs.getBoolean(5)) }.list().toMap()
            MooringVesselFacts(jv.id, jv.role, refs[jv.vesselId]?.name, refs[jv.vesselId]?.imo,
                MooringPlanValidator.DIMENSIONS.map { code -> values[code] ?: MooringDimension(jv.id, code, null, "m", versionId, null, null, false) })
        }
        val verified = jdbc.sql("SELECT value_key,verified_by,verified_at FROM mooring_plan_verified_value WHERE revision_id=:id")
            .param("id", revisionId.bytes()).query { rs, _ -> MooringVerifiedValue(rs.getString(1), rs.getBytes(2).uuid()!!,
                Uuids.fromTimestamp(rs.getObject(3, LocalDateTime::class.java))) }.list()
        val referenceFiles = model.layers.mapNotNull { layer ->
            jdbc.sql("""SELECT d.file_id,f.sha256 FROM document_version d JOIN stored_file f ON f.id=d.file_id
                WHERE d.id=:id""").param("id", layer.documentVersionId.bytes())
                .query { rs, _ -> MooringReferenceFile(layer.documentVersionId, rs.getBytes(1).uuid()!!, rs.getString(2)) }
                .optional().orElse(null)
        }
        return MooringSnapshot(vessels, capacityColumns(), verified, clock.instant().truncatedTo(ChronoUnit.MICROS), referenceFiles)
    }

    private fun blockers(model: MooringModel, snap: MooringSnapshot): List<MooringBlocker> = buildList {
        fun need(ok: Boolean, code: String, message: String) { if (!ok) add(MooringBlocker(code, message)) }
        need(model.vessels.count { it.heading == "CONSTANT" } == 1 && model.vessels.count { it.heading == "MANOEUVRING" } == 1,
            "vessels.heading", "Choose one constant-heading and one manoeuvring vessel.")
        need(model.rulerStepM != null, "rulerStepM", "Enter a metric ruler step.")
        snap.vessels.forEach { vessel ->
            vessel.dimensions.forEach { d -> need(d.value != null && d.verified, "dimensions.${vessel.role}.${d.code}",
                "${vessel.role}: verified ${d.code} is required.") }
            MooringPlanValidator.PLAN_PARTICULARS.forEach { code ->
                val p = model.particulars.firstOrNull { it.jobVesselId == vessel.jobVesselId && it.code == code }
                need(p?.value != null && p.hasSource() && snap.verifiedValues.any { it.key == MooringPlanValidator.valueKey(p) },
                    "particulars.${vessel.role}.$code", "${vessel.role}: sourced, independently verified $code is required.")
            }
        }
        snap.capacityColumns.forEach { column -> need(column.status == "ACTIVE", "capacityColumn.${column.code}",
            "${column.label} has no approved definition and unit.") }
        need(model.lines.isNotEmpty(), "lines", "Add at least one mooring line.")
        val pointIds = model.objects.filter { it.type == "MOORING_POINT" }.map { it.id }.toSet()
        model.lines.forEach { line ->
            need(line.fromPointId in pointIds && line.toPointId in pointIds && line.fromPointId != line.toPointId,
                "lines.${line.label}.endpoints", "${line.label}: both distinct connection points are required.")
            need(line.berthingOrder != null && line.unmooringOrder != null, "lines.${line.label}.sequence",
                "${line.label}: berthing and unmooring sequence are required.")
            snap.capacityColumns.forEach { column ->
                val capacity = line.capacities.firstOrNull { it.columnCode == column.code }
                need(capacity?.value != null && capacity.unit == column.unit && capacity.hasSource() &&
                    snap.verifiedValues.any { it.key == MooringPlanValidator.valueKey(line.id, column.code) },
                    "lines.${line.label}.${column.code}", "${line.label}: verified ${column.label} value and source are required.")
            }
        }
    }

    private fun warnings(model: MooringModel, snap: MooringSnapshot): List<String> = buildList {
        val tolerance = jdbc.sql("SELECT tolerance_m FROM mooring_plan_rule WHERE code='MANIFOLD_LENGTH' AND active=TRUE")
            .query { rs, _ -> rs.getBigDecimal(1) }.optional().orElse(null)
        if (tolerance == null) add("Bow + stern versus LOA tolerance is pending owner definition.")
        snap.vessels.forEach { vessel ->
            val values = vessel.dimensions.associate { it.code to it.value }
            val loa = values["LOA"]; val bow = values["BOW_TO_MANIFOLD"]; val stern = values["STERN_TO_MANIFOLD"]
            if (tolerance != null && loa != null && bow != null && stern != null && bow.add(stern).subtract(loa).abs() > tolerance)
                add("${vessel.role}: bow + stern to manifold differs from LOA beyond configured tolerance.")
        }
        if (model.lines.map { it.label }.size != model.lines.map { it.label }.toSet().size) add("Line IDs in the drawing and table differ.")
    }

    private fun validateSources(job: JobTechnicalRef, model: MooringModel) {
        val errors = mutableListOf<FieldError>()
        val columnCodes = capacityColumns().map { it.code }.toSet()
        model.layers.forEachIndexed { i, layer ->
            if (!jobDocument(job, layer.documentVersionId, layer.jobVesselId, imageOnly = true))
                errors += FieldError("layers[$i].documentVersionId", "unknown", "Choose a PNG/JPEG GA plan for this vessel.")
        }
        model.particulars.forEachIndexed { i, p ->
            if (p.sourceDocumentId != null && !jobDocument(job, p.sourceDocumentId, p.jobVesselId))
                errors += FieldError("particulars[$i].sourceDocumentId", "unknown", null)
        }
        model.lines.forEachIndexed { i, line -> line.capacities.forEachIndexed { j, c ->
            if (c.columnCode !in columnCodes)
                errors += FieldError("lines[$i].capacities[$j].columnCode", "unknown", null)
            if (c.sourceDocumentId != null && !jobDocument(job, c.sourceDocumentId, line.jobVesselId))
                errors += FieldError("lines[$i].capacities[$j].sourceDocumentId", "unknown", null)
        } }
        if (errors.isNotEmpty()) throw BadRequestException("VALIDATION_FAILED", "A source document belongs to another job or vessel.", errors)
    }

    private fun jobDocument(job: JobTechnicalRef, documentId: UUID, vesselId: UUID, imageOnly: Boolean = false): Boolean {
        val document = jdbc.sql("""SELECT d.owner_type,d.owner_id,t.code,f.content_type FROM document_version d
            JOIN document_type t ON t.id=d.document_type_id JOIN stored_file f ON f.id=d.file_id WHERE d.id=:id""")
            .param("id", documentId.bytes()).query { rs, _ ->
                listOf(rs.getString(1), rs.getBytes(2).uuid()?.toString().orEmpty(), rs.getString(3), rs.getString(4))
            }.optional().orElse(null) ?: return false
        val ownerOkay = (document[0] == "JOB" && document[1] == job.id.toString()) ||
            (document[0] == "JOB_VESSEL" && document[1] == vesselId.toString()) ||
            (document[0] == "VESSEL" && job.vessels.any { it.id == vesselId && it.vesselId.toString() == document[1] })
        return ownerOkay && (!imageOnly || (document[2] in setOf("GA-PLAN", "MOORING-ARR") &&
            document[3] in setOf("image/png", "image/jpeg")))
    }

    private fun valueHash(model: MooringModel, key: String): String? {
        val value = when {
            key.startsWith("particular:") -> model.particulars.firstOrNull { MooringPlanValidator.valueKey(it) == key }
            key.startsWith("capacity:") -> model.lines.flatMap { line -> line.capacities.map { c -> MooringPlanValidator.valueKey(line.id, c.columnCode) to c } }
                .firstOrNull { it.first == key }?.second
            else -> null
        } ?: return null
        val sourced = when (value) {
            is MooringParticular -> value.value != null && value.hasSource()
            is MooringCapacity -> value.value != null && value.hasSource()
            else -> false
        }
        return if (sourced) sha(json.writeValueAsBytes(value)) else null
    }

    private fun verified(id: UUID): Map<String, String> = jdbc.sql("SELECT value_key,value_sha256 FROM mooring_plan_verified_value WHERE revision_id=:id")
        .param("id", id.bytes()).query { rs, _ -> rs.getString(1) to rs.getString(2) }.list().toMap()

    private fun capacityColumns(): List<MooringCapacityColumn> = jdbc.sql("""SELECT code,label,definition,unit,evidence_requirement,status,version
        FROM mooring_capacity_column ORDER BY code""").query { rs, _ ->
        MooringCapacityColumn(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
            rs.getString(6), rs.getLong(7))
    }.list()

    private fun revisions(jobId: UUID): List<Row> = jdbc.sql("""SELECT r.id FROM mooring_plan_revision r
        JOIN mooring_plan p ON p.id=r.plan_id WHERE p.job_id=:job ORDER BY r.revision_no DESC""")
        .param("job", jobId.bytes()).query { rs, _ -> rs.getBytes(1).uuid()!! }.list().map(::load)

    private fun row(jobId: UUID, revisionId: UUID): Row {
        val owner = jdbc.sql("""SELECT p.job_id FROM mooring_plan_revision r JOIN mooring_plan p ON p.id=r.plan_id WHERE r.id=:id""")
            .param("id", revisionId.bytes()).query { rs, _ -> rs.getBytes(1).uuid() }.optional().orElse(null)
        if (owner != jobId) throw NotFoundException("Mooring revision")
        return load(revisionId)
    }

    private fun locked(jobId: UUID, revisionId: UUID, expected: Long): Row {
        row(jobId, revisionId)
        jdbc.sql("SELECT id FROM mooring_plan_revision WHERE id=:id FOR UPDATE").param("id", revisionId.bytes())
            .query { rs, _ -> rs.getBytes(1) }.single()
        return load(revisionId).also { Versioning.checkCurrent(expected, it.version) }
    }

    private fun load(id: UUID): Row = jdbc.sql("""SELECT revision_no,status,model_json,snapshot_json,submitted_by,submitted_at,
        decided_by,decided_at,returned_reason,issued_by,issued_at,pdf_sha256,version FROM mooring_plan_revision WHERE id=:id""")
        .param("id", id.bytes()).query { rs, _ -> Row(id, rs.getInt(1), MooringStatus.valueOf(rs.getString(2)),
            json.readValue(rs.getString(3), MooringModel::class.java),
            rs.getString(4)?.let { json.readValue(it, MooringSnapshot::class.java) },
            rs.getBytes(5).uuid(), ts(rs.getObject(6, LocalDateTime::class.java)),
            rs.getBytes(7).uuid(), ts(rs.getObject(8, LocalDateTime::class.java)), rs.getString(9),
            rs.getBytes(10).uuid(), ts(rs.getObject(11, LocalDateTime::class.java)), rs.getString(12), rs.getLong(13))
        }.optional().orElseThrow { NotFoundException("Mooring revision") }

    private fun requireStatus(r: Row, status: MooringStatus) {
        if (r.status != status) throw BusinessRuleException("INVALID_TRANSITION", "The mooring revision is ${r.status}.")
    }

    private fun status(job: JobTechnicalRef, r: Row, to: MooringStatus, reason: String? = null) =
        audit.record(AuditEntry(AuditAction.STATUS_CHANGE, ENTITY, r.id, job.branchId,
            mapOf("jobId" to job.id, "revisionNo" to r.number, "from" to r.status.name, "to" to to.name, "reason" to reason)))

    private fun can(job: JobTechnicalRef, permission: String) =
        policy.currentUser().scopeFor(permission).coversBranch(job.branchId)
    private fun actor() = policy.currentUser().id
    private fun now() = Uuids.toTimestamp(clock.instant().truncatedTo(ChronoUnit.MICROS))
    private fun ts(value: LocalDateTime?): Instant? = value?.let(Uuids::fromTimestamp)
    private fun sha(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    companion object {
        private const val ENTITY = "MOORING_PLAN_REVISION"
        private val OPEN = setOf(MooringStatus.DRAFT, MooringStatus.SUBMITTED, MooringStatus.APPROVED)
    }
}

private fun MooringParticular.hasSource() = sourceDocumentId != null || !sourceText.isNullOrBlank()
private fun MooringCapacity.hasSource() = sourceDocumentId != null || !sourceText.isNullOrBlank()
