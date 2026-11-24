package com.ship2ship.features.technical

import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FR-TECH-01 / AR-03 / K1: manual entry with source page per value, submission, verification by a different person
 * (or return with a reason), supersession, and the vessel master following the verified values.
 */
class ParticularsApiIT : TechnicalTestSupport() {

    private fun document(session: MockHttpSession, vessel: String): String =
        read(upload(session, vessel).andExpect(status().isCreated).andReturn().response.contentAsString)["id"].asString()

    private fun values(doc: String?, loa: String = "183.06", bow: String = "89.17", stern: String = "93.89") = listOf(
        mapOf("fieldCode" to "LOA", "value" to loa, "sourceDocumentId" to doc, "sourcePage" to 1),
        mapOf("fieldCode" to "BEAM", "value" to "32.2", "sourceDocumentId" to doc, "sourcePage" to 1),
        mapOf("fieldCode" to "BOW_TO_MANIFOLD", "value" to bow, "sourceDocumentId" to doc, "sourcePage" to 4),
        mapOf("fieldCode" to "STERN_TO_MANIFOLD", "value" to stern, "sourceDocumentId" to doc, "sourcePage" to 4),
        mapOf("fieldCode" to "MANIFOLD_SIZE", "value" to "16 in", "sourceDocumentId" to doc, "sourcePage" to 5),
    )

    private fun codes(node: JsonNode, field: String = "fieldCode") = (0 until node.size()).map { node[it][field].asString() }

    @Test
    fun `entered values cite their source, warnings flag gaps and inconsistencies, and submission needs every source`() {
        val technical = login("technical")
        val vessel = newVessel()
        val doc = document(technical, vessel)
        val draft = ok(call(technical, "POST", "/api/v1/technical/vessels/$vessel/particulars"))
        assertEquals("DRAFT", draft["status"].asString())
        assertEquals(listOf("EDIT", "SUBMIT"), (0 until draft["allowedActions"].size()).map { draft["allowedActions"][it].asString() })

        // Bow + stern (89.17 + 93.80 = 182.97) differs from LOA 183.06: a warning, not a block.
        val saved = ok(call(technical, "PUT", "/api/v1/technical/particulars/${draft["id"].asString()}",
            mapOf("values" to values(null, stern = "93.80")), draft["version"].asLong()))
        assertEquals(listOf("LOA_MISMATCH"), codes(saved["warnings"], "code"))
        assertTrue(saved["warnings"][0]["message"].asString().contains("182.97"))

        // Without sources the version cannot be submitted.
        call(technical, "POST", "/api/v1/technical/particulars/${saved["id"].asString()}/submit", version = saved["version"].asLong())
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("SOURCE_REQUIRED"))
            .andExpect(jsonPath("$.errors.length()").value(5))

