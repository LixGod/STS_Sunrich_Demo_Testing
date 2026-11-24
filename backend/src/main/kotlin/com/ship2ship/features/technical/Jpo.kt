package com.ship2ship.features.technical

import com.ship2ship.core.resources.AllocatedResourceSnapshot
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// Joint Plan of Operation ("Appendix C", FR-TECH-02). The structure follows the four pages of the JPO workbook;
// every choice is a fixed code so the output is clean and checkable (no free-text tick boxes).

enum class JpoStatus { DRAFT, DONE, SUBMITTED, APPROVED, ISSUED, SUPERSEDED }

enum class JpoAction { EDIT, SUBMIT, APPROVE, RETURN, ISSUE, ACCEPT, NEW_REVISION }

/** How a section's content is supplied: entered on the JPO now, or taken from its own module once that exists. */
enum class SectionSource { MANUAL, LINKED }

data class OperatingLimit(
    val parameter: String = "",
    val limit: String = "",
    val unit: String? = null,
    val action: String = "",
)

data class CargoRate(val cargoGradeCode: String = "", val maxRatePerLine: BigDecimal? = null, val rateUnit: String? = null)

/** Equipment supplied by Ship2Ship (page 4). Category: CARGO_HOSE, VAPOUR_HOSE, PRIMARY_FENDER, SECONDARY_FENDER. */
data class EquipmentRow(val category: String = "", val number: Int? = null, val dimensions: String? = null, val strings: Int? = null)

data class AncillaryRow(val item: String = "", val value: String? = null)

/** Everything a person enters on the JPO. Job facts (vessels, cargo, voyage values) are not stored here. */
data class JpoContent(
    // Page 1
    val operationDate: LocalDate? = null,
    val purpose: String? = null,
    val reverseLightering: Boolean = false,
    val similarLoaBerthing: Boolean = false,
    val mooringDiagramDocumentId: UUID? = null,
    val mooringNotes: String? = null,
    // Page 2
    val constantHeadingCourseSpeed: String? = null,
    val manoeuvringCourseSpeed: String? = null,
    /** HELM_ORDERS | COURSE_ORDERS */
    val shipHandling: String? = null,
    val rudderType: String? = null,
    val maxRudderAngleDeg: BigDecimal? = null,
    /** LEFT | RIGHT */
    val handedness: String? = null,
    /** CPP | TELEGRAPH */
    val constantHeadingPropulsion: String? = null,
    val manoeuvringPropulsion: String? = null,
    /** NOT_DP | DP | SEAGOING */
    val constantHeadingDpMode: String? = null,
    val manoeuvringDpMode: String? = null,
    val constantHeadingDeadSlow: String? = null,
    val manoeuvringDeadSlowKn: BigDecimal? = null,
    /** BALLAST | LADEN */
    val manoeuvringDeadSlowCondition: String? = null,
    val speedMonitoring: String? = null,
    val rendezvous: String? = null,
    val transferLocation: String? = null,
    /** AT_ANCHOR | UNDERWAY | WITH_TUGS */
    val approach: String? = null,
    val approachNotes: String? = null,
    val supportCraft: String? = null,
    val additionalRequirements: String? = null,
    val mooringRopeFailure: String? = null,
    val operatingLimits: List<OperatingLimit> = emptyList(),
    /** LEVEL_1 | LEVEL_2 | LEVEL_3 (ISPS) */
    val securityLevel: String? = null,
    val contingency: String? = null,
    val personnelTransfer: String? = null,
    val cargoRates: List<CargoRate> = emptyList(),
    // Page 3
    val oilBoom: String? = null,
    val unmooringDiagramDocumentId: UUID? = null,
    val unmooringNotes: String? = null,
    // Page 4
    val equipment: List<EquipmentRow> = emptyList(),
    val ancillary: List<AncillaryRow> = emptyList(),
)

// ---------- job facts frozen at submission ----------

data class SnapshotVessel(val jobVesselId: UUID, val role: String, val name: String?, val imo: String?, val etaUtc: Instant?, val etaTimezone: String?)

data class SnapshotCargo(val gradeCode: String?, val gradeName: String?, val quantity: BigDecimal, val unitCode: String?)

data class SnapshotVoyageValue(
    val role: String, val fieldCode: String, val label: String, val unit: String, val value: String,
    val sourceDocumentId: UUID?, val sourcePage: Int?,
)

/** An accepted weather report as listed in the JPO (3e). */
data class SnapshotWeatherReport(
    val kind: String, val source: String, val providerName: String?, val issuedAt: Instant?, val validFrom: Instant?, val validTo: Instant?,
    val observedAt: Instant?, val area: String?,
)

data class SnapshotChecklist(val role: String?, val code: String, val name: String, val status: String)

data class JpoSnapshot(
    val jobNumber: String,
    val operationType: String?,
    val vessels: List<SnapshotVessel>,
    val cargo: List<SnapshotCargo>,
    /** Verified voyage-condition values per vessel (3b); only verified versions are used. */
    val voyage: List<SnapshotVoyageValue>,
    val voyageVersionIds: Map<String, UUID?>,
    val checklists: List<SnapshotChecklist>,
    val warnings: List<String>,
    val takenAt: Instant,
    val linkedMooring: LinkedMooringPlan? = null,
    /** The job's approved operating limits (3e); null while none are approved, then the JPO's own table is used. */
    val operatingLimits: List<LimitRowDto>? = null,
    val operatingLimitsRevision: Int? = null,
    val weatherReports: List<SnapshotWeatherReport> = emptyList(),
    val allocatedResources: List<AllocatedResourceSnapshot> = emptyList(),
    val annexVersion: Int? = null,
    val annexSections: List<AnnexSectionDto> = emptyList(),
)

// ---------- API ----------

data class JpoBlocker(val code: String, val message: String)

data class JpoAcceptanceDto(
    val party: String, val personName: String, val acceptedAt: Instant, val signedDocumentId: UUID?, val recordedByName: String?, val recordedAt: Instant,
)

data class AnnexSectionDto(val code: String, val title: String, val body: String, val appliesWhen: String)

data class JpoRevisionDto(
    val id: UUID,
    val revisionNo: Int,
    val status: JpoStatus,
    val content: JpoContent,
    /** Job facts: frozen at submission, live while a draft. */
    val snapshot: JpoSnapshot,
    val snapshotFrozen: Boolean,
    /** Where each manually entered section will be linked from once its module exists. */
    val sources: Map<String, SectionSource>,
    val blockers: List<JpoBlocker>,
    val warnings: List<String>,
    val annexTemplateVersion: Int?,
    val annexSections: List<AnnexSectionDto>,
    val submittedByName: String?,
    val submittedAt: Instant?,
    val decidedByName: String?,
    val decidedAt: Instant?,
    val returnedReason: String?,
    val issuedByName: String?,
    val issuedAt: Instant?,
    val pdfSha256: String?,
    val acceptances: List<JpoAcceptanceDto>,
    val allowedActions: List<JpoAction>,
    val updatedAt: Instant?,
    val version: Long,
)

data class JpoOverviewDto(
    val jobId: UUID,
    val jobNumber: String,
    /** Newest first. */
    val revisions: List<JpoRevisionSummaryDto>,
    val canCreate: Boolean,
)

data class JpoRevisionSummaryDto(val id: UUID, val revisionNo: Int, val status: JpoStatus, val issuedAt: Instant?, val acceptanceCount: Int)

data class JpoReturnRequest(val reason: String = "")

data class JpoAcceptanceRequest(
    val party: String = "",
    val personName: String = "",
    val acceptedAt: Instant? = null,
    val signedDocumentId: UUID? = null,
)
