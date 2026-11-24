package com.ship2ship.features.operations

import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.jobs.JobLookup
import com.ship2ship.core.jobs.JobTechnicalRef
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.OrganisationLookup
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.web.NotFoundException
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

// Live operations and cargo (increment 4b): ETA revisions, operation events and the Statement of Facts, grade-wise cargo
// readings, delays and incidents. Times are UTC instants, shown in the branch time zone. Nothing is deleted.

// ---------- lists ----------

data class ListItemDto(
    val code: String, val label: String, val sortOrder: Int, val active: Boolean, val version: Long,
    /** Event types only. */
    val phase: String? = null,
    val interval: Boolean? = null,
)

data class ListItemInput(
    val code: String = "", val label: String = "", val sortOrder: Int = 0, val active: Boolean = true,
    val phase: String? = null, val interval: Boolean? = null,
)

// ---------- ETA ----------

data class EtaRevisionDto(
    val revisionNo: Int, val etaUtc: Instant, val timezone: String, val source: String, val note: String?,
    val recordedByName: String?, val recordedAt: Instant?,
    /** Hours later (+) or earlier (-) than the previous ETA; null for the first. */
    val changeHours: BigDecimal?,
)

data class VesselEtaDto(val jobVesselId: UUID, val role: String, val name: String?, val current: EtaRevisionDto?, val history: List<EtaRevisionDto>)

data class EtaInput(val jobVesselId: UUID? = null, val etaUtc: Instant? = null, val timezone: String = "", val source: String = "", val note: String? = null)

// ---------- events and SOF ----------

data class OperationEventDto(
    val id: UUID, val typeCode: String, val typeLabel: String, val phase: String, val interval: Boolean, val jobVesselId: UUID?,
    val occurredAt: Instant?, val startedAt: Instant?, val endedAt: Instant?, val remarks: String?, val source: String,
    val status: String, val voidReason: String?, val recordedByName: String?, val recordedAt: Instant, val corrected: Boolean,
    val version: Long,
    val clientEventId: UUID? = null, val deviceRecordedAt: Instant? = null,
)

data class EventInput(
    val typeCode: String = "",
    val jobVesselId: UUID? = null,
    val occurredAt: Instant? = null,
    val startedAt: Instant? = null,
    val endedAt: Instant? = null,
    val remarks: String? = null,
    val source: String = "LIVE",
    /** Required when correcting an existing event. */
    val reason: String? = null,
    val clientEventId: UUID? = null,
    val deviceRecordedAt: Instant? = null,
    val conflictReason: String? = null,
)

data class ReasonInput(val reason: String = "")

data class ChangeDto(val versionNo: Long, val before: Map<String, Any?>, val reason: String, val changedByName: String?, val changedAt: Instant)

data class SofRevisionDto(
    val id: UUID, val revisionNo: Int, val status: String, val eventCount: Int, val preparedByName: String?, val preparedAt: Instant,
    val issuedByName: String?, val issuedAt: Instant?, val pdfSha256: String?, val canIssue: Boolean, val canWithdraw: Boolean, val version: Long,
)

/** An event as frozen into a SOF revision. */
data class SofEvent(
    val typeLabel: String, val phase: String, val vessel: String?, val occurredAt: Instant?, val startedAt: Instant?, val endedAt: Instant?,
    val remarks: String?, val source: String,
)

// ---------- cargo ----------

data class CargoReadingDto(
    val id: UUID, val readAt: Instant, val cumulative: BigDecimal, val kind: String, val reason: String?, val certifiedDocumentId: UUID?,
    val remarks: String?, val recordedByName: String?, val recordedAt: Instant,
    /** Since the previous reading (derived for display). */
    val quantitySincePrevious: BigDecimal?,
    val ratePerHour: BigDecimal?,
)

data class CargoGradeDto(
    val lineNo: Int, val gradeCode: String?, val gradeName: String?, val unitCode: String?, val planned: BigDecimal,
    val cumulative: BigDecimal?, val balance: BigDecimal, val lastRatePerHour: BigDecimal?,
    /** Estimate from the last interval's rate; null when it cannot be estimated. Display only. */
    val estimatedCompletion: Instant?,
    val completed: Boolean, val readings: List<CargoReadingDto>,
)

data class CargoReadingInput(
    val readAt: Instant? = null,
    val cumulative: BigDecimal? = null,
    val unitCode: String = "",
    /** NORMAL, FINAL or CORRECTION (CORRECTION needs OPS.CARGO.CORRECT and a reason). */
    val kind: String = "NORMAL",
    val reason: String? = null,
    val certifiedDocumentId: UUID? = null,
    val remarks: String? = null,
)

