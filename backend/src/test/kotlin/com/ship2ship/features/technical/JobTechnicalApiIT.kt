package com.ship2ship.features.technical

import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 3b: job-scoped document request, evidence uploads, checklist responses, voyage-condition values with
 * second-person verification, and the compatibility review (warnings only, similar-LOA rule off by default).
 */
class JobTechnicalApiIT : JobTestSupport() {

    private fun items(request: JsonNode) = (0 until request["items"].size()).map { request["items"][it] }

    private fun item(request: JsonNode, code: String, jobVesselId: String?) =
        items(request).first { it["code"].asString() == code && (it["jobVesselId"].takeIf { n -> !n.isNull }?.asString()) == jobVesselId }

    private fun request(session: MockHttpSession, jobId: String) = ok(call(session, "GET", "/api/v1/jobs/$jobId/technical/request"))

    @Test
    fun `vessel GA plans are linked to the job request without a second upload`() {
        publishRequestTemplate()
        val motherVessel = newVessel()
        val daughterVessel = newVessel()
        val admin = login("admin")
        val motherGa = ok(upload(admin, motherVessel, name = "mother-ga.pdf", type = DOC_TYPE_GA))
        val job = newJob(motherVessel, daughterVessel)
        val jobId = job["id"].asString()
        val mother = jobVessel(job, "MOTHER")
        val daughter = jobVessel(job, "DAUGHTER")
        val marine = login("technical")
        val first = ok(call(marine, "POST", "/api/v1/jobs/$jobId/technical/request"))
        assertEquals("RECEIVED", item(first, "GA", mother)["status"].asString())
        assertEquals(motherGa["id"].asString(), item(first, "GA", mother)["documentVersionIds"][0].asString())
        assertEquals("PENDING", item(first, "GA", daughter)["status"].asString())

        val daughterGa = ok(upload(admin, daughterVessel, name = "daughter-ga.pdf", type = DOC_TYPE_GA))
        val refreshed = ok(call(marine, "POST", "/api/v1/jobs/$jobId/technical/request"))
        assertEquals("RECEIVED", item(refreshed, "GA", daughter)["status"].asString())
        assertEquals(daughterGa["id"].asString(), item(refreshed, "GA", daughter)["documentVersionIds"][0].asString())
        assertEquals(1, item(refreshed, "GA", mother)["documentVersionIds"].size())
        val motherItem = item(refreshed, "GA", mother)
        ok(call(marine, "PATCH", "/api/v1/jobs/$jobId/technical/request/items/${motherItem["id"].asString()}",
            mapOf("status" to "PENDING"), motherItem["version"].asLong()))
        val reopened = ok(call(marine, "POST", "/api/v1/jobs/$jobId/technical/request"))
        assertEquals("PENDING", item(reopened, "GA", mother)["status"].asString())
        assertEquals(1, item(reopened, "GA", mother)["documentVersionIds"].size())
    }

    @Test
    fun `ten pre-operation request items can be published without post-operation checklists`() {
        val admin = login("admin")
        val defaults = ok(call(admin, "GET", "/api/v1/technical/request-templates"))
        val stepFour = (0 until defaults.size()).map { defaults[it] }
            .first { it["id"].asString() == "01920000-0000-7000-8000-000000001001" }
        assertEquals(10, stepFour["items"].size())
        assertFalse((0 until stepFour["items"].size()).any { stepFour["items"][it]["kind"].asString() == "CHECKLIST" })
        assertTrue(stepFour["emailBody"].asString().contains("all five signed checklists after completion"))

        val documents = listOf("Q88", "MOORING", "PARTICULARS", "GA", "CREW", "MANIFOLD", "DRILL").map {
            mapOf("code" to it, "label" to it, "kind" to "DOCUMENT", "appliesTo" to "EACH_VESSEL", "documentTypeId" to DOC_TYPE_Q88)
        }
        val otherItems = listOf(
            mapOf("code" to "REDUCER", "label" to "Reducer availability", "kind" to "DECLARATION", "appliesTo" to "EACH_VESSEL"),
            mapOf("code" to "WHATSAPP", "label" to "POAC Whatsapp Contact Number", "kind" to "CONTACT", "appliesTo" to "EACH_VESSEL"),
            mapOf("code" to "VOYAGE", "label" to "Arrival/departure voyage conditions", "kind" to "VOYAGE_DATA", "appliesTo" to "EACH_VESSEL"),
        )
        val template = ok(call(admin, "POST", "/api/v1/technical/request-templates", mapOf(
            "name" to "Ten item request", "emailSubject" to "Documents {JOB_NUMBER}", "emailBody" to "{REQUEST_ITEMS}",
            "items" to documents + otherItems,
        )))
        ok(call(admin, "POST", "/api/v1/technical/request-templates/${template["id"].asString()}/publish",
            mapOf("ownerConfirmation" to "Confirmed against the supplied STS flow"), template["version"].asLong()))

        val job = newJob()
        val created = ok(call(login("technical"), "POST", "/api/v1/jobs/${job["id"].asString()}/technical/request"))
        assertEquals(20, created["items"].size())
        assertFalse((0 until created["items"].size()).any { created["items"][it]["kind"].asString() == "CHECKLIST" })
    }

