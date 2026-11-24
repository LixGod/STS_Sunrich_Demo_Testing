package com.ship2ship.features.technical

import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.persistence.UuidV7
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpSession
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 3e: job operating limits approved by a second person, weather reports (forecasts and observations) reviewed by a
 * second person, warnings only against approved limits, and the approved limits linked into the JPO.
 */
class WeatherLimitsApiIT : JobTestSupport() {

    private fun limits(jobId: String) = "/api/v1/jobs/$jobId/operating-limits"

    private fun list(node: JsonNode, field: String) = (0 until node[field].size()).map { node[field][it] }

    private val wind = mapOf("parameterCode" to "WIND_SPEED", "operation" to "Mooring", "comparison" to "ABOVE", "threshold" to "20",
        "action" to "Stop and review")
    private val period = mapOf("parameterCode" to "WAVE_PERIOD", "operation" to "Cargo transfer", "comparison" to "BELOW", "threshold" to "6",
        "action" to "Reduce rate and review")

    /** Enters, submits (technical) and approves (marine) a limit set; returns the approved set. */
    private fun approveLimits(jobId: String, rows: List<Map<String, Any?>>): JsonNode {
        val technical = login("technical")
        val draft = ok(call(technical, "POST", "${limits(jobId)}/revisions"))
        val url = "${limits(jobId)}/revisions/${draft["id"].asString()}"
        val saved = ok(call(technical, "PUT", url, mapOf("rows" to rows), draft["version"].asLong()))
        val submitted = ok(call(technical, "POST", "$url/submit", version = saved["version"].asLong()))
        return ok(call(login("marine"), "POST", "$url/decision", mapOf("decision" to "APPROVE"), submitted["version"].asLong()))
    }

    private fun report(session: MockHttpSession, jobId: String, report: Map<String, Any?>, file: ByteArray? = null, fileName: String = "forecast.pdf"):
        ResultActions = mvc.perform(
        multipart("/api/v1/jobs/$jobId/weather/reports")
            .file(MockMultipartFile("report", "", "application/json", body(report).toByteArray()))
            .apply { file?.let { file(MockMultipartFile("file", fileName, "application/pdf", it)) } }
            .session(session).with(csrf()),
    )

    private fun forecast(from: Instant, to: Instant, values: List<Map<String, Any?>>) = mapOf(
        "kind" to "FORECAST", "source" to "PROVIDER_REPORT", "providerName" to "Synthetic Weather Ltd", "issuedAt" to minOf(from, to, Instant.now()).minus(1, ChronoUnit.HOURS).toString(),
        "validFrom" to from.toString(), "validTo" to to.toString(), "area" to "Demo Anchorage A1", "values" to values,
    )

    private fun value(at: Instant, code: String, value: String) = mapOf("at" to at.toString(), "parameterCode" to code, "value" to value)

    private fun weather(session: MockHttpSession, jobId: String) = ok(call(session, "GET", "/api/v1/jobs/$jobId/weather"))

