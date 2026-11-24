package com.ship2ship.features.operations

import com.ship2ship.features.technical.JobTestSupport
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 4b: ETA revisions, the operation event log with corrections and voids (never deleted), the Statement of Facts
 * issued by a second person, grade-wise cargo readings, delays and incidents with review, and the operations lists.
 */
class OperationsApiIT : JobTestSupport() {

    private fun ops(jobId: String) = "/api/v1/jobs/$jobId/operations"

    private fun list(node: JsonNode, field: String) = (0 until node[field].size()).map { node[field][it] }

    private fun nodes(node: JsonNode) = (0 until node.size()).map { node[it] }

    private val now = Instant.now().truncatedTo(ChronoUnit.MINUTES)

    private fun at(hoursAgo: Long) = now.minus(hoursAgo, ChronoUnit.HOURS).toString()

    /** A job with two grades (propane and butane, both in MT), created through the commercial flow. */
    private fun twoGradeJob(): JsonNode {
        val commercial = login("commercial")
        val enquiry = ok(call(commercial, "POST", "/api/v1/enquiries", mapOf(
            "branchId" to BRANCH_MUM, "receivedAt" to "2026-10-16T05:00:00Z", "clientId" to CLIENT_CHARTERER,
            "operationType" to "LPG",
            "motherVessel" to mapOf("vesselId" to newVessel()), "daughterVessel" to mapOf("vesselId" to newVessel()),
            "cargo" to listOf(mapOf("cargoGradeId" to GRADE_PROPANE, "quantity" to "3000", "unitId" to UNIT_MT),
                mapOf("cargoGradeId" to GRADE_BUTANE, "quantity" to "2000", "unitId" to UNIT_MT)),
        )))["id"].asString()
        val estimate = ok(call(commercial, "POST", "/api/v1/enquiries/$enquiry/estimates", mapOf<String, Any>()))
        val saved = ok(call(commercial, "PUT", "/api/v1/estimates/${estimate["id"].asString()}", mapOf("currency" to "USD", "lines" to listOf(
            mapOf("kind" to "REVENUE", "costHeadId" to COST_HEAD_STS_SERVICE, "quantity" to "1", "unitPrice" to "10000"))), estimate["version"].asLong()))
        var version = ok(call(commercial, "POST", "/api/v1/enquiries/$enquiry/quotation/versions", mapOf("estimateId" to saved["id"].asString(),
            "validUntil" to LocalDate.now().plusDays(30).toString(), "tariffId" to TARIFF_DEMO)))["versions"][0]
        val id = version["id"].asString()
        version = ok(call(commercial, "POST", "/api/v1/quotation-versions/$id/submit", version = version["version"].asLong()))["versions"][0]
        version = ok(call(login("approver"), "POST", "/api/v1/quotation-versions/$id/decision", mapOf("approve" to true), version["version"].asLong()))["versions"][0]
        version = ok(call(commercial, "POST", "/api/v1/quotation-versions/$id/issue", mapOf("issuedTo" to "chartering@demo.invalid", "channel" to "EMAIL"),
            version["version"].asLong()))["versions"][0]
        val accepted = ok(call(commercial, "POST", "/api/v1/quotation-versions/$id/acceptance",
            mapOf("acceptedAt" to Instant.now().toString(), "acceptedByName" to "Demo Charterer Representative")))
        return ok(call(commercial, "GET", "/api/v1/jobs/${accepted["job"]["id"].asString()}"))
    }

