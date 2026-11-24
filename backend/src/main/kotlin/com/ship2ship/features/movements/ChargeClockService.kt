package com.ship2ship.features.movements

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.movements.ChargeClockLookup
import com.ship2ship.core.movements.ChargeClockResult
import com.ship2ship.core.jobs.JobLookup
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.security.ReferenceLookup
import com.ship2ship.core.web.BusinessRuleException
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.Versioning
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class ClockSelectionInput(val startMovementId: UUID, val stopMovementId: UUID? = null, val reason: String? = null)
data class ClockDecisionInput(val decision: String, val reason: String)
data class BasisInput(val basisHours: Int, val reason: String)
data class AdjustmentInput(val hours: BigDecimal, val reason: String)
data class DeliveryInput(val outcome: String)
data class ClockSelectionDto(val startMovementId: UUID, val stopMovementId: UUID?, val basisHours: Int,
    val overrideReason: String?, val pendingStartId: UUID?, val pendingStopId: UUID?, val pendingReason: String?,
    val requestedBy: UUID?, val version: Long)
data class NoticeDto(val id: UUID, val status: String, val snapshotJson: String, val deliveryOutcome: String?, val version: Long)
data class AdjustmentDto(val id: UUID, val hours: BigDecimal, val reason: String, val status: String,
    val requestedBy: UUID, val approvedBy: UUID?, val version: Long)
data class ClockOverview(val clock: ChargeClockResult, val selection: ClockSelectionDto?,
    val expiryAt: Instant?, val countdownSeconds: Long?, val exceeded: Boolean,
    val forecastCompletion: Instant?, val approvedAdjustmentHours: BigDecimal,
    val notice: NoticeDto?, val adjustments: List<AdjustmentDto>)

