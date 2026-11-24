package com.ship2ship.features.technical

import com.ship2ship.core.persistence.BaseEntity
import com.ship2ship.core.persistence.UuidV7
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.hibernate.annotations.BatchSize
import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Vessel particulars lifecycle (FR-TECH-01, AR-03): entered by hand from the source documents (no AI extraction, K1),
 * submitted, then verified by a different person or returned with a reason. Only VERIFIED values may be relied on;
 * a newer verified version supersedes the older one.
 */
enum class ParticularsStatus { DRAFT, SUBMITTED, VERIFIED, SUPERSEDED }

enum class ParticularsAction { EDIT, SUBMIT, VERIFY, RETURN }

/** One entry of the configurable particulars catalogue (`particular_field`). */
@Entity
@Table(name = "particular_field")
class ParticularFieldEntity(
    @Id @Column(nullable = false, updatable = false) var code: String,
    @Column(nullable = false) var label: String,
    @Column(name = "field_group", nullable = false) var fieldGroup: String,
    @Column(nullable = false) var dataType: String,
    var unit: String?,
    @Column(nullable = false) var requiredForPlanning: Boolean,
    @Column(nullable = false) var sortOrder: Int,
    @Column(nullable = false) var active: Boolean,
)

interface ParticularFieldRepository : JpaRepository<ParticularFieldEntity, String> {
    fun findByActiveTrueOrderBySortOrder(): List<ParticularFieldEntity>
}

@Entity
@Table(name = "vessel_particulars_version")
class ParticularsVersionEntity(
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)") var vesselId: UUID,
    @Column(nullable = false, updatable = false) var versionNo: Int,
) : BaseEntity() {

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    var status: ParticularsStatus = ParticularsStatus.DRAFT

    var notes: String? = null
    @Column(columnDefinition = "BINARY(16)") var submittedBy: UUID? = null
    var submittedAt: Instant? = null
    @Column(columnDefinition = "BINARY(16)") var verifiedBy: UUID? = null
    var verifiedAt: Instant? = null
    var returnedReason: String? = null

    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true)
    @JoinColumn(name = "version_id", nullable = false, updatable = false)
    @BatchSize(size = 100)
    var values: MutableList<ParticularValueEntity> = mutableListOf()

    fun value(code: String): ParticularValueEntity? = values.firstOrNull { it.fieldCode == code }

    fun toAuditMap(): Map<String, Any?> = mapOf(
        "status" to status.name, "notes" to notes,
        "values" to values.sortedBy { it.fieldCode }.map { "${it.fieldCode}=${it.valueText} [doc ${it.sourceDocumentId ?: "-"} p.${it.sourcePage ?: "-"}]" },
    )
}

@Entity
@Table(name = "vessel_particular_value")
class ParticularValueEntity(
    @Column(nullable = false, updatable = false) var fieldCode: String,
    @Column(nullable = false) var valueText: String,
    @Column(precision = 12, scale = 3) var valueNumber: BigDecimal?,
    @Column(columnDefinition = "BINARY(16)") var sourceDocumentId: UUID?,
    var sourcePage: Int?,
) {
    @Id
    @Column(columnDefinition = "BINARY(16)")
    var id: UUID = UuidV7.generate()

    @Column(nullable = false)
    var verified: Boolean = false
}

interface ParticularsVersionRepository : JpaRepository<ParticularsVersionEntity, UUID> {
    fun findByVesselIdOrderByVersionNoDesc(vesselId: UUID): List<ParticularsVersionEntity>
}

// ---------- API ----------

data class ParticularFieldDto(
    val code: String, val label: String, val group: String, val dataType: String, val unit: String?, val requiredForPlanning: Boolean,
)

data class ParticularValueRequest(
    @field:NotBlank @field:Size(max = 40) val fieldCode: String,
    @field:NotBlank @field:Size(max = 100) val value: String,
    val sourceDocumentId: UUID? = null,
    @field:Min(1) @field:Max(9999) val sourcePage: Int? = null,
)

data class ParticularsRequest(
    @field:Size(max = 1000) val notes: String? = null,
    @field:Valid @field:Size(max = 100) val values: List<ParticularValueRequest> = emptyList(),
)

data class ReturnRequest(@field:NotBlank @field:Size(max = 500) val reason: String)

/** The reviewer lists every value checked against its source; all entered values must be confirmed. */
data class VerifyRequest(@field:NotNull val confirmedFieldCodes: List<String>)

data class ParticularValueDto(
    val fieldCode: String, val value: String, val sourceDocumentId: UUID?, val sourcePage: Int?, val verified: Boolean,
)

/** A data check (never a safety approval): e.g. bow and stern to manifold should add up to LOA. */
data class ParticularsWarningDto(val code: String, val fields: List<String>, val message: String)

data class ParticularsDto(
    val id: UUID,
    val vesselId: UUID,
    val versionNo: Int,
    val status: ParticularsStatus,
    val notes: String?,
    val values: List<ParticularValueDto>,
    val warnings: List<ParticularsWarningDto>,
    val submittedByName: String?,
    val submittedAt: Instant?,
    val verifiedByName: String?,
    val verifiedAt: Instant?,
    val returnedReason: String?,
    val allowedActions: List<ParticularsAction>,
    val updatedAt: Instant?,
    val version: Long?,
)

data class ParticularsSummaryDto(val id: UUID, val versionNo: Int, val status: ParticularsStatus, val verifiedAt: Instant?, val updatedAt: Instant?)

data class ParticularsOverviewDto(
    val vesselId: UUID,
    /** Newest first. */
    val versions: List<ParticularsSummaryDto>,
    val canCreateVersion: Boolean,
)
