package com.ship2ship.features.finalreport

import com.ship2ship.core.audit.Uuids
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

// Final operation report (increment 6a, FR-REP-01): sections follow the sample report. What people enter is the
// content; job records (events, cargo, incidents, Q88 references) are the snapshot, frozen at submission.

enum class ReportStatus { DRAFT, SUBMITTED, APPROVED, ISSUED, SUPERSEDED }

enum class ReportAction { EDIT, SUBMIT, APPROVE, RETURN, ISSUE }

/** Equipment used (fenders, hoses, other) as printed: description (make, size, length) and quantity. */
data class EquipmentLine(val category: String = "FENDER", val description: String = "", val quantity: Int? = null)

/** Supervision and attendance: capacity (e.g. Mooring Master) and name. */
data class AttendanceLine(val capacity: String = "", val name: String = "")

data class ReportContent(
    val vhfChannel: String? = null,
    val summary: String? = null,
    val remarks: String? = null,
    val equipment: List<EquipmentLine> = emptyList(),
    val attendance: List<AttendanceLine> = emptyList(),
    /** Job documents listed as attachments (not embedded). */
    val attachmentDocumentIds: List<UUID> = emptyList(),
)

// ---------- frozen job records ----------

data class SnapVessel(val role: String, val name: String?, val imo: String?, val type: String?)
data class SnapCargo(val gradeName: String?, val gradeCode: String?, val quantity: BigDecimal, val unit: String?)
data class SnapQ88(val vessel: String?, val imo: String?, val documentName: String, val uploadedAt: Instant)
data class SnapEvent(val phase: String, val label: String, val vessel: String?, val occurredAt: Instant?, val startedAt: Instant?,
    val endedAt: Instant?, val remarks: String?, val code: String = "")
data class SnapReading(val readAt: Instant, val cumulative: BigDecimal, val kind: String, val since: BigDecimal?, val ratePerHour: BigDecimal?,
    val remarks: String?)
data class SnapGrade(val gradeName: String?, val gradeCode: String?, val unit: String?, val planned: BigDecimal, val finalQuantity: BigDecimal?,
    val completed: Boolean, val readings: List<SnapReading>)
data class SnapDelay(val category: String, val startedAt: Instant, val endedAt: Instant?, val description: String)
data class SnapIncident(val category: String, val severity: String, val occurredAt: Instant, val description: String, val status: String,
    val closingNote: String?)
data class SnapSof(val revisionNo: Int, val issuedAt: Instant?, val pdfSha256: String?)
data class SnapAttachment(val id: UUID, val name: String, val type: String?)

data class ReportSnapshot(
    val jobNumber: String,
    val client: String?,
    val timezone: String,
    val operationType: String?,
    val vessels: List<SnapVessel>,
    val cargo: List<SnapCargo>,
    val q88: List<SnapQ88>,
    val events: List<SnapEvent>,
    val grades: List<SnapGrade>,
    val delays: List<SnapDelay>,
    val incidents: List<SnapIncident>,
    val sof: SnapSof?,
    val attachments: List<SnapAttachment>,
    val takenAt: Instant,
)

// ---------- API ----------

data class ReportIssue(val code: String, val message: String)

data class ReportRevisionDto(
    val id: UUID,
    val revisionNo: Int,
    /** Job number and revision until the owner's reference format is confirmed. */
    val reference: String,
    val documentId: UUID,
    val templateVersion: Int,
    val status: ReportStatus,
    val content: ReportContent,
    val snapshot: ReportSnapshot,
    val snapshotFrozen: Boolean,
    val blockers: List<ReportIssue>,
    val warnings: List<ReportIssue>,
    val sources: Map<String, String>,
    val submittedByName: String?,
    val submittedAt: Instant?,
    val decidedByName: String?,
    val decidedAt: Instant?,
    val returnedReason: String?,
    val issuedByName: String?,
    val issuedAt: Instant?,
    val pdfSha256: String?,
    val allowedActions: List<ReportAction>,
    val updatedAt: Instant?,
    val version: Long,
)

data class ReportOverviewDto(
    val jobId: UUID, val jobNumber: String,
    val revisions: List<ReportRevisionSummaryDto>,
    val canCreate: Boolean,
)

data class ReportRevisionSummaryDto(val id: UUID, val revisionNo: Int, val status: ReportStatus, val issuedAt: Instant?)

data class ReportDecision(val decision: String = "", val reason: String? = null)

internal fun UUID.bytes(): ByteArray = Uuids.toBytes(this)
internal fun ByteArray?.uuid(): UUID? = Uuids.fromBytes(this)
internal fun LocalDateTime?.instant(): Instant? = this?.let(Uuids::fromTimestamp)
internal fun Instant.stamp(): LocalDateTime = Uuids.toTimestamp(this)
internal fun BigDecimal.plain(): BigDecimal = stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
