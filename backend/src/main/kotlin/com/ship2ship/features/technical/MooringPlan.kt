package com.ship2ship.features.technical

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class MooringStatus { DRAFT, SUBMITTED, APPROVED, ISSUED, SUPERSEDED }
enum class MooringAction { EDIT, VERIFY_VALUE, SUBMIT, APPROVE, RETURN, ISSUE }

/** Metres are the only geometry unit. IDs remain stable across edits and copied revisions. */
data class MooringModel(
    val schemaVersion: Int = 1,
    val vessels: List<MooringVessel> = emptyList(),
    val layers: List<MooringLayer> = emptyList(),
    val objects: List<MooringObject> = emptyList(),
    val lines: List<MooringLine> = emptyList(),
    val particulars: List<MooringParticular> = emptyList(),
    val rulerStepM: BigDecimal? = null,
)

data class MooringVessel(
    val jobVesselId: UUID,
    val heading: String? = null,
    val lateralOffsetM: BigDecimal? = null,
    val orientation: String = "BOW_UP",
)

data class MooringLayer(
    val id: UUID,
    val jobVesselId: UUID,
    val documentVersionId: UUID,
    val xM: BigDecimal = BigDecimal.ZERO,
    val yM: BigDecimal = BigDecimal.ZERO,
    val scale: BigDecimal = BigDecimal.ONE,
    val visible: Boolean = true,
)

data class MooringObject(
    val id: UUID,
    val type: String,
    val xM: BigDecimal,
    val yM: BigDecimal,
    val jobVesselId: UUID? = null,
    val label: String? = null,
)

data class MooringCapacity(
    val columnCode: String,
    val value: BigDecimal? = null,
    val unit: String? = null,
    val sourceDocumentId: UUID? = null,
    val sourcePage: Int? = null,
    val sourceText: String? = null,
)

data class MooringLine(
    val id: UUID,
    val label: String,
    val jobVesselId: UUID,
    val fromPointId: UUID? = null,
    val toPointId: UUID? = null,
    val berthingOrder: Int? = null,
    val unmooringOrder: Int? = null,
    val capacities: List<MooringCapacity> = emptyList(),
)

data class MooringParticular(
    val jobVesselId: UUID,
    val code: String,
    val value: BigDecimal? = null,
    val unit: String = "m",
    val sourceDocumentId: UUID? = null,
    val sourcePage: Int? = null,
    val sourceText: String? = null,
)

data class MooringDimension(
    val jobVesselId: UUID,
    val code: String,
    val value: BigDecimal?,
    val unit: String,
    val particularsVersionId: UUID?,
    val sourceDocumentId: UUID?,
    val sourcePage: Int?,
    val verified: Boolean,
)

data class MooringVesselFacts(
    val jobVesselId: UUID,
    val role: String,
    val name: String?,
    val imo: String?,
    val dimensions: List<MooringDimension>,
)

data class MooringCapacityColumn(
    val code: String,
    val label: String,
    val definition: String?,
    val unit: String?,
    val evidenceRequirement: String?,
    val status: String,
    val version: Long,
)

data class MooringBlocker(val code: String, val message: String)
data class MooringVerifiedValue(val key: String, val verifiedBy: UUID, val verifiedAt: Instant)
data class MooringReferenceFile(val documentVersionId: UUID, val fileId: UUID, val sha256: String)

data class MooringSnapshot(
    val vessels: List<MooringVesselFacts>,
    val capacityColumns: List<MooringCapacityColumn>,
    val verifiedValues: List<MooringVerifiedValue>,
    val takenAt: Instant,
    val referenceFiles: List<MooringReferenceFile> = emptyList(),
)

/** Frozen with a JPO submission so later mooring revisions never change its PDF. */
data class LinkedMooringPlan(
    val revisionId: UUID,
    val revisionNo: Int,
    val model: MooringModel,
    val snapshot: MooringSnapshot,
)

data class MooringRevisionDto(
    val id: UUID,
    val revisionNo: Int,
    val status: MooringStatus,
    val model: MooringModel,
    val snapshot: MooringSnapshot,
    val snapshotFrozen: Boolean,
    val blockers: List<MooringBlocker>,
    val warnings: List<String>,
    val submittedByName: String?,
    val submittedAt: Instant?,
    val decidedByName: String?,
    val decidedAt: Instant?,
    val returnedReason: String?,
    val issuedByName: String?,
    val issuedAt: Instant?,
    val pdfSha256: String?,
    val allowedActions: List<MooringAction>,
    val version: Long,
)

data class MooringRevisionSummary(val id: UUID, val revisionNo: Int, val status: MooringStatus, val issuedAt: Instant?)
data class MooringOverviewDto(
    val jobId: UUID,
    val jobNumber: String,
    val revisions: List<MooringRevisionSummary>,
    val canCreate: Boolean,
)

data class MooringDecisionRequest(val decision: String = "", val reason: String? = null)
data class MooringVerifyValueRequest(val key: String = "")
data class MooringColumnRequest(val definition: String = "", val unit: String = "", val evidenceRequirement: String = "")
data class MooringRuleRequest(val toleranceM: BigDecimal? = null, val active: Boolean = false)
data class MooringRuleDto(val toleranceM: BigDecimal?, val active: Boolean, val version: Long)
