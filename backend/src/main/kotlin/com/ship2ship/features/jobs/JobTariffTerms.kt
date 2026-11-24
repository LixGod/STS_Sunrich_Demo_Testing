package com.ship2ship.features.jobs

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.web.BusinessRuleException
import com.ship2ship.core.web.ConflictException
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.Versioning
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

data class JobTariffTermsRequest(
    @field:Min(1) @field:Max(1440) val billingIncrementMinutes: Int,
    @field:Min(0) @field:Max(1440) val graceMinutes: Int,
    @field:Pattern(regexp = "NONE|ROUND_UP_TO_INCREMENT") val roundingRule: String,
    @field:NotBlank @field:Size(max = 500) val reason: String,
)

@Service
class JobTariffTermsService(
    private val jdbc: JdbcClient,
    private val policy: AccessPolicy,
    private val audit: AuditService,
    private val jobs: JobService,
    private val clock: Clock,
) {
    @Transactional
    fun update(jobId: UUID, expectedVersion: Long, request: JobTariffTermsRequest): JobDetailDto {
        policy.requireGlobal(Permissions.MASTER_FINANCE_WRITE, "JOB", jobId)
        val id = Uuids.toBytes(jobId)
        val job = jdbc.sql("SELECT branch_id,status,version FROM sts_job WHERE id=:id FOR UPDATE")
            .param("id", id).query { rs, _ -> Triple(Uuids.fromBytes(rs.getBytes(1))!!, rs.getString(2), rs.getLong(3)) }
            .optional().orElseThrow { NotFoundException("Job") }
        Versioning.checkCurrent(expectedVersion, job.third)
        if (job.second in setOf("CLOSED", "CANCELLED"))
            throw BusinessRuleException("JOB_LOCKED", "Reopen the job before changing its billing terms.")
        val invoiced = jdbc.sql("SELECT COUNT(*) FROM job_extra_invoice WHERE job_id=:job")
            .param("job", id).query(Long::class.java).single() > 0
        if (invoiced) throw ConflictException("EXTRA_HOUR_INVOICE_EXISTS", "An extra-hours invoice already uses these billing terms.")
        val before = jdbc.sql("SELECT billing_increment_minutes,grace_minutes,rounding_rule FROM tariff_snapshot WHERE job_id=:job")
            .param("job", id).query { rs, _ -> mapOf("billingIncrementMinutes" to rs.getObject(1),
                "graceMinutes" to rs.getObject(2), "roundingRule" to rs.getString(3)) }
            .optional().orElseThrow { NotFoundException("Job tariff") }
        jdbc.sql("""UPDATE tariff_snapshot SET billing_increment_minutes=:increment,grace_minutes=:grace,
            rounding_rule=:rounding WHERE job_id=:job""")
            .param("increment", request.billingIncrementMinutes).param("grace", request.graceMinutes)
            .param("rounding", request.roundingRule).param("job", id).update()
        jdbc.sql("UPDATE sts_job SET updated_at=:now,updated_by=:actor,version=version+1 WHERE id=:id")
            .param("now", Uuids.toTimestamp(clock.instant())).param("actor", Uuids.toBytes(policy.currentUser().id))
            .param("id", id).update()
        audit.record(AuditEntry(AuditAction.UPDATE, "TARIFF_SNAPSHOT", jobId, job.first,
            mapOf("before" to before, "after" to mapOf("billingIncrementMinutes" to request.billingIncrementMinutes,
                "graceMinutes" to request.graceMinutes, "roundingRule" to request.roundingRule),
                "reason" to request.reason.trim())))
        return jobs.get(jobId)
    }
}

@RestController
@RequestMapping("/api/v1/jobs/{jobId}/tariff-terms")
class JobTariffTermsController(private val service: JobTariffTermsService) {
    @PutMapping
    fun update(@PathVariable jobId: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: JobTariffTermsRequest,
    ): ResponseEntity<JobDetailDto> = service.update(jobId, Versioning.requireIfMatch(ifMatch), request)
        .let { Versioning.ok(it, it.version) }
}
