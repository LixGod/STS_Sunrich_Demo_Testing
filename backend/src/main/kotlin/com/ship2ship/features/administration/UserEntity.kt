package com.ship2ship.features.administration

import com.ship2ship.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant

enum class UserStatus { ACTIVE, SUSPENDED, DEACTIVATED, DELETED }

/** A Ship2Ship login account (SRS FR-UM-03). Deleted accounts retain their history. */
@Entity
@Table(name = "app_user")
class UserEntity(
    @Column
    var externalSubjectId: String?,
    @Column
    var fullName: String?,
    @Column
    var workEmail: String?,
    var department: String? = null,
    var position: String? = null,
) : BaseEntity() {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: UserStatus = UserStatus.ACTIVE

    @Column(nullable = false)
    var language: String = "en"

    @Column(nullable = false)
    var mfaEnabled: Boolean = false

    var lastLoginAt: Instant? = null

    fun toAuditMap(): Map<String, Any?> = mapOf(
        "externalSubjectId" to externalSubjectId,
        "fullName" to fullName,
        "workEmail" to workEmail,
        "department" to department,
        "position" to position,
        "status" to status.name,
        "language" to language,
    )
}
