package com.ship2ship.features.operations

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.files.FileAccess
import com.ship2ship.core.files.FileService
import com.ship2ship.core.jobs.JobTechnicalRef
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.security.UserDirectory
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.BusinessRuleException
import com.ship2ship.core.web.FieldError
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.Versioning
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Delays and incidents (FR-J09, UAT-08). Every change keeps the previous values with a reason; delays are voided, never
 * deleted. An incident is reported, taken under review and closed by someone other than the reporter
 * (OPS.INCIDENT.REVIEW). Severity escalation is shown only; alerts are not built yet.
 */
@Service
class IncidentService(
    private val jdbc: JdbcClient,
    private val access: OperationsAccess,
    private val users: UserDirectory,
    private val files: FileService,
    private val history: OperationHistory,
    private val audit: AuditService,
    private val clock: Clock,
) {
    // ---------- delays ----------

    @Transactional
    fun createDelay(jobId: UUID, input: DelayInput): DelayDto {
        val job = access.job(jobId, Permissions.OPS_INCIDENT_WRITE)
        val clean = validateDelay(input)
        val id = UuidV7.generate()
        jdbc.sql("""INSERT INTO job_delay (id,job_id,category_code,started_at,ended_at,description,status,recorded_by,recorded_at,updated_at,version)
            VALUES (:id,:job,:cat,:start,:end,:desc,'ACTIVE',:a,:t,:t,0)""")
            .param("id", id.bytes()).param("job", jobId.bytes()).params(delayFields(clean)).param("a", access.actor().bytes()).param("t", now()).update()
        audit.record(AuditEntry(AuditAction.CREATE, "JOB_DELAY", id, job.branchId, delaySummary(clean)))
        return delays(job).first { it.id == id }
    }

    @Transactional
    fun updateDelay(jobId: UUID, delayId: UUID, expectedVersion: Long, input: DelayInput): DelayDto {
        val job = access.job(jobId, Permissions.OPS_INCIDENT_WRITE)
        val current = lockedDelay(jobId, delayId, expectedVersion)
        if (current.status != "ACTIVE") throw BusinessRuleException("INVALID_TRANSITION", "A voided delay cannot be changed.")
        val reason = reason(input.reason)
        val clean = validateDelay(input)
        history.record(DELAY, delayId, current.version, mapOf("categoryCode" to current.category, "startedAt" to current.startedAt,
            "endedAt" to current.endedAt, "description" to current.description), reason, access.actor())
        jdbc.sql("""UPDATE job_delay SET category_code=:cat,started_at=:start,ended_at=:end,description=:desc,updated_by=:a,updated_at=:t,
            version=version+1 WHERE id=:id""").params(delayFields(clean)).param("a", access.actor().bytes()).param("t", now())
            .param("id", delayId.bytes()).update()
        audit.record(AuditEntry(AuditAction.UPDATE, "JOB_DELAY", delayId, job.branchId, delaySummary(clean) + mapOf("reason" to reason)))
        return delays(job).first { it.id == delayId }
    }

    @Transactional
    fun voidDelay(jobId: UUID, delayId: UUID, expectedVersion: Long, input: ReasonInput): DelayDto {
        val job = access.job(jobId, Permissions.OPS_INCIDENT_WRITE)
        val current = lockedDelay(jobId, delayId, expectedVersion)
        if (current.status != "ACTIVE") throw BusinessRuleException("INVALID_TRANSITION", "This delay is already voided.")
        val reason = reason(input.reason)
        jdbc.sql("UPDATE job_delay SET status='VOIDED',void_reason=:r,updated_by=:a,updated_at=:t,version=version+1 WHERE id=:id")
            .param("r", reason).param("a", access.actor().bytes()).param("t", now()).param("id", delayId.bytes()).update()
        audit.record(AuditEntry(AuditAction.STATUS_CHANGE, "JOB_DELAY", delayId, job.branchId, mapOf("to" to "VOIDED", "reason" to reason)))
        return delays(job).first { it.id == delayId }
    }

    // ---------- incidents ----------

    @Transactional
    fun createIncident(jobId: UUID, input: IncidentInput): IncidentDto {
        val job = access.job(jobId, Permissions.OPS_INCIDENT_WRITE)
        val clean = validateIncident(input)
        val id = UuidV7.generate()
        jdbc.sql("""INSERT INTO job_incident (id,job_id,category_code,severity_code,occurred_at,description,immediate_actions,status,reported_by,
            reported_at,updated_at,version) VALUES (:id,:job,:cat,:sev,:at,:desc,:imm,'REPORTED',:a,:t,:t,0)""")
            .param("id", id.bytes()).param("job", jobId.bytes()).params(incidentFields(clean)).param("a", access.actor().bytes()).param("t", now()).update()
        replaceActions(id, clean.actions)
        audit.record(AuditEntry(AuditAction.CREATE, "JOB_INCIDENT", id, job.branchId, incidentSummary(clean)))
        return incidents(job).first { it.id == id }
    }

    /** Corrects an open incident (the previous values, including its actions, are kept with the reason). */
    @Transactional
    fun updateIncident(jobId: UUID, incidentId: UUID, expectedVersion: Long, input: IncidentInput): IncidentDto {
        val job = access.job(jobId, Permissions.OPS_INCIDENT_WRITE)
        val current = lockedIncident(jobId, incidentId, expectedVersion)
        if (current.status == "CLOSED") throw BusinessRuleException("INVALID_TRANSITION", "A closed incident cannot be changed.")
        val reason = reason(input.reason)
        val clean = validateIncident(input)
        history.record(INCIDENT, incidentId, current.version, mapOf("categoryCode" to current.category, "severityCode" to current.severity,
            "occurredAt" to current.occurredAt, "description" to current.description, "immediateActions" to current.immediateActions,
            "actions" to actions(incidentId).map { mapOf("text" to it.text, "ownerName" to it.ownerName, "dueDate" to it.dueDate, "doneAt" to it.doneAt) }),
            reason, access.actor())
        jdbc.sql("""UPDATE job_incident SET category_code=:cat,severity_code=:sev,occurred_at=:at,description=:desc,immediate_actions=:imm,
            updated_by=:a,updated_at=:t,version=version+1 WHERE id=:id""").params(incidentFields(clean)).param("a", access.actor().bytes())
            .param("t", now()).param("id", incidentId.bytes()).update()
        replaceActions(incidentId, clean.actions)
        audit.record(AuditEntry(AuditAction.UPDATE, "JOB_INCIDENT", incidentId, job.branchId, incidentSummary(clean) + mapOf("reason" to reason)))
        return incidents(job).first { it.id == incidentId }
    }

    /** START_REVIEW, then CLOSE with a closing note, by a reviewer who did not report the incident. */
    @Transactional
    fun review(jobId: UUID, incidentId: UUID, expectedVersion: Long, input: IncidentReviewInput): IncidentDto {
        val job = access.job(jobId, Permissions.OPS_INCIDENT_REVIEW)
        val current = lockedIncident(jobId, incidentId, expectedVersion)
        if (current.reportedBy == access.actor()) {
            throw BusinessRuleException("SELF_APPROVAL_FORBIDDEN", "An incident cannot be reviewed or closed by the person who reported it.")
        }
        when (input.decision) {
            "START_REVIEW" -> {
                if (current.status != "REPORTED") throw BusinessRuleException("INVALID_TRANSITION", "Only a reported incident can be taken under review.")
                jdbc.sql("""UPDATE job_incident SET status='UNDER_REVIEW',review_started_by=:a,review_started_at=:t,updated_by=:a,updated_at=:t,
                    version=version+1 WHERE id=:id""").param("a", access.actor().bytes()).param("t", now()).param("id", incidentId.bytes()).update()
            }
            "CLOSE" -> {
                if (current.status == "CLOSED") throw BusinessRuleException("INVALID_TRANSITION", "This incident is already closed.")
                val note = input.note?.trim().orEmpty()
                if (note.isEmpty()) {
                    throw BadRequestException("REASON_REQUIRED", "Record the closing note.", listOf(FieldError("note", "required", null)))
                }
                jdbc.sql("""UPDATE job_incident SET status='CLOSED',closed_by=:a,closed_at=:t,closing_note=:n,updated_by=:a,updated_at=:t,
                    version=version+1 WHERE id=:id""").param("a", access.actor().bytes()).param("t", now()).param("n", note.take(2000))
                    .param("id", incidentId.bytes()).update()
            }
            else -> throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.",
                listOf(FieldError("decision", "allowed", "START_REVIEW or CLOSE.")))
        }
        audit.record(AuditEntry(AuditAction.STATUS_CHANGE, "JOB_INCIDENT", incidentId, job.branchId,
            mapOf("jobId" to jobId, "decision" to input.decision, "note" to input.note)))
        return incidents(job).first { it.id == incidentId }
    }

    @Transactional(readOnly = true)
    fun changes(jobId: UUID, type: String, recordId: UUID): List<ChangeDto> {
        access.job(jobId, Permissions.OPS_INCIDENT_READ)
        val table = when (type) { DELAY -> "job_delay"; INCIDENT -> "job_incident"; else -> throw NotFoundException("Record") }
        owner(table, recordId, jobId)
        return history.changes(type, recordId)
    }

    // ---------- evidence files ----------

    @Transactional
    fun attach(jobId: UUID, ownerType: String, ownerId: UUID, upload: MultipartFile): FileRefDto {
        val job = access.job(jobId, Permissions.OPS_INCIDENT_WRITE)
        val table = when (ownerType) { DELAY -> "job_delay"; INCIDENT -> "job_incident"
            else -> throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", listOf(FieldError("ownerType", "allowed", null))) }
        owner(table, ownerId, jobId)
        val stored = files.store(upload, FileAccess(Permissions.OPS_INCIDENT_READ, job.branchId))
        val id = UuidV7.generate()
        jdbc.sql("""INSERT INTO operation_file (id,job_id,owner_type,owner_id,file_id,uploaded_by,uploaded_at) VALUES (:id,:job,:type,:owner,:file,:a,:t)""")
            .param("id", id.bytes()).param("job", jobId.bytes()).param("type", ownerType).param("owner", ownerId.bytes()).param("file", stored.id.bytes())
            .param("a", access.actor().bytes()).param("t", now()).update()
        audit.record(AuditEntry(AuditAction.CREATE, "OPERATION_FILE", id, job.branchId,
            mapOf("ownerType" to ownerType, "ownerId" to ownerId, "fileId" to stored.id, "name" to stored.originalName)))
        return FileRefDto(id, stored.id, stored.originalName, stored.contentType, stored.scanState.name)
    }

    // ---------- reads for the console ----------

    internal fun delays(job: JobTechnicalRef): List<DelayDto> {
        val labels = labels("delay_category")
        val rows = jdbc.sql("""SELECT id,category_code,started_at,ended_at,description,status,void_reason,recorded_by,recorded_at,version
            FROM job_delay WHERE job_id=:job ORDER BY started_at""").param("job", job.id.bytes()).query { rs, _ ->
            DelayRow(rs.getBytes(1).uuid()!!, rs.getString(2), rs.getObject(3, LocalDateTime::class.java).instant()!!,
                rs.getObject(4, LocalDateTime::class.java).instant(), rs.getString(5), rs.getString(6), rs.getString(7), rs.getBytes(8).uuid()!!,
                rs.getObject(9, LocalDateTime::class.java).instant()!!, rs.getLong(10))
        }.list()
        val names = users.names(rows.map { it.recordedBy })
        val files = filesOf(DELAY, rows.map { it.id })
        val corrected = history.corrected(DELAY, rows.map { it.id })
        return rows.map {
            DelayDto(it.id, it.category, labels[it.category] ?: it.category, it.startedAt, it.endedAt, it.description, it.status, it.voidReason,
                names[it.recordedBy], it.recordedAt, files[it.id].orEmpty(), it.id in corrected, it.version)
        }
    }

    internal fun incidents(job: JobTechnicalRef): List<IncidentDto> {
        val categories = labels("incident_category")
        val severities = labels("incident_severity")
        val rows = jdbc.sql("SELECT id FROM job_incident WHERE job_id=:job ORDER BY occurred_at").param("job", job.id.bytes())
            .query { rs, _ -> rs.getBytes(1).uuid()!! }.list().map(::loadIncident)
        val names = users.names(rows.flatMap { listOfNotNull(it.reportedBy, it.reviewStartedBy, it.closedBy) })
        val files = filesOf(INCIDENT, rows.map { it.id })
        val corrected = history.corrected(INCIDENT, rows.map { it.id })
        val canReview = access.can(job, Permissions.OPS_INCIDENT_REVIEW)
        val me = access.actor()
        return rows.map {
            IncidentDto(it.id, it.category, categories[it.category] ?: it.category, it.severity, severities[it.severity] ?: it.severity, it.occurredAt,
                it.description, it.immediateActions, it.status, names[it.reportedBy], it.reportedAt, it.reviewStartedBy?.let(names::get),
                it.reviewStartedAt, it.closedBy?.let(names::get), it.closedAt, it.closingNote, actions(it.id), files[it.id].orEmpty(),
                it.id in corrected, canReview = canReview && it.status == "REPORTED" && it.reportedBy != me,
                canClose = canReview && it.status != "CLOSED" && it.reportedBy != me, version = it.version)
        }
    }

    // ---------- helpers ----------

    private data class DelayRow(
        val id: UUID, val category: String, val startedAt: Instant, val endedAt: Instant?, val description: String, val status: String,
        val voidReason: String?, val recordedBy: UUID, val recordedAt: Instant, val version: Long,
    )

    private data class IncidentRow(
        val id: UUID, val jobId: UUID, val category: String, val severity: String, val occurredAt: Instant, val description: String,
        val immediateActions: String?, val status: String, val reportedBy: UUID, val reportedAt: Instant, val reviewStartedBy: UUID?,
        val reviewStartedAt: Instant?, val closedBy: UUID?, val closedAt: Instant?, val closingNote: String?, val version: Long,
    )

    private fun validateDelay(input: DelayInput): DelayInput {
        val errors = mutableListOf<FieldError>()
        val latest = clock.instant().plus(5, ChronoUnit.MINUTES)
        if (input.categoryCode !in activeCodes("delay_category")) errors += FieldError("categoryCode", "unknown", "Choose a category.")
        if (input.startedAt == null) errors += FieldError("startedAt", "required", null)
        else if (input.startedAt.isAfter(latest)) errors += FieldError("startedAt", "future", "Cannot be in the future.")
        if (input.endedAt != null) {
            if (input.endedAt.isAfter(latest)) errors += FieldError("endedAt", "future", "Cannot be in the future.")
            if (input.startedAt != null && input.endedAt.isBefore(input.startedAt)) errors += FieldError("endedAt", "order", "Must be after the start.")
        }
        if (input.description.isBlank() || input.description.length > 2000) errors += FieldError("description", "required", "Describe the delay (max 2000).")
        if (errors.isNotEmpty()) throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", errors)
        return input.copy(startedAt = input.startedAt!!.truncatedTo(ChronoUnit.MINUTES), endedAt = input.endedAt?.truncatedTo(ChronoUnit.MINUTES),
            description = input.description.trim())
    }

    private fun validateIncident(input: IncidentInput): IncidentInput {
        val errors = mutableListOf<FieldError>()
        if (input.categoryCode !in activeCodes("incident_category")) errors += FieldError("categoryCode", "unknown", "Choose a category.")
        if (input.severityCode !in activeCodes("incident_severity")) errors += FieldError("severityCode", "unknown", "Choose a severity.")
        if (input.occurredAt == null) errors += FieldError("occurredAt", "required", null)
        else if (input.occurredAt.isAfter(clock.instant().plus(5, ChronoUnit.MINUTES))) errors += FieldError("occurredAt", "future", "Cannot be in the future.")
        if (input.description.isBlank() || input.description.length > 2000) errors += FieldError("description", "required", "Describe what happened (max 2000).")
        if ((input.immediateActions?.length ?: 0) > 2000) errors += FieldError("immediateActions", "size", null)
        if (input.actions.size > 30) errors += FieldError("actions", "size", "At most 30 actions.")
        input.actions.forEachIndexed { i, a ->
            if (a.text.isBlank() || a.text.length > 500) errors += FieldError("actions[$i].text", "required", null)
            if ((a.ownerName?.length ?: 0) > 150) errors += FieldError("actions[$i].ownerName", "size", null)
        }
        if (errors.isNotEmpty()) throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", errors)
        return input.copy(occurredAt = input.occurredAt!!.truncatedTo(ChronoUnit.MINUTES), description = input.description.trim(),
            immediateActions = input.immediateActions?.trim()?.ifEmpty { null },
            actions = input.actions.map { it.copy(text = it.text.trim(), ownerName = it.ownerName?.trim()?.ifEmpty { null }) })
    }

    private fun delayFields(d: DelayInput): Map<String, Any?> =
        mapOf("cat" to d.categoryCode, "start" to d.startedAt?.stamp(), "end" to d.endedAt?.stamp(), "desc" to d.description)

    private fun delaySummary(d: DelayInput) = mapOf("categoryCode" to d.categoryCode, "startedAt" to d.startedAt, "endedAt" to d.endedAt)

    private fun incidentFields(i: IncidentInput): Map<String, Any?> = mapOf("cat" to i.categoryCode, "sev" to i.severityCode,
        "at" to i.occurredAt?.stamp(), "desc" to i.description, "imm" to i.immediateActions)

    private fun incidentSummary(i: IncidentInput) =
        mapOf("categoryCode" to i.categoryCode, "severityCode" to i.severityCode, "occurredAt" to i.occurredAt, "actions" to i.actions.size)

    private fun replaceActions(incidentId: UUID, actions: List<IncidentActionInput>) {
        val existing = actions(incidentId)
        jdbc.sql("DELETE FROM incident_action WHERE incident_id=:id").param("id", incidentId.bytes()).update()
        actions.forEachIndexed { i, a ->
            // Keep the original completion time when an action stays done.
            val doneAt = if (!a.done) null else existing.firstOrNull { it.text == a.text && it.doneAt != null }?.doneAt ?: clock.instant()
            jdbc.sql("""INSERT INTO incident_action (id,incident_id,sort_order,action_text,owner_name,due_date,done_at)
                VALUES (:id,:inc,:sort,:text,:owner,:due,:done)""")
                .param("id", UuidV7.generate().bytes()).param("inc", incidentId.bytes()).param("sort", i).param("text", a.text)
                .param("owner", a.ownerName).param("due", a.dueDate).param("done", doneAt?.truncatedTo(ChronoUnit.MICROS)?.stamp()).update()
        }
    }

    private fun actions(incidentId: UUID): List<IncidentActionDto> =
        jdbc.sql("SELECT id,action_text,owner_name,due_date,done_at FROM incident_action WHERE incident_id=:id ORDER BY sort_order")
            .param("id", incidentId.bytes()).query { rs, _ ->
                IncidentActionDto(rs.getBytes(1).uuid()!!, rs.getString(2), rs.getString(3), rs.getObject(4, LocalDate::class.java),
                    rs.getObject(5, LocalDateTime::class.java).instant())
            }.list()

    private fun filesOf(type: String, ids: List<UUID>): Map<UUID, List<FileRefDto>> {
        if (ids.isEmpty()) return emptyMap()
        val rows = jdbc.sql("SELECT id,owner_id,file_id FROM operation_file WHERE owner_type=:type AND owner_id IN (:ids) ORDER BY uploaded_at")
            .param("type", type).param("ids", ids.map { it.bytes() }).query { rs, _ ->
                Triple(rs.getBytes(1).uuid()!!, rs.getBytes(2).uuid()!!, rs.getBytes(3).uuid()!!)
            }.list()
        val refs = files.refs(rows.map { it.third })
        return rows.mapNotNull { (id, owner, file) ->
            refs[file]?.let { owner to FileRefDto(id, file, it.originalName, it.contentType, it.scanState.name) }
        }.groupBy({ it.first }, { it.second })
    }

    private fun labels(table: String): Map<String, String> =
        jdbc.sql("SELECT code,label FROM $table").query { rs, _ -> rs.getString(1) to rs.getString(2) }.list().toMap()

    private fun activeCodes(table: String): Set<String> =
        jdbc.sql("SELECT code FROM $table WHERE active=TRUE").query(String::class.java).list().filterNotNull().toSet()

    private fun owner(table: String, id: UUID, jobId: UUID) {
        val owner = jdbc.sql("SELECT job_id FROM $table WHERE id=:id").param("id", id.bytes()).query { rs, _ -> rs.getBytes(1).uuid() }
            .optional().orElse(null)
        if (owner != jobId) throw NotFoundException("Record")
    }

    private fun lockedDelay(jobId: UUID, id: UUID, expectedVersion: Long): DelayRow {
        owner("job_delay", id, jobId)
        jdbc.sql("SELECT id FROM job_delay WHERE id=:id FOR UPDATE").param("id", id.bytes()).query { rs, _ -> rs.getBytes(1) }.single()
        val row = jdbc.sql("""SELECT id,category_code,started_at,ended_at,description,status,void_reason,recorded_by,recorded_at,version
            FROM job_delay WHERE id=:id""").param("id", id.bytes()).query { rs, _ ->
            DelayRow(rs.getBytes(1).uuid()!!, rs.getString(2), rs.getObject(3, LocalDateTime::class.java).instant()!!,
                rs.getObject(4, LocalDateTime::class.java).instant(), rs.getString(5), rs.getString(6), rs.getString(7), rs.getBytes(8).uuid()!!,
                rs.getObject(9, LocalDateTime::class.java).instant()!!, rs.getLong(10))
        }.single()
        Versioning.checkCurrent(expectedVersion, row.version)
        return row
    }

    private fun lockedIncident(jobId: UUID, id: UUID, expectedVersion: Long): IncidentRow {
        owner("job_incident", id, jobId)
        jdbc.sql("SELECT id FROM job_incident WHERE id=:id FOR UPDATE").param("id", id.bytes()).query { rs, _ -> rs.getBytes(1) }.single()
        return loadIncident(id).also { Versioning.checkCurrent(expectedVersion, it.version) }
    }

    private fun loadIncident(id: UUID): IncidentRow =
        jdbc.sql("""SELECT id,job_id,category_code,severity_code,occurred_at,description,immediate_actions,status,reported_by,reported_at,
            review_started_by,review_started_at,closed_by,closed_at,closing_note,version FROM job_incident WHERE id=:id""").param("id", id.bytes())
            .query { rs, _ ->
                fun t(i: Int) = rs.getObject(i, LocalDateTime::class.java).instant()
                IncidentRow(rs.getBytes(1).uuid()!!, rs.getBytes(2).uuid()!!, rs.getString(3), rs.getString(4), t(5)!!, rs.getString(6), rs.getString(7),
                    rs.getString(8), rs.getBytes(9).uuid()!!, t(10)!!, rs.getBytes(11).uuid(), t(12), rs.getBytes(13).uuid(), t(14), rs.getString(15),
                    rs.getLong(16))
            }.single()

    private fun reason(reason: String?): String {
        val r = reason?.trim().orEmpty()
        if (r.isEmpty()) throw BadRequestException("REASON_REQUIRED", "A reason is required for this change.", listOf(FieldError("reason", "required", null)))
        return r.take(500)
    }

    private fun now() = clock.instant().truncatedTo(ChronoUnit.MICROS).stamp()

    companion object {
        const val DELAY = "DELAY"
        const val INCIDENT = "INCIDENT"
    }
}