        // Invalid numbers and foreign documents are refused.
        val otherVesselDoc = document(technical, newVessel())
        call(technical, "PUT", "/api/v1/technical/particulars/${saved["id"].asString()}", mapOf("values" to listOf(
            mapOf("fieldCode" to "LOA", "value" to "about 180"),
            mapOf("fieldCode" to "BEAM", "value" to "32.2", "sourceDocumentId" to otherVesselDoc, "sourcePage" to 1),
            mapOf("fieldCode" to "NOT_A_FIELD", "value" to "1"),
        )), saved["version"].asLong()).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.containsInAnyOrder("values.LOA", "values.BEAM.source", "values.NOT_A_FIELD")))

        val sourced = ok(call(technical, "PUT", "/api/v1/technical/particulars/${saved["id"].asString()}",
            mapOf("values" to values(doc)), saved["version"].asLong()))
        assertEquals(0, sourced["warnings"].size()) // 89.17 + 93.89 = 183.06; all planning fields present
        val submitted = ok(call(technical, "POST", "/api/v1/technical/particulars/${sourced["id"].asString()}/submit", version = sourced["version"].asLong()))
        assertEquals("SUBMITTED", submitted["status"].asString())
        assertEquals("Demo Technical Officer", submitted["submittedByName"].asString())
    }

    @Test
    fun `a different person verifies every value, and the vessel master follows the verified figures`() {
        val technical = login("technical")
        val vessel = newVessel()
        val doc = document(technical, vessel)
        val draft = ok(call(technical, "POST", "/api/v1/technical/vessels/$vessel/particulars"))
        val saved = ok(call(technical, "PUT", "/api/v1/technical/particulars/${draft["id"].asString()}", mapOf("values" to values(doc)), draft["version"].asLong()))
        val submitted = ok(call(technical, "POST", "/api/v1/technical/particulars/${saved["id"].asString()}/submit", version = saved["version"].asLong()))
        val id = submitted["id"].asString()

        // The submitter cannot verify their own entry.
        assertEquals(listOf("RETURN"), (0 until submitted["allowedActions"].size()).map { submitted["allowedActions"][it].asString() })
        call(technical, "POST", "/api/v1/technical/particulars/$id/verify",
            mapOf("confirmedFieldCodes" to listOf("LOA", "BEAM", "BOW_TO_MANIFOLD", "STERN_TO_MANIFOLD", "MANIFOLD_SIZE")), submitted["version"].asLong())
            .andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("SELF_VERIFICATION_FORBIDDEN"))

        val marine = login("marine")
        call(marine, "POST", "/api/v1/technical/particulars/$id/verify", mapOf("confirmedFieldCodes" to listOf("LOA", "BEAM")), submitted["version"].asLong())
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.errors.length()").value(3))
        val verified = ok(call(marine, "POST", "/api/v1/technical/particulars/$id/verify",
            mapOf("confirmedFieldCodes" to listOf("LOA", "BEAM", "BOW_TO_MANIFOLD", "STERN_TO_MANIFOLD", "MANIFOLD_SIZE")), submitted["version"].asLong()))
        assertEquals("VERIFIED", verified["status"].asString())
        assertEquals("Demo Marine Superintendent", verified["verifiedByName"].asString())
        assertTrue((0 until verified["values"].size()).all { verified["values"][it]["verified"].asBoolean() })

        val master = ok(call(marine, "GET", "/api/v1/masters/vessels/$vessel"))
        assertEquals("VERIFIED", master["particularsStatus"].asString())
        assertEquals(0, java.math.BigDecimal("183.06").compareTo(master["loaM"].decimalValue()))
        assertEquals(0, java.math.BigDecimal("32.2").compareTo(master["beamM"].decimalValue()))

        // Editing the master's dimensions by hand makes them unverified again.
        val admin = login("admin")
        ok(call(admin, "PUT", "/api/v1/masters/vessels/$vessel", mapOf("imo" to master["imo"].asString(), "name" to master["name"].asString(),
            "vesselType" to "PRODUCT_TANKER", "loaM" to "184", "beamM" to "32.2"), master["version"].asLong()))
        assertEquals("UNVERIFIED", ok(call(admin, "GET", "/api/v1/masters/vessels/$vessel"))["particularsStatus"].asString())
    }

    @Test
    fun `a returned version goes back to draft with the reason, and a newer verified version supersedes the older`() {
        val technical = login("technical")
        val marine = login("marine")
        val vessel = newVessel()
        val doc = document(technical, vessel)
        val all = listOf("LOA", "BEAM", "BOW_TO_MANIFOLD", "STERN_TO_MANIFOLD", "MANIFOLD_SIZE")

        fun submitNew(loa: String = "183.06"): JsonNode {
            val draft = ok(call(technical, "POST", "/api/v1/technical/vessels/$vessel/particulars"))
            val saved = ok(call(technical, "PUT", "/api/v1/technical/particulars/${draft["id"].asString()}",
                mapOf("values" to values(doc, loa = loa, bow = "90", stern = (java.math.BigDecimal(loa) - java.math.BigDecimal("90")).toPlainString())),
                draft["version"].asLong()))
            return ok(call(technical, "POST", "/api/v1/technical/particulars/${saved["id"].asString()}/submit", version = saved["version"].asLong()))
        }

        val first = submitNew()
        // Only one open version per vessel.
        call(technical, "POST", "/api/v1/technical/vessels/$vessel/particulars").andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("PARTICULARS_VERSION_OPEN"))
        val v1 = ok(call(marine, "POST", "/api/v1/technical/particulars/${first["id"].asString()}/verify", mapOf("confirmedFieldCodes" to all), first["version"].asLong()))

        val second = submitNew("184.5")
        assertEquals(2, second["versionNo"].asInt())
        call(marine, "POST", "/api/v1/technical/particulars/${second["id"].asString()}/return", mapOf("reason" to " "), second["version"].asLong())
            .andExpect(status().isBadRequest)
        val returned = ok(call(marine, "POST", "/api/v1/technical/particulars/${second["id"].asString()}/return",
            mapOf("reason" to "Beam on page 1 reads 32.20, not 32.2 - please recheck"), second["version"].asLong()))
        assertEquals("DRAFT", returned["status"].asString())
        assertTrue(returned["returnedReason"].asString().startsWith("Beam on page 1"))

        // Stale If-Match is refused; the current version is resubmitted and verified.
        call(technical, "POST", "/api/v1/technical/particulars/${returned["id"].asString()}/submit", version = second["version"].asLong())
            .andExpect(status().isConflict)
        val resubmitted = ok(call(technical, "POST", "/api/v1/technical/particulars/${returned["id"].asString()}/submit", version = returned["version"].asLong()))
        ok(call(marine, "POST", "/api/v1/technical/particulars/${resubmitted["id"].asString()}/verify", mapOf("confirmedFieldCodes" to all), resubmitted["version"].asLong()))

        val overview = ok(call(technical, "GET", "/api/v1/technical/vessels/$vessel/particulars"))
        assertEquals(listOf("VERIFIED", "SUPERSEDED"), (0 until overview["versions"].size()).map { overview["versions"][it]["status"].asString() })
        assertEquals("SUPERSEDED", ok(call(technical, "GET", "/api/v1/technical/particulars/${v1["id"].asString()}"))["status"].asString())

        // Read-only users cannot enter particulars.
        call(login("commercial"), "POST", "/api/v1/technical/vessels/$vessel/particulars").andExpect(status().isForbidden)
    }
}
