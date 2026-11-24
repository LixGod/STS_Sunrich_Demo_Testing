package com.ship2ship.features.commercial

import com.ship2ship.support.IntegrationTest
import org.hamcrest.Matchers.everyItem
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.matchesPattern
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** FR-J01 enquiry register: numbering, scope, multi-grade cargo, ETA time zones, idempotency, duplicates, lifecycle. */
class EnquiryApiIT : IntegrationTest() {

    private fun request(
        mother: String? = VESSEL_MOTHER,
        daughter: String? = VESSEL_DAUGHTER,
        branch: String = BRANCH_MUM,
        cargo: List<Map<String, Any>> = listOf(
            mapOf("cargoGradeId" to GRADE_PROPANE, "quantity" to "34000.000", "unitId" to UNIT_MT),
            mapOf("cargoGradeId" to GRADE_BUTANE, "quantity" to "12000.500", "unitId" to UNIT_MT),
        ),
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = mapOf(
        "branchId" to branch,
        "receivedAt" to "2026-10-16T05:00:00Z",
        "clientId" to CLIENT_CHARTERER,
        "clientContactId" to CONTACT_CHARTERER,
        "operationType" to "LPG",
        "expectedDurationHours" to "48",
        "scopeOfServices" to "Synthetic scope",
        "motherVessel" to mother?.let { mapOf("vesselId" to it, "etaLocal" to "2026-10-20T08:00:00") },
        "daughterVessel" to daughter?.let { mapOf("vesselId" to it) },
        "cargo" to cargo,
    ) + extra

    private fun create(session: MockHttpSession, payload: Map<String, Any?>, key: String? = null): ResultActions =
        mvc.perform(post("/api/v1/enquiries").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .apply { key?.let { header("Idempotency-Key", it) } }.content(body(payload)))

    private fun created(session: MockHttpSession, payload: Map<String, Any?> = request()): JsonNode =
        read(create(session, payload).andExpect(status().isCreated).andReturn().response.contentAsString)

    private fun transition(session: MockHttpSession, id: String, version: Long, to: String, reason: String? = null): ResultActions =
        mvc.perform(post("/api/v1/enquiries/$id/transitions").session(session).with(csrf()).header(HttpHeaders.IF_MATCH, "\"$version\"")
            .contentType(MediaType.APPLICATION_JSON).content(body(mapOf("to" to to, "reason" to reason))))

    @Test
    fun `received date filters use the viewer time zone`() {
        val session = login("commercial")
        val enquiry = created(session, request(extra = mapOf("receivedAt" to "2026-10-15T20:00:00Z")))
        val result = read(mvc.perform(get("/api/v1/enquiries").session(session)
            .param("q", enquiry["reference"].asString())
            .param("receivedFrom", "2026-10-16").param("receivedTo", "2026-10-16")
            .param("timeZone", "Asia/Kolkata"))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        assertTrue(result["items"].any { it["id"].asString() == enquiry["id"].asString() })
    }

    @Test
    fun `creates a numbered enquiry with two cargo grades and converts the ETA from the location time zone`() {
        val enquiry = created(login("commercial"))
        assertEquals("DRAFT", enquiry["status"].asString())
        assert(Regex("""^ENQ-STS/MUM/16102026/\d{3,}$""").matches(enquiry["reference"].asString()))
        assertEquals(2, enquiry["cargo"].size())
        assertEquals("BUTANE", enquiry["cargo"][1]["cargoGradeCode"].asString())
        // 08:00 in Asia/Kolkata (UTC+05:30) is 02:30 UTC.
        assertEquals("2026-10-20T02:30:00Z", enquiry["motherVessel"]["etaUtc"].asString())
        assertEquals("Asia/Kolkata", enquiry["motherVessel"]["etaTimezone"].asString())
        assertEquals(listOf("COSTING", "LOST", "CANCELLED"), enquiry["allowedTransitions"].let { node -> (0 until node.size()).map { node[it].asString() } })
        assertEquals("DRAFT", enquiry["history"][0]["toStatus"].asString())
    }

    @Test
    fun `keeps multiple cargo types while preserving the legacy operation type`() {
        val session = login("commercial")
        val enquiry = created(session, request(extra = mapOf("cargoTypes" to listOf("LPG", "CHEMICAL"))))
        assertEquals(listOf("CHEMICAL", "LPG"), (0 until enquiry["cargoTypes"].size()).map { enquiry["cargoTypes"][it].asString() })
        assertEquals("OTHER", enquiry["operationType"].asString())

        mvc.perform(get("/api/v1/enquiries/${enquiry["id"].asString()}").session(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.cargoTypes.length()").value(2))

        mvc.perform(put("/api/v1/enquiries/${enquiry["id"].asString()}").session(session).with(csrf())
            .header(HttpHeaders.IF_MATCH, "\"0\"").contentType(MediaType.APPLICATION_JSON)
            .content(body(request(extra = mapOf("cargoTypes" to listOf("LPG"))))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.cargoTypes[0]").value("LPG"))
            .andExpect(jsonPath("$.operationType").value("LPG"))
    }

    @Test
    fun `the same idempotency key returns the first enquiry instead of a second one`() {
        val session = login("commercial")
        val key = UUID.randomUUID().toString()
        val first = read(create(session, request(), key).andExpect(status().isCreated).andReturn().response.contentAsString)
        val second = read(create(session, request(), key).andExpect(status().isCreated).andReturn().response.contentAsString)
        assertEquals(first["id"].asString(), second["id"].asString())
        assertEquals(first["reference"].asString(), second["reference"].asString())
    }

    @Test
    fun `rejects inconsistent references with field errors`() {
        val session = login("commercial")
        create(session, request(mother = VESSEL_MOTHER, daughter = VESSEL_MOTHER))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("daughterVessel.vesselId"))
        create(session, request(extra = mapOf("clientId" to CLIENT_OWNER)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("clientContactId"))
        create(session, request(cargo = listOf(
            mapOf("cargoGradeId" to GRADE_PROPANE, "quantity" to "1", "unitId" to UNIT_MT),
            mapOf("cargoGradeId" to GRADE_PROPANE, "quantity" to "2", "unitId" to UNIT_MT),
        ))).andExpect(status().isBadRequest)
        create(session, request(extra = mapOf("cargoTypes" to listOf("LPG", "lpg"))))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("cargoTypes"))
    }

    @Test
    fun `scope decides who may create and see enquiries`() {
        val enquiry = created(login("commercial"))

        // Operations (Mumbai) may read but not create.
        create(login("ops.mumbai"), request()).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/enquiries/${enquiry["id"].asString()}").session(login("ops.mumbai"))).andExpect(status().isOk)

        // An enquiry in Kandla is invisible to the Mumbai operations user.
        val kandla = created(login("commercial"), request(branch = BRANCH_KDL))
        mvc.perform(get("/api/v1/enquiries/${kandla["id"].asString()}").session(login("ops.mumbai"))).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/enquiries").session(login("ops.mumbai")).param("size", "100"))
            .andExpect(jsonPath("$.items[*].branchCode").value(everyItem(equalTo("MUM"))))
    }

    @Test
    fun `list searches by vessel name and reports possible duplicates`() {
        val session = login("commercial")
        val first = created(session, request(mother = VESSEL_TANKER, daughter = VESSEL_DAUGHTER))
        mvc.perform(get("/api/v1/enquiries").session(session).param("q", "demo tanker").param("size", "100"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[*].reference").value(org.hamcrest.Matchers.hasItem(first["reference"].asString())))
        mvc.perform(get("/api/v1/enquiries/duplicates").session(session)
            .param("motherVesselId", VESSEL_DAUGHTER).param("daughterVesselId", VESSEL_TANKER))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[*].id").value(org.hamcrest.Matchers.hasItem(first["id"].asString())))
    }

    @Test
    fun `lifecycle needs reasons for loss, blocks invalid moves and locks closed enquiries`() {
        val session = login("commercial")
        val enquiry = created(session)
        val id = enquiry["id"].asString()

        transition(session, id, 0, "COSTING").andExpect(status().isOk).andExpect(jsonPath("$.status").value("COSTING"))
        transition(session, id, 0, "LOST", "Late").andExpect(status().isConflict) // stale version
        transition(session, id, 1, "LOST").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("REASON_REQUIRED"))
        transition(session, id, 1, "LOST", "Client chose another operator").andExpect(status().isOk)
            .andExpect(jsonPath("$.history.length()").value(3))
            .andExpect(jsonPath("$.editable").value(false))
        transition(session, id, 2, "COSTING").andExpect(status().isUnprocessableContent)
            .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"))

        mvc.perform(put("/api/v1/enquiries/$id").session(session).with(csrf()).header(HttpHeaders.IF_MATCH, "\"2\"")
            .contentType(MediaType.APPLICATION_JSON).content(body(request())))
            .andExpect(status().isUnprocessableContent)
            .andExpect(jsonPath("$.code").value("ENQUIRY_LOCKED"))
    }

    @Test
    fun `editing cargo lines updates rows in place and keeps line numbers unique`() {
        val session = login("commercial")
        val enquiry = created(session)
        val id = enquiry["id"].asString()
        // Swap the two grades, then reduce to one line.
        val swapped = request(cargo = listOf(
            mapOf("cargoGradeId" to GRADE_BUTANE, "quantity" to "500", "unitId" to UNIT_MT),
            mapOf("cargoGradeId" to GRADE_PROPANE, "quantity" to "600", "unitId" to UNIT_MT),
        ))
        mvc.perform(put("/api/v1/enquiries/$id").session(session).with(csrf()).header(HttpHeaders.IF_MATCH, "\"0\"")
            .contentType(MediaType.APPLICATION_JSON).content(body(swapped)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.cargo[0].cargoGradeCode").value("BUTANE"))
            .andExpect(jsonPath("$.version").value(1)) // cargo-only edits still advance the enquiry version
        mvc.perform(put("/api/v1/enquiries/$id").session(session).with(csrf()).header(HttpHeaders.IF_MATCH, "\"1\"")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(request(daughter = null, cargo = listOf(mapOf("cargoGradeId" to GRADE_PROPANE, "quantity" to "700", "unitId" to UNIT_MT))))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.cargo.length()").value(1))
            .andExpect(jsonPath("$.daughterVessel").doesNotExist())
            .andExpect(jsonPath("$.reference").value(matchesPattern("^ENQ-STS/.*")))
    }

    @Test
    fun `allows creating a minimal enquiry with branch only and adding client and cargo later`() {
        val session = login("commercial")
        val minimal = created(session, mapOf("branchId" to BRANCH_MUM))
        val id = minimal["id"].asString()
        assertEquals("DRAFT", minimal["status"].asString())
        assertEquals(0, minimal["cargo"].size())
        assertTrue(minimal["client"].isNull)
        assertTrue(minimal["receivedAt"].isNull)

        val updated = read(mvc.perform(put("/api/v1/enquiries/$id").session(session).with(csrf()).header(HttpHeaders.IF_MATCH, "\"0\"")
            .contentType(MediaType.APPLICATION_JSON).content(body(request())))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.client.id").value(CLIENT_CHARTERER))
            .andExpect(jsonPath("$.cargo.length()").value(2))
            .andReturn().response.contentAsString)
        assertTrue(updated["version"].asLong() > minimal["version"].asLong())
    }

    @Test
    fun `exports filtered enquiries overview as an Excel workbook`() {
        val session = login("commercial")
        val enquiry = created(session)
        val reference = enquiry["reference"].asString()
        val response = mvc.perform(get("/api/v1/enquiries/xlsx").session(session)
            .param("q", reference).param("timeZone", "Asia/Kolkata"))
            .andExpect(status().isOk)
            .andReturn().response
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", response.contentType)
        assertTrue(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)!!.contains("enquiries-overview.xlsx"))
        org.apache.poi.xssf.usermodel.XSSFWorkbook(response.contentAsByteArray.inputStream()).use { book ->
            val sheet = book.getSheet("Enquiries")
            assertEquals("Reference", sheet.getRow(2).getCell(1).stringCellValue)
            assertEquals(reference, sheet.getRow(3).getCell(1).stringCellValue)
            assertEquals("MUM", sheet.getRow(3).getCell(6).stringCellValue)
            assertEquals("DRAFT", sheet.getRow(3).getCell(17).stringCellValue)
        }
    }
}
