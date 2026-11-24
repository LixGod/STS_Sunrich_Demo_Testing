package com.ship2ship.features.technical

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 3c: the Joint Plan of Operation. Marking it done is blocked until the required entries and verified voyage
 * values exist; done stores the final PDF, which is immutable; acceptances are recorded on the done revision; a newer
 * done revision supersedes the older one. No approval or published annex wording is needed.
 */
class JpoApiIT : JobTestSupport() {

    private fun codes(node: JsonNode, field: String) = (0 until node[field].size()).map { node[field][it]["code"].asString() }

    private fun base(jobId: String) = "/api/v1/jobs/$jobId/jpo/revisions"

    private fun png(): ByteArray = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    private class Ready(val job: JsonNode, val jobId: String, val diagramId: String)

    /** A job with a document request, a PNG mooring diagram and verified voyage values for both vessels. */
    private fun readyJob(): Ready {
        publishRequestTemplate()
        val job = newJob()
        val jobId = job["id"].asString()
        val technical = login("technical")
        val marine = login("marine")
        val request = ok(call(technical, "POST", "/api/v1/jobs/$jobId/technical/request"))
        fun item(code: String, vessel: String) = (0 until request["items"].size()).map { request["items"][it] }
            .first { it["code"].asString() == code && it["jobVesselId"].takeIf { n -> !n.isNull }?.asString() == vessel }["id"].asString()
        var diagram = ""
        for (role in listOf("MOTHER", "DAUGHTER")) {
            val vessel = jobVessel(job, role)
            val doc = read(jobUpload(technical, jobId, vessel, item("Q88", vessel)).andReturn().response.contentAsString)["id"].asString()
            if (role == "MOTHER") {
                diagram = read(jobUpload(technical, jobId, vessel, item("MOORING", vessel), bytes = png()).andReturn().response.contentAsString)["id"].asString()
            }
            val fields = JpoService.JPO_VOYAGE_FIELDS
            val draft = ok(call(technical, "POST", "/api/v1/jobs/$jobId/technical/vessels/$vessel/voyage"))
            val url = "/api/v1/jobs/$jobId/technical/voyage/${draft["id"].asString()}"
            val saved = ok(call(technical, "PUT", url, mapOf("values" to fields.mapIndexed { i, f ->
                mapOf("fieldCode" to f, "value" to "${5 + i}.5", "sourceDocumentId" to doc, "sourcePage" to 1)
            }), draft["version"].asLong()))
            val submitted = ok(call(technical, "POST", "$url/submit", version = saved["version"].asLong()))
            ok(call(marine, "POST", "$url/verify", mapOf("confirmedFieldCodes" to fields), submitted["version"].asLong()))
        }
        return Ready(job, jobId, diagram)
    }

    private fun content(draft: JsonNode, diagramId: String?, contingency: String = "Synthetic contingency plan") = mapOf(
        "operationDate" to "2026-11-02",
        "purpose" to "Synthetic transfer",
        "reverseLightering" to false,
        "mooringDiagramDocumentId" to diagramId,
        "shipHandling" to "HELM_ORDERS",
        "maxRudderAngleDeg" to "35",
        "constantHeadingPropulsion" to "CPP",
        "approach" to "UNDERWAY",
        "operatingLimits" to listOf(mapOf("parameter" to "Wind", "limit" to "25", "unit" to "kn", "action" to "Stop and review")),
        "securityLevel" to "LEVEL_1",
        "contingency" to contingency,
        "personnelTransfer" to "Synthetic gangway",
        "cargoRates" to draft["content"]["cargoRates"],
        "equipment" to listOf(
            mapOf("category" to "CARGO_HOSE", "number" to 2, "dimensions" to "8in x 10m", "strings" to 1),
            mapOf("category" to "PRIMARY_FENDER", "number" to 4, "dimensions" to "3.3m x 6.5m"),
        ),
        "ancillary" to draft["content"]["ancillary"],
    )

    @Test
    fun `submission is blocked until required entries and verified voyage values exist, and entries are validated`() {
        val job = newJob()
        val jobId = job["id"].asString()
        val technical = login("technical")

        val draft = ok(call(technical, "POST", base(jobId)))
        assertEquals("DRAFT", draft["status"].asString())
        assertEquals(1, draft["revisionNo"].asInt())
        assertEquals(4, draft["content"]["equipment"].size()) // empty rows for hoses and fenders
        assertEquals(1, draft["content"]["cargoRates"].size()) // one per cargo grade of the job
        assertEquals("MANUAL", draft["sources"]["mooringDiagram"].asString())
        val blockers = codes(draft, "blockers")
        assertTrue(listOf("operationDate", "mooringDiagramDocumentId", "shipHandling", "operatingLimits", "equipment.CARGO_HOSE",
            "voyage.MOTHER", "voyage.DAUGHTER").all { it in blockers }, blockers.toString())
        call(technical, "POST", base(jobId)).andExpect(jsonPath("$.code").value("JPO_REVISION_OPEN"))

        val url = "${base(jobId)}/${draft["id"].asString()}"
        call(technical, "POST", "$url/submit", version = draft["version"].asLong())
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("JPO_INCOMPLETE"))

