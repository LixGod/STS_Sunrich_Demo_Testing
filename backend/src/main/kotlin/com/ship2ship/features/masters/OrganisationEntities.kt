package com.ship2ship.features.masters

import com.ship2ship.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "branch")
class BranchEntity(
    @Column(nullable = false, updatable = false)
    var code: String,
    @Column(nullable = false)
    var name: String,
    @Column(nullable = false)
    var timezone: String,
    var address: String?,
) : BaseEntity() {

    @Column(nullable = false)
    var active: Boolean = true

    fun toAuditMap(): Map<String, Any?> = mapOf(
        "code" to code, "name" to name, "timezone" to timezone,
        "address" to address, "active" to active,
    )
}
