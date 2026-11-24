package com.ship2ship.features.administration

import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class UserSummaryDto(
    val id: UUID,
    val fullName: String?,
    val workEmail: String?,
    val department: String?,
    val position: String?,
    val status: UserStatus,
    val lastLoginAt: Instant?,
)

data class UserDetailDto(
    val id: UUID,
    val externalSubjectId: String?,
    val fullName: String?,
    val workEmail: String?,
    val department: String?,
    val position: String?,
    val status: UserStatus,
    val language: String,
    val mfaEnabled: Boolean,
    val lastLoginAt: Instant?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val version: Long?,
)

data class UserCreateRequest(
    @field:Size(max = 128) val externalSubjectId: String? = null,
    @field:Size(max = 150) val fullName: String? = null,
    @field:Email @field:Size(max = 254) val workEmail: String? = null,
    @field:Size(max = 80) val department: String? = null,
    @field:Size(max = 80) val position: String? = null,
)

data class UserUpdateRequest(
    @field:Size(max = 128) val externalSubjectId: String? = null,
    @field:Size(max = 150) val fullName: String? = null,
    @field:Email @field:Size(max = 254) val workEmail: String? = null,
    @field:Size(max = 80) val department: String? = null,
    @field:Size(max = 80) val position: String? = null,
)

data class OwnProfileUpdateRequest(
    @field:NotBlank @field:Size(max = 128) val username: String,
    @field:Size(max = 150) val fullName: String? = null,
    @field:Email @field:Size(max = 254) val workEmail: String? = null,
    @field:Size(max = 80) val department: String? = null,
    @field:Size(max = 80) val position: String? = null,
)

data class UserStatusRequest(
    @field:NotNull val status: UserStatus,
    @field:Size(max = 500) val reason: String? = null,
)

data class UserPasswordRequest(
    @field:NotBlank val password: String,
)

data class RoleScopeDto(
    val id: UUID,
    val roleId: UUID,
    val roleCode: String,
    val roleName: String,
    val branchId: UUID?,
    val validFrom: LocalDate,
    val validTo: LocalDate?,
)

data class RoleScopeRequest(
    @field:NotNull val roleId: UUID,
    val branchId: UUID? = null,
    val validTo: LocalDate? = null,
)

data class RoleScopesUpdateRequest(
    @field:Valid @field:Size(max = 50) val grants: List<RoleScopeRequest>,
)

data class PermissionOverrideDto(val code: String, val allowed: Boolean, val branchIds: Set<UUID>? = null)

data class PermissionOverridesUpdateRequest(
    @field:Valid @field:Size(max = 250) val overrides: List<PermissionOverrideDto>,
)

data class RoleDto(
    val id: UUID,
    val code: String,
    val name: String,
    val description: String?,
    val permissions: List<String>,
    val version: Long,
)

data class RoleCreateRequest(
    @field:NotBlank @field:Pattern(regexp = "^[A-Z][A-Z0-9_]{1,39}$") val code: String,
    @field:NotBlank @field:Size(max = 100) val name: String,
    @field:Size(max = 500) val description: String? = null,
    @field:Size(max = 100) val permissions: Set<String> = emptySet(),
)

data class RoleUpdateRequest(
    @field:NotBlank @field:Size(max = 100) val name: String,
    @field:Size(max = 500) val description: String? = null,
    @field:Size(max = 100) val permissions: Set<String> = emptySet(),
)

data class PermissionDto(val code: String, val module: String, val description: String)

data class GrantDto(val roleCode: String, val branchId: UUID?)

data class MeDto(
    val id: UUID,
    val fullName: String,
    val workEmail: String?,
    val language: String,
    val permissions: Set<String>,
    val grants: List<GrantDto>,
    val directPermissions: Set<String>,
    val globalPermissions: Set<String>,
)

data class PreferencesRequest(
    @field:NotBlank @field:Pattern(regexp = "^[a-z]{2}(-[A-Z]{2})?$") val language: String,
)

data class LoginRequest(
    @field:NotBlank @field:Size(max = 150) val username: String,
    @field:NotBlank @field:Size(max = 200) val password: String,
)