        // Codes, numbers and documents are checked; a document of another job is refused.
        call(technical, "PUT", url, content(draft, null) + mapOf("approach" to "SWIMMING"), draft["version"].asLong())
            .andExpect(jsonPath("$.errors[0].field").value("approach"))
        call(technical, "PUT", url, content(draft, null) + mapOf("maxRudderAngleDeg" to "120"), draft["version"].asLong())
            .andExpect(status().isBadRequest)
        val other = readyJob()
        call(technical, "PUT", url, content(draft, other.diagramId), draft["version"].asLong())
            .andExpect(jsonPath("$.errors[0].field").value("mooringDiagramDocumentId"))

        // Complete entries: only the missing verified voyage values still block.
        val saved = ok(call(technical, "PUT", url, content(draft, null), draft["version"].asLong()))
        assertEquals(setOf("mooringDiagramDocumentId", "voyage.MOTHER", "voyage.DAUGHTER"), codes(saved, "blockers").toSet())
        call(technical, "POST", "$url/submit", version = saved["version"].asLong()).andExpect(jsonPath("$.code").value("JPO_INCOMPLETE"))

        // Scope: read-only roles see it but cannot change it; another job's id does not reach this revision.
        ok(call(login("commercial"), "GET", url))
        call(login("commercial"), "PUT", url, content(draft, null), saved["version"].asLong()).andExpect(status().isForbidden)
        call(login("finance"), "GET", "/api/v1/jobs/$jobId/jpo").andExpect(status().isForbidden)
        call(technical, "GET", "${base(other.jobId)}/${draft["id"].asString()}").andExpect(status().isNotFound)
    }

    @Test
    fun `a completed JPO is marked done with a stored PDF, accepted, and superseded by a newer revision`() {
        val ready = readyJob()
        val jobId = ready.jobId
        val technical = login("technical")
        fun get(path: String) = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).session(technical))
            .andExpect(status().isOk).andReturn().response

        val draft = ok(call(technical, "POST", base(jobId)))
        val url = "${base(jobId)}/${draft["id"].asString()}"
        val saved = ok(call(technical, "PUT", url, content(draft, ready.diagramId), draft["version"].asLong()))
        assertEquals(emptyList(), codes(saved, "blockers"))
        assertTrue(!saved["snapshotFrozen"].asBoolean())

        // A draft PDF is watermarked and not stored.
        assertTrue(get("$url/pdf").getHeader("Content-Disposition")!!.contains("-draft.pdf"))

        // Done straight away, with no approver and no published annex: job data frozen, final PDF stored with its hash.
        val done = ok(call(technical, "POST", "$url/submit", version = saved["version"].asLong()))
        assertEquals("DONE", done["status"].asString())
        assertTrue(done["snapshotFrozen"].asBoolean())
        assertEquals(8, (0 until done["snapshot"]["voyage"].size()).count { done["snapshot"]["voyage"][it]["role"].asString() == "MOTHER" })
        call(technical, "PUT", url, content(draft, ready.diagramId), done["version"].asLong()).andExpect(jsonPath("$.code").value("INVALID_TRANSITION"))
        val sha = done["pdfSha256"].asString()
        assertEquals(64, sha.length)
        repeat(2) {
            val response = get("$url/pdf")
            assertTrue(!response.getHeader("Content-Disposition")!!.contains("-draft"))
            val pdf = response.contentAsByteArray
            assertEquals("%PDF", String(pdf.copyOf(4)))
            assertEquals(sha, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf)))
        }

        // Excel export: the job number and the vessel names.
        XSSFWorkbook(get("$url/xlsx").contentAsByteArray.inputStream()).use { book ->
            val text = (0 until book.numberOfSheets).flatMap { s -> book.getSheetAt(s).flatMap { row -> row.map { it.toString() } } }.joinToString("\n")
            assertTrue(text.contains(ready.job["jobNumber"].asString()))
            val vessels = (0 until done["snapshot"]["vessels"].size()).map { done["snapshot"]["vessels"][it]["name"].asString() }
            assertTrue(vessels.all { text.contains(it) }, vessels.toString())
        }

        // Acceptances on the done revision: not in the future, one per party.
        call(technical, "POST", "$url/acceptances", mapOf("party" to "DISCHARGING_MASTER", "personName" to "Demo Master A",
            "acceptedAt" to Instant.now().plus(2, ChronoUnit.DAYS).toString())).andExpect(status().isBadRequest)
        val accepted = ok(call(technical, "POST", "$url/acceptances", mapOf("party" to "DISCHARGING_MASTER", "personName" to "Demo Master A",
            "acceptedAt" to Instant.now().toString(), "signedDocumentId" to ready.diagramId)))
        assertEquals(1, accepted["acceptances"].size())
        call(technical, "POST", "$url/acceptances", mapOf("party" to "DISCHARGING_MASTER", "personName" to "Demo Master B",
            "acceptedAt" to Instant.now().toString())).andExpect(status().isConflict)
        assertEquals(sha, ok(call(technical, "GET", url))["pdfSha256"].asString())

        // Revision 2 copies the entries; once done, revision 1 is superseded and keeps its PDF.
        assertTrue(ok(call(technical, "GET", "/api/v1/jobs/$jobId/jpo"))["canCreate"].asBoolean())
        val second = ok(call(technical, "POST", base(jobId)))
        assertEquals(2, second["revisionNo"].asInt())
        assertEquals("Synthetic contingency plan", second["content"]["contingency"].asString())
        val secondUrl = "${base(jobId)}/${second["id"].asString()}"
        val s2 = ok(call(technical, "PUT", secondUrl, content(second, ready.diagramId, "Revised synthetic contingency"), second["version"].asLong()))
        ok(call(technical, "POST", "$secondUrl/submit", version = s2["version"].asLong()))
        val first = ok(call(technical, "GET", url))
        assertEquals("SUPERSEDED", first["status"].asString())
        assertEquals(sha, first["pdfSha256"].asString())
        val statuses = ok(call(technical, "GET", "/api/v1/jobs/$jobId/jpo"))["revisions"]
        assertEquals(listOf("DONE", "SUPERSEDED"), (0 until statuses.size()).map { statuses[it]["status"].asString() })
    }

    @Test
    fun `submitted JPO keeps linked resource values after the allocation is cancelled`() {
        val ready = readyJob()
        val admin = login("admin")
        val ops = login("ops.mumbai")
        val technical = login("technical")
        val suffix = UUID.randomUUID().toString().take(8).uppercase()
        val certType = "JPO_CERT_$suffix"
        ok(call(admin,"POST","/api/v1/resources/certificate-types",mapOf("code" to certType,"label" to "Synthetic hose test")))
        val model = ok(call(admin,"POST","/api/v1/resources/models",mapOf("categoryCode" to "CARGO_HOSE","code" to "JPO_MODEL_$suffix","name" to "Synthetic linked hose")))
        val asset = ok(call(admin,"POST","/api/v1/resources/assets",mapOf("modelId" to model["id"].asString(),"assetIdentifier" to "JPO-HOSE-$suffix",
            "dimensionsText" to "8in x 12m")))
        jdbc.sql("UPDATE resource_certificate_requirement SET active = false WHERE scope_code = 'CARGO_HOSE'").update()
        ok(call(admin,"POST","/api/v1/resources/requirements",mapOf("resourceKind" to "ASSET",
            "scopeCode" to "CARGO_HOSE","certificateTypeCode" to certType)))
        val policy = ok(call(admin,"GET","/api/v1/resources/policy"))
        ok(call(admin,"PUT","/api/v1/resources/policy",mapOf("mode" to "BLOCK",
            "warningDays" to null,"requirementsConfigured" to true),policy["version"].asLong()))
        mvc.perform(multipart("/api/v1/resources/assets/${asset["id"].asString()}/certificates")
            .file(MockMultipartFile("certificate","","application/json",body(mapOf("typeCode" to certType,
                "certificateNumber" to "SYN-$suffix","issuer" to "Synthetic inspector",
                "issueDate" to "2026-01-01","expiryDate" to "2027-01-01")).toByteArray()))
            .session(admin).with(csrf())).andExpect(status().isCreated)
        val allocations = "/api/v1/jobs/${ready.jobId}/resources/allocations"
        val allocated = ok(call(ops,"POST",allocations,mapOf("kind" to "ASSET","resourceId" to asset["id"].asString(),
            "startAt" to "2026-11-02T00:00:00Z","endAt" to "2026-11-03T00:00:00Z")))
        val draft = ok(call(technical,"POST",base(ready.jobId)))
        val url = "${base(ready.jobId)}/${draft["id"].asString()}"
        val saved = ok(call(technical,"PUT",url,content(draft,ready.diagramId),draft["version"].asLong()))
        val submitted = ok(call(technical,"POST","$url/submit",version=saved["version"].asLong()))
        assertEquals("LINKED",submitted["sources"]["equipment"].asString())
        val frozen = submitted["snapshot"]["allocatedResources"]
        assertEquals(1,frozen.size())
        assertEquals(asset["id"].asString(),frozen[0]["resourceId"].asString())
        assertEquals("8in x 12m",frozen[0]["dimensions"].asString())
        ok(call(ops,"POST","$allocations/${allocated["id"].asString()}/cancel",
            mapOf("reason" to "Synthetic return"),allocated["version"].asLong()))
        val reread = ok(call(technical,"GET",url))
        assertEquals("LINKED",reread["sources"]["equipment"].asString())
        assertEquals(frozen.toString(),reread["snapshot"]["allocatedResources"].toString())
    }

}
