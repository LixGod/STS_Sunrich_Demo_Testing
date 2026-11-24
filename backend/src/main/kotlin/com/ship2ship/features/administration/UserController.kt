package com.ship2ship.features.administration

import com.ship2ship.core.web.PageResponse
import com.ship2ship.core.web.Paging
import com.ship2ship.core.web.Versioning
import jakarta.validation.Valid
import org.springframework.data.domain.Sort
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin")
class UserController(
    private val userService: UserService,
    private val roleService: RoleService,
    private val paging: Paging,
) {

    @GetMapping("/users")
    fun list(
        @RequestParam page: Int?,
        @RequestParam size: Int?,
        @RequestParam sort: String?,
        @RequestParam q: String?,
        @RequestParam status: UserStatus?,
    ): PageResponse<UserSummaryDto> =
        userService.list(q, status, paging.pageable(page, size, sort, USER_SORTS, Sort.by("fullName")))

    @PostMapping("/users")
    fun create(@Valid @RequestBody request: UserCreateRequest): ResponseEntity<UserDetailDto> {
        val created = userService.create(request)
        val location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id).toUri()
        return ResponseEntity.created(location).eTag(Versioning.etag(created.version)).body(created)
    }

    @GetMapping("/users/{id}")
    fun get(@PathVariable id: UUID): ResponseEntity<UserDetailDto> =
        userService.get(id).let { Versioning.ok(it, it.version) }

    @PutMapping("/users/{id}")
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: UserUpdateRequest,
    ): ResponseEntity<UserDetailDto> =
        userService.update(id, Versioning.requireIfMatch(ifMatch), request).let { Versioning.ok(it, it.version) }

    @PatchMapping("/users/{id}/status")
    fun changeStatus(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: UserStatusRequest,
    ): ResponseEntity<UserDetailDto> =
        userService.changeStatus(id, Versioning.requireIfMatch(ifMatch), request).let { Versioning.ok(it, it.version) }

    @PutMapping("/users/{id}/password")
    fun setPassword(@PathVariable id: UUID, @Valid @RequestBody request: UserPasswordRequest): ResponseEntity<Void> {
        userService.setPassword(id, request)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/users/{id}")
    fun delete(@PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?): ResponseEntity<Void> {
        userService.delete(id, Versioning.requireIfMatch(ifMatch))
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/users/{id}/role-scopes")
    fun roleScopes(@PathVariable id: UUID): List<RoleScopeDto> = userService.roleScopes(id)

    @PutMapping("/users/{id}/role-scopes")
    fun replaceRoleScopes(@PathVariable id: UUID, @Valid @RequestBody request: RoleScopesUpdateRequest): List<RoleScopeDto> =
        userService.replaceRoleScopes(id, request)

    @GetMapping("/users/{id}/permission-overrides")
    fun permissionOverrides(@PathVariable id: UUID): List<PermissionOverrideDto> = userService.permissionOverrides(id)

    @PutMapping("/users/{id}/permission-overrides")
    fun replacePermissionOverrides(@PathVariable id: UUID, @Valid @RequestBody request: PermissionOverridesUpdateRequest): List<PermissionOverrideDto> =
        userService.replacePermissionOverrides(id, request)

    @GetMapping("/roles")
    fun roles(): List<RoleDto> = roleService.roles()

    @PostMapping("/roles")
    fun createRole(@Valid @RequestBody request: RoleCreateRequest): ResponseEntity<RoleDto> =
        roleService.create(request).let { ResponseEntity.status(201).eTag(Versioning.etag(it.version)).body(it) }

    @PutMapping("/roles/{id}")
    fun updateRole(@PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
                   @Valid @RequestBody request: RoleUpdateRequest): ResponseEntity<RoleDto> =
        roleService.update(id, Versioning.requireIfMatch(ifMatch), request).let { Versioning.ok(it, it.version) }

    @GetMapping("/permissions")
    fun permissions(): List<PermissionDto> = roleService.permissions()

    private companion object {
        val USER_SORTS = mapOf(
            "fullName" to "fullName", "workEmail" to "workEmail", "status" to "status", "lastLoginAt" to "lastLoginAt",
        )
    }
}
