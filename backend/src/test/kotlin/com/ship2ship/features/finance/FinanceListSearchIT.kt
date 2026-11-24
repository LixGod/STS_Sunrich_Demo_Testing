package com.ship2ship.features.finance

import com.ship2ship.features.technical.JobTestSupport
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import java.time.LocalDate
import kotlin.test.assertTrue

class FinanceListSearchIT : JobTestSupport() {
    @Test
    fun `invoice search finds a job number and receipt search finds a client`() {
        val job = newJob()
        val finance = login("ops.manager")
        val draft = ok(call(finance, "POST", "/api/v1/jobs/${job["id"].asString()}/invoices"))
        val invoices = ok(mvc.perform(get("/api/v1/finance/invoices").param("q", job["jobNumber"].asString()).session(finance)))
        assertTrue(invoices["items"].any { it["id"].asString() == draft["id"].asString() })

        val receipt = ok(call(login("finance"), "POST", "/api/v1/finance/receipts", mapOf(
            "clientId" to CLIENT_CHARTERER, "receivedOn" to LocalDate.now().toString(),
            "amount" to "100", "currency" to "USD", "allocations" to emptyList<Any>(),
        )))
        val receipts = ok(mvc.perform(get("/api/v1/finance/receipts").param("q", job["client"]["name"].asString()).session(login("finance"))))
        assertTrue(receipts["items"].any { it["id"].asString() == receipt["id"].asString() })
    }
}
