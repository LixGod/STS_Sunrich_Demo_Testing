package com.ship2ship.features.technical

import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.jobs.JobLookup
import com.ship2ship.core.jobs.JobTechnicalRef
import com.ship2ship.core.jobs.JobTechnicalVesselRef
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.NotFoundException
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class JobTechnicalAccess(private val jobs: JobLookup, private val policy: AccessPolicy) {
    fun job(id: UUID, permission: String = Permissions.TECH_JOB_READ): JobTechnicalRef {
        val job = jobs.technical(id) ?: throw NotFoundException("Job")
        policy.requireBranch(Permissions.JOB_READ, job.branchId, "JOB", id)
        policy.requireBranch(permission, job.branchId, "JOB", id)
        if (!permission.endsWith(".READ")) jobs.requireWritable(id)
        return job
    }

    fun vessel(job: JobTechnicalRef, id: UUID): JobTechnicalVesselRef =
        job.vessels.firstOrNull { it.id == id }
            ?: throw BadRequestException("WRONG_JOB_VESSEL", "The vessel does not belong to this job.")
}

internal fun UUID.bytes(): ByteArray = Uuids.toBytes(this)
internal fun ByteArray?.uuid(): UUID? = Uuids.fromBytes(this)
