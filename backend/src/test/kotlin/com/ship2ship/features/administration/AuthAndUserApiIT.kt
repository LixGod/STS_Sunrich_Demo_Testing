package com.ship2ship.features.administration

import com.ship2ship.support.IntegrationTest
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import kotlin.test.assertTrue

/** UAT-01 (user administration and denied access) and FR-UM-01/03/04/05. */
class AuthAndUserApiIT : IntegrationTest() {

    @Autowired private lateinit var userAccess: UserAccessService

    @Test
    fun `user edits own profile and signs in with new username and same password`() {
        val admin = login("admin")
        val oldUsername = "profile-${UUID.randomUUID()}"
        val newUsername = "renamed-${UUID.randomUUID()}"
        val newEmail = "after-${UUID.randomUUID()}@example.com"
        val password = "profile-password-123"
        val created = read(mvc.perform(post("/api/v1/admin/users").session(admin).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("externalSubjectId" to oldUsername, "fullName" to "Before"))))
            .andExpect(status().isCreated).andReturn().response.contentAsString)
        val id = created["id"].asString()
        mvc.perform(put("/api/v1/admin/users/$id/password").session(admin).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body(mapOf("password" to password))))
            .andExpect(status().isNoContent)
        val session = read(mvc.perform(post("/api/v1/auth/login").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("username" to oldUsername, "password" to password))))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        assertTrue(session["id"].asString() == id)
        val signedIn = login(oldUsername, password)
        val profile = read(mvc.perform(get("/api/v1/me/profile").session(signedIn))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        val version = profile["version"].asLong()
        mvc.perform(put("/api/v1/me/profile").session(signedIn).with(csrf())
            .header(HttpHeaders.IF_MATCH, "\"$version\"")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("username" to newUsername, "fullName" to "After", "workEmail" to newEmail.uppercase(),
                "department" to "Operations", "position" to "Lead"))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.externalSubjectId").value(newUsername))
            .andExpect(jsonPath("$.workEmail").value(newEmail.lowercase()))
        mvc.perform(get("/api/v1/me").session(signedIn))
            .andExpect(status().isOk).andExpect(jsonPath("$.fullName").value("After"))
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("username" to oldUsername, "password" to password))))
            .andExpect(status().isUnauthorized)
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("username" to newUsername, "password" to password))))
            .andExpect(status().isOk).andExpect(jsonPath("$.id").value(id))
    }

    @Test
    fun `demo user can change username and keep the same password`() {
        val original = "dev-sts-admin"
        val renamed = "renamed-demo-${UUID.randomUUID()}"
        val session = login("sts.admin")
        val profile = read(mvc.perform(get("/api/v1/me/profile").session(session))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        val version = profile["version"].asLong()
        try {
            mvc.perform(put("/api/v1/me/profile").session(session).with(csrf())
                .header(HttpHeaders.IF_MATCH, "\"$version\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(mapOf("username" to renamed, "fullName" to profile["fullName"].asString()))))
                .andExpect(status().isOk)
            login(renamed, "sts")
            mvc.perform(post("/api/v1/admin/users").session(login("admin")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(mapOf("externalSubjectId" to original, "fullName" to "Impersonator"))))
                .andExpect(status().isConflict)
        } finally {
            jdbc.sql("UPDATE app_user SET external_subject_id=:subject WHERE id=UUID_TO_BIN(:id)")
                .param("subject", original).param("id", profile["id"].asString()).update()
        }
        login("sts.admin")
    }

    @Test
    fun `Super Admin sets a password and deletion revokes login while retaining history`() {
        val admin = login("admin")
        val subject = "local-${UUID.randomUUID()}"
        val password = "test-password-123"
        val created = read(mvc.perform(post("/api/v1/admin/users").session(admin).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("externalSubjectId" to subject, "fullName" to "Local Login"))))
            .andExpect(status().isCreated).andReturn().response.contentAsString)
        val id = created["id"].asString()
        mvc.perform(put("/api/v1/admin/users/$id/password").session(login("sts.admin")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body(mapOf("password" to password))))
            .andExpect(status().isForbidden)
        mvc.perform(put("/api/v1/admin/users/$id/password").session(admin).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body(mapOf("password" to password))))
            .andExpect(status().isNoContent)
        val hash = jdbc.sql("SELECT password_hash FROM user_local_credential WHERE user_id=UUID_TO_BIN(:id)")
            .param("id", id).query(String::class.java).single()
        assertTrue(hash != password && hash.startsWith("$2"))
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("username" to subject, "password" to "wrong-password"))))
            .andExpect(status().isUnauthorized)
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("username" to subject, "password" to password))))
            .andExpect(status().isOk)
        val version = read(mvc.perform(get("/api/v1/admin/users/$id").session(admin))
            .andExpect(status().isOk).andReturn().response.contentAsString)["version"].asLong()
        mvc.perform(delete("/api/v1/admin/users/$id").session(admin).with(csrf())
            .header(HttpHeaders.IF_MATCH, "\"$version\""))
            .andExpect(status().isNoContent)
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("username" to subject, "password" to password))))
            .andExpect(status().isUnauthorized)
        mvc.perform(get("/api/v1/admin/users/$id").session(admin)).andExpect(status().isNotFound)
        assertTrue(jdbc.sql("SELECT COUNT(*) FROM app_user WHERE id=UUID_TO_BIN(:id) AND status='DELETED'")
            .param("id", id).query(Long::class.java).single() == 1L)
    }

    @Test
    fun `admin can create and edit a role while built in administrator remains protected`() {
        val admin = login("admin")
        val code = "CUSTOM_${UUID.randomUUID().toString().take(8).uppercase()}"
        val created = read(mvc.perform(post("/api/v1/admin/roles").session(admin).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("code" to code, "name" to "Custom role", "description" to "For a team",
                "permissions" to listOf("MASTER.ORG.READ")))))
            .andExpect(status().isCreated).andReturn().response.contentAsString)
        val id = created["id"].asString()
        try {
            mvc.perform(put("/api/v1/admin/roles/$id").session(admin).with(csrf())
                .header(HttpHeaders.IF_MATCH, "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content(body(mapOf("name" to "Updated role", "description" to null,
                    "permissions" to listOf("MASTER.ORG.WRITE")))))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.permissions").value(hasItem("MASTER.ORG.WRITE")))
            mvc.perform(put("/api/v1/admin/roles/$id").session(admin).with(csrf())
                .header(HttpHeaders.IF_MATCH, "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content(body(mapOf("name" to "Stale", "permissions" to emptyList<String>()))))
                .andExpect(status().isConflict)
            mvc.perform(put("/api/v1/admin/roles/$id").session(admin).with(csrf())
                .header(HttpHeaders.IF_MATCH, "\"1\"").contentType(MediaType.APPLICATION_JSON)
                .content(body(mapOf("name" to "Invalid", "permissions" to listOf("NOT.REAL")))))
                .andExpect(status().isBadRequest)
            val systemId = jdbc.sql("SELECT BIN_TO_UUID(id) FROM role WHERE code='SYSTEM_ADMIN'")
                .query(String::class.java).single()
            mvc.perform(put("/api/v1/admin/roles/$systemId").session(admin).with(csrf())
                .header(HttpHeaders.IF_MATCH, "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content(body(mapOf("name" to "Locked out", "permissions" to emptyList<String>()))))
                .andExpect(status().isForbidden)
            mvc.perform(post("/api/v1/admin/roles").session(login("sts.admin")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(mapOf("code" to "DENIED", "name" to "Denied", "permissions" to emptyList<String>()))))
                .andExpect(status().isForbidden)
        } finally {
            jdbc.sql("DELETE FROM role_permission WHERE role_id=UUID_TO_BIN(:id)").param("id", id).update()
            jdbc.sql("DELETE FROM role WHERE id=UUID_TO_BIN(:id)").param("id", id).update()
        }
    }

    @Test
    fun `anonymous requests cannot read database-backed API data`() {
        mvc.perform(get("/api/v1/masters/cargo-grades"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
    }

    @Test
    fun `login issues the named HttpOnly authentication cookie`() {
        val result = mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("username" to "ops.mumbai", "password" to "ops"))))
            .andExpect(status().isOk).andReturn()
        val session = result.request.getSession(false)
        assertTrue(session != null && session.id.isNotBlank())
    }

    @Test
    fun `Super Admin has every configured permission including setup and approvals`() {
        val session=login("admin")
        val profile = read(mvc.perform(get("/api/v1/me").session(session)).andExpect(status().isOk).andReturn().response.contentAsString)
        assertTrue(profile["globalPermissions"].any { it.asString() == "MASTER.RESOURCE.READ" })
        for (permission in listOf("ADMIN.USER.READ", "ADMIN.USER.WRITE", "ADMIN.ROLE.READ", "AUDIT.READ")) {
            assertTrue(profile["permissions"].any { it.asString() == permission })
        }
        for(permission in listOf("MASTER.ORG.WRITE", "MASTER.COMMERCIAL.WRITE", "MASTER.FINANCE.WRITE", "MASTER.RESOURCE.WRITE",
                "COMMERCIAL.QUOTATION.APPROVE","TECH.JPO.APPROVE","TECH.MOORING.APPROVE","FIN.INVOICE.APPROVE","JOB.CLOSE.APPROVE")) {
            mvc.perform(get("/api/v1/me").session(session)).andExpect(jsonPath("$.permissions").value(hasItem(permission)))
        }
        val catalogue = jdbc.sql("SELECT code FROM permission").query(String::class.java).list().toSet()
        assertTrue(catalogue.all { code -> profile["permissions"].any { it.asString() == code } })
    }

    @Test
    fun `STS Admin has setup permissions but no user access or audit permissions`() {
        val role = jdbc.sql("SELECT BIN_TO_UUID(id) FROM role WHERE code = 'STS_ADMIN'").query(String::class.java).single()
        val permissions = jdbc.sql("SELECT permission_code FROM role_permission WHERE role_id = UUID_TO_BIN(:role)")
            .param("role", role).query(String::class.java).list().toSet()
        assertTrue("MASTER.ORG.WRITE" in permissions)
        assertTrue("MASTER.RESOURCE.WRITE" in permissions)
        assertTrue("ADMIN.USER.READ" !in permissions)
        assertTrue("ADMIN.ROLE.READ" !in permissions)
        assertTrue("AUDIT.READ" !in permissions)
    }

    @Test
    fun `synthetic STS Admin account can manage setup but cannot manage users or audit`() {
        val session = login("sts.admin")
        mvc.perform(get("/api/v1/me").session(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.permissions").value(hasItem("MASTER.ORG.WRITE")))
            .andExpect(jsonPath("$.permissions").value(hasItem("MASTER.RESOURCE.WRITE")))
            .andExpect(jsonPath("$.permissions").value(not(hasItem("ADMIN.USER.READ"))))
            .andExpect(jsonPath("$.permissions").value(not(hasItem("AUDIT.READ"))))
        mvc.perform(get("/api/v1/admin/users").session(session)).andExpect(status().isForbidden)
    }

    @Test
    fun `commercial user can read cargo lookups but cannot change master data`() {
        val session = login("commercial")
        mvc.perform(get("/api/v1/me").session(session))
            .andExpect(jsonPath("$.permissions").value(not(hasItem("MASTER.COMMERCIAL.WRITE"))))
        mvc.perform(post("/api/v1/masters/cargo-grades").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("code" to "UNAUTHORIZED-GRADE", "name" to "Unauthorized", "category" to "LPG"))))
            .andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/masters/cargo-grades").session(session)).andExpect(status().isOk)
    }

    @Test
    fun `login returns the profile with permissions and grants, and logout ends the session`() {
        val loginsBefore = auditCount("LOGIN", "USER")
        val logoutsBefore = auditCount("LOGOUT", "USER")
        val session = login("ops.mumbai")
        mvc.perform(get("/api/v1/me").session(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.fullName").value("Demo Operations (Mumbai)"))
            .andExpect(jsonPath("$.permissions").value(hasItem("MASTER.ORG.READ")))
            .andExpect(jsonPath("$.globalPermissions").value(not(hasItem("MASTER.RESOURCE.READ"))))
            .andExpect(jsonPath("$.permissions").value(not(hasItem("ADMIN.USER.WRITE"))))
            .andExpect(jsonPath("$.grants[0].branchId").value(BRANCH_MUM))

        mvc.perform(post("/api/v1/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent)
        assertTrue(auditCount("LOGIN", "USER") > loginsBefore)
        assertTrue(auditCount("LOGOUT", "USER") > logoutsBefore)
        mvc.perform(get("/api/v1/me").session(session))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
    }

    @Test
    fun `wrong password is refused and audited without exposing the password`() {
        val before = auditCount("LOGIN_FAILED")
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("username" to "admin", "password" to "wrong"))))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
        assertTrue(auditCount("LOGIN_FAILED") > before)
        val leaked = jdbc.sql("SELECT COUNT(*) FROM audit_event WHERE action = 'LOGIN_FAILED' AND details LIKE '%wrong%'").query(Long::class.java).single()
        assertTrue(leaked == 0L)
    }

    @Test
    fun `valid credentials without a provisioned Ship2Ship account are rejected`() {
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("username" to "stranger", "password" to "stranger"))))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"))
    }

    @Test
    fun `state-changing requests without the CSRF token are refused`() {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("username" to "admin", "password" to "admin"))))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `finance user cannot open user administration and the denial is audited`() {
        val session = login("finance")
        val before = auditCount("ACCESS_DENIED")
        mvc.perform(get("/api/v1/admin/users").session(session))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
        assertTrue(auditCount("ACCESS_DENIED") > before)
    }

    @Test
    fun `administrator creates a user, grants a branch-scoped role and suspends the account`() {
        val admin = login("admin")
        val subject = "it-${UUID.randomUUID()}"
        val created = mvc.perform(post("/api/v1/admin/users").session(admin).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("externalSubjectId" to subject, "fullName" to "Test Coordinator",
                "workEmail" to "$subject@demo.invalid", "department" to "Operations"))))
            .andExpect(status().isCreated)
            .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
            .andReturn().response.contentAsString
        val userId = read(created)["id"].asString()

        mvc.perform(put("/api/v1/admin/users/$userId/role-scopes").session(admin).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("grants" to listOf(mapOf("roleId" to ROLE_OPERATIONS,
                "branchId" to BRANCH_KDL))))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].roleCode").value("OPERATIONS"))
            .andExpect(jsonPath("$[0].branchId").value(BRANCH_KDL))

        // An unknown branch is rejected.
        mvc.perform(put("/api/v1/admin/users/$userId/role-scopes").session(admin).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("grants" to listOf(mapOf("roleId" to ROLE_OPERATIONS,
                "branchId" to UUID.randomUUID().toString()))))))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_GRANTS"))

        // Suspension needs a reason.
        mvc.perform(patch("/api/v1/admin/users/$userId/status").session(admin).with(csrf())
            .header(HttpHeaders.IF_MATCH, "\"0\"").contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("status" to "SUSPENDED"))))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("REASON_REQUIRED"))

        mvc.perform(patch("/api/v1/admin/users/$userId/status").session(admin).with(csrf())
            .header(HttpHeaders.IF_MATCH, "\"0\"").contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("status" to "SUSPENDED", "reason" to "Left the project"))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("SUSPENDED"))
    }

    @Test
    fun `Super Admin can save an empty draft and later give it a sign in subject`() {
        val admin = login("admin")
        val created = read(mvc.perform(post("/api/v1/admin/users").session(admin).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("externalSubjectId" to "", "fullName" to "", "workEmail" to ""))))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("DEACTIVATED"))
            .andExpect(jsonPath("$.workEmail").isEmpty)
            .andReturn().response.contentAsString)
        val userId = created["id"].asString()
        mvc.perform(patch("/api/v1/admin/users/$userId/status").session(admin).with(csrf())
            .header(HttpHeaders.IF_MATCH, "\"0\"").contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("status" to "ACTIVE"))))
            .andExpect(status().isBadRequest)
        val subject = "draft-${UUID.randomUUID()}"
        mvc.perform(put("/api/v1/admin/users/$userId").session(admin).with(csrf())
            .header(HttpHeaders.IF_MATCH, "\"0\"").contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("externalSubjectId" to subject, "fullName" to "Draft User", "workEmail" to ""))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.externalSubjectId").value(subject))
        mvc.perform(patch("/api/v1/admin/users/$userId/status").session(admin).with(csrf())
            .header(HttpHeaders.IF_MATCH, "\"1\"").contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("status" to "ACTIVE"))))
            .andExpect(status().isOk)
        val anotherSubject = "no-email-${UUID.randomUUID()}"
        mvc.perform(post("/api/v1/admin/users").session(admin).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("externalSubjectId" to anotherSubject, "fullName" to "No Email", "workEmail" to ""))))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("ACTIVE"))
    }

    @Test
    fun `a role does not grant module access until Super Admin assigns it to the user`() {
        val admin = login("admin")
        val subject = "manual-${UUID.randomUUID()}"
        val created = read(mvc.perform(post("/api/v1/admin/users").session(admin).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("externalSubjectId" to subject, "fullName" to "Manual Access User",
                "workEmail" to "$subject@demo.invalid"))))
            .andExpect(status().isCreated).andReturn().response.contentAsString)
        val userId = created["id"].asString()
        mvc.perform(put("/api/v1/admin/users/$userId/role-scopes").session(admin).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("grants" to listOf(mapOf("roleId" to ROLE_OPERATIONS,
                "branchId" to BRANCH_KDL))))))
            .andExpect(status().isOk)
        val access = jdbc.sql("SELECT permission_code FROM user_permission_grant WHERE user_id=UUID_TO_BIN(:id)")
            .param("id", userId).query(String::class.java).list()
        assertTrue(access.isEmpty())
        assertTrue(userAccess.load(UUID.fromString(userId))!!.scopeFor("OPS.MOVEMENT.READ").isEmpty)

        mvc.perform(put("/api/v1/admin/users/$userId/permission-overrides").session(admin).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("overrides" to listOf(
                mapOf("code" to "COMMERCIAL.ENQUIRY.READ", "allowed" to true),
                mapOf("code" to "FIN.INVOICE.READ", "allowed" to true),
                mapOf("code" to "MASTER.COMMERCIAL.READ", "allowed" to true),
            )))))
            .andExpect(status().isOk)
        val assigned = jdbc.sql("SELECT permission_code FROM user_permission_grant WHERE user_id=UUID_TO_BIN(:id)")
            .param("id", userId).query(String::class.java).list().toSet()
        assertTrue(assigned == setOf("COMMERCIAL.ENQUIRY.READ", "FIN.INVOICE.READ", "MASTER.COMMERCIAL.READ"))
        val effective = userAccess.load(UUID.fromString(userId))!!
        assertTrue(effective.scopeFor("COMMERCIAL.ENQUIRY.READ").global)
        assertTrue(effective.scopeFor("FIN.INVOICE.READ").global)
        assertTrue(effective.scopeFor("OPS.MOVEMENT.READ").isEmpty)
    }

    @Test
    fun `administrators cannot change their own status or grants`() {
        val admin = login("admin")
        mvc.perform(put("/api/v1/admin/users/$USER_ADMIN/role-scopes").session(admin).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body(mapOf("grants" to emptyList<Any>()))))
            .andExpect(status().isUnprocessableContent)
            .andExpect(jsonPath("$.code").value("SELF_MODIFICATION_FORBIDDEN"))
    }

    @Test
    fun `Super Admin can grant and block user actions and changes apply to the API immediately`() {
        val admin = login("admin")
        val commercial = login("commercial")
        val userId = jdbc.sql("SELECT BIN_TO_UUID(id) FROM app_user WHERE external_subject_id='dev-commercial'")
            .query(String::class.java).single()
        val url = "/api/v1/admin/users/$userId/permission-overrides"
        val original = mvc.perform(get(url).session(admin)).andExpect(status().isOk).andReturn().response.contentAsString
        try {
            mvc.perform(put(url).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(mapOf("overrides" to listOf(
                    mapOf("code" to "MASTER.COMMERCIAL.READ", "allowed" to false),
                    mapOf("code" to "TECH.JPO.APPROVE", "allowed" to true),
                ))))).andExpect(status().isOk)
            mvc.perform(get("/api/v1/masters/cargo-grades").session(commercial)).andExpect(status().isForbidden)
            mvc.perform(get("/api/v1/me").session(commercial))
                .andExpect(jsonPath("$.permissions").value(hasItem("TECH.JPO.APPROVE")))
                .andExpect(jsonPath("$.permissions").value(not(hasItem("MASTER.COMMERCIAL.READ"))))
            mvc.perform(put(url).session(commercial).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(mapOf("overrides" to emptyList<Any>())))).andExpect(status().isForbidden)
            mvc.perform(put(url).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(mapOf("overrides" to listOf(mapOf("code" to "ADMIN.USER.WRITE", "allowed" to true))))))
                .andExpect(status().isBadRequest)
        } finally {
            mvc.perform(put(url).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(mapOf("overrides" to read(original))))).andExpect(status().isOk)
        }
        mvc.perform(get("/api/v1/masters/cargo-grades").session(commercial)).andExpect(status().isOk)
        mvc.perform(put("/api/v1/admin/users/$USER_ADMIN/permission-overrides").session(admin).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body(mapOf("overrides" to emptyList<Any>()))))
            .andExpect(status().isUnprocessableContent)
    }

    @Test
    fun `duplicate email is a conflict and invalid fields are reported per field`() {
        val admin = login("admin")
        mvc.perform(post("/api/v1/admin/users").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("externalSubjectId" to "dup-${UUID.randomUUID()}", "fullName" to "Dup",
                "workEmail" to "ADMIN@demo.invalid"))))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"))

        mvc.perform(post("/api/v1/admin/users").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("externalSubjectId" to "", "fullName" to "X", "workEmail" to "not-an-email"))))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.errors[*].field").value(hasItem("workEmail")))
    }
}
