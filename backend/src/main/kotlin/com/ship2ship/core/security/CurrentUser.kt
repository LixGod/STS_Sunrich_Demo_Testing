package com.ship2ship.core.security

import com.ship2ship.core.audit.Uuids
import java.io.Serializable
import java.util.UUID

/**
 * What the HTTP session remembers after login. Permissions are deliberately NOT cached here: they are
 * re-read on every request so revoked roles and suspended accounts take effect immediately.
 * The external authentication token, if any, stays server-side in this object and never reaches the browser.
 */
data class SessionUser(
    val userId: UUID,
    val fullName: String,
    val externalSessionToken: String? = null,
) : Serializable

/** One role granted within a scope. Null branch = the whole company. */
data class ScopeGrant(
    val roleCode: String,
    val branchId: UUID?,
    val permissions: Set<String>,
)

/** Where a user holds a given permission: the whole company ([global]) or some branches. */
data class AccessScope(val global: Boolean, val branchIds: Set<UUID>) {

    val isEmpty: Boolean get() = !global && branchIds.isEmpty()

    /** Applies to records owned by a branch. */
    fun coversBranch(branchId: UUID): Boolean = global || branchId in branchIds

    /** SQL condition limiting rows to this scope (`branch` is the column name; `prefix` keeps parameter names unique). */
    fun sqlCondition(branch: String, params: MutableMap<String, Any>, prefix: String = "scope"): String? {
        if (global) return null
        if (branchIds.isEmpty()) return "1=0"
        params["${prefix}Branches"] = branchIds.map(Uuids::toBytes)
        return "$branch IN (:${prefix}Branches)"
    }

    /** SQL condition for company-wide records (no branch column): only a company-wide grant sees them. */
    fun companyCondition(): String? = if (global) null else "1=0"

    companion object {
        val NONE = AccessScope(false, emptySet())
    }
}

data class CurrentUser(
    val id: UUID,
    val fullName: String,
    val language: String,
    val grants: List<ScopeGrant>,
    val universalJobRead: Boolean = false,
) {
    val permissions: Set<String> by lazy {
        grants.flatMapTo(sortedSetOf()) { it.permissions }.apply { if (universalJobRead) add(Permissions.JOB_READ) }
    }

    fun scopeFor(permission: String): AccessScope {
        if (permission == Permissions.JOB_READ && universalJobRead) return AccessScope(true, emptySet())
        val matching = grants.filter { permission in it.permissions }
        if (matching.isEmpty()) return AccessScope.NONE
        return AccessScope(
            global = matching.any { it.branchId == null },
            branchIds = matching.mapNotNullTo(mutableSetOf()) { it.branchId },
        )
    }
}

/** Implemented by the administration feature: loads an ACTIVE user with effective grants, or null. */
fun interface CurrentUserSource {
    fun load(userId: UUID): CurrentUser?
}
