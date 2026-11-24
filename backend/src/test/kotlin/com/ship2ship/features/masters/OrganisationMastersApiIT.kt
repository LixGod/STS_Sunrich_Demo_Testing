package com.ship2ship.features.masters

import com.ship2ship.support.IntegrationTest
import org.hamcrest.Matchers.hasItems
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.random.Random
import kotlin.test.assertTrue

/** UAT-01 (cross-branch access), UAT-02 pattern (duplicates/validation), TR-02 (optimistic locking), FR-MD-01. */
class OrganisationMastersApiIT : IntegrationTest() {

    private fun uniqueCode(prefix: String) = "$prefix-${Random.nextInt(100_000, 999_999)}"

    private fun branchBody(code: String, name: String = "Test Branch $code") = body(mapOf(
        "code" to code, "name" to name, "timezone" to "Asia/Dubai",
    ))

    @Test
    fun `branch-scoped user sees only their branch and is refused another branch, with the denial audited`() {
        val ops = login("ops.mumbai")
        mvc.perform(get("/api/v1/masters/branches").session(ops))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.items[0].id").value(BRANCH_MUM))

        val before = auditCount("ACCESS_DENIED", "BRANCH")
        mvc.perform(get("/api/v1/masters/branches/$BRANCH_KDL").session(ops))
            .andExpect(status().isForbidden)
        assertTrue(auditCount("ACCESS_DENIED", "BRANCH") > before)

        // Read-only role cannot create a branch.
        mvc.perform(post("/api/v1/masters/branches").session(ops).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(branchBody(uniqueCode("BR"))))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `company-wide finance user lists every branch`() {
        val finance = login("finance")
        mvc.perform(get("/api/v1/masters/branches").session(finance).param("size", "100"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[*].id").value(hasItems(BRANCH_MUM, BRANCH_KDL, BRANCH_FUJ)))
    }

    @Test
    fun `create, read with ETag, update with If-Match, and detect stale or missing versions`() {
        val admin = login("admin")
        val code = uniqueCode("BR")
        val id = read(mvc.perform(post("/api/v1/masters/branches").session(admin).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(branchBody(code)))
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString)["id"].asString()

        mvc.perform(get("/api/v1/masters/branches/$id").session(admin))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))

        mvc.perform(put("/api/v1/masters/branches/$id").session(admin).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(branchBody(code, "Renamed")))
            .andExpect(status().isPreconditionRequired)
            .andExpect(jsonPath("$.code").value("VERSION_REQUIRED"))

        mvc.perform(put("/api/v1/masters/branches/$id").session(admin).with(csrf()).header(HttpHeaders.IF_MATCH, "\"0\"")
            .contentType(MediaType.APPLICATION_JSON).content(branchBody(code, "Renamed")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Renamed"))
            .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))

        // Second editor still holding version 0.
        mvc.perform(put("/api/v1/masters/branches/$id").session(admin).with(csrf()).header(HttpHeaders.IF_MATCH, "\"0\"")
            .contentType(MediaType.APPLICATION_JSON).content(branchBody(code, "Lost update")))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("STALE_VERSION"))

        val changes = jdbc.sql("SELECT details FROM audit_event WHERE action = 'UPDATE' AND entity_type = 'BRANCH' ORDER BY seq DESC LIMIT 1")
            .query(String::class.java).single()
        assertTrue(changes.contains("Renamed"))
    }

    @Test
    fun `duplicate codes and unknown time zones are rejected`() {
        val admin = login("admin")
        mvc.perform(post("/api/v1/masters/branches").session(admin).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(branchBody("MUM")))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("DUPLICATE_CODE"))

        mvc.perform(post("/api/v1/masters/branches").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("code" to uniqueCode("BR"), "name" to "X", "timezone" to "Mars/Base"))))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors.length()").value(1))

        mvc.perform(post("/api/v1/masters/branches").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("code" to "lower case", "name" to "", "timezone" to "UTC"))))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `unused branches can be deleted while referenced branches reject delete`() {
        val admin = login("admin")
        val etag = mvc.perform(get("/api/v1/masters/branches/$BRANCH_MUM").session(admin))
            .andExpect(status().isOk).andReturn().response.getHeader(HttpHeaders.ETAG)!!
        mvc.perform(delete("/api/v1/masters/branches/$BRANCH_MUM").session(admin).with(csrf()).header(HttpHeaders.IF_MATCH, etag))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONSTRAINT_VIOLATION"))

        val createdId = read(mvc.perform(post("/api/v1/masters/branches").session(admin).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(branchBody(uniqueCode("DEL"))))
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString)["id"].asString()
        mvc.perform(delete("/api/v1/masters/branches/$createdId").session(admin).with(csrf()).header(HttpHeaders.IF_MATCH, "\"0\""))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `page size is capped and unknown sort fields are rejected`() {
        val admin = login("admin")
        mvc.perform(get("/api/v1/masters/branches").session(admin).param("size", "50000"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.size").value(100))
        mvc.perform(get("/api/v1/masters/branches").session(admin).param("sort", "createdBy,asc"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_SORT"))
    }

    @Test
    fun `audit trail is readable by scope and paginates by cursor`() {
        val admin = login("admin")
        val first = read(mvc.perform(get("/api/v1/audit-events").session(admin).param("limit", "2"))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        val cursor = first["nextCursor"].asString()
        mvc.perform(get("/api/v1/audit-events").session(admin).param("limit", "2").param("after", cursor))
            .andExpect(status().isOk)

        // Operations role has no AUDIT.READ.
        mvc.perform(get("/api/v1/audit-events").session(login("ops.mumbai"))).andExpect(status().isForbidden)
    }
}