    @Test
    fun `request templates publish only when complete and confirmed, and a job request lists items per vessel`() {
        val admin = login("admin")
        // A template with a pending checklist name, or without the owner confirmation, is not published.
        val pending = ok(call(admin, "POST", "/api/v1/technical/request-templates", mapOf(
            "name" to "Pending", "emailSubject" to "S", "emailBody" to "B",
            "items" to listOf(mapOf("code" to "CL", "label" to "Signed checklist - pending owner name", "kind" to "CHECKLIST", "appliesTo" to "EACH_VESSEL")),
        )))
        call(admin, "POST", "/api/v1/technical/request-templates/${pending["id"].asString()}/publish",
            mapOf("ownerConfirmation" to "x"), pending["version"].asLong())
            .andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("TEMPLATE_PENDING"))
        call(admin, "POST", "/api/v1/technical/request-templates", mapOf(
            "name" to "Bad", "emailSubject" to "S", "emailBody" to "B",
            "items" to listOf(mapOf("code" to "DOC", "label" to "Document without type", "kind" to "DOCUMENT", "appliesTo" to "EACH_VESSEL")),
        )).andExpect(status().isBadRequest)
        // Template administration needs MASTER.TECHNICAL.WRITE.
        call(login("commercial"), "POST", "/api/v1/technical/request-templates", mapOf(
            "name" to "X", "emailSubject" to "S", "emailBody" to "B", "items" to emptyList<Any>(),
        )).andExpect(status().isForbidden)

        publishRequestTemplate()
        val job = newJob()
        val jobId = job["id"].asString()
        val technical = login("technical")
        val created = ok(call(technical, "POST", "/api/v1/jobs/$jobId/technical/request"))
        // 9 items + 5 checklists, each for both vessels; creating again returns the same request.
        assertEquals(28, created["items"].size())
        assertEquals(created["id"].asString(), ok(call(technical, "POST", "/api/v1/jobs/$jobId/technical/request"))["id"].asString())
        assertTrue(items(created).all { it["status"].asString() == "PENDING" })

