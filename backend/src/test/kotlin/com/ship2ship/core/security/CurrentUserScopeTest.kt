package com.ship2ship.core.security

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CurrentUserScopeTest {

    private val branchA = UUID.randomUUID()
    private val branchB = UUID.randomUUID()

    private fun user(vararg grants: ScopeGrant) = CurrentUser(UUID.randomUUID(), "Test", "en", grants.toList())

    @Test
    fun `company-wide job visibility does not widen other permissions`() {
        val current = CurrentUser(UUID.randomUUID(), "Test", "en",
            listOf(ScopeGrant("OPS", branchA, setOf("JOB.READ", "JOB.WRITE"))), universalJobRead = true)
        assertTrue("JOB.READ" in current.permissions)
        assertTrue(current.scopeFor("JOB.READ").coversBranch(branchB))
        assertFalse(current.scopeFor("JOB.WRITE").coversBranch(branchB))
    }

    @Test
    fun `company-wide grant covers every branch and company records`() {
        val scope = user(ScopeGrant("ADMIN", null, setOf("P"))).scopeFor("P")
        assertTrue(scope.global)
        assertTrue(scope.coversBranch(branchB))
        assertNull(scope.companyCondition())
        assertNull(scope.sqlCondition("branch_id", mutableMapOf()))
    }

    @Test
    fun `branch grant covers only that branch and no company-wide records`() {
        val scope = user(ScopeGrant("OPS", branchA, setOf("P"))).scopeFor("P")
        assertTrue(scope.coversBranch(branchA))
        assertFalse(scope.coversBranch(branchB))
        assertFalse(scope.global)
        assertEquals("1=0", scope.companyCondition())
        val params = mutableMapOf<String, Any>()
        assertEquals("b IN (:scopeBranches)", scope.sqlCondition("b", params))
        assertEquals(1, (params["scopeBranches"] as List<*>).size)
    }

    @Test
    fun `permission held under another role does not leak into this scope`() {
        val current = user(
            ScopeGrant("OPS", branchA, setOf("READ")),
            ScopeGrant("AUDITOR", null, setOf("AUDIT")),
        )
        assertTrue(current.scopeFor("WRITE").isEmpty)
        assertFalse(current.scopeFor("READ").global)
        assertTrue(current.scopeFor("AUDIT").global)
    }
}
