package com.ship2ship.core.security

import java.util.UUID

data class BranchRef(val id: UUID, val code: String, val timezone: String, val active: Boolean)

/**
 * Read-only organisation facts needed outside the masters feature (e.g. validating role scopes).
 * Implemented by the masters feature so other features do not reach into its tables.
 */
interface OrganisationLookup {
    fun branch(branchId: UUID): BranchRef?

    fun branches(ids: Collection<UUID>): Map<UUID, BranchRef>
}
