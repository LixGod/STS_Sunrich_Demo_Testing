package com.ship2ship.features.administration

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import jakarta.persistence.Version
import org.springframework.data.domain.Persistable
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Built-in and admin-managed roles with permissions from the catalogue. */
@Entity
@Table(name = "role")
class RoleEntity(
    @Id
    @Column(columnDefinition = "BINARY(16)")
    val id: UUID,
    val code: String,
    val name: String,
    val description: String?,
    @ElementCollection
    @CollectionTable(name = "role_permission", joinColumns = [JoinColumn(name = "role_id")])
    @Column(name = "permission_code")
    val permissionCodes: Set<String> = emptySet(),
) {
    @Version
    var version: Long = 0
}

@Entity
@Table(name = "permission")
class PermissionEntity(
    @Id
    val code: String,
    val module: String,
    val description: String,
)

/**
 * A role granted to a user within a scope (null entity = all; null branch = whole entity) for a validity period.
 * Grants are ended by setting [validTo], never deleted, so the history of who could do what is preserved.
 */
@Entity
@Table(name = "user_role_scope")
class UserRoleScopeEntity(
    @Id
    @Column(columnDefinition = "BINARY(16)")
    private val id: UUID,
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    val userId: UUID,
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    val roleId: UUID,
    @Column(updatable = false, columnDefinition = "BINARY(16)")
    val branchId: UUID?,
    @Column(nullable = false, updatable = false)
    val validFrom: LocalDate,
    var validTo: LocalDate?,
    @Column(nullable = false, updatable = false)
    val createdAt: Instant,
    @Column(updatable = false, columnDefinition = "BINARY(16)")
    val createdBy: UUID?,
) : Persistable<UUID> {

    @Transient
    private var isNewEntity = true

    override fun getId(): UUID = id

    override fun isNew(): Boolean = isNewEntity

    @PostLoad
    @PostPersist
    fun markNotNew() {
        isNewEntity = false
    }

    fun isEffectiveOn(date: LocalDate): Boolean = !validFrom.isAfter(date) && (validTo == null || !validTo!!.isBefore(date))

    fun sameScopeAs(roleId: UUID, branchId: UUID?) = this.roleId == roleId && this.branchId == branchId
}
