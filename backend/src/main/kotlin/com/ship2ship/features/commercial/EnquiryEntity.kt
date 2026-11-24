package com.ship2ship.features.commercial

import com.ship2ship.core.persistence.BaseEntity
import com.ship2ship.core.persistence.TransitionRules
import com.ship2ship.core.persistence.UuidV7
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.CollectionTable
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import org.hibernate.annotations.BatchSize
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Enquiry lifecycle (SRS §6). Quotation states are driven by the quotation workflow, not set by hand. */
enum class EnquiryStatus { DRAFT, COSTING, QUOTATION_APPROVAL, QUOTED, ACCEPTED, LOST, CANCELLED }

enum class VesselRole { MOTHER, DAUGHTER }

object EnquiryLifecycle {
    val OPEN = setOf(EnquiryStatus.DRAFT, EnquiryStatus.COSTING, EnquiryStatus.QUOTATION_APPROVAL, EnquiryStatus.QUOTED)
    val EDITABLE = setOf(EnquiryStatus.DRAFT, EnquiryStatus.COSTING, EnquiryStatus.QUOTATION_APPROVAL,
        EnquiryStatus.QUOTED, EnquiryStatus.ACCEPTED)

    /** Moves a user may make directly (POST /enquiries/{id}/transitions). */
    val RULES = TransitionRules(
        allowed = mapOf(
            EnquiryStatus.DRAFT to setOf(EnquiryStatus.COSTING, EnquiryStatus.LOST, EnquiryStatus.CANCELLED),
            EnquiryStatus.COSTING to setOf(EnquiryStatus.LOST, EnquiryStatus.CANCELLED),
            EnquiryStatus.QUOTATION_APPROVAL to setOf(EnquiryStatus.LOST, EnquiryStatus.CANCELLED),
            EnquiryStatus.QUOTED to setOf(EnquiryStatus.LOST, EnquiryStatus.CANCELLED),
        ),
        reasonRequired = setOf(EnquiryStatus.LOST, EnquiryStatus.CANCELLED),
    )

    /** Moves made by the estimate and quotation workflow (issue, accept). */
    val WORKFLOW = TransitionRules(
        allowed = mapOf(
            EnquiryStatus.DRAFT to setOf(EnquiryStatus.COSTING),
            EnquiryStatus.COSTING to setOf(EnquiryStatus.QUOTED, EnquiryStatus.QUOTATION_APPROVAL),
            EnquiryStatus.QUOTATION_APPROVAL to setOf(EnquiryStatus.COSTING, EnquiryStatus.QUOTED),
            EnquiryStatus.QUOTED to setOf(EnquiryStatus.QUOTATION_APPROVAL, EnquiryStatus.ACCEPTED),
        ),
    )
}