@Service
class ChargeClockService(private val jdbc: JdbcClient, private val access: MovementAccess,
    private val movements: MovementService, private val references: ReferenceLookup,
    private val json: JsonMapper, private val audit: AuditService, private val jobs:JobLookup) : ChargeClockLookup {
    private data class Tariff(val base: Int, val rate: BigDecimal, val increment: Int?, val grace: Int?,
        val rounding: String?, val currency: String)

    @Transactional(readOnly = true)
    override fun forJob(jobId: UUID): ChargeClockResult = calculate(jobId, selection(jobId) ?: activeFallback(jobId))

    /** Scheduler entry point: no user session is needed to detect an elapsed charging block. */
    @Transactional
    fun captureOverrun(jobId:UUID) {
        val status=jdbc.sql("SELECT status FROM sts_job WHERE id=:id FOR UPDATE").param("id",jobId.bytes()).query(String::class.java).single()
        if(status in setOf("CLOSED","CANCELLED")) return
        ensureSynced(jobId)
        val selected=selection(jobId) ?: return
        val result=calculate(jobId,selected)
        if(result.extraHours?.signum()==1) ensureNotice(jobId,result,selected)
    }

    @Transactional
    fun overview(jobId: UUID): ClockOverview {
        access.job(jobId, Permissions.OPS_MOVEMENT_READ)
        ensureSynced(jobId)
        val selected = selection(jobId)
        val result = calculate(jobId, selected)
        val expiry = result.startAt?.plus(Duration.ofHours(result.basisHours.toLong()))
        val countdown = expiry?.let { Duration.between(result.stopAt ?: access.instant(), it).seconds }
        if (selected != null && countdown != null && countdown < 0) ensureNotice(jobId, result, selected)
        val adjustments = adjustments(jobId)
        return ClockOverview(result, selected, expiry, countdown, countdown?.let { it < 0 } ?: false,
            forecast(jobId), adjustments.filter { it.status == "APPROVED" }.fold(BigDecimal.ZERO) { total, it -> total + it.hours },
            notice(jobId), adjustments)
    }

    @Transactional
    fun select(jobId: UUID, expected: Long?, input: ClockSelectionInput): ClockSelectionDto {
        val job = access.job(jobId, Permissions.OPS_MOVEMENT_WRITE)
        validatePair(jobId, input.startMovementId, input.stopMovementId)
        val current = selectionLocked(jobId)
        val now = access.now()
        if (current == null) {
            if (expected != null) Versioning.checkCurrent(expected, -1)
            val tariff = tariff(jobId)
            jdbc.sql("""INSERT INTO job_charge_clock (job_id,start_movement_id,stop_movement_id,basis_hours,
                created_at,created_by,updated_at,updated_by,version) VALUES (:job,:start,:stop,:basis,:now,:actor,:now,:actor,0)""")
                .param("job", jobId.bytes()).param("start", input.startMovementId.bytes())
                .param("stop", input.stopMovementId?.bytes()).param("basis", tariff.base)
                .param("now", now).param("actor", access.actor().bytes()).update()
            change(jobId, "INITIAL", "Initial designation", "{}", json.writeValueAsString(input))
        } else {
            Versioning.checkCurrent(expected ?: bad("version", "If-Match is required."), current.version)
            if (current.pendingStartId != null) throw BusinessRuleException("CLOCK_CHANGE_PENDING", "Decide the pending selection first.")
            if (current.startMovementId == input.startMovementId && current.stopMovementId == input.stopMovementId)
                bad("startMovementId", "Choose a different clock designation.")
            val why = reason(input.reason)
            jdbc.sql("""UPDATE job_charge_clock SET pending_start_id=:start,pending_stop_id=:stop,pending_reason=:reason,
                requested_by=:actor,approved_by=NULL,decided_at=NULL,updated_at=:now,updated_by=:actor,version=version+1
                WHERE job_id=:job""")
                .param("start", input.startMovementId.bytes()).param("stop", input.stopMovementId?.bytes())
                .param("reason", why).param("actor", access.actor().bytes()).param("now", now)
                .param("job", jobId.bytes()).update()
            change(jobId, "REQUESTED", why, json.writeValueAsString(current), json.writeValueAsString(input))
        }
        audit.record(AuditEntry(AuditAction.UPDATE, "JOB_CHARGE_CLOCK", jobId, job.branchId,
            mapOf("action" to if (current == null) "INITIAL" else "REQUESTED")))
        return selection(jobId)!!
    }

    @Transactional
    fun decide(jobId: UUID, expected: Long, input: ClockDecisionInput): ClockSelectionDto {
        val job = access.job(jobId, Permissions.OPS_MOVEMENT_APPROVE)
        val current = selectionLocked(jobId) ?: throw NotFoundException("Charge clock")
        Versioning.checkCurrent(expected, current.version)
        val start = current.pendingStartId ?: throw BusinessRuleException("INVALID_TRANSITION", "No selection is awaiting approval.")
        if (current.requestedBy == access.actor()) throw BusinessRuleException("SELF_APPROVAL_FORBIDDEN", "A different person must decide the selection.")
        val why = reason(input.reason)
        val approve = when (input.decision) { "APPROVE" -> true; "RETURN" -> false
            else -> bad("decision", "Choose APPROVE or RETURN.") }
        if (approve) validatePair(jobId, start, current.pendingStopId)
        jdbc.sql("""UPDATE job_charge_clock SET start_movement_id=:start,stop_movement_id=:stop,
            pending_start_id=NULL,pending_stop_id=NULL,pending_reason=NULL,approved_by=:actor,decided_at=:now,
            updated_at=:now,updated_by=:actor,version=version+1 WHERE job_id=:job""")
            .param("start", if (approve) start.bytes() else current.startMovementId.bytes())
            .param("stop", (if (approve) current.pendingStopId else current.stopMovementId)?.bytes())
            .param("actor", access.actor().bytes()).param("now", access.now()).param("job", jobId.bytes()).update()
        change(jobId, input.decision, why, json.writeValueAsString(current), json.writeValueAsString(selection(jobId)))
        audit.record(AuditEntry(AuditAction.UPDATE, "JOB_CHARGE_CLOCK", jobId, job.branchId,
            mapOf("decision" to input.decision, "reason" to why)))
        return selection(jobId)!!
    }

    @Transactional
    fun overrideBasis(jobId: UUID, expected: Long, input: BasisInput): ClockSelectionDto {
        val job = access.job(jobId, Permissions.OPS_MOVEMENT_APPROVE)
        val current = selectionLocked(jobId) ?: throw NotFoundException("Charge clock")
        Versioning.checkCurrent(expected, current.version)
        if (input.basisHours !in setOf(24, 48, 72)) bad("basisHours", "Choose 24, 48 or 72 hours.")
        val why = reason(input.reason)
        jdbc.sql("""UPDATE job_charge_clock SET basis_hours=:basis,override_reason=:reason,
            updated_at=:now,updated_by=:actor,version=version+1 WHERE job_id=:job""")
            .param("basis", input.basisHours).param("reason", why).param("now", access.now())
            .param("actor", access.actor().bytes()).param("job", jobId.bytes()).update()
        change(jobId, "BASIS", why, json.writeValueAsString(current), json.writeValueAsString(selection(jobId)))
        audit.record(AuditEntry(AuditAction.UPDATE, "JOB_CHARGE_CLOCK", jobId, job.branchId,
            mapOf("basisHours" to input.basisHours, "reason" to why)))
        return selection(jobId)!!
    }

    @Transactional
    fun requestAdjustment(jobId: UUID, input: AdjustmentInput): AdjustmentDto {
        val job = access.job(jobId, Permissions.OPS_MOVEMENT_WRITE)
        ensureSynced(jobId)
        if (selection(jobId) == null) throw NotFoundException("Charge clock")
        if (input.hours.scale() > 4 || input.hours.abs() > BigDecimal("99999999")) bad("hours", "Use at most four decimal places.")
        val why = reason(input.reason)
        val id = UuidV7.generate()
        jdbc.sql("""INSERT INTO clock_adjustment (id,job_id,hours,reason,requested_by,created_at)
            VALUES (:id,:job,:hours,:reason,:actor,:now)""")
            .param("id", id.bytes()).param("job", jobId.bytes()).param("hours", input.hours)
            .param("reason", why).param("actor", access.actor().bytes()).param("now", access.now()).update()
        audit.record(AuditEntry(AuditAction.CREATE, "CLOCK_ADJUSTMENT", id, job.branchId,
            mapOf("hours" to input.hours, "reason" to why)))
        return adjustments(jobId).first { it.id == id }
    }

    @Transactional
    fun decideAdjustment(jobId: UUID, id: UUID, expected: Long, input: ClockDecisionInput): AdjustmentDto {
        val job = access.job(jobId, Permissions.OPS_MOVEMENT_APPROVE)
        val item = adjustments(jobId).firstOrNull { it.id == id } ?: throw NotFoundException("Adjustment")
        Versioning.checkCurrent(expected, item.version)
        if (item.status != "PENDING") throw BusinessRuleException("INVALID_TRANSITION", "Adjustment is already decided.")
        if (item.requestedBy == access.actor()) throw BusinessRuleException("SELF_APPROVAL_FORBIDDEN", "A second person must approve the adjustment.")
        val status = when (input.decision) { "APPROVE" -> "APPROVED"; "RETURN" -> "RETURNED"
            else -> bad("decision", "Choose APPROVE or RETURN.") }
        val why = reason(input.reason)
        jdbc.sql("""UPDATE clock_adjustment SET status=:status,approved_by=:actor,decided_at=:now,version=version+1
            WHERE id=:id AND job_id=:job AND version=:version""")
            .param("status", status).param("actor", access.actor().bytes()).param("now", access.now())
            .param("id", id.bytes()).param("job", jobId.bytes()).param("version", expected)
            .update().also { if (it != 1) Versioning.checkCurrent(expected, adjustments(jobId).first { a -> a.id == id }.version) }
        change(jobId, "ADJUSTMENT_$status", why, json.writeValueAsString(item), "{}")
        audit.record(AuditEntry(AuditAction.UPDATE, "CLOCK_ADJUSTMENT", id, job.branchId,
            mapOf("decision" to status, "reason" to why)))
        return adjustments(jobId).first { it.id == id }
    }

    @Transactional
    fun release(jobId: UUID, expected: Long): NoticeDto {
        val job = access.job(jobId, Permissions.OPS_MOVEMENT_APPROVE)
        val current = notice(jobId) ?: throw NotFoundException("Additional-hours notice")
        Versioning.checkCurrent(expected, current.version)
        if (current.status != "DRAFT") throw BusinessRuleException("INVALID_TRANSITION", "Notice was already released.")
        jdbc.sql("""UPDATE additional_hours_notice SET status='RELEASED',released_at=:now,released_by=:actor,
            version=version+1 WHERE id=:id AND version=:version""")
            .param("now", access.now()).param("actor", access.actor().bytes()).param("id", current.id.bytes())
            .param("version", expected).update().also { if (it != 1) Versioning.checkCurrent(expected, notice(jobId)!!.version) }
        audit.record(AuditEntry(AuditAction.STATUS_CHANGE, "ADDITIONAL_HOURS_NOTICE", current.id, job.branchId, mapOf("status" to "RELEASED")))
        return notice(jobId)!!
    }

    @Transactional
    fun delivery(jobId: UUID, expected: Long, input: DeliveryInput): NoticeDto {
        val job = access.job(jobId, Permissions.OPS_MOVEMENT_WRITE)
        val current = notice(jobId) ?: throw NotFoundException("Additional-hours notice")
        Versioning.checkCurrent(expected, current.version)
        if (current.status != "RELEASED") throw BusinessRuleException("INVALID_TRANSITION", "Release the notice before recording delivery.")
        val outcome = reason(input.outcome)
        jdbc.sql("""UPDATE additional_hours_notice SET delivery_outcome=:outcome,delivered_at=:now,version=version+1
            WHERE id=:id AND version=:version""")
            .param("outcome", outcome).param("now", access.now()).param("id", current.id.bytes())
            .param("version", expected).update().also { if (it != 1) Versioning.checkCurrent(expected, notice(jobId)!!.version) }
        audit.record(AuditEntry(AuditAction.UPDATE, "ADDITIONAL_HOURS_NOTICE", current.id, job.branchId, mapOf("deliveryOutcome" to outcome)))
        return notice(jobId)!!
    }

    internal fun calculate(jobId: UUID, selected: ClockSelectionDto?): ChargeClockResult {
        val terms = tariff(jobId)
        val start = selected?.let { movements.row(jobId, it.startMovementId).departedAt }
        val stop = selected?.stopMovementId?.let { movements.row(jobId, it).returnedAt }
        val final = stop != null
        val elapsedSeconds = start?.let { Duration.between(it, stop ?: access.instant()).seconds.coerceAtLeast(0) }
        val elapsed = elapsedSeconds?.let { BigDecimal(it).divide(BigDecimal(3600), 6, RoundingMode.HALF_UP) }
        val basis = selected?.basisHours ?: terms.base
        val extra = elapsed?.subtract(BigDecimal(basis))?.max(BigDecimal.ZERO)
        val confirmed = terms.increment != null && terms.grace != null && terms.rounding != null
        val adjustment = jdbc.sql("SELECT COALESCE(SUM(hours),0) FROM clock_adjustment WHERE job_id=:job AND status='APPROVED'")
            .param("job",jobId.bytes()).query(BigDecimal::class.java).single()
        val units = if (confirmed && final && elapsedSeconds != null)
            billableUnits(elapsedSeconds, basis, terms.increment, terms.grace, terms.rounding, adjustment) else null
        val minor = references.currency(terms.currency)?.minorUnits ?: 2
        val amount = units?.multiply(terms.rate)?.setScale(minor, RoundingMode.HALF_UP)
        return ChargeClockResult(selected?.startMovementId, selected?.stopMovementId, start, stop, basis,
            elapsed, extra, if (confirmed) "CONFIRMED" else "PENDING_TARIFF_TERMS", units, amount, terms.currency, final)
    }

    private fun tariff(jobId: UUID): Tariff = jdbc.sql("""SELECT base_hours,extra_hour_rate,billing_increment_minutes,
        grace_minutes,rounding_rule,currency FROM tariff_snapshot WHERE job_id=:job""")
        .param("job", jobId.bytes()).query { rs, _ -> Tariff(rs.getInt(1), rs.getBigDecimal(2),
            rs.getInt(3).takeUnless { rs.wasNull() }, rs.getInt(4).takeUnless { rs.wasNull() },
            rs.getString(5), rs.getString(6)) }.optional().orElseThrow { NotFoundException("Job tariff") }

    private fun selection(jobId: UUID): ClockSelectionDto? = jdbc.sql("""SELECT c.start_movement_id,c.stop_movement_id,c.basis_hours,
        c.override_reason,c.pending_start_id,c.pending_stop_id,c.pending_reason,c.requested_by,c.version
        FROM job_charge_clock c JOIN craft_movement m ON m.id=c.start_movement_id
        WHERE c.job_id=:job AND m.status='ACTIVE'""").param("job", jobId.bytes())
        .query { rs, _ -> ClockSelectionDto(rs.getBytes(1).uuid()!!, rs.getBytes(2).uuid(), rs.getInt(3),
            rs.getString(4), rs.getBytes(5).uuid(), rs.getBytes(6).uuid(), rs.getString(7),
            rs.getBytes(8).uuid(), rs.getLong(9)) }.optional().orElse(null)

    private fun activeFallback(jobId: UUID): ClockSelectionDto? = jdbc.sql("""SELECT id,returned_at FROM craft_movement
        WHERE job_id=:job AND status='ACTIVE' ORDER BY departed_at DESC,id DESC LIMIT 1""").param("job", jobId.bytes())
        .query { rs, _ -> val id = rs.getBytes(1).uuid()!!
            ClockSelectionDto(id, if (rs.instant("returned_at") != null) id else null, tariff(jobId).base, null, null, null, null, null, 0)
        }.optional().orElse(null)

    private fun selectionLocked(jobId: UUID): ClockSelectionDto? {
        jdbc.sql("SELECT id FROM sts_job WHERE id=:id FOR UPDATE").param("id", jobId.bytes())
            .query { rs, _ -> rs.getBytes(1) }.single()
        ensureSynced(jobId)
        return selection(jobId)
    }

    private fun ensureSynced(jobId: UUID) {
        val active = jdbc.sql("""SELECT id,returned_at,created_by FROM craft_movement
            WHERE job_id=:job AND status='ACTIVE' ORDER BY departed_at DESC,id DESC LIMIT 1""")
            .param("job", jobId.bytes())
            .query { rs, _ -> Triple(rs.getBytes(1).uuid()!!, rs.instant("returned_at"), rs.getBytes(3)) }.optional().orElse(null) ?: return
        val stop = if (active.second != null) active.first else null
        val current = selection(jobId)
        if (current != null && current.startMovementId == active.first && current.stopMovementId == stop) return
        val now = access.now()
        jdbc.sql("""INSERT INTO job_charge_clock (job_id,start_movement_id,stop_movement_id,basis_hours,created_at,created_by,updated_at,updated_by,version)
            VALUES (:job,:start,:stop,:basis,:now,:actor,:now,:actor,0)
            ON DUPLICATE KEY UPDATE start_movement_id=:start,stop_movement_id=:stop,updated_at=:now,updated_by=:actor""")
            .param("job", jobId.bytes()).param("start", active.first.bytes()).param("stop", stop?.bytes())
            .param("basis", tariff(jobId).base).param("now", now).param("actor", active.third).update()
    }

    private fun validatePair(jobId: UUID, startId: UUID, stopId: UUID?) {
        val start = movements.row(jobId, startId)
        if (start.status != "ACTIVE") bad("startMovementId", "Choose an active departure.")
        if (stopId != null) {
            val stop = movements.row(jobId, stopId)
            if (stop.status != "ACTIVE" || stop.returnedAt == null)
                bad("stopMovementId", "Choose an active returned movement.")
            if (!stop.returnedAt.isAfter(start.departedAt)) bad("stopMovementId", "Return must follow the designated departure.")
        }
    }

    private fun ensureNotice(jobId: UUID, result: ChargeClockResult, selected: ClockSelectionDto) {
        jdbc.sql("SELECT job_id FROM job_charge_clock WHERE job_id=:job FOR UPDATE")
            .param("job", jobId.bytes()).query { rs, _ -> rs.getBytes(1) }.single()
        val episode = listOf(result.startMovementId, result.stopMovementId, result.startAt,
            result.stopAt, result.basisHours).joinToString("|")
        val exists = jdbc.sql("SELECT COUNT(*) FROM additional_hours_notice WHERE job_id=:job AND episode_key=:episode")
            .param("job", jobId.bytes()).param("episode", episode).query(Long::class.java).single()
        if (exists > 0) return
        val noticeId = UuidV7.generate()
        jdbc.sql("""INSERT INTO additional_hours_notice (id,job_id,episode_key,snapshot_json,created_at)
            VALUES (:id,:job,:episode,:snapshot,:now)""")
            .param("id", noticeId.bytes()).param("job", jobId.bytes()).param("episode", episode)
            .param("snapshot", json.writeValueAsString(mapOf("clock" to result, "selectionVersion" to selected.version,
                "tariff" to tariff(jobId)))).param("now", access.now()).update()
        val job = jobs.technical(jobId) ?: throw NotFoundException("Job")
        audit.record(AuditEntry(AuditAction.CREATE, "ADDITIONAL_HOURS_NOTICE", noticeId, job.branchId, mapOf("episode" to episode)))
    }

    private fun notice(jobId: UUID): NoticeDto? = jdbc.sql("""SELECT id,status,snapshot_json,delivery_outcome,version
        FROM additional_hours_notice WHERE job_id=:job ORDER BY created_at DESC,id DESC LIMIT 1""")
        .param("job", jobId.bytes()).query { rs, _ -> NoticeDto(rs.getBytes(1).uuid()!!,
            rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)) }.optional().orElse(null)

    private fun adjustments(jobId: UUID): List<AdjustmentDto> = jdbc.sql("""SELECT id,hours,reason,status,requested_by,
        approved_by,version FROM clock_adjustment WHERE job_id=:job ORDER BY created_at,id""")
        .param("job", jobId.bytes()).query { rs, _ -> AdjustmentDto(rs.getBytes(1).uuid()!!, rs.getBigDecimal(2),
            rs.getString(3), rs.getString(4), rs.getBytes(5).uuid()!!, rs.getBytes(6).uuid(), rs.getLong(7)) }.list()

    private fun change(jobId: UUID, decision: String, why: String, before: String, after: String) {
        jdbc.sql("""INSERT INTO clock_selection_change (id,job_id,before_json,after_json,reason,decision,changed_at,changed_by)
            VALUES (:id,:job,:before,:after,:reason,:decision,:now,:actor)""")
            .param("id", UuidV7.generate().bytes()).param("job", jobId.bytes()).param("before", before)
            .param("after", after).param("reason", why).param("decision", decision)
            .param("now", access.now()).param("actor", access.actor().bytes()).update()
    }

    private fun forecast(jobId: UUID): Instant? {
        data class Reading(val line: UUID, val planned: BigDecimal, val at: Instant, val cumulative: BigDecimal, val kind: String)
        val recent = jdbc.sql("""SELECT line_id,planned,read_at,cumulative,kind FROM (
            SELECT jc.id line_id,jc.quantity planned,cr.read_at,cr.cumulative,cr.kind,
                ROW_NUMBER() OVER (PARTITION BY jc.id ORDER BY cr.read_at DESC,cr.recorded_at DESC) rn
            FROM job_cargo jc JOIN cargo_reading cr ON cr.job_cargo_id=jc.id WHERE jc.job_id=:job
            ) latest WHERE rn<=2 ORDER BY line_id,read_at DESC""")
            .param("job", jobId.bytes()).query { rs, _ -> Reading(rs.getBytes(1).uuid()!!, rs.getBigDecimal(2),
                rs.instant("read_at")!!, rs.getBigDecimal(4), rs.getString(5)) }.list()
        return recent.groupBy { it.line }.values.mapNotNull { pair ->
            if (pair.size < 2 || pair[0].kind == "FINAL") return@mapNotNull null
            val seconds = Duration.between(pair[1].at, pair[0].at).seconds
            val transferred = pair[0].cumulative - pair[1].cumulative
            val remaining = pair[0].planned - pair[0].cumulative
            if (seconds <= 0 || transferred.signum() <= 0 || remaining.signum() <= 0) return@mapNotNull null
            pair[0].at.plusSeconds(remaining.multiply(BigDecimal(seconds))
                .divide(transferred, 0, RoundingMode.HALF_UP).toLong())
        }.maxOrNull()
    }

    companion object {
        /** Rates are per hour; grace is removed before the approved increment/rounding rule. */
        internal fun billableUnits(elapsedSeconds: Long, basisHours: Int, incrementMinutes: Int,
            graceMinutes: Int, rule: String, adjustmentHours: BigDecimal = BigDecimal.ZERO): BigDecimal {
            require(incrementMinutes > 0 && graceMinutes >= 0)
            val extraSeconds = (BigDecimal(elapsedSeconds - basisHours.toLong() * 3600 - graceMinutes.toLong() * 60)
                + adjustmentHours.multiply(BigDecimal(3600))).max(BigDecimal.ZERO)
            val increment = BigDecimal(incrementMinutes * 60L)
            val billedSeconds = when (rule) {
                "NONE" -> extraSeconds
                "ROUND_UP_TO_INCREMENT" -> extraSeconds.divide(increment,0,RoundingMode.CEILING).multiply(increment)
                else -> throw BusinessRuleException("TARIFF_TERMS_PENDING", "The job tariff has an unsupported rounding rule.")
            }
            return billedSeconds.divide(BigDecimal(3600), 6, RoundingMode.HALF_UP)
        }
    }
}
