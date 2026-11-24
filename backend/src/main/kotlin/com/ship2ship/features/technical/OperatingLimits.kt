package com.ship2ship.features.technical

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.jobs.JobTechnicalRef
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.security.UserDirectory
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.BusinessRuleException
import com.ship2ship.core.web.ConflictException
import com.ship2ship.core.web.FieldError
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.Versioning
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Operating limits of a job (FR-TECH-04, D09): parameter, operation, threshold and action, entered per job and approved
 * by someone other than the submitter. Nothing is pre-filled; a new revision copies the job's newest one. Only the
 * APPROVED set is used for weather warnings and the JPO; approving a newer set supersedes it.
 */
@Service
class OperatingLimitService(
    private val jdbc: JdbcClient,
    private val access: JobTechnicalAccess,
    private val policy: AccessPolicy,
    private val users: UserDirectory,
    private val audit: AuditService,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun overview(jobId: UUID): LimitOverviewDto {
        val job = access.job(jobId, Permissions.TECH_WEATHER_READ)
        val sets = setsOf(jobId)
        return LimitOverviewDto(jobId, job.jobNumber, sets.map { LimitSetSummaryDto(it.id, it.revisionNo, it.status, it.decidedAt) },
            sets.firstOrNull { it.status == LimitSetStatus.APPROVED }?.let { toDto(job, it) },
            canCreate = can(job, Permissions.TECH_WEATHER_WRITE) && sets.none { it.status in OPEN })
    }

    @Transactional(readOnly = true)
    fun get(jobId: UUID, setId: UUID): LimitSetDto {
        val job = access.job(jobId, Permissions.TECH_WEATHER_READ)
        return toDto(job, row(jobId, setId))
    }

    /** A new draft, copying the rows of the job's newest revision (an empty table for the first one). */
    @Transactional
    fun create(jobId: UUID): LimitSetDto {
        val job = access.job(jobId, Permissions.TECH_WEATHER_WRITE)
        jdbc.sql("SELECT id FROM sts_job WHERE id=:id FOR UPDATE").param("id", jobId.bytes()).query { rs, _ -> rs.getBytes(1) }.single()
        val existing = setsOf(jobId)
        if (existing.any { it.status in OPEN }) throw ConflictException("LIMITS_REVISION_OPEN", "Finish the open operating-limits revision first.")
        val id = UuidV7.generate()
        val number = (existing.maxOfOrNull { it.revisionNo } ?: 0) + 1
        jdbc.sql("""INSERT INTO job_limit_set (id,job_id,revision_no,status,created_at,created_by,updated_at,updated_by,version)
            VALUES (:id,:job,:no,'DRAFT',:t,:a,:t,:a,0)""")
            .param("id", id.bytes()).param("job", jobId.bytes()).param("no", number).param("t", now()).param("a", actor().bytes()).update()
        existing.firstOrNull()?.rows?.let { insertRows(id, it) }
        audit.record(AuditEntry(AuditAction.CREATE, ENTITY, id, job.branchId,
            mapOf("jobId" to jobId, "revisionNo" to number, "copiedFrom" to existing.firstOrNull()?.revisionNo)))
        return toDto(job, row(jobId, id))
    }

    @Transactional
    fun update(jobId: UUID, setId: UUID, expectedVersion: Long, input: LimitSetInput): LimitSetDto {
        val job = access.job(jobId, Permissions.TECH_WEATHER_WRITE)
        val set = locked(jobId, setId, expectedVersion)
        requireStatus(set, LimitSetStatus.DRAFT)
        val rows = validate(input.rows)
        jdbc.sql("DELETE FROM job_limit WHERE set_id=:id").param("id", setId.bytes()).update()
        insertRows(setId, rows)
        jdbc.sql("UPDATE job_limit_set SET updated_at=:t,updated_by=:a,version=version+1 WHERE id=:id")
            .param("t", now()).param("a", actor().bytes()).param("id", setId.bytes()).update()
        audit.record(AuditEntry(AuditAction.UPDATE, ENTITY, setId, job.branchId, mapOf("revisionNo" to set.revisionNo,
            "rows" to rows.map { "${it.parameterCode} ${it.comparison} ${it.threshold?.toPlainString()} (${it.operation})" })))
        return toDto(job, row(jobId, setId))
    }

    @Transactional
    fun submit(jobId: UUID, setId: UUID, expectedVersion: Long): LimitSetDto {
        val job = access.job(jobId, Permissions.TECH_WEATHER_WRITE)
        val set = locked(jobId, setId, expectedVersion)
        requireStatus(set, LimitSetStatus.DRAFT)
        if (set.rows.isEmpty()) throw BusinessRuleException("LIMITS_EMPTY", "Enter at least one operating limit before submitting.")
        val inactive = set.rows.map { it.parameterCode }.filter { it !in activeParameters().keys }
        if (inactive.isNotEmpty()) throw BusinessRuleException("PARAMETER_INACTIVE", "Remove the limits for parameters no longer in use: ${inactive.joinToString()}.")
        jdbc.sql("""UPDATE job_limit_set SET status='SUBMITTED',submitted_by=:a,submitted_at=:t,returned_reason=NULL,updated_at=:t,
            updated_by=:a,version=version+1 WHERE id=:id""").param("a", actor().bytes()).param("t", now()).param("id", setId.bytes()).update()
        status(job, set, LimitSetStatus.DRAFT, LimitSetStatus.SUBMITTED, null)
        return toDto(job, row(jobId, setId))
    }

    /** APPROVE (by someone other than the submitter; supersedes the previous approved set) or RETURN with a reason. */
    @Transactional
    fun decide(jobId: UUID, setId: UUID, expectedVersion: Long, request: LimitDecisionRequest): LimitSetDto {
        val job = access.job(jobId, Permissions.TECH_WEATHER_APPROVE)
        val set = locked(jobId, setId, expectedVersion)
        requireStatus(set, LimitSetStatus.SUBMITTED)
        when (request.decision) {
            "APPROVE" -> {
                if (set.submittedBy == actor()) {
                    throw BusinessRuleException("SELF_APPROVAL_FORBIDDEN", "Operating limits cannot be approved by the person who submitted them.")
                }
                setsOf(jobId).filter { it.status == LimitSetStatus.APPROVED }.forEach { older ->
                    jdbc.sql("UPDATE job_limit_set SET status='SUPERSEDED',updated_at=:t,version=version+1 WHERE id=:id")
                        .param("t", now()).param("id", older.id.bytes()).update()
                    status(job, older, LimitSetStatus.APPROVED, LimitSetStatus.SUPERSEDED, "Superseded by revision ${set.revisionNo}")
                }
                jdbc.sql("UPDATE job_limit_set SET status='APPROVED',decided_by=:a,decided_at=:t,updated_at=:t,version=version+1 WHERE id=:id")
                    .param("a", actor().bytes()).param("t", now()).param("id", setId.bytes()).update()
                status(job, set, LimitSetStatus.SUBMITTED, LimitSetStatus.APPROVED, null)
            }
            "RETURN" -> {
                val reason = request.reason?.trim().orEmpty()
                if (reason.isEmpty()) {
                    throw BadRequestException("REASON_REQUIRED", "A reason is required for this change.", listOf(FieldError("reason", "required", null)))
                }
                jdbc.sql("UPDATE job_limit_set SET status='DRAFT',returned_reason=:r,updated_at=:t,version=version+1 WHERE id=:id")
                    .param("r", reason.take(500)).param("t", now()).param("id", setId.bytes()).update()
                status(job, set, LimitSetStatus.SUBMITTED, LimitSetStatus.DRAFT, reason)
            }
            else -> throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.",
                listOf(FieldError("decision", "allowed", "APPROVE or RETURN.")))
        }
        return toDto(job, row(jobId, setId))
    }

    /** The job's approved limits with their revision number, or null; no access check (callers have checked the job). */
    internal fun approved(jobId: UUID): Pair<Int, List<LimitRowDto>>? =
        setsOf(jobId).firstOrNull { it.status == LimitSetStatus.APPROVED }?.let { it.revisionNo to rowDtos(it.rows) }

    // ---------- helpers ----------

    internal data class LimitSet(
        val id: UUID, val revisionNo: Int, val status: LimitSetStatus, val rows: List<LimitRow>, val submittedBy: UUID?, val submittedAt: Instant?,
        val decidedBy: UUID?, val decidedAt: Instant?, val returnedReason: String?, val updatedAt: Instant?, val version: Long,
    )

    private fun validate(rows: List<LimitRow>): List<LimitRow> {
        val errors = mutableListOf<FieldError>()
        if (rows.size > MAX_ROWS) errors += FieldError("rows", "size", "At most $MAX_ROWS limits.")
        val parameters = activeParameters()
        rows.forEachIndexed { i, r ->
            if (r.parameterCode !in parameters) errors += FieldError("rows[$i].parameterCode", "unknown", "Choose an active parameter.")
            if (r.operation.isBlank() || r.operation.trim().length > 100) errors += FieldError("rows[$i].operation", "required", "Enter the operation (max 100).")
            if (r.comparison !in COMPARISONS) errors += FieldError("rows[$i].comparison", "allowed", null)
            val t = r.threshold
            if (t == null) errors += FieldError("rows[$i].threshold", "required", null)
            else if (t.stripTrailingZeros().scale() > 2 || t.abs() >= BigDecimal(100_000_000)) {
                errors += FieldError("rows[$i].threshold", "range", "At most 2 decimals.")
            }
            if (r.action.isBlank() || r.action.trim().length > 300) errors += FieldError("rows[$i].action", "required", "Enter the action (max 300).")
        }
        if (errors.isNotEmpty()) throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", errors)
        return rows.map { it.copy(operation = it.operation.trim(), action = it.action.trim(), threshold = it.threshold!!.plain()) }
    }

    private fun insertRows(setId: UUID, rows: List<LimitRow>) = rows.forEachIndexed { i, r ->
        jdbc.sql("""INSERT INTO job_limit (id,set_id,sort_order,parameter_code,operation,comparison,threshold,action)
            VALUES (:id,:set,:sort,:p,:op,:c,:t,:a)""")
            .param("id", UuidV7.generate().bytes()).param("set", setId.bytes()).param("sort", i).param("p", r.parameterCode)
            .param("op", r.operation).param("c", r.comparison).param("t", r.threshold).param("a", r.action).update()
    }

    private fun activeParameters(): Map<String, Pair<String, String>> =
        jdbc.sql("SELECT code,label,unit FROM weather_parameter WHERE active=TRUE").query { rs, _ -> rs.getString(1) to (rs.getString(2) to rs.getString(3)) }
            .list().toMap()

    private fun rowDtos(rows: List<LimitRow>): List<LimitRowDto> {
        val labels = jdbc.sql("SELECT code,label,unit FROM weather_parameter").query { rs, _ -> rs.getString(1) to (rs.getString(2) to rs.getString(3)) }
            .list().toMap()
        return rows.map { r ->
            val (label, unit) = labels[r.parameterCode] ?: (r.parameterCode to "")
            LimitRowDto(r.parameterCode, label, unit, r.operation, r.comparison, r.threshold!!, r.action)
        }
    }

    private fun toDto(job: JobTechnicalRef, s: LimitSet): LimitSetDto {
        val names = users.names(listOfNotNull(s.submittedBy, s.decidedBy))
        val actions = buildList {
            when (s.status) {
                LimitSetStatus.DRAFT -> if (can(job, Permissions.TECH_WEATHER_WRITE)) addAll(listOf(LimitAction.EDIT, LimitAction.SUBMIT))
                LimitSetStatus.SUBMITTED -> if (can(job, Permissions.TECH_WEATHER_APPROVE)) {
                    if (s.submittedBy != actor()) add(LimitAction.APPROVE)
                    add(LimitAction.RETURN)
                }
                else -> Unit
            }
        }
        return LimitSetDto(s.id, s.revisionNo, s.status, rowDtos(s.rows), s.submittedBy?.let(names::get), s.submittedAt,
            s.decidedBy?.let(names::get), s.decidedAt, s.returnedReason, actions, s.updatedAt, s.version)
    }

    private fun setsOf(jobId: UUID): List<LimitSet> =
        jdbc.sql("SELECT id FROM job_limit_set WHERE job_id=:job ORDER BY revision_no DESC").param("job", jobId.bytes())
            .query { rs, _ -> rs.getBytes(1).uuid()!! }.list().map(::load)

    private fun row(jobId: UUID, setId: UUID): LimitSet {
        val owner = jdbc.sql("SELECT job_id FROM job_limit_set WHERE id=:id").param("id", setId.bytes())
            .query { rs, _ -> rs.getBytes(1).uuid() }.optional().orElse(null)
        if (owner != jobId) throw NotFoundException("Operating limits")
        return load(setId)
    }

    private fun locked(jobId: UUID, setId: UUID, expectedVersion: Long): LimitSet {
        row(jobId, setId)
        jdbc.sql("SELECT id FROM job_limit_set WHERE id=:id FOR UPDATE").param("id", setId.bytes()).query { rs, _ -> rs.getBytes(1) }.single()
        return load(setId).also { Versioning.checkCurrent(expectedVersion, it.version) }
    }

    private fun load(id: UUID): LimitSet {
        val rows = jdbc.sql("SELECT parameter_code,operation,comparison,threshold,action FROM job_limit WHERE set_id=:id ORDER BY sort_order")
            .param("id", id.bytes()).query { rs, _ ->
                LimitRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4).plain(), rs.getString(5))
            }.list()
        return jdbc.sql("""SELECT revision_no,status,submitted_by,submitted_at,decided_by,decided_at,returned_reason,updated_at,version
            FROM job_limit_set WHERE id=:id""").param("id", id.bytes()).query { rs, _ ->
            LimitSet(id, rs.getInt(1), LimitSetStatus.valueOf(rs.getString(2)), rows, rs.getBytes(3).uuid(), ts(rs.getObject(4, LocalDateTime::class.java)),
                rs.getBytes(5).uuid(), ts(rs.getObject(6, LocalDateTime::class.java)), rs.getString(7), ts(rs.getObject(8, LocalDateTime::class.java)),
                rs.getLong(9))
        }.optional().orElseThrow { NotFoundException("Operating limits") }
    }

    private fun requireStatus(s: LimitSet, expected: LimitSetStatus) {
        if (s.status != expected) throw BusinessRuleException("INVALID_TRANSITION", "Operating limits in status ${s.status} cannot do this.")
    }

    private fun status(job: JobTechnicalRef, s: LimitSet, from: LimitSetStatus, to: LimitSetStatus, reason: String?) =
        audit.record(AuditEntry(AuditAction.STATUS_CHANGE, ENTITY, s.id, job.branchId,
            mapOf("jobId" to job.id, "revisionNo" to s.revisionNo, "from" to from.name, "to" to to.name, "reason" to reason)))

    private fun can(job: JobTechnicalRef, permission: String) = policy.currentUser().scopeFor(permission).coversBranch(job.branchId)

    private fun actor() = policy.currentUser().id

    private fun now() = Uuids.toTimestamp(clock.instant().truncatedTo(ChronoUnit.MICROS))

    private fun ts(value: LocalDateTime?): Instant? = value?.let(Uuids::fromTimestamp)

    companion object {
        private const val ENTITY = "JOB_OPERATING_LIMITS"
        private const val MAX_ROWS = 30
        val OPEN = setOf(LimitSetStatus.DRAFT, LimitSetStatus.SUBMITTED)
        val COMPARISONS = setOf("ABOVE", "BELOW")
    }
}

