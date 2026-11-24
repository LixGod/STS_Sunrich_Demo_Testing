package com.ship2ship.features.operations

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.jobs.JobTechnicalRef
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.security.ReferenceLookup
import com.ship2ship.core.security.UserDirectory
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.BusinessRuleException
import com.ship2ship.core.web.FieldError
import com.ship2ship.core.web.NotFoundException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Grade-wise cargo progress (FR-OPS-02, UAT-07): cumulative readings per job cargo line in that line's unit. A total
 * lower than the previous reading is refused unless recorded as a CORRECTION (OPS.CARGO.CORRECT, with a reason); a
 * FINAL reading completes the grade. Quantity, rate, balance and the estimated completion are arithmetic on recorded
 * readings, for display only.
 */
@Service
class CargoService(
    private val jdbc: JdbcClient,
    private val access: OperationsAccess,
    private val references: ReferenceLookup,
    private val users: UserDirectory,
    private val audit: AuditService,
    private val clock: Clock,
) {
    @Transactional
    fun record(jobId: UUID, lineNo: Int, input: CargoReadingInput): CargoGradeDto {
        val job = access.job(jobId, Permissions.OPS_CARGO_WRITE)
        val line = lines(job).firstOrNull { it.lineNo == lineNo } ?: throw NotFoundException("Cargo line")
        jdbc.sql("SELECT id FROM job_cargo WHERE id=:id FOR UPDATE").param("id", line.id.bytes()).query { rs, _ -> rs.getBytes(1) }.single()
        val readings = readings(line.id)
        val last = readings.lastOrNull()
        val errors = mutableListOf<FieldError>()
        if (input.kind !in KINDS) errors += FieldError("kind", "allowed", null)
        val readAt = input.readAt?.truncatedTo(ChronoUnit.MINUTES)
        if (readAt == null) errors += FieldError("readAt", "required", null)
        else if (readAt.isAfter(clock.instant().plus(5, ChronoUnit.MINUTES))) errors += FieldError("readAt", "future", "Cannot be in the future.")
        val value = input.cumulative
        if (value == null) errors += FieldError("cumulative", "required", null)
        else if (value.signum() < 0 || value.stripTrailingZeros().scale() > 3 || value >= BigDecimal("1000000000000000")) {
            errors += FieldError("cumulative", "range", "Zero or more, at most 3 decimals.")
        }
        if (input.unitCode != line.unitCode) errors += FieldError("unitCode", "unit", "Readings for this grade are in ${line.unitCode}.")
        if ((input.remarks?.length ?: 0) > 500) errors += FieldError("remarks", "size", null)
        if (input.certifiedDocumentId != null && !isJobDocument(job, input.certifiedDocumentId)) {
            errors += FieldError("certifiedDocumentId", "unknown", "Choose a document of this job.")
        }
        if (input.kind == "CORRECTION" && input.reason.isNullOrBlank()) errors += FieldError("reason", "required", "Give the reason for the correction.")
        if (errors.isNotEmpty()) throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", errors)
        if (last != null && readAt!!.isBefore(last.readAt))
            throw BusinessRuleException("READING_OUT_OF_ORDER", "A reading cannot be earlier than the latest reading.")
        if (input.kind == "CORRECTION") {
            if (!access.can(job, Permissions.OPS_CARGO_CORRECT)) throw AccessDeniedException("OPS.CARGO.CORRECT")
        } else if (last != null) {
            if (last.kind == "FINAL") throw BusinessRuleException("CARGO_COMPLETED", "This grade is complete. Record a correction to change it.")
            if (value!! < last.cumulative) {
                throw BusinessRuleException("CARGO_TOTAL_FALLING", "The cumulative total cannot fall. Record a correction with a reason.")
            }
        }
        val id = UuidV7.generate()
        jdbc.sql("""INSERT INTO cargo_reading (id,job_cargo_id,read_at,cumulative,unit_id,kind,reason,certified_document_id,remarks,recorded_by,recorded_at)
            VALUES (:id,:line,:at,:v,:unit,:kind,:reason,:doc,:remarks,:a,:t)""")
            .param("id", id.bytes()).param("line", line.id.bytes()).param("at", readAt!!.stamp()).param("v", value)
            .param("unit", line.unitId.bytes()).param("kind", input.kind).param("reason", input.reason?.trim()?.ifEmpty { null }?.take(500))
            .param("doc", input.certifiedDocumentId?.bytes()).param("remarks", input.remarks?.trim()?.ifEmpty { null })
            .param("a", access.actor().bytes()).param("t", clock.instant().truncatedTo(ChronoUnit.MICROS).stamp()).update()
        audit.record(AuditEntry(if (input.kind == "CORRECTION") AuditAction.UPDATE else AuditAction.CREATE, ENTITY, id, job.branchId,
            mapOf("jobId" to jobId, "lineNo" to lineNo, "kind" to input.kind, "readAt" to readAt, "cumulative" to value,
                "previous" to last?.cumulative, "reason" to input.reason)))
        return grades(job).first { it.lineNo == lineNo }
    }

    internal fun grades(job: JobTechnicalRef): List<CargoGradeDto> {
        val all = lines(job)
        val names = users.names(all.flatMap { l -> readings(l.id).map { it.recordedBy } })
        return all.map { line ->
            val readings = readings(line.id)
            val dtos = readings.mapIndexed { i, r ->
                val previous = readings.getOrNull(i - 1)
                val qty = previous?.let { r.cumulative - it.cumulative }
                val hours = previous?.let { BigDecimal(Duration.between(it.readAt, r.readAt).toMinutes()).divide(BigDecimal(60), 6, RoundingMode.HALF_UP) }
                val rate = if (qty != null && hours != null && hours.signum() > 0 && r.kind != "CORRECTION") qty.divide(hours, 3, RoundingMode.HALF_UP).plainNumber() else null
                CargoReadingDto(r.id, r.readAt, r.cumulative, r.kind, r.reason, r.documentId, r.remarks, names[r.recordedBy], r.recordedAt, qty, rate)
            }
            val last = readings.lastOrNull()
            val cumulative = last?.cumulative
            val balance = line.planned - (cumulative ?: BigDecimal.ZERO)
            val lastRate = dtos.lastOrNull()?.ratePerHour
            val completed = last?.kind == "FINAL"
            val etc = if (!completed && last != null && lastRate != null && lastRate.signum() > 0 && balance.signum() > 0) {
                last.readAt.plusSeconds(balance.divide(lastRate, 6, RoundingMode.HALF_UP).multiply(BigDecimal(3600)).toLong())
            } else null
            CargoGradeDto(line.lineNo, line.gradeCode, line.gradeName, line.unitCode, line.planned, cumulative, balance, lastRate, etc, completed, dtos)
        }
    }

    private data class Line(
        val id: UUID, val lineNo: Int, val gradeCode: String?, val gradeName: String?, val planned: BigDecimal, val unitId: UUID, val unitCode: String?,
    )

    private data class Reading(
        val id: UUID, val readAt: Instant, val cumulative: BigDecimal, val kind: String, val reason: String?, val documentId: UUID?,
        val remarks: String?, val recordedBy: UUID, val recordedAt: Instant,
    )

    private fun lines(job: JobTechnicalRef): List<Line> {
        val rows = jdbc.sql("SELECT id,line_no,cargo_grade_id,quantity,unit_id FROM job_cargo WHERE job_id=:job ORDER BY line_no")
            .param("job", job.id.bytes()).query { rs, _ ->
                arrayOf(rs.getBytes(1).uuid()!!, rs.getInt(2), rs.getBytes(3).uuid()!!, rs.getBigDecimal(4), rs.getBytes(5).uuid()!!)
            }.list()
        val grades = references.cargoGrades(rows.map { it[2] as UUID })
        val units = references.units(rows.map { it[4] as UUID })
        return rows.map {
            val grade = grades[it[2] as UUID]
            Line(it[0] as UUID, it[1] as Int, grade?.code, grade?.name, (it[3] as BigDecimal).stripTrailingZeros().let { q -> if (q.scale() < 0) q.setScale(0) else q },
                it[4] as UUID, units[it[4] as UUID]?.code)
        }
    }

    /** Readings in time order (same time: in the order recorded). */
    private fun readings(lineId: UUID): List<Reading> =
        jdbc.sql("""SELECT id,read_at,cumulative,kind,reason,certified_document_id,remarks,recorded_by,recorded_at FROM cargo_reading
            WHERE job_cargo_id=:line ORDER BY read_at, recorded_at""").param("line", lineId.bytes()).query { rs, _ ->
            Reading(rs.getBytes(1).uuid()!!, rs.getObject(2, LocalDateTime::class.java).instant()!!,
                rs.getBigDecimal(3).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }, rs.getString(4), rs.getString(5),
                rs.getBytes(6).uuid(), rs.getString(7), rs.getBytes(8).uuid()!!, rs.getObject(9, LocalDateTime::class.java).instant()!!)
        }.list()

    private fun isJobDocument(job: JobTechnicalRef, documentId: UUID): Boolean {
        val owner = jdbc.sql("SELECT owner_type,owner_id FROM document_version WHERE id=:id").param("id", documentId.bytes())
            .query { rs, _ -> rs.getString(1) to rs.getBytes(2).uuid() }.optional().orElse(null) ?: return false
        return (owner.first == "JOB" && owner.second == job.id) || (owner.first == "JOB_VESSEL" && job.vessels.any { it.id == owner.second })
    }

    private fun BigDecimal.plainNumber(): BigDecimal = stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }

    companion object {
        private const val ENTITY = "CARGO_READING"
        val KINDS = setOf("NORMAL", "FINAL", "CORRECTION")
    }
}