    @Test
    fun `operating limits start empty, are approved by a second person, and a newer approval supersedes the old one`() {
        val job = newJob()
        val jobId = job["id"].asString()
        val technical = login("technical")
        val marine = login("marine")

        val draft = ok(call(technical, "POST", "${limits(jobId)}/revisions"))
        assertEquals(1, draft["revisionNo"].asInt())
        assertEquals(0, draft["rows"].size()) // nothing pre-filled
        call(technical, "POST", "${limits(jobId)}/revisions").andExpect(jsonPath("$.code").value("LIMITS_REVISION_OPEN"))
        val url = "${limits(jobId)}/revisions/${draft["id"].asString()}"
        call(technical, "POST", "$url/submit", version = draft["version"].asLong()).andExpect(jsonPath("$.code").value("LIMITS_EMPTY"))

        call(technical, "PUT", url, mapOf("rows" to listOf(wind + mapOf("parameterCode" to "SWELL"))), draft["version"].asLong())
            .andExpect(jsonPath("$.errors[0].field").value("rows[0].parameterCode"))
        call(technical, "PUT", url, mapOf("rows" to listOf(wind + mapOf("comparison" to "AROUND", "action" to " "))), draft["version"].asLong())
            .andExpect(status().isBadRequest)
        call(technical, "PUT", url, mapOf("rows" to listOf(wind + mapOf("threshold" to "20.125"))), draft["version"].asLong())
            .andExpect(jsonPath("$.errors[0].field").value("rows[0].threshold"))
        val saved = ok(call(technical, "PUT", url, mapOf("rows" to listOf(wind, period)), draft["version"].asLong()))
        assertEquals(listOf("Wind speed", "Wave period"), list(saved, "rows").map { it["parameterLabel"].asString() })
        assertEquals("20", list(saved, "rows")[0]["threshold"].asString())

        val submitted = ok(call(technical, "POST", "$url/submit", version = saved["version"].asLong()))
        assertTrue("APPROVE" !in list(submitted, "allowedActions").map { it.asString() })
        call(technical, "POST", "$url/decision", mapOf("decision" to "APPROVE"), submitted["version"].asLong())
            .andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("SELF_APPROVAL_FORBIDDEN"))
        call(marine, "POST", "$url/decision", mapOf("decision" to "RETURN", "reason" to " "), submitted["version"].asLong())
            .andExpect(jsonPath("$.code").value("REASON_REQUIRED"))
        val returned = ok(call(marine, "POST", "$url/decision", mapOf("decision" to "RETURN", "reason" to "Check the period"), submitted["version"].asLong()))
        assertEquals("DRAFT", returned["status"].asString())
        assertEquals("Check the period", returned["returnedReason"].asString())
        val again = ok(call(technical, "POST", "$url/submit", version = returned["version"].asLong()))
        val approved = ok(call(marine, "POST", "$url/decision", mapOf("decision" to "APPROVE"), again["version"].asLong()))
        assertEquals("APPROVED", approved["status"].asString())
        assertEquals("Demo Marine Superintendent", approved["decidedByName"].asString())
        call(technical, "PUT", url, mapOf("rows" to listOf(wind)), approved["version"].asLong()).andExpect(jsonPath("$.code").value("INVALID_TRANSITION"))

        // Revision 2 copies the rows; once approved, revision 1 is superseded.
        val second = approveLimits(jobId, listOf(wind + mapOf("threshold" to "18")))
        assertEquals(2, second["revisionNo"].asInt())
        val overview = ok(call(technical, "GET", limits(jobId)))
        assertEquals(listOf("APPROVED", "SUPERSEDED"), list(overview, "revisions").map { it["status"].asString() })
        assertEquals("18", list(overview["approved"], "rows")[0]["threshold"].asString())
        assertTrue(overview["canCreate"].asBoolean())

