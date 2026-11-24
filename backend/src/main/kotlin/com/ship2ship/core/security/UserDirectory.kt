package com.ship2ship.core.security

import java.util.UUID

/** Display names of users (e.g. who submitted or approved a document); implemented by the administration feature. */
fun interface UserDirectory {
    fun names(ids: Collection<UUID>): Map<UUID, String>
}
