package com.ship2ship.features.technical

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

// Weather reports and job operating limits (increment 3e, FR-TECH-04). Limits are entered and approved per job; the app
// only compares reported values with approved thresholds and shows warnings. It never sets or suggests a limit.

data class WeatherParameterDto(val code: String, val label: String, val unit: String, val sortOrder: Int, val active: Boolean, val version: Long)

data class WeatherParameterInput(
    val code: String = "", val label: String = "", val unit: String = "", val sortOrder: Int = 0, val active: Boolean = true,
)

// ---------- operating limits ----------

enum class LimitSetStatus { DRAFT, SUBMITTED, APPROVED, SUPERSEDED }

enum class LimitAction { EDIT, SUBMIT, APPROVE, RETURN }

/** One limit as entered. `comparison` ABOVE warns when a value is above the threshold, BELOW when below. */
data class LimitRow(
    val parameterCode: String = "",
    val operation: String = "",
    val comparison: String = "ABOVE",
    val threshold: BigDecimal? = null,
    val action: String = "",
)

data class LimitRowDto(
    val parameterCode: String, val parameterLabel: String, val unit: String, val operation: String, val comparison: String,
    val threshold: BigDecimal, val action: String,
)

data class LimitSetDto(
    val id: UUID,
    val revisionNo: Int,
    val status: LimitSetStatus,
    val rows: List<LimitRowDto>,
    val submittedByName: String?,
    val submittedAt: Instant?,
    val decidedByName: String?,
    val decidedAt: Instant?,
    val returnedReason: String?,
    val allowedActions: List<LimitAction>,
    val updatedAt: Instant?,
    val version: Long,
)

data class LimitSetSummaryDto(val id: UUID, val revisionNo: Int, val status: LimitSetStatus, val decidedAt: Instant?)

data class LimitOverviewDto(
    val jobId: UUID,
    val jobNumber: String,
    /** Newest first. */
    val revisions: List<LimitSetSummaryDto>,
    val approved: LimitSetDto?,
    val canCreate: Boolean,
)

data class LimitSetInput(val rows: List<LimitRow> = emptyList())

data class LimitDecisionRequest(val decision: String = "", val reason: String? = null)

// ---------- weather reports ----------

data class WeatherValueInput(val at: Instant? = null, val parameterCode: String = "", val value: BigDecimal? = null)

/** FORECAST: provider, issue time and validity window. OBSERVATION: the time observed. Values are optional with a file. */
data class WeatherReportInput(
    val kind: String = "",
    val source: String = "",
    val providerName: String? = null,
    val issuedAt: Instant? = null,
    val validFrom: Instant? = null,
    val validTo: Instant? = null,
    val observedAt: Instant? = null,
    val area: String? = null,
    val notes: String? = null,
    val values: List<WeatherValueInput> = emptyList(),
)

data class WeatherValueDto(val at: Instant, val parameterCode: String, val value: BigDecimal)

data class WeatherFileDto(val id: UUID, val name: String, val contentType: String, val scanState: String)

data class WeatherReportDto(
    val id: UUID,
    val kind: String,
    val source: String,
    val providerName: String?,
    val issuedAt: Instant?,
    val validFrom: Instant?,
    val validTo: Instant?,
    val observedAt: Instant?,
    val area: String?,
    val notes: String?,
    val file: WeatherFileDto?,
    val values: List<WeatherValueDto>,
    val status: String,
    val recordedByName: String?,
    val recordedAt: Instant,
    val reviewedByName: String?,
    val reviewedAt: Instant?,
    val reviewReason: String?,
    /** A forecast whose validity has ended. */
    val expired: Boolean,
    val canEdit: Boolean,
    val canReview: Boolean,
    val version: Long,
)

/** A reported value outside an approved limit. Information only: it never blocks or decides anything. */
data class WeatherWarningDto(
    val reportId: UUID,
    val kind: String,
    val reportStatus: String,
    val at: Instant,
    val parameterCode: String,
    val parameterLabel: String,
    val unit: String,
    val value: BigDecimal,
    val comparison: String,
    val threshold: BigDecimal,
    val operation: String,
    val action: String,
    val message: String,
)

data class WeatherOverviewDto(
    val jobId: UUID,
    val jobNumber: String,
    /** Branch time zone for display. */
    val timezone: String,
    val parameters: List<WeatherParameterDto>,
    /** Newest first. */
    val reports: List<WeatherReportDto>,
    val warnings: List<WeatherWarningDto>,
    val limitsApproved: Boolean,
    val canRecord: Boolean,
)

data class WeatherReviewRequest(val decision: String = "", val reason: String? = null)

/** Without trailing zeros and never in exponent form (20.00 → 20, 2.50 → 2.5). */
internal fun BigDecimal.plain(): BigDecimal = stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