// ---------- delays and incidents ----------

data class FileRefDto(val id: UUID, val fileId: UUID, val name: String, val contentType: String, val scanState: String)

data class DelayDto(
    val id: UUID, val categoryCode: String, val categoryLabel: String, val startedAt: Instant, val endedAt: Instant?, val description: String,
    val status: String, val voidReason: String?, val recordedByName: String?, val recordedAt: Instant, val files: List<FileRefDto>,
    val corrected: Boolean, val version: Long,
)

data class DelayInput(
    val categoryCode: String = "", val startedAt: Instant? = null, val endedAt: Instant? = null, val description: String = "",
    val reason: String? = null,
)

data class IncidentActionDto(val id: UUID, val text: String, val ownerName: String?, val dueDate: LocalDate?, val doneAt: Instant?)

data class IncidentActionInput(val text: String = "", val ownerName: String? = null, val dueDate: LocalDate? = null, val done: Boolean = false)

data class IncidentDto(
    val id: UUID, val categoryCode: String, val categoryLabel: String, val severityCode: String, val severityLabel: String,
    val occurredAt: Instant, val description: String, val immediateActions: String?, val status: String,
    val reportedByName: String?, val reportedAt: Instant, val reviewStartedByName: String?, val reviewStartedAt: Instant?,
    val closedByName: String?, val closedAt: Instant?, val closingNote: String?, val actions: List<IncidentActionDto>,
    val files: List<FileRefDto>, val corrected: Boolean, val canReview: Boolean, val canClose: Boolean, val version: Long,
)

data class IncidentInput(
    val categoryCode: String = "", val severityCode: String = "", val occurredAt: Instant? = null, val description: String = "",
    val immediateActions: String? = null, val actions: List<IncidentActionInput> = emptyList(), val reason: String? = null,
)

/** START_REVIEW, or CLOSE with a closing note (by someone other than the reporter). */
data class IncidentReviewInput(val decision: String = "", val note: String? = null)

// ---------- console ----------

data class OperationsPermissionsDto(
    val write: Boolean, val approve: Boolean, val cargoWrite: Boolean, val cargoCorrect: Boolean, val incidentRead: Boolean,
    val incidentWrite: Boolean, val incidentReview: Boolean,
)

data class OperationsOverviewDto(
    val jobId: UUID,
    val jobNumber: String,
    val timezone: String,
    val vessels: List<VesselEtaDto>,
    val eventTypes: List<ListItemDto>,
    /** Chronological. */
    val events: List<OperationEventDto>,
    val cargo: List<CargoGradeDto>,
    val delays: List<DelayDto>,
    val incidents: List<IncidentDto>,
    val delayCategories: List<ListItemDto>,
    val incidentCategories: List<ListItemDto>,
    val incidentSeverities: List<ListItemDto>,
    /** Newest first. */
    val sofRevisions: List<SofRevisionDto>,
    val permissions: OperationsPermissionsDto,
)

data class OperationsJobDto(
    val jobId: UUID, val jobNumber: String, val status: String, val motherVessel: String?, val daughterVessel: String?,
    val lastEventLabel: String?, val lastEventAt: Instant?, val openIncidents: Int,
)

// ---------- shared helpers ----------

internal fun UUID.bytes(): ByteArray = Uuids.toBytes(this)
internal fun ByteArray?.uuid(): UUID? = Uuids.fromBytes(this)
internal fun LocalDateTime?.instant(): Instant? = this?.let(Uuids::fromTimestamp)
internal fun Instant.stamp(): LocalDateTime = Uuids.toTimestamp(this)

/** Job access for operations: the job must be visible (JOB.READ) and the operations permission held for its branch. */
@Component
class OperationsAccess(private val jobs: JobLookup, private val policy: AccessPolicy, private val organisation: OrganisationLookup) {
    fun job(id: UUID, permission: String): JobTechnicalRef {
        val job = jobs.technical(id) ?: throw NotFoundException("Job")
        policy.requireBranch(Permissions.JOB_READ, job.branchId, "JOB", id)
        policy.requireBranch(permission, job.branchId, "JOB", id)
        if (!permission.endsWith(".READ")) jobs.requireWritable(id)
        return job
    }

    fun can(job: JobTechnicalRef, permission: String): Boolean =
        policy.currentUser().scopeFor(permission).coversBranch(job.branchId)

    fun actor(): UUID = policy.currentUser().id

    fun zone(job: JobTechnicalRef): ZoneId =
        organisation.branch(job.branchId)?.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of("UTC")
}
