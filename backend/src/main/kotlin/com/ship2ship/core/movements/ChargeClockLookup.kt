package com.ship2ship.core.movements

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class ChargeClockResult(
    val startMovementId: UUID?, val stopMovementId: UUID?, val startAt: Instant?, val stopAt: Instant?,
    val basisHours: Int, val elapsedHours: BigDecimal?, val extraHours: BigDecimal?,
    val tariffTermsStatus: String, val billableExtraUnits: BigDecimal?, val additionalCharge: BigDecimal?,
    val currency: String?, val final: Boolean,
)

/** The job's charge clock for finance and reports, without a dependency on the movements feature. */
interface ChargeClockLookup {
    fun forJob(jobId: UUID): ChargeClockResult
}
