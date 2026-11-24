package com.ship2ship.features.technical

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the real V14 schema, job permissions, provenance gates and immutable output on MySQL. */
class MooringPlanApiIT : JobTestSupport() {
    private fun base(jobId: String) = "/api/v1/jobs/$jobId/mooring-plan/revisions"
    private fun codes(node: JsonNode) = (0 until node["blockers"].size()).map { node["blockers"][it]["code"].asString() }
    private fun sha(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun verifyParticulars(vessel: String, length: String = "150", manifoldHalf: String = "75") {
        val technical = login("technical")
        val doc = read(upload(technical, vessel).andExpect(status().isCreated).andReturn().response.contentAsString)["id"].asString()
        val draft = ok(call(technical, "POST", "/api/v1/technical/vessels/$vessel/particulars"))
        val codes = listOf("LOA" to length, "BEAM" to "30", "BOW_TO_MANIFOLD" to manifoldHalf,
            "STERN_TO_MANIFOLD" to manifoldHalf, "ACCOMMODATION_TO_MANIFOLD" to "40")
        val saved = ok(call(technical, "PUT", "/api/v1/technical/particulars/${draft["id"].asString()}",
            mapOf("values" to codes.map { (code, value) -> mapOf("fieldCode" to code, "value" to value,
                "sourceDocumentId" to doc, "sourcePage" to 1) }), draft["version"].asLong()))
        val submitted = ok(call(technical, "POST", "/api/v1/technical/particulars/${draft["id"].asString()}/submit",
            version = saved["version"].asLong()))
        ok(call(login("marine"), "POST", "/api/v1/technical/particulars/${draft["id"].asString()}/verify",
            mapOf("confirmedFieldCodes" to codes.map { it.first }), submitted["version"].asLong()))
    }

    private fun model(job: JsonNode, complete: Boolean): Map<String, Any?> {
        val mother = jobVessel(job, "MOTHER"); val daughter = jobVessel(job, "DAUGHTER")
        val first = UUID.randomUUID().toString(); val second = UUID.randomUUID().toString(); val line = UUID.randomUUID().toString()
        val particulars = listOf(mother, daughter).flatMap { vessel -> listOf("PRIMARY_FENDER_SIZE", "OIL_BOOM_LENGTH").map { code ->
            mapOf("jobVesselId" to vessel, "code" to code, "value" to 10, "unit" to "m", "sourceText" to "Synthetic verified source")
        } }
        return mapOf(
            "schemaVersion" to 1,
            "vessels" to listOf(mapOf("jobVesselId" to mother, "heading" to "CONSTANT", "lateralOffsetM" to 20),
                mapOf("jobVesselId" to daughter, "heading" to "MANOEUVRING", "lateralOffsetM" to -20)),
            "objects" to listOf(mapOf("id" to first, "type" to "MOORING_POINT", "xM" to -10, "yM" to 20),
                mapOf("id" to second, "type" to "MOORING_POINT", "xM" to 10, "yM" to -20)),
            "lines" to listOf(mapOf("id" to line, "label" to "F1", "jobVesselId" to mother,
                "fromPointId" to first, "toPointId" to second, "berthingOrder" to 1, "unmooringOrder" to 1,
                "capacities" to if (complete) listOf("OPS_BHC", "BIGS_MBL").map { code ->
                    mapOf("columnCode" to code, "value" to 20, "unit" to "t", "sourceText" to "Synthetic certified source")
                } else emptyList<Any>())),
            "particulars" to particulars,
            "rulerStepM" to 8,
        )
    }

    private fun activateColumns(session: MockHttpSession) {
        val columns = ok(call(session, "GET", "/api/v1/technical/mooring-capacity-columns"))
        for (i in 0 until columns.size()) {
            val col = columns[i]
            ok(call(session, "PUT", "/api/v1/technical/mooring-capacity-columns/${col["code"].asString()}",
                mapOf("definition" to "Synthetic capacity definition", "unit" to "t", "evidenceRequirement" to "Certified document"),
                col["version"].asLong()))
        }
    }

    @Test
    fun `new plan includes uploaded GA images from both job vessels`() {
        publishRequestTemplate()
        val job = newJob(); val jobId = job["id"].asString(); val technical = login("technical")
        val request = ok(call(technical, "POST", "/api/v1/jobs/$jobId/technical/request"))
        val gaItems = (0 until request["items"].size()).map { request["items"][it] }.filter { it["code"].asString() == "GA" }
        assertEquals(2, gaItems.size)
        val uploaded = gaItems.map { item ->
            val vessel = item["jobVesselId"].asString()
            val doc = read(jobUpload(technical, jobId, vessel, item["id"].asString(), DOC_TYPE_GA, PNG, "image/png")
                .andExpect(status().isCreated).andReturn().response.contentAsString)
            vessel to doc["id"].asString()
        }.toMap()
        val draft = ok(call(technical, "POST", base(jobId)))
        val layers = (0 until draft["model"]["layers"].size()).map { draft["model"]["layers"][it] }
        assertEquals(2, layers.size)
        assertTrue(layers.all { uploaded[it["jobVesselId"].asString()] == it["documentVersionId"].asString() })
    }

    @Test
    fun `vessel master GA follows both selected vessels into marine plan`() {
        val mother = newVessel(); val daughter = newVessel()
        val admin = login("sts.admin"); val marine = login("marine")
        val masterGa = listOf(mother, daughter).associateWith { vessel ->
            read(upload(admin, vessel, PNG, "ga.png", DOC_TYPE_GA)
                .andExpect(status().isCreated).andReturn().response.contentAsString)["id"].asString()
        }
        val job = newJob(mother, daughter); val jobId = job["id"].asString()
        val documents = ok(call(marine, "GET", "/api/v1/jobs/$jobId/technical/documents"))
        assertTrue(masterGa.values.all { id -> (0 until documents.size()).any { documents[it]["id"].asString() == id } })
        val draft = ok(call(marine, "POST", base(jobId)))
        val layers = (0 until draft["model"]["layers"].size()).map { draft["model"]["layers"][it] }
        assertEquals(2, layers.size)
        assertTrue(layers.all { layer ->
            val vessel = (0 until job["vessels"].size()).map { job["vessels"][it] }
                .first { it["id"].asString() == layer["jobVesselId"].asString() }
            masterGa[vessel["vesselId"].asString()] == layer["documentVersionId"].asString()
        })
        ok(call(marine, "PUT", "${base(jobId)}/${draft["id"].asString()}", draft["model"], draft["version"].asLong()))
    }

    @Test
    fun `missing verified values and capacities block submission, malformed models fail, and job scope is enforced`() {
        val job = newJob(); val jobId = job["id"].asString(); val technical = login("technical")
        val draft = ok(call(technical, "POST", base(jobId)))
        assertTrue(codes(draft).any { it.startsWith("dimensions.MOTHER") })
        assertTrue(codes(draft).contains("lines"))
        val url = "${base(jobId)}/${draft["id"].asString()}"
        call(technical, "POST", "$url/submit", version = draft["version"].asLong())
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("MOORING_INCOMPLETE"))
        call(technical, "PUT", url, mapOf("schemaVersion" to 99, "vessels" to emptyList<Any>()), draft["version"].asLong())
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        call(technical, "PUT", url, model(job, false) + mapOf("unexpectedField" to true), draft["version"].asLong())
            .andExpect(status().isBadRequest)
        val withoutCapacities = ok(call(technical, "PUT", url, model(job, false), draft["version"].asLong()))
        assertTrue(codes(withoutCapacities).any { it.startsWith("lines.F1.") })
        val other = newJob()
        call(technical, "GET", "${base(other["id"].asString())}/${draft["id"].asString()}").andExpect(status().isNotFound)
        ok(call(login("commercial"), "GET", url))
        call(login("commercial"), "PUT", url, model(job, false), withoutCapacities["version"].asLong()).andExpect(status().isForbidden)
    }