        // Scope: read-only roles see but cannot change; another job's path does not reach this set.
        ok(call(login("commercial"), "GET", limits(jobId)))
        call(login("commercial"), "POST", "${limits(jobId)}/revisions").andExpect(status().isForbidden)
        call(login("finance"), "GET", limits(jobId)).andExpect(status().isForbidden)
        call(technical, "GET", "${limits(newJob()["id"].asString())}/revisions/${draft["id"].asString()}").andExpect(status().isNotFound)
    }

    @Test
    fun `weather reports are validated, reviewed by someone else, and warn only against approved limits`() {
        val jobId = newJob()["id"].asString()
        val technical = login("technical")
        val marine = login("marine")
        val now = Instant.now().truncatedTo(ChronoUnit.MINUTES)
        val from = now.plus(1, ChronoUnit.HOURS)
        val to = now.plus(2, ChronoUnit.DAYS)
        val parameters = list(weather(technical, jobId), "parameters").associate { it["code"].asString() to it["unit"].asString() }
        assertEquals(mapOf("WAVE_DIRECTION" to "°", "SWELL_WAVE_HEIGHT" to "m", "SWELL_WAVE_DIRECTION" to "°",
            "OCEAN_CURRENT_VELOCITY" to "km/h"), parameters.filterKeys { it in setOf(
            "WAVE_DIRECTION", "SWELL_WAVE_HEIGHT", "SWELL_WAVE_DIRECTION", "OCEAN_CURRENT_VELOCITY") })

        // Validation.
        report(technical, jobId, forecast(to, from, listOf(value(from, "WIND_SPEED", "10")))).andExpect(jsonPath("$.errors[0].field").value("validTo"))
        report(technical, jobId, forecast(from, to, listOf(value(to.plus(1, ChronoUnit.HOURS), "WIND_SPEED", "10"))))
            .andExpect(jsonPath("$.errors[0].field").value("values[0].at"))
        report(technical, jobId, forecast(from, to, emptyList())).andExpect(jsonPath("$.errors[0].field").value("values"))
        report(technical, jobId, forecast(from, to, emptyList()) + mapOf("issuedAt" to now.plus(1, ChronoUnit.DAYS).toString(), "providerName" to null),
            PDF).andExpect(status().isBadRequest)
        report(technical, jobId, forecast(from, to, emptyList()) + mapOf("source" to "OBSERVATION"), PDF)
            .andExpect(jsonPath("$.errors[0].field").value("source"))
        report(technical, jobId, forecast(from, to, emptyList()), "not a pdf".toByteArray()).andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"))
        call(login("commercial"), "GET", "/api/v1/jobs/$jobId/weather").andExpect(status().isOk)

        // Without approved limits there are no warnings, only the notice.
        val values = listOf(value(from, "WIND_SPEED", "25"), value(from, "WAVE_PERIOD", "4.5"), value(to, "WIND_SPEED", "15"),
            value(from, "WAVE_DIRECTION", "203"), value(from, "SWELL_WAVE_HEIGHT", "0.52"),
            value(from, "SWELL_WAVE_DIRECTION", "201"), value(from, "OCEAN_CURRENT_VELOCITY", "0.8"))
        val created = read(report(technical, jobId, forecast(from, to, values), PDF).andExpect(status().isCreated).andReturn().response.contentAsString)
        assertEquals("RECORDED", created["status"].asString())
        assertEquals("NOT_SCANNED", created["file"]["scanState"].asString())
        assertEquals(7, created["values"].size())
        val before = weather(technical, jobId)
        assertTrue(!before["limitsApproved"].asBoolean())
        assertEquals(0, before["warnings"].size())

        // Draft limits do not warn; approved ones do (ABOVE and BELOW).
        val draft = ok(call(technical, "POST", "${limits(jobId)}/revisions"))
        ok(call(technical, "PUT", "${limits(jobId)}/revisions/${draft["id"].asString()}", mapOf("rows" to listOf(wind, period)), draft["version"].asLong()))
        assertEquals(0, weather(technical, jobId)["warnings"].size())
        val set = ok(call(technical, "GET", "${limits(jobId)}/revisions/${draft["id"].asString()}"))
        val submitted = ok(call(technical, "POST", "${limits(jobId)}/revisions/${set["id"].asString()}/submit", version = set["version"].asLong()))
        ok(call(marine, "POST", "${limits(jobId)}/revisions/${set["id"].asString()}/decision", mapOf("decision" to "APPROVE"), submitted["version"].asLong()))
        val warned = weather(technical, jobId)
        assertTrue(warned["limitsApproved"].asBoolean())
        assertEquals(listOf("WIND_SPEED" to "ABOVE", "WAVE_PERIOD" to "BELOW"),
            list(warned, "warnings").map { it["parameterCode"].asString() to it["comparison"].asString() }.sortedByDescending { it.first })
        assertTrue(list(warned, "warnings").first { it["parameterCode"].asString() == "WIND_SPEED" }["message"].asString()
            .contains("wind speed 25 kn is above the 20 kn limit for Mooring"))

        // Review by a second person; a reviewed report is locked.
        val url = "/api/v1/jobs/$jobId/weather/reports/${created["id"].asString()}"
        assertTrue(!created["canReview"].asBoolean())
        call(technical, "POST", "$url/review", mapOf("decision" to "ACCEPT"), created["version"].asLong())
            .andExpect(jsonPath("$.code").value("SELF_APPROVAL_FORBIDDEN"))
        call(marine, "POST", "$url/review", mapOf("decision" to "REJECT"), created["version"].asLong()).andExpect(jsonPath("$.code").value("REASON_REQUIRED"))
        val accepted = ok(call(marine, "POST", "$url/review", mapOf("decision" to "ACCEPT"), created["version"].asLong()))
        assertEquals("ACCEPTED", accepted["status"].asString())
        assertEquals("Demo Marine Superintendent", accepted["reviewedByName"].asString())
        call(technical, "PUT", url, forecast(from, to, values), accepted["version"].asLong()).andExpect(jsonPath("$.code").value("INVALID_TRANSITION"))

        // An observation outside the limit warns until it is rejected; an expired forecast never warns.
        val observation = read(report(technical, jobId, mapOf("kind" to "OBSERVATION", "source" to "OBSERVATION",
            "observedAt" to now.minus(1, ChronoUnit.HOURS).toString(), "values" to listOf(value(now.minus(1, ChronoUnit.HOURS), "WIND_SPEED", "30"))))
            .andExpect(status().isCreated).andReturn().response.contentAsString)
        assertEquals(3, weather(technical, jobId)["warnings"].size())
        ok(call(marine, "POST", "/api/v1/jobs/$jobId/weather/reports/${observation["id"].asString()}/review",
            mapOf("decision" to "REJECT", "reason" to "Instrument fault"), observation["version"].asLong()))
        val old = read(report(technical, jobId, forecast(now.minus(3, ChronoUnit.DAYS), now.minus(2, ChronoUnit.DAYS),
            listOf(value(now.minus(3, ChronoUnit.DAYS), "WIND_SPEED", "40")))).andExpect(status().isCreated).andReturn().response.contentAsString)
        assertTrue(old["expired"].asBoolean())
        val after = weather(technical, jobId)
        assertEquals(2, after["warnings"].size())
        assertEquals(3, after["reports"].size())
        call(technical, "GET", "/api/v1/jobs/${newJob()["id"].asString()}/weather/reports/${created["id"].asString()}").andExpect(status().is4xxClientError)
    }

    @Test
    fun `approved limits link into the JPO and are frozen when it is submitted`() {
        publishAnnex()
        publishRequestTemplate()
        val job = newJob()
        val jobId = job["id"].asString()
        val technical = login("technical")
        val marine = login("marine")

        // Verified voyage values for both vessels and a mooring diagram, so only the limits decide submission.
        val request = ok(call(technical, "POST", "/api/v1/jobs/$jobId/technical/request"))
        fun item(code: String, vessel: String) = list(request, "items")
            .first { it["code"].asString() == code && it["jobVesselId"].takeIf { n -> !n.isNull }?.asString() == vessel }["id"].asString()
        var diagram = ""
        for (role in listOf("MOTHER", "DAUGHTER")) {
            val vessel = jobVessel(job, role)
            val doc = read(jobUpload(technical, jobId, vessel, item("Q88", vessel)).andReturn().response.contentAsString)["id"].asString()
            if (role == "MOTHER") diagram = doc
            val draft = ok(call(technical, "POST", "/api/v1/jobs/$jobId/technical/vessels/$vessel/voyage"))
            val url = "/api/v1/jobs/$jobId/technical/voyage/${draft["id"].asString()}"
            val fields = JpoService.JPO_VOYAGE_FIELDS
            val saved = ok(call(technical, "PUT", url, mapOf("values" to fields.map {
                mapOf("fieldCode" to it, "value" to "7.5", "sourceDocumentId" to doc, "sourcePage" to 1)
            }), draft["version"].asLong()))
            val submitted = ok(call(technical, "POST", "$url/submit", version = saved["version"].asLong()))
            ok(call(marine, "POST", "$url/verify", mapOf("confirmedFieldCodes" to fields), submitted["version"].asLong()))
        }

        val jpo = ok(call(technical, "POST", "/api/v1/jobs/$jobId/jpo/revisions"))
        val jpoUrl = "/api/v1/jobs/$jobId/jpo/revisions/${jpo["id"].asString()}"
        assertEquals("MANUAL", jpo["sources"]["operatingLimits"].asString())
        val content = mapOf("operationDate" to "2026-11-02", "mooringDiagramDocumentId" to diagram, "shipHandling" to "HELM_ORDERS",
            "approach" to "UNDERWAY", "securityLevel" to "LEVEL_1", "contingency" to "Synthetic contingency", "personnelTransfer" to "Synthetic gangway",
            "cargoRates" to jpo["content"]["cargoRates"], "ancillary" to jpo["content"]["ancillary"],
            "equipment" to listOf(mapOf("category" to "CARGO_HOSE", "number" to 2), mapOf("category" to "PRIMARY_FENDER", "number" to 4)))
        val saved = ok(call(technical, "PUT", jpoUrl, content, jpo["version"].asLong()))
        assertEquals(listOf("operatingLimits"), list(saved, "blockers").map { it["code"].asString() })

        // An accepted forecast is listed in the JPO; approved limits satisfy the blocker and show as linked.
        val now = Instant.now().truncatedTo(ChronoUnit.MINUTES)
        val forecast = read(report(technical, jobId, forecast(now.plus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.DAYS), emptyList()), PDF)
            .andReturn().response.contentAsString)
        ok(call(marine, "POST", "/api/v1/jobs/$jobId/weather/reports/${forecast["id"].asString()}/review", mapOf("decision" to "ACCEPT"),
            forecast["version"].asLong()))
        approveLimits(jobId, listOf(wind))
        val linked = ok(call(technical, "GET", jpoUrl))
        assertEquals("LINKED", linked["sources"]["operatingLimits"].asString())
        assertEquals(0, linked["blockers"].size())
        assertEquals("20", linked["snapshot"]["operatingLimits"][0]["threshold"].asString())
        assertEquals("Synthetic Weather Ltd", linked["snapshot"]["weatherReports"][0]["providerName"].asString())

        val submitted = ok(call(technical, "POST", "$jpoUrl/submit", version = linked["version"].asLong()))
        assertEquals(1, submitted["snapshot"]["operatingLimitsRevision"].asInt())
        // A later approval does not change the submitted JPO.
        approveLimits(jobId, listOf(wind + mapOf("threshold" to "18")))
        val frozen = ok(call(technical, "GET", jpoUrl))
        assertEquals("20", frozen["snapshot"]["operatingLimits"][0]["threshold"].asString())
        assertEquals("LINKED", frozen["sources"]["operatingLimits"].asString())

        val xlsx = mvc.perform(get("$jpoUrl/xlsx").session(technical)).andExpect(status().isOk).andReturn().response.contentAsByteArray
        XSSFWorkbook(xlsx.inputStream()).use { book ->
            val text = book.getSheet("JPO").flatMap { row -> row.map { it.toString() } }.joinToString("\n")
            assertTrue(text.contains("Above 20 kn"), "linked limit printed")
            assertTrue(text.contains("approved operating limits (revision 1)"))
            assertTrue(text.contains("Synthetic Weather Ltd"))
        }
        assertNull(ok(call(technical, "GET", "/api/v1/jobs/${newJob()["id"].asString()}/operating-limits"))["approved"].takeUnless { it.isNull })
    }
}
