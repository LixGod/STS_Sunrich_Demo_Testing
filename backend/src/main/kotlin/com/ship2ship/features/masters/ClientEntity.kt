package com.ship2ship.features.masters

import com.ship2ship.core.persistence.BaseEntity
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import org.hibernate.annotations.BatchSize
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.util.UUID

enum class ClientRole { OWNER, CHARTERER, TRADER, AGENT, OTHER }

/** Customer / counterparty (SRS §4, §21): owners, charterers and other commercial parties. */
@Entity
@Table(name = "client")
class ClientEntity(
    @Column(nullable = false, updatable = false)
    var code: String,
    @Column(nullable = false)
    var legalName: String,
) : BaseEntity() {
    var taxRegistrationNo: String? = null
    var billingAddress: String? = null
    @Column(columnDefinition = "CHAR(2)")
    var countryCode: String? = null
    @Column(length = 40)
    var phone: String? = null
    @Column(columnDefinition = "CHAR(3)")
    var defaultCurrency: String? = null
    var paymentTerms: String? = null

    @Column(nullable = false)
    var active: Boolean = true

    // Loaded for a whole page at once (no per-row query) when a list is mapped to DTOs.
    @ElementCollection(fetch = FetchType.LAZY)
    @BatchSize(size = 100)
    @CollectionTable(name = "client_role", joinColumns = [JoinColumn(name = "client_id")])
    @Column(name = "role")
    @Enumerated(EnumType.STRING)
    var roles: MutableSet<ClientRole> = mutableSetOf()

    @ElementCollection(fetch = FetchType.LAZY)
    @BatchSize(size = 100)
    @CollectionTable(name = "client_email", joinColumns = [JoinColumn(name = "client_id")])
    @OrderColumn(name = "position")
    @Column(name = "email", nullable = false, length = 254)
    var emails: MutableList<String> = mutableListOf()

    @ElementCollection(fetch = FetchType.LAZY)
    @BatchSize(size = 100)
    @CollectionTable(name = "client_phone", joinColumns = [JoinColumn(name = "client_id")])
    @OrderColumn(name = "position")
    @Column(name = "phone", nullable = false, length = 40)
    var phones: MutableList<String> = mutableListOf()

    fun toAuditMap(): Map<String, Any?> = mapOf(
        "code" to code, "legalName" to legalName, "roles" to roles.map { it.name }.sorted(),
        "taxRegistrationNo" to taxRegistrationNo, "billingAddress" to billingAddress, "countryCode" to countryCode, "phone" to phone,
        "emails" to emails.toList(), "phones" to phones.toList(),
        "defaultCurrency" to defaultCurrency, "paymentTerms" to paymentTerms, "active" to active,
    )
}

@Entity
@Table(name = "client_contact")
class ClientContactEntity(
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    var clientId: UUID,
    @Column(nullable = false)
    var fullName: String,
) : BaseEntity() {
    var position: String? = null
    var email: String? = null
    var phone: String? = null

    @Column(name = "is_primary", nullable = false)
    var primary: Boolean = false

    @Column(nullable = false)
    var active: Boolean = true

    fun toAuditMap(): Map<String, Any?> = mapOf(
        "clientId" to clientId, "fullName" to fullName, "position" to position, "email" to email, "phone" to phone,
        "primary" to primary, "active" to active,
    )
}

interface ClientRepository : JpaRepository<ClientEntity, UUID>, JpaSpecificationExecutor<ClientEntity> {
    fun existsByCode(code: String): Boolean
}

interface ClientContactRepository : JpaRepository<ClientContactEntity, UUID> {
    fun findByClientIdOrderByPrimaryDescFullNameAsc(clientId: UUID): List<ClientContactEntity>
}