    @Test
    fun `second person verifies values and approves, the PDF stays hashed, and JPO links the issued plan`() {
        val mother = newVessel(); val daughter = newVessel()
        verifyParticulars(mother); verifyParticulars(daughter)
        val job = newJob(mother, daughter); val jobId = job["id"].asString()
        val technical = login("technical"); val marine = login("marine")
        activateColumns(login("sts.admin"))
        val draft = ok(call(technical, "POST", base(jobId)))
        val url = "${base(jobId)}/${draft["id"].asString()}"
        val saved = ok(call(technical, "PUT", url, model(job, true), draft["version"].asLong()))
        assertTrue(codes(saved).any { it.startsWith("particulars.") })
        call(technical, "POST", "$url/verify-value", mapOf("key" to "particular:${jobVessel(job, "MOTHER")}:PRIMARY_FENDER_SIZE"),
            saved["version"].asLong()).andExpect(status().isUnprocessableContent)
        var version = saved["version"].asLong()
        val keys = mutableListOf<String>()
        for (vessel in listOf(jobVessel(job, "MOTHER"), jobVessel(job, "DAUGHTER"))) {
            for (code in listOf("PRIMARY_FENDER_SIZE", "OIL_BOOM_LENGTH")) keys += "particular:$vessel:$code"
        }
        val lineId = saved["model"]["lines"][0]["id"].asString()
        for (code in listOf("OPS_BHC", "BIGS_MBL")) keys += "capacity:$lineId:$code"
        for (key in keys) {
            val verified = ok(call(marine, "POST", "$url/verify-value", mapOf("key" to key), version))
            version = verified["version"].asLong()
        }
        val complete = ok(call(technical, "GET", url))
        assertEquals(0, complete["blockers"].size())
        val submitted = ok(call(technical, "POST", "$url/submit", version = version))
        assertTrue(submitted["snapshotFrozen"].asBoolean())
        assertEquals(150.0, submitted["snapshot"]["vessels"][0]["dimensions"][0]["value"].asDouble())
        verifyParticulars(mother, "160", "80")
        val frozen = ok(call(technical, "GET", url))
        assertEquals(150.0, frozen["snapshot"]["vessels"][0]["dimensions"][0]["value"].asDouble())
        call(technical, "POST", "$url/decision", mapOf("decision" to "APPROVE"), submitted["version"].asLong())
            .andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("SELF_APPROVAL_FORBIDDEN"))
        call(marine, "POST", "$url/decision", mapOf("decision" to "RETURN", "reason" to " "), submitted["version"].asLong())
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("REASON_REQUIRED"))
        val approved = ok(call(marine, "POST", "$url/decision", mapOf("decision" to "APPROVE"), submitted["version"].asLong()))
        val issued = ok(call(technical, "POST", "$url/issue", version = approved["version"].asLong()))
        assertEquals("ISSUED", issued["status"].asString())
        val firstPdf = call(technical, "GET", "$url/pdf").andExpect(status().isOk).andReturn().response.contentAsByteArray
        val secondPdf = call(technical, "GET", "$url/pdf").andExpect(status().isOk).andReturn().response.contentAsByteArray
        assertTrue(firstPdf.contentEquals(secondPdf))
        assertEquals(sha(firstPdf), issued["pdfSha256"].asString())
        call(technical, "PUT", url, model(job, true), issued["version"].asLong()).andExpect(status().isUnprocessableContent)
        val jpo = ok(call(technical, "POST", "/api/v1/jobs/$jobId/jpo/revisions"))
        assertEquals("LINKED", jpo["sources"]["mooringDiagram"].asString())
        assertTrue((0 until jpo["blockers"].size()).none { jpo["blockers"][it]["code"].asString() == "mooringDiagramDocumentId" })
        val jpoPdf = call(technical, "GET", "/api/v1/jobs/$jobId/jpo/revisions/${jpo["id"].asString()}/pdf")
            .andExpect(status().isOk).andReturn().response.contentAsByteArray
        Loader.loadPDF(jpoPdf).use { document ->
            val text = PDFTextStripper().getText(document)
            assertTrue(text.contains("Mooring diagram - plan revision 1"))
            assertTrue(text.contains("Unmooring diagram - plan revision 1"))
        }

        val second = ok(call(technical, "POST", base(jobId)))
        assertEquals(2, second["revisionNo"].asInt())
        assertTrue(codes(second).any { it.startsWith("particulars.") }) // copied values need fresh verification
        val secondUrl = "${base(jobId)}/${second["id"].asString()}"
        var secondVersion = second["version"].asLong()
        for (key in keys) {
            secondVersion = ok(call(marine, "POST", "$secondUrl/verify-value", mapOf("key" to key), secondVersion))["version"].asLong()
        }
        val secondSubmitted = ok(call(technical, "POST", "$secondUrl/submit", version = secondVersion))
        val secondApproved = ok(call(marine, "POST", "$secondUrl/decision", mapOf("decision" to "APPROVE"),
            secondSubmitted["version"].asLong()))
        ok(call(technical, "POST", "$secondUrl/issue", version = secondApproved["version"].asLong()))
        val superseded = ok(call(technical, "GET", url))
        assertEquals("SUPERSEDED", superseded["status"].asString())
        assertEquals(sha(firstPdf), superseded["pdfSha256"].asString())
    }
}
