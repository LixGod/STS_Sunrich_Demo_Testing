package com.ship2ship.core.security

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder
import java.util.UUID

/**
 * Server-side authorisation by permission and branch scope (SRS FR-UM-04, SR-02).
 * Every denial is written to the audit trail in its own transaction, so it survives the rollback.
 * Call the `require*` checks before making changes in a request.
 */
@Component
class AccessPolicy(
    private val currentUserSource: CurrentUserSource,
    private val auditService: AuditService,
) {

    /** The signed-in user with grants loaded once per request. */
    fun currentUser(): CurrentUser {
        val sessionUser = SecurityContextHolder.getContext().authentication?.principal as? SessionUser
            ?: throw AuthenticationCredentialsNotFoundException("Not signed in")
        val attributes = RequestContextHolder.getRequestAttributes()
        (attributes?.getAttribute(REQUEST_KEY, RequestAttributes.SCOPE_REQUEST) as? CurrentUser)?.let { return it }
        val loaded = currentUserSource.load(sessionUser.userId)
            ?: throw AuthenticationCredentialsNotFoundException("Account is not active")
        attributes?.setAttribute(REQUEST_KEY, loaded, RequestAttributes.SCOPE_REQUEST)
        return loaded
    }

    /** Scope in which the user holds [permission]; denied if it is held nowhere. */
    fun scope(permission: String): AccessScope {
        val scope = currentUser().scopeFor(permission)
        if (scope.isEmpty) deny(permission, null, null)
        return scope
    }

    /** Permission held in at least one scope (used for non-scoped reference data such as roles). */
    fun requireAny(permission: String) {
        scope(permission)
    }

    /** Permission held for the whole company (e.g. company-wide masters, managing user accounts). */
    fun requireGlobal(permission: String, entityType: String? = null, entityId: UUID? = null) {
        if (!currentUser().scopeFor(permission).global) deny(permission, entityType, entityId)
    }

    fun requireBranch(permission: String, branchId: UUID, entityType: String? = null, entityId: UUID? = null) {
        if (!currentUser().scopeFor(permission).coversBranch(branchId)) deny(permission, entityType, entityId, branchId)
    }

    /** Custom scope rule, e.g. "may read a record because a grant covers one of its branches". */
    fun requireWhere(permission: String, entityType: String?, entityId: UUID?, rule: (AccessScope) -> Boolean) {
        if (!rule(currentUser().scopeFor(permission))) deny(permission, entityType, entityId)
    }

    private fun deny(permission: String, entityType: String?, entityId: UUID?, branchId: UUID? = null): Nothing {
        auditService.recordIndependently(
            AuditEntry(
                action = AuditAction.ACCESS_DENIED,
                entityType = entityType,
                entityId = entityId,
                branchId = branchId,
                details = mapOf("permission" to permission),
            ),
        )
        throw AccessDeniedException("Missing permission $permission")
    }

    private companion object {
        const val REQUEST_KEY = "ship2ship.currentUser"
    }
}