/** Operating-limit endpoints; actions carry the revision's `If-Match` and return the whole revision. */
@RestController
@RequestMapping("/api/v1/jobs/{jobId}/operating-limits")
class OperatingLimitController(private val service: OperatingLimitService) {

    @GetMapping
    fun overview(@PathVariable jobId: UUID): LimitOverviewDto = service.overview(jobId)

    @PostMapping("/revisions")
    fun create(@PathVariable jobId: UUID): ResponseEntity<LimitSetDto> =
        service.create(jobId).let { ResponseEntity.status(201).eTag(Versioning.etag(it.version)).body(it) }

    @GetMapping("/revisions/{id}")
    fun get(@PathVariable jobId: UUID, @PathVariable id: UUID): ResponseEntity<LimitSetDto> =
        service.get(jobId, id).let { Versioning.ok(it, it.version) }

    @PutMapping("/revisions/{id}")
    fun update(
        @PathVariable jobId: UUID, @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?, @RequestBody input: LimitSetInput,
    ): ResponseEntity<LimitSetDto> = service.update(jobId, id, Versioning.requireIfMatch(ifMatch), input).let { Versioning.ok(it, it.version) }

    @PostMapping("/revisions/{id}/submit")
    fun submit(
        @PathVariable jobId: UUID, @PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<LimitSetDto> = service.submit(jobId, id, Versioning.requireIfMatch(ifMatch)).let { Versioning.ok(it, it.version) }

    @PostMapping("/revisions/{id}/decision")
    fun decide(
        @PathVariable jobId: UUID, @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?, @RequestBody request: LimitDecisionRequest,
    ): ResponseEntity<LimitSetDto> = service.decide(jobId, id, Versioning.requireIfMatch(ifMatch), request).let { Versioning.ok(it, it.version) }
}