@Entity
@Table(name = "enquiry")
class EnquiryEntity(
    @Column(nullable = false, updatable = false) var reference: String,
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)") var branchId: UUID,
    var receivedAt: Instant? = null,
    @Column(columnDefinition = "BINARY(16)") var clientId: UUID? = null,
) : BaseEntity() {

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    var status: EnquiryStatus = EnquiryStatus.DRAFT

    @Column(columnDefinition = "BINARY(16)") var clientContactId: UUID? = null
    @Column(length = 20) var operationType: String? = null
    @ElementCollection
    @CollectionTable(name = "enquiry_cargo_type", joinColumns = [JoinColumn(name = "enquiry_id")])
    @Column(name = "cargo_type", length = 20, nullable = false)
    var cargoTypes: MutableSet<String> = mutableSetOf()
    @Column(name = "expected_duration_hrs", precision = 6, scale = 1) var expectedDurationHours: BigDecimal? = null
    @Column(columnDefinition = "TEXT") var scopeOfServices: String? = null
    @Column(columnDefinition = "TEXT") var specialRequirements: String? = null
    var sourceReference: String? = null

    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true)
    @JoinColumn(name = "enquiry_id", nullable = false, updatable = false)
    @BatchSize(size = 100)
    var vessels: MutableList<EnquiryVesselEntity> = mutableListOf()

    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true)
    @JoinColumn(name = "enquiry_id", nullable = false, updatable = false)
    @OrderBy("lineNo")
    @BatchSize(size = 100)
    var cargo: MutableList<EnquiryCargoEntity> = mutableListOf()

    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true)
    @JoinColumn(name = "enquiry_id", nullable = false, updatable = false)
    @OrderBy("lineNo")
    @BatchSize(size = 100)
    var specialItems: MutableList<EnquirySpecialRequirementEntity> = mutableListOf()

    fun vessel(role: VesselRole): EnquiryVesselEntity? = vessels.firstOrNull { it.role == role }

    fun toAuditMap(): Map<String, Any?> = mapOf(
        "status" to status.name, "receivedAt" to receivedAt?.toString(), "clientId" to clientId, "clientContactId" to clientContactId,
        "operationType" to operationType, "cargoTypes" to cargoTypes.sorted(),
        "expectedDurationHours" to expectedDurationHours?.toPlainString(), "scopeOfServices" to scopeOfServices,
        "specialRequirements" to specialRequirements, "sourceReference" to sourceReference,
        "vessels" to vessels.sortedBy { it.role }.map { "${it.role}:${it.vesselId}:${it.etaUtc ?: "-"}" },
        "cargo" to cargo.sortedBy { it.lineNo }.map { "${it.cargoGradeId}:${it.quantity.toPlainString()}:${it.unitId}" },
        "specialItems" to specialItems.sortedBy { it.lineNo }.map { "${it.description}:${it.paid}:${it.salePriceUsd}" },
    )
}

@Entity
@Table(name = "enquiry_special_requirement")
class EnquirySpecialRequirementEntity(
    @Column(nullable = false) var lineNo: Int,
    @Column(nullable = false, length = 300) var description: String,
    @Column(nullable = false) var paid: Boolean,
    @Column(precision = 19, scale = 4) var salePriceUsd: BigDecimal?,
) {
    @Id
    @Column(columnDefinition = "BINARY(16)")
    var id: UUID = UuidV7.generate()
}

@Entity
@Table(name = "enquiry_vessel")
class EnquiryVesselEntity(
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) var role: VesselRole,
    @Column(nullable = false, columnDefinition = "BINARY(16)") var vesselId: UUID,
    var etaUtc: Instant?,
    var etaTimezone: String?,
) {
    @Id
    @Column(columnDefinition = "BINARY(16)")
    var id: UUID = UuidV7.generate()

    /** Read-only view of the owning enquiry, for queries; the association is written through the enquiry. */
    @Column(name = "enquiry_id", insertable = false, updatable = false, columnDefinition = "BINARY(16)")
    var enquiryId: UUID? = null
}

@Entity
@Table(name = "enquiry_cargo")
class EnquiryCargoEntity(
    @Column(nullable = false) var lineNo: Int,
    @Column(nullable = false, columnDefinition = "BINARY(16)") var cargoGradeId: UUID,
    @Column(nullable = false, precision = 18, scale = 3) var quantity: BigDecimal,
    @Column(nullable = false, columnDefinition = "BINARY(16)") var unitId: UUID,
) {
    @Id
    @Column(columnDefinition = "BINARY(16)")
    var id: UUID = UuidV7.generate()
}

interface EnquiryRepository : JpaRepository<EnquiryEntity, UUID>, JpaSpecificationExecutor<EnquiryEntity> {

    /** Open enquiries for the same two vessels (in either role), for the duplicate warning (FR-J01). */
    @Query(
        """
        select distinct e from EnquiryEntity e join e.vessels a join e.vessels b
        where a.vesselId = :first and b.vesselId = :second and a.id <> b.id
          and e.status in :statuses and (:excludeId is null or e.id <> :excludeId)
        order by e.receivedAt desc
        """,
    )
    fun findOpenForVesselPair(first: UUID, second: UUID, statuses: Collection<EnquiryStatus>, excludeId: UUID?): List<EnquiryEntity>
}
