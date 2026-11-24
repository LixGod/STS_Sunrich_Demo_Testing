package com.ship2ship.features.resources

import com.ship2ship.features.technical.JobTestSupport
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourcesApiIT : JobTestSupport() {
    private val root = "/api/v1/resources"

    private fun fixture(): Triple<String, String, String> {
        val admin = login("admin")
        val suffix = UUID.randomUUID().toString().take(8).uppercase()
        val category = "TEST_HOSE_$suffix"
        val certType = "TEST_CERT_$suffix"
        ok(call(admin, "POST", "$root/categories", mapOf("code" to category, "label" to "Synthetic hose $suffix")))
        ok(call(admin, "POST", "$root/certificate-types", mapOf("code" to certType, "label" to "Synthetic inspection $suffix")))
        val model = ok(call(admin, "POST", "$root/models", mapOf("categoryCode" to category, "code" to "MODEL_$suffix", "name" to "Synthetic hose model")))
        ok(call(admin, "POST", "$root/requirements", mapOf("resourceKind" to "ASSET",
            "scopeCode" to category, "certificateTypeCode" to certType)))
        val policy = ok(call(admin, "GET", "$root/policy"))
        ok(call(admin, "PUT", "$root/policy", mapOf("mode" to "BLOCK", "warningDays" to 10,
            "requirementsConfigured" to true), policy["version"].asLong()))
        return Triple(category, certType, model["id"].asString())
    }

    private fun asset(modelId: String, identifier: String): JsonNode =
        ok(call(login("admin"), "POST", "$root/assets", mapOf(
            "modelId" to modelId, "assetIdentifier" to identifier, "status" to "AVAILABLE")))

    private fun certificate(assetId: String, type: String, issue: LocalDate, expiry: LocalDate?) = mvc.perform(
        multipart("$root/assets/$assetId/certificates")
            .file(MockMultipartFile("certificate", "", "application/json", body(mapOf(
                "typeCode" to type, "certificateNumber" to "SYN-001", "issuer" to "Synthetic inspector",
                "issueDate" to issue.toString(), "expiryDate" to expiry?.toString())).toByteArray()))
            .session(login("admin")).with(csrf()),
    )

    private fun allocation(assetId: String, from: Instant, to: Instant, reason: String? = null) =
        mapOf("kind" to "ASSET", "resourceId" to assetId, "startAt" to from.toString(),
            "endAt" to to.toString(), "exceptionReason" to reason)

    @Test
    fun `master lists page search sort and validate edits`() {
        val (_, _, modelId) = fixture()
        val admin=login("admin")
        val identifier="PAGE-${UUID.randomUUID()}"
        val created=asset(modelId,identifier)
        val page=ok(call(admin,"GET","$root/assets?q=$identifier&page=0&size=1&sort=assetIdentifier,desc"))
        assertEquals(1,page["items"].size())
        assertEquals(identifier,page["items"][0]["assetIdentifier"].asString())
        assertEquals(1,page["size"].asInt())
        val capped=ok(call(admin,"GET","$root/models?size=1000"))
        assertEquals(100,capped["size"].asInt())
        for (path in listOf("models","personnel","vendors","craft")) {
            val rows=ok(call(admin,"GET","$root/$path?page=0&size=1"))
            assertEquals(1,rows["size"].asInt())
            assertTrue(rows["items"].isArray)
        }
        call(admin,"GET","$root/assets?sort=unexpected,asc")
            .andExpect(status().isBadRequest)
        val updated=ok(call(admin,"PUT","$root/assets/${created["id"].asString()}",mapOf(
            "branchId" to null,"modelId" to modelId,
            "assetIdentifier" to identifier,"status" to "MAINTENANCE"),created["version"].asLong()))
        assertEquals("MAINTENANCE",updated["status"].asString())
    }

    @Test
    fun `certificate and inspection corrections keep audited history and require reason after allocation`() {
        val (_,type,model)=fixture()
        val admin=login("admin")
        val resource=asset(model,"EDIT-${UUID.randomUUID()}")["id"].asString()
        certificate(resource,type,LocalDate.parse("2027-01-01"),LocalDate.parse("2029-01-01"))
            .andExpect(status().isCreated)
        val cert=ok(call(admin,"GET","$root/assets/$resource/certificates"))[0]
        val inspection=ok(call(admin,"POST","$root/assets/$resource/inspections",mapOf(
            "inspectedAt" to "2028-01-01","result" to "PASS","source" to "Synthetic inspection")))
        val job=newJob()["id"].asString()
        ok(call(login("ops.mumbai"),"POST","/api/v1/jobs/$job/resources/allocations",allocation(resource,
            Instant.parse("2028-02-01T00:00:00Z"),Instant.parse("2028-02-02T00:00:00Z"))))
        val certUrl="$root/assets/$resource/certificates/${cert["id"].asString()}"
        val certEdit=mapOf("typeCode" to type,"certificateNumber" to "SYN-002","issuer" to "Synthetic inspector",
            "issueDate" to "2027-01-01","expiryDate" to "2029-01-01")
        call(admin,"PUT",certUrl,certEdit,cert["version"].asLong()).andExpect(status().isBadRequest)
        val changed=ok(call(admin,"PUT",certUrl,certEdit + ("reason" to "Corrected transcription"),cert["version"].asLong()))
        assertEquals("SYN-002",changed["certificateNumber"].asString())
        mvc.perform(multipart(HttpMethod.PUT,certUrl)
            .file(MockMultipartFile("certificate","","application/json",body(certEdit +
                ("reason" to "Replaced synthetic evidence")).toByteArray()))
            .file(MockMultipartFile("file","synthetic.pdf","application/pdf",PDF))
            .header("If-Match","\"${changed["version"].asLong()}\"")
            .session(admin).with(csrf())).andExpect(status().isOk)
        val certHistory=ok(call(admin,"GET","$certUrl/history"))
        assertTrue(certHistory.size()>=3)
        assertTrue(certHistory.toString().contains("SYN-001"))
        val inspectionUrl="$root/assets/$resource/inspections/${inspection["id"].asString()}"
        call(admin,"PUT",inspectionUrl,mapOf("inspectedAt" to "2028-01-01","result" to "PASS",
            "source" to "Corrected source"),inspection["version"].asLong()).andExpect(status().isBadRequest)
        val corrected=ok(call(admin,"PUT",inspectionUrl,mapOf("inspectedAt" to "2028-01-01","result" to "PASS",
            "source" to "Corrected source","reason" to "Source typo"),inspection["version"].asLong()))
        assertEquals("Corrected source",corrected["source"].asString())
        assertTrue(ok(call(admin,"GET","$inspectionUrl/history")).size()>=2)
    }

    @Test
    fun `warn policy permits missing certificates and records warning`() {
        val (_, _, model)=fixture()
        val admin=login("admin")
        val before=ok(call(admin,"GET","$root/policy"))
        ok(call(admin,"PUT","$root/policy",mapOf("mode" to "WARN","warningDays" to 10,
            "requirementsConfigured" to true),before["version"].asLong()))
        val resource=asset(model,"WARN-${UUID.randomUUID()}")["id"].asString()
        val job=newJob()["id"].asString()
        val assigned=ok(call(login("ops.mumbai"),"POST","/api/v1/jobs/$job/resources/allocations",allocation(resource,
            Instant.parse("2028-02-01T00:00:00Z"),Instant.parse("2028-02-02T00:00:00Z"))))
        assertEquals("ACTIVE",assigned["status"].asString())
        assertTrue(assigned["eligibilityMessages"].size()>0)
    }

    @Test
    fun `asset identifiers and certificate dates are validated`() {
        val (_, type, model) = fixture()
        val identifier = "SYN-${UUID.randomUUID()}"
        val first = asset(model, identifier)
        call(login("admin"), "POST", "$root/assets", mapOf("modelId" to model, "assetIdentifier" to identifier)).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("DUPLICATE_ASSET_ID"))
        certificate(first["id"].asString(), type, LocalDate.parse("2028-01-02"), LocalDate.parse("2028-01-01"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_CERTIFICATE_DATES"))
        certificate(first["id"].asString(), type, LocalDate.parse("2028-01-02"), LocalDate.parse("2029-01-01"))
            .andExpect(status().isCreated)
        val updated = ok(call(login("admin"), "GET", "$root/assets/${first["id"].asString()}"))
        assertEquals(first["version"].asLong() + 1, updated["version"].asLong())
    }

    @Test
    fun `allocation checks certificates overlap exception approval and job scope`() {
        val (_, type, model) = fixture()
        val resource = asset(model, "SYN-${UUID.randomUUID()}")["id"].asString()
        val firstJob = newJob()["id"].asString()
        val otherJob = newJob()["id"].asString()
        val from = Instant.parse("2028-02-01T00:00:00Z")
        val to = Instant.parse("2028-02-02T00:00:00Z")
        val url = "/api/v1/jobs/$firstJob/resources/allocations"
        val choices = ok(call(login("ops.mumbai"), "GET", "$url/choices"))
        assertTrue((0 until choices["assets"].size()).any { choices["assets"][it]["id"].asString() == resource })
        call(login("ops.mumbai"), "POST", url, allocation(resource, from, to))
            .andExpect(jsonPath("$.code").value("CERTIFICATE_REQUIRED"))
        certificate(resource, type, LocalDate.parse("2027-01-01"), LocalDate.parse("2028-01-31"))
            .andExpect(status().isCreated)
        call(login("ops.mumbai"), "POST", url, allocation(resource, from, to))
            .andExpect(jsonPath("$.code").value("CERTIFICATE_REQUIRED"))
        certificate(resource, type, LocalDate.parse("2027-01-01"), LocalDate.parse("2028-02-05"))
            .andExpect(status().isCreated)
        val active = ok(call(login("ops.mumbai"), "POST", url, allocation(resource, from, to)))
        assertEquals("ACTIVE", active["status"].asString())
        assertTrue(active["eligibilityMessages"].size() > 0) // warning horizon
        val secondUrl = "/api/v1/jobs/$otherJob/resources/allocations"
        call(login("ops.mumbai"), "POST", secondUrl, allocation(resource, from.plusSeconds(3600), to))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ALLOCATION_COLLISION"))
        val adjacent = ok(call(login("ops.mumbai"), "POST", secondUrl, allocation(resource, to, to.plusSeconds(3600))))
        assertEquals("ACTIVE", adjacent["status"].asString())
        val pending = ok(call(login("ops.mumbai"), "POST", secondUrl,
            allocation(resource, from.plusSeconds(3600), to, "Synthetic operational exception")))
        assertEquals("PENDING_EXCEPTION", pending["status"].asString())
        val decisionUrl = "$secondUrl/${pending["id"].asString()}/decision"
        call(login("ops.mumbai"), "POST", decisionUrl, mapOf("decision" to "APPROVE", "reason" to "Review"),
            pending["version"].asLong()).andExpect(jsonPath("$.code").value("SELF_APPROVAL_FORBIDDEN"))
        val decided = ok(call(login("ops.manager"), "POST", decisionUrl,
            mapOf("decision" to "APPROVE", "reason" to "Synthetic owner decision"), pending["version"].asLong()))
        assertEquals("ACTIVE", decided["status"].asString())
        call(login("ops.mumbai"), "GET", "$url/${pending["id"].asString()}").andExpect(status().isNotFound)
        call(login("commercial"), "POST", url, allocation(resource, to.plusSeconds(7200), to.plusSeconds(10800)))
            .andExpect(status().isForbidden)
        call(login("ops.mumbai"), "POST", decisionUrl, mapOf("decision" to "RETURN", "reason" to "Too late"),
            pending["version"].asLong()).andExpect(jsonPath("$.code").value("STALE_VERSION"))
    }

    @Test
    fun `two concurrent requests for one asset activate only one`() {
        val (_, type, model) = fixture()
        val resource = asset(model, "SYN-${UUID.randomUUID()}")["id"].asString()
        certificate(resource, type, LocalDate.parse("2027-01-01"), LocalDate.parse("2029-01-01"))
            .andExpect(status().isCreated)
        val jobs = listOf(newJob()["id"].asString(), newJob()["id"].asString())
        val sessions = listOf(login("ops.mumbai"), login("admin"))
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = jobs.mapIndexed { index, job -> pool.submit<Int> {
                start.await()
                call(sessions[index], "POST", "/api/v1/jobs/$job/resources/allocations", allocation(resource,
                    Instant.parse("2028-06-01T00:00:00Z"), Instant.parse("2028-06-02T00:00:00Z")))
                    .andReturn().response.status
            } }
            start.countDown()
            assertEquals(listOf(201, 409), futures.map { it.get(30, TimeUnit.SECONDS) }.sorted())
        } finally { pool.shutdownNow() }
    }
}
