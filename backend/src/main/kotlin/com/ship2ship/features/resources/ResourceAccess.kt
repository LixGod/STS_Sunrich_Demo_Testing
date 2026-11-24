package com.ship2ship.features.resources

import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.jobs.JobLookup
import com.ship2ship.core.jobs.JobTechnicalRef
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.FieldError
import com.ship2ship.core.web.NotFoundException
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

@Component
class ResourceAccess(private val jobs: JobLookup, private val policy: AccessPolicy, private val clock: Clock) {
    fun job(id: UUID, permission: String): JobTechnicalRef {
        val job = jobs.technical(id) ?: throw NotFoundException("Job")
        policy.requireBranch(Permissions.JOB_READ, job.branchId, "JOB", id)
        policy.requireBranch(permission, job.branchId, "JOB", id)
        if (!permission.endsWith(".READ")) jobs.requireWritable(id)
        return job
    }

    /** Resource masters are company-wide: writing them needs a company-wide grant. */
    fun company(permission: String) = policy.requireGlobal(permission, "RESOURCE")
    fun actor(): UUID = policy.currentUser().id
    fun now(): LocalDateTime = Uuids.toTimestamp(clock.instant().truncatedTo(ChronoUnit.MICROS))
}

internal fun UUID.bytes(): ByteArray = Uuids.toBytes(this)
internal fun ByteArray?.uuid(): UUID? = Uuids.fromBytes(this)

internal fun invalid(field: String, message: String): Nothing =
    throw BadRequestException("VALIDATION_FAILED", "The resource has invalid fields.", listOf(FieldError(field, "invalid", message)))

internal fun text(value: String, field: String, max: Int): String = value.trim().also {
    if (it.isEmpty() || it.length > max) invalid(field, "Enter a value of at most $max characters.")
}

internal fun optional(value: String?, max: Int): String? = value?.trim()?.takeIf(String::isNotEmpty)?.also {
    if (it.length > max) invalid("text", "Use at most $max characters.")
}
