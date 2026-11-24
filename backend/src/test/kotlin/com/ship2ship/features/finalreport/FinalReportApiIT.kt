package com.ship2ship.features.finalreport

import com.ship2ship.features.technical.JobTestSupport
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 6a: the final operation report is generated from the job's records, blocked until the records are complete,
 * approved by someone other than the submitter, frozen at submission and issued as an immutable PDF.
 */
class FinalReportApiIT : JobTestSupport() {

    private fun report(jobId: String) = "/api/v1/jobs/$jobId/final-report"
    private fun ops(jobId: String) = "/api/v1/jobs/$jobId/operations"
    private fun codes(node: JsonNode, field: String) = (0 until node[field].size()).map { node[field][it]["code"].asString() }
    private val now = Instant.now().truncatedTo(ChronoUnit.MINUTES)
    private fun at(hoursAgo: Long) = now.minus(hoursAgo, ChronoUnit.HOURS).toString()

    /** Records events and a final reading as Operations, and issues a SOF (prepared by Operations, issued by the admin). */
    private fun operate(jobId: String, sof: Boolean = true) {
        val operations = login("ops.mumbai")
        for ((type, hours) in listOf("ALL_FAST" to 6L, "CARGO_COMMENCED" to 5L, "CARGO_COMPLETED" to 2L, "CAST_OFF" to 1L)) {
            ok(call(operations, "POST", "${ops(jobId)}/events", mapOf("typeCode" to type, "occurredAt" to at(hours))))
        }
        ok(call(operations, "POST", "${ops(jobId)}/cargo/1/readings", mapOf("readAt" to at(4), "cumulative" to "20000", "unitCode" to "MT")))
        ok(call(operations, "POST", "${ops(jobId)}/cargo/1/readings", mapOf("readAt" to at(2), "cumulative" to "34000", "unitCode" to "MT", "kind" to "FINAL")))
        if (sof) {
            val prepared = ok(call(operations, "POST", "${ops(jobId)}/sof"))[0]
            ok(call(login("ops.manager"), "POST", "${ops(jobId)}/sof/${prepared["id"].asString()}/issue", version = prepared["version"].asLong()))
        }
    }

    private val content = mapOf(
        "vhfChannel" to "VHF 72", "summary" to "Synthetic summary", "remarks" to "Synthetic remarks",
        "equipment" to listOf(mapOf("category" to "FENDER", "description" to "Synthetic primary fender 3.3 m x 6.5 m", "quantity" to 4)),
        "attendance" to listOf(mapOf("capacity" to "Mooring Master", "name" to "Demo Mooring Master")),
    )

    @Test
    fun `the report is blocked until the records are complete, approved by a second person, frozen and issued`() {
        val jobId = newJob()["id"].asString()
        val operations = login("ops.mumbai")
        val admin = login("admin")

        // A first draft from the records: the generated summary states only what the log shows.
        val draft = ok(call(operations, "POST", "${report(jobId)}/revisions"))
        assertEquals("DRAFT", draft["status"].asString())
        assertTrue(draft["content"]["summary"].asString().contains("No incidents or delays were recorded in the operations log."))
        assertEquals(setOf("attendance", "cargo", "sof"), codes(draft, "blockers").toSet())
        assertEquals("MANUAL", draft["sources"]["equipment"].asString())
        call(operations, "POST", "${report(jobId)}/revisions").andExpect(jsonPath("$.code").value("REPORT_REVISION_OPEN"))
        val url = "${report(jobId)}/revisions/${draft["id"].asString()}"
        call(operations, "POST", "$url/submit", version = draft["version"].asLong())
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("REPORT_INCOMPLETE"))

        operate(jobId)
        val regenerated = ok(call(operations, "POST", "$url/regenerate-summary", version = draft["version"].asLong()))
        val summary = regenerated["content"]["summary"].asString()
        assertTrue(summary.contains("Cargo transfer commenced at"), summary)
        assertTrue(summary.contains("Final quantities: Propane 34000 MT"), summary)
        call(operations, "PUT", url, content + mapOf("equipment" to listOf(mapOf("category" to "ANCHOR", "description" to "x"))),
            regenerated["version"].asLong()).andExpect(jsonPath("$.errors[0].field").value("equipment[0].category"))
        val saved = ok(call(operations, "PUT", url, content, regenerated["version"].asLong()))
        assertEquals(0, saved["blockers"].size())

