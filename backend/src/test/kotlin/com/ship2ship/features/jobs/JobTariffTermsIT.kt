package com.ship2ship.features.jobs

import com.ship2ship.core.audit.Uuids
import com.ship2ship.features.technical.JobTestSupport
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JobTariffTermsIT : JobTestSupport() {
    @Test
    fun `STS Admin confirms audited job terms and existing invoice locks them`() {
        val job = newJob()
        val id = job["id"].asString()
        val path = "/api/v1/jobs/$id/tariff-terms"
        val terms = mapOf("billingIncrementMinutes" to 15, "graceMinutes" to 0,
            "roundingRule" to "ROUND_UP_TO_INCREMENT", "reason" to "Confirmed with owner for synthetic job")
        val ops = login("ops.mumbai")
        val admin = login("sts.admin")
        val oldVersion = job["version"].asLong()

        call(ops, "PUT", path, terms, oldVersion).andExpect(status().isForbidden)
        call(admin, "PUT", path, terms + ("reason" to " "), oldVersion).andExpect(status().isBadRequest)
        val updated = ok(call(admin, "PUT", path, terms, oldVersion))
        assertEquals(15, updated["tariff"]["billingIncrementMinutes"].asInt())
        assertEquals(0, updated["tariff"]["graceMinutes"].asInt())
        assertEquals("ROUND_UP_TO_INCREMENT", updated["tariff"]["roundingRule"].asString())
        assertEquals(job["tariff"]["extraHourRate"].decimalValue(), updated["tariff"]["extraHourRate"].decimalValue())
        assertEquals("CONFIRMED", ok(call(ops, "GET", "/api/v1/jobs/$id/movements/clock"))["clock"]["tariffTermsStatus"].asString())
        call(admin, "PUT", path, terms, oldVersion).andExpect(status().isConflict)
        val auditCount = jdbc.sql("SELECT COUNT(*) FROM audit_event WHERE entity_type='TARIFF_SNAPSHOT' AND entity_id=:job AND details LIKE '%Confirmed with owner%'")
            .param("job", Uuids.toBytes(UUID.fromString(id))).query(Long::class.java).single()
        assertEquals(1, auditCount)

        val invoice = ok(call(ops, "POST", "/api/v1/jobs/$id/invoices", mapOf("fromQuotation" to true)))
        jdbc.sql("""INSERT INTO job_extra_invoice (job_id,invoice_id,fingerprint,clock_snapshot,created_at,created_by)
            VALUES (:job,:invoice,:fingerprint,'{}',UTC_TIMESTAMP(6),:actor)""")
            .param("job", Uuids.toBytes(UUID.fromString(id)))
            .param("invoice", Uuids.toBytes(UUID.fromString(invoice["id"].asString())))
            .param("fingerprint", "a".repeat(64))
            .param("actor", Uuids.toBytes(UUID.fromString(ok(call(admin, "GET", "/api/v1/me"))["id"].asString())))
            .update()
        call(admin, "PUT", path, terms + ("graceMinutes" to 5), updated["version"].asLong())
            .andExpect(status().isConflict)
        val unchanged = ok(call(admin, "GET", "/api/v1/jobs/$id"))
        assertEquals(0, unchanged["tariff"]["graceMinutes"].asInt())
        assertTrue(unchanged["version"].asLong() >= updated["version"].asLong())
    }
}
