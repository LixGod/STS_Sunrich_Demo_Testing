package com.ship2ship.core.resources

import java.time.Instant
import java.util.UUID

/** Frozen into the JPO at submission; the technical feature never reads resource tables directly. */
data class AllocatedResourceSnapshot(
    val allocationId: UUID,
    val resourceId: UUID,
    val kind: String,
    val category: String?,
    val identifier: String,
    val dimensions: String?,
    val startAt: Instant,
    val endAt: Instant,
    val exceptionApproved: Boolean,
    val allocationVersion: Long = 0,
    val eligibilityMessages: List<String> = emptyList(),
    val collisionIds: List<UUID> = emptyList(),
)

interface ResourceAllocationLookup {
    fun activeForJob(jobId: UUID): List<AllocatedResourceSnapshot>
}