        // Submitted by the admin: the admin may not approve it; the management approver may.
        val submitted = ok(call(admin, "POST", "$url/submit", version = saved["version"].asLong()))
        assertTrue(submitted["snapshotFrozen"].asBoolean())
        assertEquals(4, submitted["snapshot"]["events"].size())
        call(admin, "POST", "$url/decision", mapOf("decision" to "APPROVE"), submitted["version"].asLong())
            .andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("SELF_APPROVAL_FORBIDDEN"))
        call(operations, "POST", "$url/decision", mapOf("decision" to "APPROVE"), submitted["version"].asLong()).andExpect(status().isForbidden)

        // A later event does not change the submitted report.
        ok(call(operations, "POST", "${ops(jobId)}/events", mapOf("typeCode" to "POAC_DISEMBARKED", "occurredAt" to at(0))))
        val frozen = ok(call(operations, "GET", url))
        assertEquals(4, frozen["snapshot"]["events"].size())

        val approved = ok(call(login("approver"), "POST", "$url/decision", mapOf("decision" to "APPROVE"), frozen["version"].asLong()))
        assertEquals("APPROVED", approved["status"].asString())
        call(operations, "PUT", url, content, approved["version"].asLong()).andExpect(jsonPath("$.code").value("INVALID_TRANSITION"))
        val issued = ok(call(operations, "POST", "$url/issue", version = approved["version"].asLong()))
        assertEquals("ISSUED", issued["status"].asString())
        val sha = issued["pdfSha256"].asString()
        repeat(2) {
            val pdf = mvc.perform(get("$url/pdf").session(operations)).andExpect(status().isOk).andReturn().response.contentAsByteArray
            assertEquals("%PDF", String(pdf.copyOf(4)))
            assertEquals(sha, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf)))
        }

        // A new revision copies the entries; issuing it supersedes the first.
        val second = ok(call(operations, "POST", "${report(jobId)}/revisions"))
        assertEquals("Demo Mooring Master", second["content"]["attendance"][0]["name"].asString())
        val secondUrl = "${report(jobId)}/revisions/${second["id"].asString()}"
        val sub2 = ok(call(admin, "POST", "$secondUrl/submit", version = second["version"].asLong()))
        val app2 = ok(call(login("approver"), "POST", "$secondUrl/decision", mapOf("decision" to "APPROVE"), sub2["version"].asLong()))
        ok(call(operations, "POST", "$secondUrl/issue", version = app2["version"].asLong()))
        val first = ok(call(operations, "GET", url))
        assertEquals("SUPERSEDED", first["status"].asString())
        assertEquals(sha, first["pdfSha256"].asString())
    }

    @Test
    fun `the summary reports incidents, open incidents warn, returns need a reason, and access is scoped`() {
        val jobId = newJob()["id"].asString()
        val operations = login("ops.mumbai")
        operate(jobId)
        ok(call(operations, "POST", "${ops(jobId)}/incidents", mapOf("categoryCode" to "NEAR_MISS", "severityCode" to "LOW", "occurredAt" to at(3),
            "description" to "Synthetic near miss")))

        val draft = ok(call(operations, "POST", "${report(jobId)}/revisions"))
        val summary = draft["content"]["summary"].asString()
        assertTrue(!summary.contains("No incidents"), summary)
        assertTrue(summary.contains("1 incident(s) and 0 delay(s) were recorded"), summary)
        assertTrue("incidentsOpen" in codes(draft, "warnings"))

        // Attachments must be documents of this job.
        publishRequestTemplate()
        val other = newJob()
        val otherId = other["id"].asString()
        val technical = login("technical")
        val request = ok(call(technical, "POST", "/api/v1/jobs/$otherId/technical/request"))
        val mother = jobVessel(other, "MOTHER")
        val item = (0 until request["items"].size()).map { request["items"][it] }
            .first { it["code"].asString() == "Q88" && it["jobVesselId"].asString() == mother }["id"].asString()
        val foreign = read(jobUpload(technical, otherId, mother, item).andReturn().response.contentAsString)["id"].asString()
        val url = "${report(jobId)}/revisions/${draft["id"].asString()}"
        call(operations, "PUT", url, content + mapOf("attachmentDocumentIds" to listOf(foreign)), draft["version"].asLong())
            .andExpect(jsonPath("$.errors[0].field").value("attachmentDocumentIds[0]"))

        val saved = ok(call(operations, "PUT", url, content, draft["version"].asLong()))
        val submitted = ok(call(operations, "POST", "$url/submit", version = saved["version"].asLong()))
        val approver = login("approver")
        call(approver, "POST", "$url/decision", mapOf("decision" to "RETURN"), submitted["version"].asLong())
            .andExpect(jsonPath("$.code").value("REASON_REQUIRED"))
        val returned = ok(call(approver, "POST", "$url/decision", mapOf("decision" to "RETURN", "reason" to "Close the incident first"),
            submitted["version"].asLong()))
        assertEquals("DRAFT", returned["status"].asString())
        assertEquals("Close the incident first", returned["returnedReason"].asString())
        assertTrue(!returned["snapshotFrozen"].asBoolean())

        // Read-only roles see it but cannot change it; another job's path does not reach it.
        val commercial = login("commercial")
        ok(call(commercial, "GET", url))
        call(commercial, "PUT", url, content, returned["version"].asLong()).andExpect(status().isForbidden)
        call(operations, "GET", "${report(otherId)}/revisions/${draft["id"].asString()}").andExpect(status().isNotFound)
    }
}