    @Test
    fun `ETA revisions, events corrected or voided with a reason, and a SOF issued by a second person`() {
        val job = newJob()
        val jobId = job["id"].asString()
        val operations = login("ops.mumbai")
        val admin = login("ops.manager")
        val mother = jobVessel(job, "MOTHER")

        // ETA revisions with source and time zone; the change from the previous ETA is shown.
        call(operations, "POST", "${ops(jobId)}/eta", mapOf("jobVesselId" to mother, "etaUtc" to "2026-11-02T06:00:00Z", "timezone" to "Mars/Base",
            "source" to "AGENT")).andExpect(jsonPath("$.errors[0].field").value("timezone"))
        ok(call(operations, "POST", "${ops(jobId)}/eta", mapOf("jobVesselId" to mother, "etaUtc" to "2026-11-02T06:00:00Z", "timezone" to "Asia/Kolkata",
            "source" to "AGENT")))
        val etas = ok(call(operations, "POST", "${ops(jobId)}/eta", mapOf("jobVesselId" to mother, "etaUtc" to "2026-11-02T09:30:00Z",
            "timezone" to "Asia/Kolkata", "source" to "VESSEL_MASTER", "note" to "Synthetic delay at previous port")))
        val vessel = nodes(etas).first { it["role"].asString() == "MOTHER" }
        assertEquals("2026-11-02T09:30:00Z", vessel["current"]["etaUtc"].asString())
        assertEquals("3.5", vessel["current"]["changeHours"].asString())
        assertEquals(2, vessel["history"].size())

        // Events: required times, intervals, no future times.
        call(operations, "POST", "${ops(jobId)}/events", mapOf("typeCode" to "ALL_FAST")).andExpect(jsonPath("$.errors[0].field").value("occurredAt"))
        call(operations, "POST", "${ops(jobId)}/events", mapOf("typeCode" to "ALL_FAST", "occurredAt" to now.plus(1, ChronoUnit.HOURS).toString()))
            .andExpect(status().isBadRequest)
        call(operations, "POST", "${ops(jobId)}/events", mapOf("typeCode" to "HOSE_CONNECTION", "startedAt" to at(2), "endedAt" to at(3)))
            .andExpect(jsonPath("$.errors[0].field").value("endedAt"))
        val allFast = read(call(operations, "POST", "${ops(jobId)}/events", mapOf("typeCode" to "ALL_FAST", "occurredAt" to at(5),
            "jobVesselId" to mother)).andExpect(status().isCreated).andReturn().response.contentAsString)
        val hoses = read(call(operations, "POST", "${ops(jobId)}/events", mapOf("typeCode" to "HOSE_CONNECTION", "startedAt" to at(4), "endedAt" to at(3),
            "source" to "PAPER_LOG")).andReturn().response.contentAsString)
        assertTrue(hoses["interval"].asBoolean())
        val wrong = read(call(operations, "POST", "${ops(jobId)}/events", mapOf("typeCode" to "LEAK_TEST", "occurredAt" to at(4)))
            .andReturn().response.contentAsString)

        // A correction needs a reason and keeps the earlier values; a void needs a reason; nothing is deleted.
        val eventUrl = "${ops(jobId)}/events/${allFast["id"].asString()}"
        call(operations, "PUT", eventUrl, mapOf("typeCode" to "ALL_FAST", "occurredAt" to at(6)), allFast["version"].asLong())
            .andExpect(jsonPath("$.code").value("REASON_REQUIRED"))
        val corrected = ok(call(operations, "PUT", eventUrl, mapOf("typeCode" to "ALL_FAST", "occurredAt" to at(6), "jobVesselId" to mother,
            "reason" to "Time copied from the deck log"), allFast["version"].asLong()))
        assertTrue(corrected["corrected"].asBoolean())
        val changes = ok(call(operations, "GET", "$eventUrl/history"))
        assertEquals(at(5), changes[0]["before"]["occurredAt"].asString())
        assertEquals("Time copied from the deck log", changes[0]["reason"].asString())
        val wrongUrl = "${ops(jobId)}/events/${wrong["id"].asString()}"
        call(operations, "POST", "$wrongUrl/void", mapOf("reason" to " "), wrong["version"].asLong()).andExpect(jsonPath("$.code").value("REASON_REQUIRED"))
        val voided = ok(call(operations, "POST", "$wrongUrl/void", mapOf("reason" to "Recorded twice"), wrong["version"].asLong()))
        assertEquals("VOIDED", voided["status"].asString())
        call(operations, "PUT", wrongUrl, mapOf("typeCode" to "LEAK_TEST", "occurredAt" to at(4), "reason" to "x"), voided["version"].asLong())
            .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"))
        val overview = ok(call(operations, "GET", ops(jobId)))
        assertEquals(3, overview["events"].size()) // the voided event stays visible
        assertEquals(listOf("ALL_FAST", "HOSE_CONNECTION", "LEAK_TEST"), list(overview, "events").map { it["typeCode"].asString() })

        // SOF: prepared from the active events, issued by someone else, immutable; a newer issue supersedes it.
        mvc.perform(get("${ops(jobId)}/sof/draft/pdf").session(operations)).andExpect(status().isOk)
        val prepared = nodes(ok(call(operations, "POST", "${ops(jobId)}/sof")))[0]
        assertEquals("PREPARED", prepared["status"].asString())
        assertEquals(2, prepared["eventCount"].asInt())
        call(operations, "POST", "${ops(jobId)}/sof").andExpect(jsonPath("$.code").value("SOF_REVISION_OPEN"))
        val sofUrl = "${ops(jobId)}/sof/${prepared["id"].asString()}"
        call(operations, "POST", "$sofUrl/issue", version = prepared["version"].asLong()).andExpect(jsonPath("$.code").value("SELF_APPROVAL_FORBIDDEN"))
        val issued = nodes(ok(call(admin, "POST", "$sofUrl/issue", version = prepared["version"].asLong())))[0]
        assertEquals("ISSUED", issued["status"].asString())
        val sha = issued["pdfSha256"].asString()
        repeat(2) {
            val pdf = mvc.perform(get("$sofUrl/pdf").session(operations)).andExpect(status().isOk).andReturn().response.contentAsByteArray
            assertEquals("%PDF", String(pdf.copyOf(4)))
            assertEquals(sha, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf)))
        }
        ok(call(operations, "POST", "${ops(jobId)}/events", mapOf("typeCode" to "CARGO_COMMENCED", "occurredAt" to at(2))))
        val second = nodes(ok(call(operations, "POST", "${ops(jobId)}/sof")))[0]
        assertEquals(3, second["eventCount"].asInt())
        val revisions = nodes(ok(call(admin, "POST", "${ops(jobId)}/sof/${second["id"].asString()}/issue", version = second["version"].asLong())))
        assertEquals(listOf("ISSUED", "SUPERSEDED"), revisions.map { it["status"].asString() })
        assertEquals(sha, revisions[1]["pdfSha256"].asString())

        // Scope: read-only roles see the console but cannot record; another job's path does not reach this event.
        val commercial = login("commercial")
        assertTrue(!ok(call(commercial, "GET", ops(jobId)))["permissions"]["write"].asBoolean())
        call(commercial, "POST", "${ops(jobId)}/events", mapOf("typeCode" to "ALL_FAST", "occurredAt" to at(1))).andExpect(status().isForbidden)
        call(login("finance"), "GET", ops(jobId)).andExpect(status().isForbidden)
        call(operations, "GET", "${ops(newJob()["id"].asString())}/events/${allFast["id"].asString()}/history").andExpect(status().isNotFound)
    }

    @Test
    fun `cargo readings per grade refuse unit mixing and falling totals, accept corrections with a reason, and complete on a final reading`() {
        val jobId = twoGradeJob()["id"].asString()
        val operations = login("ops.mumbai")
        fun reading(line: Int, hoursAgo: Long, value: String, extra: Map<String, Any?> = emptyMap()) =
            call(operations, "POST", "${ops(jobId)}/cargo/$line/readings", mapOf("readAt" to at(hoursAgo), "cumulative" to value, "unitCode" to "MT") + extra)

        reading(1, 4, "100", mapOf("unitCode" to "M3")).andExpect(jsonPath("$.errors[0].field").value("unitCode"))
        ok(reading(1, 4, "1000"))
        val propane = ok(reading(1, 3, "2500"))
        assertEquals("1500", propane["readings"][1]["quantitySincePrevious"].asString())
        assertEquals("1500", propane["readings"][1]["ratePerHour"].asString())
        assertEquals("500", propane["balance"].asString())
        assertNotNull(propane["estimatedCompletion"].takeUnless { it.isNull })

        reading(1, 2, "2000").andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("CARGO_TOTAL_FALLING"))
        reading(1, 5, "2600").andExpect(jsonPath("$.code").value("READING_OUT_OF_ORDER"))
        reading(1, 2, "2400", mapOf("kind" to "CORRECTION")).andExpect(jsonPath("$.errors[0].field").value("reason"))
        val correctedGrade = ok(reading(1, 2, "2400", mapOf("kind" to "CORRECTION", "reason" to "Reading 2 misread on the gauge")))
        assertEquals("2400", correctedGrade["cumulative"].asString())
        reading(1, 3, "2500", mapOf("kind" to "CORRECTION", "reason" to "Late paper log"))
            .andExpect(jsonPath("$.code").value("READING_OUT_OF_ORDER"))
        // Commercial users cannot record readings at all.
        call(login("commercial"), "POST", "${ops(jobId)}/cargo/1/readings", mapOf("readAt" to at(1), "cumulative" to "2600", "unitCode" to "MT"))
            .andExpect(status().isForbidden)

        // Grades are independent: butane has its own readings and balance.
        val butane = ok(reading(2, 3, "500"))
        assertEquals("1500", butane["balance"].asString())
        val overview = ok(call(operations, "GET", ops(jobId)))
        assertEquals(listOf("2400", "500"), list(overview, "cargo").map { it["cumulative"].asString() })

        val final = ok(reading(1, 1, "3000", mapOf("kind" to "FINAL", "remarks" to "Synthetic final figure")))
        assertTrue(final["completed"].asBoolean())
        assertNull(final["estimatedCompletion"].takeUnless { it.isNull })
        reading(1, 0, "3001").andExpect(jsonPath("$.code").value("CARGO_COMPLETED"))
        call(operations, "POST", "${ops(jobId)}/cargo/9/readings", mapOf("readAt" to at(1), "cumulative" to "1", "unitCode" to "MT"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `delays and incidents keep their history, and incidents are closed by a reviewer who did not report them`() {
        val jobId = newJob()["id"].asString()
        val operations = login("ops.mumbai")
        val reviewer = login("ops.manager")

        call(operations, "POST", "${ops(jobId)}/delays", mapOf("categoryCode" to "WEATHER", "startedAt" to at(3), "endedAt" to at(4), "description" to "Swell"))
            .andExpect(jsonPath("$.errors[0].field").value("endedAt"))
        val delay = read(call(operations, "POST", "${ops(jobId)}/delays", mapOf("categoryCode" to "WEATHER", "startedAt" to at(4),
            "description" to "Synthetic swell above the agreed limit")).andExpect(status().isCreated).andReturn().response.contentAsString)
        assertNull(delay["endedAt"].takeUnless { it.isNull }) // ongoing
        val delayUrl = "${ops(jobId)}/delays/${delay["id"].asString()}"
        call(operations, "PUT", delayUrl, mapOf("categoryCode" to "WEATHER", "startedAt" to at(4), "endedAt" to at(2), "description" to "x"),
            delay["version"].asLong()).andExpect(jsonPath("$.code").value("REASON_REQUIRED"))
        val ended = ok(call(operations, "PUT", delayUrl, mapOf("categoryCode" to "WEATHER", "startedAt" to at(4), "endedAt" to at(2),
            "description" to "Synthetic swell above the agreed limit", "reason" to "Delay ended"), delay["version"].asLong()))
        assertTrue(ended["corrected"].asBoolean())
        assertEquals(1, ok(call(operations, "GET", "$delayUrl/history")).size())

        val incident = read(call(operations, "POST", "${ops(jobId)}/incidents", mapOf("categoryCode" to "NEAR_MISS", "severityCode" to "LOW",
            "occurredAt" to at(3), "description" to "Synthetic near miss during hose handling", "immediateActions" to "Work stopped and briefed",
            "actions" to listOf(mapOf("text" to "Review hose handling procedure", "ownerName" to "Demo Superintendent", "dueDate" to "2026-11-30"))))
            .andExpect(status().isCreated).andReturn().response.contentAsString)
        assertEquals("REPORTED", incident["status"].asString())
        val incidentUrl = "${ops(jobId)}/incidents/${incident["id"].asString()}"
        val updated = ok(call(operations, "PUT", incidentUrl, mapOf("categoryCode" to "NEAR_MISS", "severityCode" to "MEDIUM", "occurredAt" to at(3),
            "description" to "Synthetic near miss during hose handling", "actions" to listOf(mapOf("text" to "Review hose handling procedure", "done" to true)),
            "reason" to "Severity raised after review"), incident["version"].asLong()))
        assertEquals("Medium", updated["severityLabel"].asString())
        assertNotNull(updated["actions"][0]["doneAt"].takeUnless { it.isNull })
        val history = ok(call(operations, "GET", "$incidentUrl/history"))
        assertEquals("LOW", history[0]["before"]["severityCode"].asString())
        assertEquals("Demo Superintendent", history[0]["before"]["actions"][0]["ownerName"].asString())

        // Evidence file, shown as not scanned.
        val file = read(mvc.perform(multipart("${ops(jobId)}/files").file(MockMultipartFile("file", "evidence.pdf", "application/pdf", PDF))
            .param("ownerType", "INCIDENT").param("ownerId", incident["id"].asString()).session(operations).with(csrf()))
            .andExpect(status().isCreated).andReturn().response.contentAsString)
        assertEquals("NOT_SCANNED", file["scanState"].asString())

        // A second Operations user reviews and closes the incident; a closed incident is locked.
        call(operations, "POST", "$incidentUrl/review", mapOf("decision" to "START_REVIEW"), updated["version"].asLong())
            .andExpect(jsonPath("$.code").value("SELF_APPROVAL_FORBIDDEN"))
        val reviewing = ok(call(reviewer, "POST", "$incidentUrl/review", mapOf("decision" to "START_REVIEW"), updated["version"].asLong()))
        assertEquals("UNDER_REVIEW", reviewing["status"].asString())
        call(reviewer, "POST", "$incidentUrl/review", mapOf("decision" to "CLOSE"), reviewing["version"].asLong()).andExpect(jsonPath("$.code").value("REASON_REQUIRED"))
        val closed = ok(call(reviewer, "POST", "$incidentUrl/review", mapOf("decision" to "CLOSE", "note" to "Actions complete"), reviewing["version"].asLong()))
        assertEquals("CLOSED", closed["status"].asString())
        assertEquals(1, closed["files"].size())
        call(operations, "PUT", incidentUrl, mapOf("categoryCode" to "NEAR_MISS", "severityCode" to "LOW", "occurredAt" to at(3), "description" to "x",
            "reason" to "x"), closed["version"].asLong()).andExpect(jsonPath("$.code").value("INVALID_TRANSITION"))

        // A reporter cannot close their own incident.
        val own = read(call(reviewer, "POST", "${ops(jobId)}/incidents", mapOf("categoryCode" to "OTHER", "severityCode" to "LOW", "occurredAt" to at(1),
            "description" to "Synthetic")).andReturn().response.contentAsString)
        call(reviewer, "POST", "${ops(jobId)}/incidents/${own["id"].asString()}/review", mapOf("decision" to "CLOSE", "note" to "x"), own["version"].asLong())
            .andExpect(jsonPath("$.code").value("SELF_APPROVAL_FORBIDDEN"))
        assertTrue(!list(ok(call(reviewer, "GET", ops(jobId))), "incidents").last()["canClose"].asBoolean())
    }

    @Test
    fun `operations lists are managed by Operations, and the jobs list follows the user's scope`() {
        val jobId = newJob()["id"].asString()
        val operations = login("ops.mumbai")
        val code = "SYN_${(System.nanoTime() % 100_000)}"
        call(login("technical"), "POST", "/api/v1/operations/lists/event-types", mapOf("code" to code, "label" to "x", "phase" to "OTHER"))
            .andExpect(status().isForbidden)
        val listAdmin = login("sts.admin")
        call(listAdmin, "POST", "/api/v1/operations/lists/event-types", mapOf("code" to code, "label" to "Synthetic event", "phase" to "SAILING"))
            .andExpect(jsonPath("$.errors[0].field").value("phase"))
        val created = read(call(listAdmin, "POST", "/api/v1/operations/lists/event-types", mapOf("code" to code, "label" to "Synthetic event",
            "phase" to "OTHER", "interval" to true, "sortOrder" to 950)).andExpect(status().isCreated).andReturn().response.contentAsString)
        assertTrue(created["interval"].asBoolean())
        call(listAdmin, "POST", "/api/v1/operations/lists/event-types", mapOf("code" to code, "label" to "Again", "phase" to "OTHER"))
            .andExpect(status().isConflict)
        val inactive = ok(call(listAdmin, "PUT", "/api/v1/operations/lists/event-types/$code", mapOf("label" to "Synthetic event", "phase" to "OTHER",
            "interval" to true, "sortOrder" to 950, "active" to false), created["version"].asLong()))
        assertTrue(!inactive["active"].asBoolean())
        call(operations, "POST", "${ops(jobId)}/events", mapOf("typeCode" to code, "startedAt" to at(1)))
            .andExpect(jsonPath("$.errors[0].field").value("typeCode"))
        call(operations, "GET", "/api/v1/operations/lists/weather").andExpect(status().isNotFound)

        val jobs = ok(call(operations, "GET", "/api/v1/operations/jobs?size=100"))
        assertTrue(list(jobs, "items").any { it["jobId"].asString() == jobId })
        ok(call(login("commercial"), "GET", "/api/v1/operations/jobs"))
        call(login("finance"), "GET", "/api/v1/operations/jobs").andExpect(status().isForbidden)
    }
}
