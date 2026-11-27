package com.ship2ship.features.technical

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals

class IncomingMailApiIT : JobTestSupport() {
    @Autowired private lateinit var intake: IncomingMailService

    @Test
    fun `a reviewed mailbox attachment files once to the selected job and vessel`() {
        publishRequestTemplate()
        val job = newJob()
        val jobId = job["id"].asString()
        val vesselId = jobVessel(job, "MOTHER")
        val admin = login("admin")
        val request = ok(call(admin, "POST", "/api/v1/jobs/$jobId/technical/request"))
        val item = (0 until request["items"].size()).map { request["items"][it] }
            .first { it["code"].asString() == "Q88" && it["jobVesselId"].asString() == vesselId }
        val itemId = item["id"].asString()

        val key = "a".repeat(64)
        intake.ingest(key, 8172, "owner@example.test", "Vessel documents", null,
            listOf(ParsedMailAttachment(0, "owner-q88.pdf", "application/pdf", PDF)))
        intake.ingest(key, 8172, "owner@example.test", "Vessel documents", null,
            listOf(ParsedMailAttachment(0, "owner-q88.pdf", "application/pdf", PDF)))
        val pending = ok(call(admin, "GET", "/api/v1/technical/incoming-mail"))
        val attachment = (0 until pending.size()).map { pending[it] }.single { it["fileName"].asString() == "owner-q88.pdf" }
        val attachmentId = attachment["id"].asString()

        val filed = ok(call(admin, "POST", "/api/v1/technical/incoming-mail/$attachmentId/file",
            mapOf("jobId" to jobId, "itemId" to itemId)))
        assertEquals(vesselId, filed["jobVesselId"].asString())
        assertEquals("owner-q88.pdf", filed["fileName"].asString())
        val updated = ok(call(admin, "GET", "/api/v1/jobs/$jobId/technical/request"))
        assertEquals("RECEIVED", (0 until updated["items"].size()).map { updated["items"][it] }
            .first { it["id"].asString() == itemId }["status"].asString())
        val remaining = ok(call(admin, "GET", "/api/v1/technical/incoming-mail"))
        assertEquals(0, (0 until remaining.size()).count { remaining[it]["id"].asString() == attachmentId })
        call(admin, "POST", "/api/v1/technical/incoming-mail/$attachmentId/file",
            mapOf("jobId" to jobId, "itemId" to itemId)).andExpect(status().isConflict)
    }
}
