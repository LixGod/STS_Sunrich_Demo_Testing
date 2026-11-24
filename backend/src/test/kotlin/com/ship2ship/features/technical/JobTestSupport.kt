package com.ship2ship.features.technical

import org.springframework.mock.web.MockHttpSession
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Creates real STS jobs through the commercial flow (enquiry → quotation → approval → acceptance) for job-scoped tests. */
abstract class JobTestSupport : TechnicalTestSupport() {

    /** A new job on two new vessels in the Mumbai branch; returns the job detail (with job vessel ids). */
    protected fun newJob(mother: String = newVessel(), daughter: String = newVessel(), branch: String = BRANCH_MUM): JsonNode {
        val commercial = login("commercial")
        val enquiry = ok(call(commercial, "POST", "/api/v1/enquiries", mapOf(
            "branchId" to branch, "receivedAt" to "2026-10-16T05:00:00Z",
            "clientId" to CLIENT_CHARTERER,
            "operationType" to "LPG",
            "motherVessel" to mapOf("vesselId" to mother), "daughterVessel" to mapOf("vesselId" to daughter),
            "cargo" to listOf(mapOf("cargoGradeId" to GRADE_PROPANE, "quantity" to "34000", "unitId" to UNIT_MT)),
        )))["id"].asString()
        val estimate = ok(call(commercial, "POST", "/api/v1/enquiries/$enquiry/estimates", mapOf<String, Any>()))
        val saved = ok(call(commercial, "PUT", "/api/v1/estimates/${estimate["id"].asString()}", mapOf("currency" to "USD", "lines" to listOf(
            mapOf("kind" to "REVENUE", "costHeadId" to COST_HEAD_STS_SERVICE, "quantity" to "1", "unitPrice" to "10000"),
        )), estimate["version"].asLong()))
        var version = ok(call(commercial, "POST", "/api/v1/enquiries/$enquiry/quotation/versions", mapOf(
            "estimateId" to saved["id"].asString(), "validUntil" to LocalDate.now().plusDays(30).toString(), "tariffId" to TARIFF_DEMO,
        )))["versions"][0]
        val id = version["id"].asString()
        version = ok(call(commercial, "POST", "/api/v1/quotation-versions/$id/submit", version = version["version"].asLong()))["versions"][0]
        version = ok(call(login("approver"), "POST", "/api/v1/quotation-versions/$id/decision", mapOf("approve" to true),
            version["version"].asLong()))["versions"][0]
        version = ok(call(commercial, "POST", "/api/v1/quotation-versions/$id/issue",
            mapOf("issuedTo" to "chartering@demo.invalid", "channel" to "EMAIL"), version["version"].asLong()))["versions"][0]
        val accepted = ok(call(commercial, "POST", "/api/v1/quotation-versions/$id/acceptance",
            mapOf("acceptedAt" to Instant.now().toString(), "acceptedByName" to "Demo Charterer Representative")))
        return ok(call(commercial, "GET", "/api/v1/jobs/${accepted["job"]["id"].asString()}"))
    }

    protected fun jobVessel(job: JsonNode, role: String): String =
        (0 until job["vessels"].size()).map { job["vessels"][it] }.first { it["role"].asString() == role }["id"].asString()

    protected fun jobUpload(
        session: MockHttpSession, jobId: String, jobVesselId: String?, itemId: String, documentTypeId: String = DOC_TYPE_Q88,
        bytes: ByteArray = PDF, mime: String = "application/pdf",
    ): ResultActions = mvc.perform(
        multipart("/api/v1/jobs/$jobId/technical/documents")
            .file(MockMultipartFile("file", if (mime == "image/png") "ga.png" else "evidence.pdf", mime, bytes))
            .apply { jobVesselId?.let { param("jobVesselId", it) } }
            .param("itemId", itemId).param("documentTypeId", documentTypeId)
            .session(session).with(csrf()),
    )

