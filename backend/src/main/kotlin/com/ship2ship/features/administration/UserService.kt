package com.ship2ship.features.administration

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditDiff
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.OrganisationLookup
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.BusinessRuleException
import com.ship2ship.core.web.ConflictException
import com.ship2ship.core.web.FieldError
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.PageResponse
import com.ship2ship.core.web.Versioning
import com.ship2ship.integrations.auth.ExternalAuthClient
import com.ship2ship.integrations.auth.ExternalAuthUnavailableException
import org.springframework.http.HttpStatus
import com.ship2ship.core.web.ApiException
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.findByIdOrNull
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * User administration (SRS FR-UM-01/03/05). Account changes need ADMIN.USER.WRITE for all entities;
 * administrators cannot change their own status or role grants (separation of duties).
 */
@Service
class UserService(
    private val users: UserRepository,
    private val roles: RoleRepository,
    private val grants: UserRoleScopeRepository,
    private val permissions: PermissionRepository,
    private val jdbc: JdbcClient,
    private val organisation: OrganisationLookup,
    private val accessPolicy: AccessPolicy,
    private val audit: AuditService,
    private val clock: Clock,
    private val externalAuth: ExternalAuthClient,
) {
    private val passwordEncoder = BCryptPasswordEncoder()

    @Transactional(readOnly = true)
    fun list(q: String?, status: UserStatus?, pageable: Pageable): PageResponse<UserSummaryDto> {
        accessPolicy.requireAny(Permissions.ADMIN_USER_READ)
        return PageResponse.of(users.findAll(UserRepository.matching(q, status), pageable)) { it.toSummary() }
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): UserDetailDto {
        accessPolicy.requireAny(Permissions.ADMIN_USER_READ)
        return loadExisting(id).toDetail()
    }

    @Transactional(readOnly = true)
    fun ownProfile(id: UUID): UserDetailDto = loadExisting(id).toDetail()

    @Transactional
    fun updateOwnProfile(id: UUID, expectedVersion: Long, request: OwnProfileUpdateRequest): UserDetailDto {
        val user = loadExisting(id)
        Versioning.checkCurrent(expectedVersion, user.version)
        val username = request.username.trim()
        if (username.isEmpty()) throw BadRequestException("USERNAME_REQUIRED", "Enter a username.")
        val email = request.workEmail?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (email != null && users.existsByWorkEmailIgnoreCaseAndIdNot(email, id)) {
            throw ConflictException("DUPLICATE_EMAIL", "A user with this work email already exists.",
                listOf(FieldError("workEmail", "duplicate", null)))
        }
        if (username != user.externalSubjectId) {
            if (users.existsByExternalSubjectId(username) || externalAuth.isReservedUsername(username)) {
                throw ConflictException("DUPLICATE_SUBJECT", "This username is already in use.",
                    listOf(FieldError("username", "duplicate", null)))
            }
            try {
                externalAuth.prepareUsernameChange(id, user.externalSubjectId ?: "", username)
            } catch (ex: ExternalAuthUnavailableException) {
                throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "USERNAME_CHANGE_UNAVAILABLE", ex.message ?: "Username change unavailable.")
            }
        }
        val before = user.toAuditMap()
        user.externalSubjectId = username
        user.fullName = request.fullName?.trim()?.takeIf { it.isNotEmpty() }
        user.workEmail = email
        user.department = request.department?.trim()?.takeIf { it.isNotEmpty() }
        user.position = request.position?.trim()?.takeIf { it.isNotEmpty() }
        users.saveAndFlush(user)
        audit.record(AuditEntry(AuditAction.UPDATE, ENTITY, id, details = AuditDiff.changes(before, user.toAuditMap())))
        return user.toDetail()
    }

    @Transactional
    fun create(request: UserCreateRequest): UserDetailDto {
        accessPolicy.requireGlobal(Permissions.ADMIN_USER_WRITE, ENTITY)
        val subject = request.externalSubjectId?.trim()?.takeIf { it.isNotEmpty() }
        val email = request.workEmail?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (subject != null && (users.existsByExternalSubjectId(subject) || externalAuth.isReservedUsername(subject))) {
            throw ConflictException("DUPLICATE_SUBJECT", "A user with this authentication subject already exists.",
                listOf(FieldError("externalSubjectId", "duplicate", null)))
        }
        if (email != null && users.existsByWorkEmailIgnoreCase(email)) {
            throw ConflictException("DUPLICATE_EMAIL", "A user with this work email already exists.",
                listOf(FieldError("workEmail", "duplicate", null)))
        }
        val user = UserEntity(subject, request.fullName?.trim()?.takeIf { it.isNotEmpty() }, email,
            request.department?.trim()?.takeIf { it.isNotEmpty() }, request.position?.trim()?.takeIf { it.isNotEmpty() })
        if (subject == null) user.status = UserStatus.DEACTIVATED
        users.saveAndFlush(user)
        audit.record(AuditEntry(AuditAction.CREATE, ENTITY, user.id, details = user.toAuditMap()))
        return user.toDetail()
    }

    @Transactional
    fun update(id: UUID, expectedVersion: Long, request: UserUpdateRequest): UserDetailDto {
        accessPolicy.requireGlobal(Permissions.ADMIN_USER_WRITE, ENTITY, id)
        val user = loadExisting(id)
        Versioning.checkCurrent(expectedVersion, user.version)
        val email = request.workEmail?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (email != null && users.existsByWorkEmailIgnoreCaseAndIdNot(email, id)) {
            throw ConflictException("DUPLICATE_EMAIL", "A user with this work email already exists.",
                listOf(FieldError("workEmail", "duplicate", null)))
        }
        val before = user.toAuditMap()
        if (request.externalSubjectId != null) {
            val subject = request.externalSubjectId.trim().takeIf { it.isNotEmpty() }
            if (user.externalSubjectId != null && user.externalSubjectId != subject) {
                throw BadRequestException("IMMUTABLE_SUBJECT", "An existing authentication subject cannot be changed.")
            }
            if (subject != null && subject != user.externalSubjectId &&
                (users.existsByExternalSubjectId(subject) || externalAuth.isReservedUsername(subject))) {
                throw ConflictException("DUPLICATE_SUBJECT", "A user with this authentication subject already exists.",
                    listOf(FieldError("externalSubjectId", "duplicate", null)))
            }
            user.externalSubjectId = subject
        }
        user.fullName = request.fullName?.trim()?.takeIf { it.isNotEmpty() }
        user.workEmail = email
        user.department = request.department?.trim()?.takeIf { it.isNotEmpty() }
        user.position = request.position?.trim()?.takeIf { it.isNotEmpty() }
        users.saveAndFlush(user)
        audit.record(AuditEntry(AuditAction.UPDATE, ENTITY, id, details = AuditDiff.changes(before, user.toAuditMap())))
        return user.toDetail()
    }

    @Transactional
    fun changeStatus(id: UUID, expectedVersion: Long, request: UserStatusRequest): UserDetailDto {
        accessPolicy.requireGlobal(Permissions.ADMIN_USER_WRITE, ENTITY, id)
        forbidSelf(id)
        if (request.status == UserStatus.DELETED) throw BadRequestException("INVALID_STATUS", "Use Delete to remove a user.")
        val user = loadExisting(id)
        Versioning.checkCurrent(expectedVersion, user.version)
        if (request.status == UserStatus.SUSPENDED && request.reason.isNullOrBlank()) {
            throw BadRequestException("REASON_REQUIRED", "A reason is required to suspend a user.",
                listOf(FieldError("reason", "required", null)))
        }
        if (request.status == UserStatus.ACTIVE && user.externalSubjectId == null) {
            throw BadRequestException("SUBJECT_REQUIRED", "Add an authentication subject before activating this user.",
                listOf(FieldError("externalSubjectId", "required", null)))
        }
        val previous = user.status
        user.status = request.status
        users.saveAndFlush(user)
        audit.record(AuditEntry(AuditAction.STATUS_CHANGE, ENTITY, id,
            details = mapOf("from" to previous.name, "to" to request.status.name, "reason" to request.reason)))
        return user.toDetail()
    }

    @Transactional
    fun setPassword(id: UUID, request: UserPasswordRequest) {
        requireSuperAdmin(id)
        val user = loadExisting(id)
        if (user.externalSubjectId == null) throw BadRequestException("SUBJECT_REQUIRED", "Add a sign-in subject before setting a password.")
        if (request.password.toByteArray(Charsets.UTF_8).size !in 8..72) {
            throw BadRequestException("INVALID_PASSWORD", "Password must be 8 to 72 bytes long.")
        }
        jdbc.sql("""
            INSERT INTO user_local_credential (user_id, password_hash) VALUES (:id, :hash)
            ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash)
        """).param("id", Uuids.toBytes(id)).param("hash", passwordEncoder.encode(request.password)).update()
        audit.record(AuditEntry(AuditAction.UPDATE, ENTITY, id, details = mapOf("localPasswordReset" to true)))
    }

    @Transactional
    fun delete(id: UUID, expectedVersion: Long) {
        requireSuperAdmin(id)
        val user = loadExisting(id)
        Versioning.checkCurrent(expectedVersion, user.version)
        val before = user.toAuditMap()
        jdbc.sql("DELETE FROM user_local_credential WHERE user_id=:id").param("id", Uuids.toBytes(id)).update()
        user.status = UserStatus.DELETED
        user.externalSubjectId = null
        user.workEmail = null
        users.saveAndFlush(user)
        audit.record(AuditEntry(AuditAction.DELETE, ENTITY, id, details = before))
    }

    @Transactional(readOnly = true)
    fun roleScopes(userId: UUID): List<RoleScopeDto> {
        accessPolicy.requireAny(Permissions.ADMIN_USER_READ)
        loadExisting(userId)
        val roleById = roles.findAll().associateBy { it.id }
        return grants.findByUserIdOrderByCreatedAt(userId).map { it.toDto(roleById.getValue(it.roleId)) }
    }

    /**
     * Replaces the user's current and future grants. Grants no longer requested are ended (validTo = yesterday),
     * never deleted. Grants already in place keep their original start date.
     */
    @Transactional
    fun replaceRoleScopes(userId: UUID, request: RoleScopesUpdateRequest): List<RoleScopeDto> {
        accessPolicy.requireGlobal(Permissions.ADMIN_USER_WRITE, ENTITY, userId)
        forbidSelf(userId)
        loadExisting(userId)
        val today = LocalDate.now(clock)
        val roleById = roles.findAll().associateBy { it.id }
        validateGrants(request.grants, roleById, today)
        if (request.grants.any { roleById[it.roleId]?.code == "SYSTEM_ADMIN" } &&
            accessPolicy.currentUser().grants.none { it.roleCode == "SYSTEM_ADMIN" }) {
            throw AccessDeniedException("Only Super Admin can assign the Super Admin role")
        }

        val existing = grants.findByUserIdOrderByCreatedAt(userId).filter { it.validTo == null || !it.validTo!!.isBefore(today) }
        val before = existing.map { it.describe(roleById) }

        for (grant in existing) {
            val requested = request.grants.firstOrNull { grant.sameScopeAs(it.roleId, it.branchId) }
            grant.validTo = if (requested == null) today.minusDays(1) else requested.validTo
        }
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val actor = accessPolicy.currentUser().id
        val added = request.grants
            .filter { req -> existing.none { it.sameScopeAs(req.roleId, req.branchId) } }
            .map { UserRoleScopeEntity(UuidV7.generate(), userId, it.roleId, it.branchId, today, it.validTo, now, actor) }
        grants.saveAll(existing + added)
        grants.flush()

        val after = (existing + added).filter { it.isEffectiveOn(today) || it.validFrom.isAfter(today) }.map { it.describe(roleById) }
        audit.record(AuditEntry(AuditAction.ROLE_SCOPES_CHANGE, ENTITY, userId, details = mapOf("before" to before, "after" to after)))
        return grants.findByUserIdOrderByCreatedAt(userId).map { it.toDto(roleById.getValue(it.roleId)) }
    }

    @Transactional(readOnly = true)
    fun permissionOverrides(userId: UUID): List<PermissionOverrideDto> {
        accessPolicy.requireAny(Permissions.ADMIN_USER_READ)
        loadExisting(userId)
        return loadPermissionOverrides(userId)
    }

    @Transactional
    fun replacePermissionOverrides(userId: UUID, request: PermissionOverridesUpdateRequest): List<PermissionOverrideDto> {
        accessPolicy.requireGlobal(Permissions.ADMIN_USER_WRITE, ENTITY, userId)
        if (accessPolicy.currentUser().grants.none { it.roleCode == "SYSTEM_ADMIN" }) {
            throw AccessDeniedException("Only Super Admin can assign module access")
        }
        forbidSelf(userId)
        loadExisting(userId)
        val codes = request.overrides.map { it.code }
        val available = permissions.findAll().mapTo(mutableSetOf()) { it.code }
        if (codes.size != codes.toSet().size || codes.any { it !in available || it.startsWith("ADMIN.") || it == Permissions.AUDIT_READ } ||
            request.overrides.any { it.allowed && it.branchIds?.isEmpty() == true ||
                it.branchIds?.any { branchId -> organisation.branch(branchId) == null } == true }) {
            throw BadRequestException("INVALID_PERMISSIONS", "Unknown, duplicate or reserved permission override.")
        }
        val before = loadPermissionOverrides(userId)
        jdbc.sql("DELETE FROM user_permission_grant WHERE user_id=:userId")
            .param("userId", Uuids.toBytes(userId)).update()
        request.overrides.filter { it.allowed }.forEach { grant ->
            (grant.branchIds?.toList() ?: listOf(null)).forEach { branchId ->
                jdbc.sql("INSERT INTO user_permission_grant (id,user_id,permission_code,branch_id) VALUES (:id,:userId,:code,:branchId)")
                    .param("id", Uuids.toBytes(UuidV7.generate())).param("userId", Uuids.toBytes(userId))
                    .param("code", grant.code).param("branchId", branchId?.let(Uuids::toBytes)).update()
            }
        }
        audit.record(AuditEntry(AuditAction.ROLE_SCOPES_CHANGE, ENTITY, userId,
            details = mapOf("permissionOverridesBefore" to before, "permissionOverridesAfter" to request.overrides)))
        return loadPermissionOverrides(userId)
    }

    private fun loadPermissionOverrides(userId: UUID): List<PermissionOverrideDto> =
        jdbc.sql("SELECT permission_code,branch_id FROM user_permission_grant WHERE user_id=:userId ORDER BY permission_code")
            .param("userId", Uuids.toBytes(userId))
            .query { rs, _ -> rs.getString(1) to Uuids.fromBytes(rs.getBytes(2)) }.list()
            .groupBy({ it.first }, { it.second })
            .map { (code, branches) -> PermissionOverrideDto(code, true,
                if (null in branches) null else branches.filterNotNull().toSet()) }

    @Transactional(readOnly = true)
    fun findActiveBySubject(subjectId: String): UserEntity? =
        users.findByExternalSubjectId(subjectId)?.takeIf { it.status == UserStatus.ACTIVE }

    @Transactional
    fun recordLogin(userId: UUID, mfaVerified: Boolean) {
        val user = load(userId)
        user.lastLoginAt = clock.instant().truncatedTo(ChronoUnit.MICROS)
        if (mfaVerified) user.mfaEnabled = true
        audit.record(AuditEntry(AuditAction.LOGIN, ENTITY, userId, details = mapOf("mfaVerified" to mfaVerified)))
    }

    @Transactional
    fun updateLanguage(userId: UUID, language: String) {
        val user = load(userId)
        if (user.language != language) {
            audit.record(AuditEntry(AuditAction.UPDATE, ENTITY, userId,
                details = AuditDiff.changes(mapOf("language" to user.language), mapOf("language" to language))))
            user.language = language
        }
    }

    @Transactional(readOnly = true)
    fun workEmail(userId: UUID): String? = load(userId).workEmail

    private fun validateGrants(requested: List<RoleScopeRequest>, roleById: Map<UUID, RoleEntity>, today: LocalDate) {
        val errors = mutableListOf<FieldError>()
        requested.forEachIndexed { i, grant ->
            val field = "grants[$i]"
            if (grant.roleId !in roleById) errors += FieldError("$field.roleId", "unknown", "Unknown role.")
            if (grant.branchId != null && organisation.branch(grant.branchId) == null) {
                errors += FieldError("$field.branchId", "unknown", "Unknown branch.")
            }
            if (grant.validTo != null && grant.validTo.isBefore(today)) {
                errors += FieldError("$field.validTo", "past", "The end date cannot be in the past.")
            }
        }
        val duplicates = requested.groupBy { it.roleId to it.branchId }.filterValues { it.size > 1 }
        if (duplicates.isNotEmpty()) errors += FieldError("grants", "duplicate", "The same role and scope is listed twice.")
        if (errors.isNotEmpty()) throw BadRequestException("INVALID_GRANTS", "Some role grants are invalid.", errors)
    }

    private fun forbidSelf(userId: UUID) {
        if (accessPolicy.currentUser().id == userId) {
            throw BusinessRuleException("SELF_MODIFICATION_FORBIDDEN", "You cannot change your own status or role grants.")
        }
    }

    private fun requireSuperAdmin(userId: UUID) {
        accessPolicy.requireGlobal(Permissions.ADMIN_USER_WRITE, ENTITY, userId)
        if (accessPolicy.currentUser().grants.none { it.roleCode == "SYSTEM_ADMIN" }) {
            throw AccessDeniedException("Only Super Admin can manage account credentials and deletion")
        }
        forbidSelf(userId)
    }

    private fun loadExisting(id: UUID) = load(id).takeUnless { it.status == UserStatus.DELETED } ?: throw NotFoundException("User")

    private fun load(id: UUID) = users.findByIdOrNull(id) ?: throw NotFoundException("User")

    private fun UserRoleScopeEntity.describe(roleById: Map<UUID, RoleEntity>) = mapOf(
        "role" to roleById[roleId]?.code, "branchId" to branchId, "validTo" to validTo,
    )

    private companion object {
        const val ENTITY = "USER"
    }
}

internal fun UserEntity.toSummary() = UserSummaryDto(id, fullName, workEmail, department, position, status, lastLoginAt)

internal fun UserEntity.toDetail() = UserDetailDto(
    id, externalSubjectId, fullName, workEmail, department, position, status, language, mfaEnabled, lastLoginAt,
    createdAt, updatedAt, version,
)

internal fun UserRoleScopeEntity.toDto(role: RoleEntity) =
    RoleScopeDto(id, roleId, role.code, role.name, branchId, validFrom, validTo)
