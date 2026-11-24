package com.ship2ship.features.administration

import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.security.CurrentUser
import com.ship2ship.core.security.CurrentUserSource
import com.ship2ship.core.security.ScopeGrant
import com.ship2ship.core.security.UserDirectory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** Supplies the signed-in user's effective grants to [com.ship2ship.core.security.AccessPolicy] on each request. */
@Service
class UserAccessService(
    private val users: UserRepository,
    private val roles: RoleRepository,
    private val grants: UserRoleScopeRepository,
    private val jdbc: JdbcClient,
    private val clock: Clock,
) : CurrentUserSource {

    @Transactional(readOnly = true)
    override fun load(userId: UUID): CurrentUser? {
        val user = users.findByIdOrNull(userId)?.takeIf { it.status == UserStatus.ACTIVE } ?: return null
        val today = LocalDate.now(clock)
        val roleById = roles.findAllWithPermissions().associateBy { it.id }
        val effective = grants.findByUserIdOrderByCreatedAt(userId).filter { it.isEffectiveOn(today) }
        val roleGrants = effective.mapNotNull { grant ->
            roleById[grant.roleId]?.let { role -> ScopeGrant(role.code, grant.branchId,
                if (role.code == "SYSTEM_ADMIN") role.permissionCodes else emptySet()) }
        }
        val directGrants = jdbc.sql("SELECT permission_code, branch_id FROM user_permission_grant WHERE user_id=:userId")
            .param("userId", Uuids.toBytes(userId))
            .query { rs, _ -> rs.getString(1) to Uuids.fromBytes(rs.getBytes(2)) }.list()
            .groupBy({ it.second }, { it.first })
            .map { (branchId, codes) -> ScopeGrant("DIRECT", branchId, codes.toSet()) }
        return CurrentUser(
            id = user.id,
            fullName = user.fullName ?: user.externalSubjectId ?: "User",
            language = user.language,
            grants = roleGrants + directGrants,
        )
    }
}

/** Display names for users referenced by other features (submitter, approver, recorder). */
@Service
class UserNameDirectory(private val users: UserRepository) : UserDirectory {

    @Transactional(readOnly = true)
    override fun names(ids: Collection<UUID>): Map<UUID, String> =
        if (ids.isEmpty()) emptyMap() else users.findAllById(ids.toSet()).associate { it.id to (it.fullName ?: it.externalSubjectId ?: "User") }
}
