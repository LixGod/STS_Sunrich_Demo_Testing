package com.ship2ship.core.vessels

import java.math.BigDecimal
import java.util.UUID

/**
 * Lets the technical feature publish verified particulars to the vessel master without touching its tables
 * (implemented by the masters feature).
 */
interface VesselMasterUpdates {
    fun exists(vesselId: UUID): Boolean

    /** Copies the verified main dimensions to the vessel and marks its particulars VERIFIED. */
    fun markVerified(vesselId: UUID, loaM: BigDecimal?, beamM: BigDecimal?, dwtT: BigDecimal?)
}
