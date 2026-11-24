package com.ship2ship.core.jobs

import java.util.UUID

/** Trusted finance refresh after an accepted commercial change, before final invoice issue. */
fun interface FinalInvoiceDrafts {
    fun refreshFromQuotation(jobId: UUID)
}