        val email = ok(call(technical, "GET", "/api/v1/jobs/$jobId/technical/request/email-preview"))
        assertTrue(email["subject"].asString().contains(job["jobNumber"].asString()))
        assertTrue(email["body"].asString().contains("Demo Anchorage A1"))
        // No radius is configured for the location: shown as pending, never guessed.
        assertTrue((0 until email["unresolved"].size()).map { email["unresolved"][it].asString() }.contains("RADIUS_NM"))
    }

    @Test
    fun `evidence uploads mark items received, reviews need received evidence, returns and removals need reasons`() {
        publishRequestTemplate()
        val job = newJob()
        val jobId = job["id"].asString()
        val mother = jobVessel(job, "MOTHER")
        val technical = login("technical")
        val req = ok(call(technical, "POST", "/api/v1/jobs/$jobId/technical/request"))
        val q88 = item(req, "Q88", mother)

        // Reviewing before evidence is refused.
        call(technical, "PATCH", "/api/v1/jobs/$jobId/technical/request/items/${q88["id"].asString()}",
            mapOf("status" to "REVIEWED"), q88["version"].asLong()).andExpect(status().isUnprocessableContent)
        // The wrong document type for the item, or another job's vessel, is refused.
        jobUpload(technical, jobId, mother, q88["id"].asString(), DOC_TYPE_GA).andExpect(status().isBadRequest)
        jobUpload(technical, jobId, jobVessel(newJob(), "MOTHER"), q88["id"].asString()).andExpect(status().isBadRequest)

        val uploaded = read(jobUpload(technical, jobId, mother, q88["id"].asString()).andExpect(status().isCreated).andReturn().response.contentAsString)
        assertEquals("NOT_SCANNED", uploaded["scanState"].asString())
        val received = item(request(technical, jobId), "Q88", mother)
        assertEquals("RECEIVED", received["status"].asString())
        assertEquals(uploaded["id"].asString(), received["documentVersionIds"][0].asString())

        val reviewed = item(ok(call(technical, "PATCH", "/api/v1/jobs/$jobId/technical/request/items/${received["id"].asString()}",
            mapOf("status" to "REVIEWED"), received["version"].asLong())), "Q88", mother)
        assertEquals("REVIEWED", reviewed["status"].asString())

        val reducer = item(request(technical, jobId), "REDUCER", mother)
        call(technical, "PATCH", "/api/v1/jobs/$jobId/technical/request/items/${reducer["id"].asString()}",
            mapOf("status" to "RECEIVED"), reducer["version"].asLong()).andExpect(status().isBadRequest) // declaration needs a response
        call(technical, "PATCH", "/api/v1/jobs/$jobId/technical/request/items/${reducer["id"].asString()}",
            mapOf("status" to "REMOVED"), reducer["version"].asLong()).andExpect(jsonPath("$.code").value("REASON_REQUIRED"))
        // Read-only roles cannot change the request.
        call(login("commercial"), "PATCH", "/api/v1/jobs/$jobId/technical/request/items/${reducer["id"].asString()}",
            mapOf("status" to "RECEIVED", "responseText" to "Yes"), reducer["version"].asLong()).andExpect(status().isForbidden)
        call(login("finance"), "GET", "/api/v1/jobs/$jobId/technical/request").andExpect(status().isForbidden)
    }

    @Test
    fun `checklist responses validate answers and signed documents, and recording one marks its request item received`() {
        val checklists = publishRequestTemplate()
        val job = newJob()
        val jobId = job["id"].asString()
        val mother = jobVessel(job, "MOTHER")
        val daughter = jobVessel(job, "DAUGHTER")
        val technical = login("technical")
        val req = ok(call(technical, "POST", "/api/v1/jobs/$jobId/technical/request"))
        val template = checklists[0]

        call(technical, "POST", "/api/v1/jobs/$jobId/technical/checklists", mapOf("templateId" to template["id"].asString()))
            .andExpect(status().isBadRequest) // a per-vessel checklist needs a vessel
        val response = ok(call(technical, "POST", "/api/v1/jobs/$jobId/technical/checklists",
            mapOf("templateId" to template["id"].asString(), "jobVesselId" to mother)))
        val url = "/api/v1/jobs/$jobId/technical/checklists/${response["id"].asString()}"

        call(technical, "PUT", url, mapOf("answers" to listOf(mapOf("itemCode" to "Q1", "answer" to "maybe")), "status" to "DRAFT"),
            response["version"].asLong()).andExpect(status().isBadRequest)
        call(technical, "PUT", url, mapOf("answers" to listOf(mapOf("itemCode" to "Q2", "answer" to "note")), "status" to "RECORDED"),
            response["version"].asLong()).andExpect(jsonPath("$.code").value("CHECKLIST_INCOMPLETE"))

        // The signed copy must belong to the same job vessel.
        val daughterItem = item(req, "Q88", daughter)
        val foreign = read(jobUpload(technical, jobId, daughter, daughterItem["id"].asString()).andReturn().response.contentAsString)
        call(technical, "PUT", url, mapOf("answers" to listOf(mapOf("itemCode" to "Q1", "answer" to "YES")), "status" to "RECORDED",
            "signedDocumentId" to foreign["id"].asString()), response["version"].asLong()).andExpect(jsonPath("$.code").value("WRONG_JOB_VESSEL"))

        val motherItem = item(req, "Q88", mother)
        val signed = read(jobUpload(technical, jobId, mother, motherItem["id"].asString()).andReturn().response.contentAsString)
        val recorded = ok(call(technical, "PUT", url, mapOf("answers" to listOf(mapOf("itemCode" to "Q1", "answer" to "YES")),
            "status" to "RECORDED", "signedDocumentId" to signed["id"].asString()), response["version"].asLong()))
        assertEquals("RECORDED", recorded["status"].asString())
        assertEquals("RECEIVED", item(request(technical, jobId), template["code"].asString(), mother)["status"].asString())
        assertEquals("PENDING", item(request(technical, jobId), template["code"].asString(), daughter)["status"].asString())
    }

    @Test
    fun `voyage values cite job documents, are verified by someone else, and newer verified versions supersede older ones`() {
        publishRequestTemplate()
        val job = newJob()
        val jobId = job["id"].asString()
        val mother = jobVessel(job, "MOTHER")
        val technical = login("technical")
        val marine = login("marine")
        val req = ok(call(technical, "POST", "/api/v1/jobs/$jobId/technical/request"))
        val doc = read(jobUpload(technical, jobId, mother, item(req, "Q88", mother)["id"].asString()).andReturn().response.contentAsString)["id"].asString()
        val otherVesselDoc = read(jobUpload(technical, jobId, jobVessel(job, "DAUGHTER"),
            item(req, "Q88", jobVessel(job, "DAUGHTER"))["id"].asString()).andReturn().response.contentAsString)["id"].asString()

        fun values(source: String?) = listOf(
            mapOf("fieldCode" to "ARRIVAL_DRAFT_FWD", "value" to "9.85", "sourceDocumentId" to source, "sourcePage" to 2),
            mapOf("fieldCode" to "ARRIVAL_FREEBOARD_FWD", "value" to "4.2", "sourceDocumentId" to source, "sourcePage" to 2),
        )

        fun submitted(): JsonNode {
            val draft = ok(call(technical, "POST", "/api/v1/jobs/$jobId/technical/vessels/$mother/voyage"))
            val base = "/api/v1/jobs/$jobId/technical/voyage/${draft["id"].asString()}"
            call(technical, "PUT", base, mapOf("values" to values(otherVesselDoc)), draft["version"].asLong())
                .andExpect(jsonPath("$.code").value("WRONG_JOB_VESSEL"))
            val noSource = ok(call(technical, "PUT", base, mapOf("values" to values(null)), draft["version"].asLong()))
            call(technical, "POST", "$base/submit", version = noSource["version"].asLong()).andExpect(jsonPath("$.code").value("SOURCE_REQUIRED"))
            val sourced = ok(call(technical, "PUT", base, mapOf("values" to values(doc)), noSource["version"].asLong()))
            return ok(call(technical, "POST", "$base/submit", version = sourced["version"].asLong()))
        }

        val first = submitted()
        assertEquals("SUBMITTED", first["status"].asString())
        assertEquals("RECEIVED", item(request(technical, jobId), "VOYAGE", mother)["status"].asString())
        call(technical, "POST", "/api/v1/jobs/$jobId/technical/vessels/$mother/voyage").andExpect(jsonPath("$.code").value("VOYAGE_VERSION_OPEN"))

        val firstUrl = "/api/v1/jobs/$jobId/technical/voyage/${first["id"].asString()}"
        val all = listOf("ARRIVAL_DRAFT_FWD", "ARRIVAL_FREEBOARD_FWD")
        call(technical, "POST", "$firstUrl/verify", mapOf("confirmedFieldCodes" to all), first["version"].asLong())
            .andExpect(jsonPath("$.code").value("SELF_VERIFICATION_FORBIDDEN"))
        call(marine, "POST", "$firstUrl/verify", mapOf("confirmedFieldCodes" to all.take(1)), first["version"].asLong()).andExpect(status().isBadRequest)
        ok(call(marine, "POST", "$firstUrl/verify", mapOf("confirmedFieldCodes" to all), first["version"].asLong()))

        val second = submitted()
        val returned = ok(call(marine, "POST", "/api/v1/jobs/$jobId/technical/voyage/${second["id"].asString()}/return",
            mapOf("reason" to "Recheck page 2"), second["version"].asLong()))
        assertEquals("DRAFT", returned["status"].asString())
        val again = ok(call(technical, "POST", "/api/v1/jobs/$jobId/technical/voyage/${second["id"].asString()}/submit", version = returned["version"].asLong()))
        ok(call(marine, "POST", "/api/v1/jobs/$jobId/technical/voyage/${second["id"].asString()}/verify",
            mapOf("confirmedFieldCodes" to all), again["version"].asLong()))
        val overview = ok(call(technical, "GET", "/api/v1/jobs/$jobId/technical/vessels/$mother/voyage"))
        assertEquals(listOf("VERIFIED", "SUPERSEDED"), (0 until overview["versions"].size()).map { overview["versions"][it]["status"].asString() })

        val auto = ok(call(technical, "POST", "/api/v1/jobs/$jobId/technical/vessels/${jobVessel(job, "DAUGHTER")}/voyage/from-document"))
        assertEquals("DRAFT", auto["status"].asString())
        assertEquals(0, auto["values"].size()) // the placeholder PDF has no voyage figures to extract
    }

    @Test
    fun `the compatibility review warns about missing verified data, and similar LOA only when the rule is configured`() {
        val technical = login("technical")
        val marine = login("marine")
        val motherVessel = newVessel()
        val daughterVessel = newVessel()
        val job = newJob(motherVessel, daughterVessel)
        val jobId = job["id"].asString()

        val before = ok(call(technical, "GET", "/api/v1/jobs/$jobId/technical/review"))
        val codes = (0 until before["warnings"].size()).map { before["warnings"][it]["code"].asString() }
        assertEquals(2, codes.count { it == "MISSING_VERIFIED_PARTICULARS" })
        assertEquals(2, codes.count { it == "MISSING_VERIFIED_VOYAGE" })

        // Verified fixed particulars for both vessels: LOA 183.06 m and 183 m (0.03% apart).
        fun verifyParticulars(vessel: String, loa: String) {
            val doc = read(upload(technical, vessel).andReturn().response.contentAsString)["id"].asString()
            val draft = ok(call(technical, "POST", "/api/v1/technical/vessels/$vessel/particulars"))
            val saved = ok(call(technical, "PUT", "/api/v1/technical/particulars/${draft["id"].asString()}",
                mapOf("values" to listOf(mapOf("fieldCode" to "LOA", "value" to loa, "sourceDocumentId" to doc, "sourcePage" to 1))),
                draft["version"].asLong()))
            val submitted = ok(call(technical, "POST", "/api/v1/technical/particulars/${saved["id"].asString()}/submit", version = saved["version"].asLong()))
            ok(call(marine, "POST", "/api/v1/technical/particulars/${submitted["id"].asString()}/verify",
                mapOf("confirmedFieldCodes" to listOf("LOA")), submitted["version"].asLong()))
        }
        verifyParticulars(motherVessel, "183.06")
        verifyParticulars(daughterVessel, "183")

        val disabled = ok(call(technical, "GET", "/api/v1/jobs/$jobId/technical/review"))
        val disabledCodes = (0 until disabled["warnings"].size()).map { disabled["warnings"][it]["code"].asString() }
        assertTrue("MISSING_VERIFIED_PARTICULARS" !in disabledCodes)
        assertTrue("SIMILAR_LOA" !in disabledCodes) // the rule ships disabled pending owner confirmation

        val admin = login("admin")
        val rule = ok(call(admin, "GET", "/api/v1/technical/compatibility-rules/SIMILAR_LOA"))
        call(admin, "PUT", "/api/v1/technical/compatibility-rules/SIMILAR_LOA", mapOf("enabled" to true, "thresholdPercent" to "10"),
            rule["version"].asLong()).andExpect(status().isBadRequest) // no confirmed comparison method
        val enabled = ok(call(admin, "PUT", "/api/v1/technical/compatibility-rules/SIMILAR_LOA",
            mapOf("enabled" to true, "thresholdPercent" to "10", "comparisonMethod" to "LONGER"), rule["version"].asLong()))
        try {
            val review = ok(call(technical, "GET", "/api/v1/jobs/$jobId/technical/review"))
            assertTrue((0 until review["warnings"].size()).any { review["warnings"][it]["code"].asString() == "SIMILAR_LOA" })
        } finally {
            ok(call(admin, "PUT", "/api/v1/technical/compatibility-rules/SIMILAR_LOA",
                mapOf("enabled" to false, "thresholdPercent" to "10", "comparisonMethod" to null), enabled["version"].asLong()))
        }
    }
}
