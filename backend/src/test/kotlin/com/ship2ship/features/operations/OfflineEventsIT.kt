package com.ship2ship.features.operations

import com.ship2ship.features.technical.JobTestSupport
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OfflineEventsIT : JobTestSupport() {
    @Test
    fun `device retry is idempotent and conflicts need explicit review without replacing official data`() {
        val job=newJob()["id"].asString(); val ops=login("ops.mumbai"); val path="/api/v1/jobs/$job/operations/events"
        val occurred=Instant.now().minusSeconds(3600).toString()
        val event=mapOf("typeCode" to "ALL_FAST","occurredAt" to occurred,"source" to "PAPER_LOG",
            "clientEventId" to UUID.randomUUID().toString(),"deviceRecordedAt" to Instant.now().minusSeconds(120).toString())
        val first=ok(call(ops,"POST",path,event))
        assertNotNull(first["deviceRecordedAt"].asString())
        assertEquals(first["id"],ok(call(ops,"POST",path,event))["id"])
        call(ops,"POST",path,event+mapOf("remarks" to "Changed retry"))
            .andExpect(status().isUnprocessableEntity).andExpect(jsonPath("$.code").value("OFFLINE_EVENT_CONFLICT"))
        val duplicate=event+mapOf("clientEventId" to UUID.randomUUID().toString())
        call(ops,"POST",path,duplicate).andExpect(jsonPath("$.code").value("OFFLINE_EVENT_CONFLICT"))
        val reviewed=ok(call(ops,"POST",path,duplicate+mapOf("conflictReason" to "Checked paper log: two separate all-fast records")))
        assertEquals(occurred.take(16),reviewed["occurredAt"].asString().take(16))
        assertEquals(2,jdbc.sql("SELECT COUNT(*) FROM operation_event WHERE job_id=UUID_TO_BIN(:job)").param("job",job).query(Int::class.java).single())
        call(login("finance"),"POST",path,event).andExpect(status().isForbidden)
    }
}
