package com.ship2ship.features.administration

import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.ConflictException
import com.ship2ship.core.web.NotFoundException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class RoleService(
    private val roles: RoleRepository,
    private val permissions: PermissionRepository,
    private val accessPolicy: AccessPolicy,
    private val jdbc: JdbcClient,
    private val audit: AuditService,
) {

    @Transactional(readOnly = true)
    fun roles(): List<RoleDto> {
        accessPolicy.requireAny(Permissions.ADMIN_ROLE_READ)
        return roles.findAllWithPermissions().map(::dto)
    }

    @Transactional(readOnly = true)
    fun permissions(): List<PermissionDto> {
        accessPolicy.requireAny(Permissions.ADMIN_ROLE_READ)
        return permissions.findAll().sortedBy { it.code }.map { PermissionDto(it.code, it.module, it.description) }
    }

    @Transactional
    fun create(request: RoleCreateRequest): RoleDto {
        accessPolicy.requireGlobal(Permissions.ADMIN_ROLE_WRITE, "ROLE")
        if (roles.findByCode(request.code) != null) throw ConflictException("DUPLICATE_ROLE", "Role code already exists.")
        validatePermissions(request.permissions)
        val id = UuidV7.generate()
        jdbc.sql("INSERT INTO role (id, code, name, description, version) VALUES (:id, :code, :name, :description, 0)")
            .param("id", Uuids.toBytes(id)).param("code", request.code).param("name", request.name.trim())
            .param("description", request.description?.trim()).update()
        replacePermissions(id, request.permissions)
        audit.record(AuditEntry(AuditAction.CREATE, "ROLE", id, details = mapOf("code" to request.code,
            "permissions" to request.permissions.sorted())))
        return RoleDto(id, request.code, request.name.trim(), request.description?.trim(), request.permissions.sorted(), 0)
    }

    @Transactional
    fun update(id: UUID, expectedVersion: Long, request: RoleUpdateRequest): RoleDto {
        accessPolicy.requireGlobal(Permissions.ADMIN_ROLE_WRITE, "ROLE", id)
        val role = roles.findByIdOrNull(id) ?: throw NotFoundException("Role")
        if (role.code == "SYSTEM_ADMIN") throw org.springframework.security.access.AccessDeniedException("The built-in administrator role cannot be changed")
        validatePermissions(request.permissions)
        val before = mapOf("name" to role.name, "description" to role.description, "permissions" to role.permissionCodes.sorted())
        val changed = jdbc.sql("UPDATE role SET name=:name, description=:description, version=version+1 WHERE id=:id AND version=:version")
            .param("name", request.name.trim()).param("description", request.description?.trim())
            .param("id", Uuids.toBytes(id)).param("version", expectedVersion).update()
        if (changed == 0) throw ConflictException("STALE_VERSION", "The role changed. Reload and try again.")
        replacePermissions(id, request.permissions)
        audit.record(AuditEntry(AuditAction.UPDATE, "ROLE", id, details = mapOf("before" to before,
            "after" to mapOf("name" to request.name.trim(), "description" to request.description?.trim(),
                "permissions" to request.permissions.sorted()))))
        return RoleDto(id, role.code, request.name.trim(), request.description?.trim(), request.permissions.sorted(), expectedVersion + 1)
    }

    private fun validatePermissions(codes: Set<String>) {
        val valid = permissions.findAll().mapTo(mutableSetOf()) { it.code }
        if (!valid.containsAll(codes)) throw BadRequestException("UNKNOWN_PERMISSION", "A permission is not in the catalogue.")
    }

    private fun replacePermissions(id: UUID, codes: Set<String>) {
        jdbc.sql("DELETE FROM role_permission WHERE role_id=:id").param("id", Uuids.toBytes(id)).update()
        for (code in codes) {
            jdbc.sql("INSERT INTO role_permission (role_id, permission_code) VALUES (:id, :code)")
                .param("id", Uuids.toBytes(id)).param("code", code).update()
        }
    }

    private fun dto(role: RoleEntity) = RoleDto(role.id, role.code, role.name, role.description, role.permissionCodes.sorted(), role.version)
}
