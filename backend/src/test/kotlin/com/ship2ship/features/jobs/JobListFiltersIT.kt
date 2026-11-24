package com.ship2ship.features.jobs

import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.features.technical.JobTestSupport
import java.util.UUID
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JobListFiltersIT : JobTestSupport() {
    @Test
    fun `job overview shows marine progress across branches without granting marine access`() {
        val job = newJob(branch = BRANCH_FUJ)
        val id = job["id"].asString()
        val marine = login("marine")
        ok(call(marine, "POST", "/api/v1/jobs/$id/jpo/revisions"))
        ok(call(marine, "POST", "/api/v1/jobs/$id/mooring-plan/revisions"))

        val detail = ok(mvc.perform(get("/api/v1/jobs/$id").session(login("ops.mumbai"))))
        assertEquals("DRAFT", detail["flow"]["jpoStatus"].asString())
        assertEquals("DRAFT", detail["flow"]["mooringStatus"].asString())
        assertFalse(detail["flow"]["canOpenJpo"].asBoolean())
        assertFalse(detail["flow"]["canOpenMooring"].asBoolean())
    }

    @Test
    fun `job overview reports an issued final report before financial closing`() {
        val job = newJob()
        val id = UUID.fromString(job["id"].asString())
        val reportId = UuidV7.generate()
        val revisionId = UuidV7.generate()
        jdbc.sql("INSERT INTO final_report (id,job_id,created_at) VALUES (:id,:job,UTC_TIMESTAMP(6))")
            .param("id", Uuids.toBytes(reportId)).param("job", Uuids.toBytes(id)).update()
        jdbc.sql("""INSERT INTO final_report_revision
            (id,report_id,revision_no,status,template_version,content_json,created_at,updated_at,version)
            VALUES (:id,:report,1,'ISSUED',1,'{}',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)""")
            .param("id", Uuids.toBytes(revisionId)).param("report", Uuids.toBytes(reportId)).update()

        val detail = ok(mvc.perform(get("/api/v1/jobs/$id").session(login("ops.mumbai"))))
        assertEquals("OPEN", detail["status"].asString())
        assertTrue(detail["flow"]["finalReportIssued"].asBoolean())
    }

    @Test
    fun `accepted jobs are visible to active users without job role and across branches`() {
        val job = newJob(branch = BRANCH_KDL)
        for (username in listOf("sts.admin", "ops.mumbai")) {
            val session = login(username)
            val me = ok(mvc.perform(get("/api/v1/me").session(session)))
            assertTrue(me["permissions"].any { it.asString() == "JOB.READ" }, username)
            val listed = ok(mvc.perform(get("/api/v1/jobs").session(session).param("size", "100")))
            assertTrue(listed["items"].any { it["id"].asString() == job["id"].asString() }, username)
            assertEquals(job["id"].asString(), ok(mvc.perform(get("/api/v1/jobs/${job["id"].asString()}").session(session)))["id"].asString())
        }
    }

    @Test
    fun `status counts match the scoped job list`() {
        newJob()
        val session = login("commercial")
        val counts = ok(mvc.perform(get("/api/v1/jobs/status-counts").session(session)))
        val open = ok(mvc.perform(get("/api/v1/jobs").session(session).param("status", "OPEN").param("size", "1")))
        assertEquals(open["totalElements"].asLong(), counts["OPEN"].asLong())
    }

    @Test
    fun `name searches find jobs and enquiries even when more than 200 clients or vessels match`() {
        val job = newJob()
        val enquiryId = job["enquiryId"].asString()
        fun b(id: String) = Uuids.toBytes(UUID.fromString(id))
        // 205 matching masters each; the job's own are last by name, beyond the first 200 any lookup would return.
        val clients = (0..204).map { n -> UuidV7.generate().toString().also { id ->
            jdbc.sql("""INSERT INTO client (id,code,legal_name,active,created_at,updated_at,version)
                VALUES (:id,:code,:name,TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)""")
                .param("id", b(id)).param("code", "BRD-" + id.takeLast(10)).param("name", "Broadmatch Synthetic %03d".format(n)).update()
        } }
        val vessels = (0..204).map { n -> UuidV7.generate().toString().also { id ->
            jdbc.sql("""INSERT INTO vessel (id,imo,name,vessel_type,created_at,updated_at,version)
                VALUES (:id,:imo,:name,'PRODUCT_TANKER',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)""")
                .param("id", b(id)).param("imo", "81%05d".format(n)).param("name", "BROADMATCH %03d".format(n)).update()
        } }
        val original = jdbc.sql("SELECT BIN_TO_UUID(client_id) FROM sts_job WHERE id=:id").param("id", b(job["id"].asString())).query(String::class.java).single()
        val originalVessel = job["vessels"].first { it["role"].asString() == "MOTHER" }["vesselId"].asString()
        fun link(client: String, vessel: String) {
            jdbc.sql("UPDATE sts_job SET client_id=:c WHERE id=:id").param("c", b(client)).param("id", b(job["id"].asString())).update()
            jdbc.sql("UPDATE enquiry SET client_id=:c WHERE id=:id").param("c", b(client)).param("id", b(enquiryId)).update()
            jdbc.sql("UPDATE job_vessel SET vessel_id=:v WHERE job_id=:id AND role='MOTHER'").param("v", b(vessel)).param("id", b(job["id"].asString())).update()
            jdbc.sql("UPDATE enquiry_vessel SET vessel_id=:v WHERE enquiry_id=:id AND role='MOTHER'").param("v", b(vessel)).param("id", b(enquiryId)).update()
        }
        link(clients.last(), vessels.last())
        try {
            val session = login("commercial")
            fun ids(url: String, field: String, vararg params: Pair<String, String>) =
                ok(mvc.perform(get(url).session(session).param("size", "100").apply { params.forEach { (k, v) -> param(k, v) } }))["items"].let { n -> (0 until n.size()).map { n[it][field].asString() } }
            assertTrue(job["id"].asString() in ids("/api/v1/jobs", "id", "client" to "broadmatch synthetic"))
            assertTrue(job["id"].asString() in ids("/api/v1/jobs", "id", "vessel" to "Broadmatch"))
            assertTrue(enquiryId in ids("/api/v1/enquiries", "id", "q" to "Broadmatch Synthetic"))
            assertTrue(enquiryId in ids("/api/v1/enquiries", "id", "q" to "BROADMATCH 204"))
            assertTrue(job["id"].asString() !in ids("/api/v1/jobs", "id", "client" to "Broadmatch Synthetic 203"))
        } finally {
            link(original, originalVessel)
            jdbc.sql("DELETE FROM client WHERE id IN (:ids)").param("ids", clients.map(::b)).update()
            jdbc.sql("DELETE FROM vessel WHERE id IN (:ids)").param("ids", vessels.map(::b)).update()
        }
    }

    @Test
    fun `job register filters every displayed column before paging`() {
        val job = newJob()
        val session = login("commercial")
        val number = job["jobNumber"].asString()
        val created = Instant.parse(job["createdAt"].asString()).atZone(ZoneId.of("Asia/Kolkata")).toLocalDate()
        val vessel = job["vessels"][0]["name"].asString()
        val base = mapOf("jobNumber" to number, "timeZone" to "Asia/Kolkata")
        fun jobs(filters: Map<String, String>) = mvc.perform(get("/api/v1/jobs").session(session).apply {
            filters.forEach { (name, value) -> param(name, value) }
        })

        val matching = ok(jobs(base + mapOf(
            "createdFrom" to created.toString(), "createdTo" to created.toString(),
            "client" to job["client"]["name"].asString(), "vessel" to vessel,
            "enquiryReference" to job["enquiryReference"].asString(), "status" to "OPEN",
        )))
        assertTrue(matching["items"].any { it["id"].asString() == job["id"].asString() })

        for (term in listOf(job["enquiryReference"].asString(), vessel)) {
            val operations = ok(mvc.perform(get("/api/v1/operations/jobs").session(login("ops.mumbai")).param("q", term)))
            assertTrue(operations["items"].any { it["jobId"].asString() == job["id"].asString() }, term)
        }

        for ((name, value) in listOf(
            "jobNumber" to "NO_MATCH_JOB",
            "createdFrom" to created.plusDays(1).toString(), "createdTo" to created.minusDays(1).toString(),
            "client" to "NO_MATCH_CLIENT", "vessel" to "NO_MATCH_VESSEL",
            "enquiryReference" to "NO_MATCH_ENQUIRY", "status" to "CANCELLED",
        )) {
            val filters = if (name == "jobNumber") mapOf(name to value) else base + (name to value)
            assertEquals(0, ok(jobs(filters))["totalElements"].asInt(), "$name=$value")
        }
    }
}