    /**
     * Publishes five checklist templates and a request template that uses them, so jobs can open a document request.
     * Codes are unique per call, so tests do not depend on each other's templates.
     */
    protected fun publishRequestTemplate(): List<JsonNode> {
        val admin = login("sts.admin")
        val suffix = UUID.randomUUID().toString().take(6).uppercase()
        val checklists = (1..5).map { n ->
            val created = ok(call(admin, "POST", "/api/v1/technical/checklist-templates", mapOf(
                "code" to "CHK$n-$suffix", "name" to "Synthetic checklist $n", "appliesTo" to "EACH_VESSEL",
                "items" to listOf(
                    mapOf("code" to "Q1", "label" to "Synthetic question", "answerType" to "YES_NO", "required" to true),
                    mapOf("code" to "Q2", "label" to "Synthetic remark", "answerType" to "TEXT", "required" to false),
                ),
            )))
            ok(call(admin, "POST", "/api/v1/technical/checklist-templates/${created["id"].asString()}/publish", version = created["version"].asLong()))
        }
        val documents = listOf("Q88", "MOORING", "PARTICULARS", "GA", "CREW", "MANIFOLD", "DRILL").map {
            mapOf("code" to it, "label" to "Synthetic $it", "kind" to "DOCUMENT", "appliesTo" to "EACH_VESSEL",
                "documentTypeId" to if (it == "GA") DOC_TYPE_GA else DOC_TYPE_Q88)
        }
        val others = listOf(
            mapOf("code" to "REDUCER", "label" to "Reducer availability", "kind" to "DECLARATION", "appliesTo" to "EACH_VESSEL"),
            mapOf("code" to "VOYAGE", "label" to "Voyage conditions", "kind" to "VOYAGE_DATA", "appliesTo" to "EACH_VESSEL"),
        )
        val checklistItems = checklists.map {
            mapOf("code" to it["code"].asString(), "label" to it["name"].asString(), "kind" to "CHECKLIST", "appliesTo" to "EACH_VESSEL")
        }
        val template = ok(call(admin, "POST", "/api/v1/technical/request-templates", mapOf(
            "name" to "Synthetic request $suffix", "emailSubject" to "Document request {JOB_NUMBER}",
            "emailBody" to "For {MOTHER_VESSEL} and {DAUGHTER_VESSEL}:\n{REQUEST_ITEMS}",
            "items" to documents + others + checklistItems,
        )))
        ok(call(admin, "POST", "/api/v1/technical/request-templates/${template["id"].asString()}/publish",
            mapOf("ownerConfirmation" to "Synthetic test confirmation"), template["version"].asLong()))
        return checklists
    }

    /**
     * Publishes a new JPO annex version with synthetic wording in every section: edited by STS Admin, published by
     * Super Admin (a second person). Returns the published version.
     */
    protected fun publishAnnex(marker: String = "Synthetic annex wording"): JsonNode {
        val admin = login("sts.admin")
        val list = ok(call(admin, "GET", "/api/v1/technical/annex-templates"))
        val draft = (0 until list.size()).map { list[it] }.firstOrNull { it["status"].asString() == "DRAFT" }
            ?: ok(call(admin, "POST", "/api/v1/technical/annex-templates"))
        val sections = (0 until draft["sections"].size()).map { draft["sections"][it] }.map {
            mapOf("code" to it["code"].asString(), "title" to it["title"].asString(), "appliesWhen" to it["appliesWhen"].asString(),
                "body" to "$marker for ${it["code"].asString()}.\nSecond paragraph.")
        }
        val saved = ok(call(admin, "PUT", "/api/v1/technical/annex-templates/${draft["id"].asString()}",
            mapOf("name" to "JPO annex (synthetic)", "sections" to sections), draft["version"].asLong()))
        return ok(call(login("admin"), "POST", "/api/v1/technical/annex-templates/${saved["id"].asString()}/publish",
            mapOf("ownerConfirmation" to "Synthetic test confirmation"), saved["version"].asLong()))
    }

    protected companion object {
        const val COST_HEAD_STS_SERVICE = "01920000-0000-7000-9000-000000000635"
        const val TARIFF_DEMO = "01920000-0000-7000-9000-000000000731"
    }
}
