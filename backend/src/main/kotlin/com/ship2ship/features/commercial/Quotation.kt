package com.ship2ship.features.commercial

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
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.hibernate.annotations.BatchSize
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Quotation version lifecycle (FR-J03): prepared from an estimate, approved by someone other than the submitter,
 * issued to the client (PDF stored), then accepted by the owner, which creates the job. A newer issued version
 * supersedes the previous one.
 */
enum class QuotationStatus { DRAFT, PENDING_APPROVAL, APPROVED, REJECTED, ISSUED, SUPERSEDED, ACCEPTED, OWNER_REJECTED }

enum class IssueChannel { EMAIL, OTHER }

/** Actions the current user may take on a quotation version (drives the buttons shown). */
enum class QuotationAction { EDIT, SUBMIT, APPROVE, REJECT, ISSUE, ACCEPT, OWNER_REJECT, RECORD_ADVANCE }

enum class AdvancePaymentStatus { UNPAID, PARTIAL, PAID }

@Entity
@Table(name = "quotation")
class QuotationEntity(
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)") var enquiryId: UUID,
    @Column(nullable = false, updatable = false) var reference: String,
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)") var branchId: UUID,
) : BaseEntity()

@Entity
@Table(name = "quotation_version")
class QuotationVersionEntity(
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)") var quotationId: UUID,
    @Column(nullable = false, updatable = false) var versionNo: Int,
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)") var estimateId: UUID,
    @Column(nullable = false, updatable = false, columnDefinition = "CHAR(3)") var currency: String,
    @Column(nullable = false, precision = 19, scale = 4) var totalAmount: BigDecimal,
    @Column(nullable = false) var validUntil: LocalDate,
) : BaseEntity() {

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    var status: QuotationStatus = QuotationStatus.DRAFT

    var paymentTerms: String? = null
    @Column(name = "advance_received", precision = 19, scale = 4) var advanceReceived: BigDecimal? = null
    @Column(name = "advance_given", precision = 19, scale = 4) var advanceGiven: BigDecimal? = null
    @Column(columnDefinition = "TEXT") var termsText: String? = null
    @Column(name = "scope_of_services", columnDefinition = "TEXT") var scopeOfServices: String? = null
    @Column(columnDefinition = "BINARY(16)") var tariffId: UUID? = null

    @Column(columnDefinition = "BINARY(16)") var submittedBy: UUID? = null
    var submittedAt: Instant? = null
    @Column(columnDefinition = "BINARY(16)") var decidedBy: UUID? = null
    var decidedAt: Instant? = null
    var decisionReason: String? = null
    @Column(columnDefinition = "BINARY(16)") var issuedBy: UUID? = null
    var issuedAt: Instant? = null
    var issuedTo: String? = null
    @Enumerated(EnumType.STRING) var issueChannel: IssueChannel? = null
    var issueNote: String? = null
    /** Hash of the issued PDF; the bytes are stored in `quotation_version.pdf` (read and written separately). */
    @Column(name = "pdf_sha256", columnDefinition = "CHAR(64)") var pdfSha256: String? = null
    @Column(name = "inr_fx_rate", precision = 19, scale = 8) var inrFxRate: BigDecimal? = null
    @Column(name = "inr_fx_rate_date") var inrFxRateDate: LocalDate? = null

    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true)
    @JoinColumn(name = "quotation_version_id", nullable = false, updatable = false)
    @OrderBy("lineNo")
    @BatchSize(size = 100)
    var lines: MutableList<QuotationLineEntity> = mutableListOf()

    fun toAuditMap(): Map<String, Any?> = mapOf(
        "status" to status.name, "validUntil" to validUntil.toString(), "paymentTerms" to paymentTerms, "termsText" to termsText,
        "scopeOfServices" to scopeOfServices,
        "tariffId" to tariffId, "inrFxRate" to inrFxRate?.toPlainString(),
        "totalAmount" to totalAmount.toPlainString(), "currency" to currency,
        "advanceReceived" to advanceReceived, "advanceGiven" to advanceGiven,
    )
}

@Entity
@Table(name = "quotation_line")
class QuotationLineEntity(
    @Column(nullable = false, updatable = false) var lineNo: Int,
    @Column(nullable = false, updatable = false) var costHeadCode: String,
    @Column(nullable = false, updatable = false) var costHeadName: String,
    @Column(updatable = false) var description: String?,
    @Column(nullable = false, updatable = false, precision = 18, scale = 3) var quantity: BigDecimal,
    @Column(nullable = false, updatable = false, precision = 19, scale = 4) var unitPrice: BigDecimal,
    @Column(nullable = false, updatable = false, precision = 19, scale = 4) var amount: BigDecimal,
    @Column(nullable = false) var extraHours: Boolean = false,
) {
    @Id
    @Column(columnDefinition = "BINARY(16)")
    var id: UUID = UuidV7.generate()
}

/** The owner's acceptance of an issued quotation version; created together with the job it produced. */
@Entity
@Table(name = "owner_acceptance")
class OwnerAcceptanceEntity(
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)") var quotationVersionId: UUID,
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)") var jobId: UUID,
    @Column(nullable = false, updatable = false) var acceptedAt: Instant,
    @Column(name = "accepted_by_name", nullable = false, updatable = false) var acceptedByName: String,
    @Column(updatable = false) var workOrderReference: String?,
    @Column(updatable = false) var paymentTerms: String?,
    @Column(columnDefinition = "TEXT", updatable = false) var specialConditions: String?,
    @Column(nullable = false, updatable = false) var afterValidity: Boolean,
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)") var recordedBy: UUID,
    @Column(nullable = false, updatable = false) var recordedAt: Instant,
) {
    @Id
    @Column(columnDefinition = "BINARY(16)")
    var id: UUID = UuidV7.generate()
}

/** An advance payment recorded against an accepted proforma invoice. */
@Entity
@Table(name = "quotation_advance_payment")
class QuotationAdvancePaymentEntity(
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)") var quotationVersionId: UUID,
    @Column(nullable = false, updatable = false, precision = 19, scale = 4) var amount: BigDecimal,
    @Column(updatable = false) var note: String?,
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)") var recordedBy: UUID,
    @Column(nullable = false, updatable = false) var recordedAt: Instant,
) {
    @Id
    @Column(columnDefinition = "BINARY(16)")
    var id: UUID = UuidV7.generate()
}

interface QuotationRepository : JpaRepository<QuotationEntity, UUID> {
    fun findByEnquiryId(enquiryId: UUID): QuotationEntity?
}

interface QuotationVersionRepository : JpaRepository<QuotationVersionEntity, UUID>, JpaSpecificationExecutor<QuotationVersionEntity> {
    fun findByQuotationIdOrderByVersionNoDesc(quotationId: UUID): List<QuotationVersionEntity>
}

interface OwnerAcceptanceRepository : JpaRepository<OwnerAcceptanceEntity, UUID> {
    fun findByQuotationVersionIdIn(ids: Collection<UUID>): List<OwnerAcceptanceEntity>
}

interface QuotationAdvancePaymentRepository : JpaRepository<QuotationAdvancePaymentEntity, UUID> {
    fun findByQuotationVersionIdInOrderByRecordedAtAsc(ids: Collection<UUID>): List<QuotationAdvancePaymentEntity>
    fun findByQuotationVersionIdOrderByRecordedAtAsc(quotationVersionId: UUID): List<QuotationAdvancePaymentEntity>
}

// ---------- API ----------

data class NewQuotationVersionRequest(
    @field:NotNull val estimateId: UUID,
    @field:NotNull val validUntil: LocalDate,
    @field:Size(max = 500) val paymentTerms: String? = null,
    @field:Size(max = 8000) val termsText: String? = null,
    @field:Size(max = 8000) val scopeOfServices: String? = null,
    val tariffId: UUID? = null,
    @field:jakarta.validation.constraints.DecimalMin(value = "0", inclusive = false) @field:jakarta.validation.constraints.Digits(integer = 11, fraction = 8) val inrFxRate: BigDecimal? = null,
    @field:jakarta.validation.constraints.DecimalMin("0.00") @field:jakarta.validation.constraints.Digits(integer = 15, fraction = 4) val advanceReceived: BigDecimal? = null,
    @field:jakarta.validation.constraints.DecimalMin("0.00") @field:jakarta.validation.constraints.Digits(integer = 15, fraction = 4) val advanceGiven: BigDecimal? = null,
)

data class QuotationVersionRequest(
    @field:NotNull val validUntil: LocalDate,
    @field:Size(max = 500) val paymentTerms: String? = null,
    @field:Size(max = 8000) val termsText: String? = null,
    @field:Size(max = 8000) val scopeOfServices: String? = null,
    val tariffId: UUID? = null,
    @field:jakarta.validation.constraints.DecimalMin(value = "0", inclusive = false) @field:jakarta.validation.constraints.Digits(integer = 11, fraction = 8) val inrFxRate: BigDecimal? = null,
    @field:jakarta.validation.constraints.DecimalMin("0.00") @field:jakarta.validation.constraints.Digits(integer = 15, fraction = 4) val advanceReceived: BigDecimal? = null,
    @field:jakarta.validation.constraints.DecimalMin("0.00") @field:jakarta.validation.constraints.Digits(integer = 15, fraction = 4) val advanceGiven: BigDecimal? = null,
)

data class DecisionRequest(
    @field:NotNull val approve: Boolean,
    @field:Size(max = 500) val reason: String? = null,
)

data class IssueRequest(
    @field:NotBlank @field:Size(max = 300) val issuedTo: String,
    @field:NotNull val channel: IssueChannel,
    @field:Size(max = 500) val note: String? = null,
)

data class AcceptanceRequest(
    @field:NotNull val acceptedAt: Instant,
    @field:NotBlank @field:Size(max = 150) val acceptedByName: String,
    @field:Size(max = 40) val jobNumber: String? = null,
    @field:Size(max = 100) val workOrderReference: String? = null,
    @field:Size(max = 500) val paymentTerms: String? = null,
    @field:Size(max = 4000) val specialConditions: String? = null,
)

data class RecordAdvanceRequest(
    @field:NotNull @field:jakarta.validation.constraints.DecimalMin(value = "0", inclusive = false)
    @field:jakarta.validation.constraints.Digits(integer = 15, fraction = 4) val amount: BigDecimal,
    @field:Size(max = 500) val note: String? = null,
)

data class AdvancePaymentDto(
    val id: UUID,
    val amount: BigDecimal,
    val note: String?,
    val recordedByName: String?,
    val recordedAt: Instant,
)

data class TariffTermsDto(
    val id: UUID, val code: String, val name: String, val currency: String, val baseHours: Int, val baseCharge: BigDecimal,
    val extraHourRate: BigDecimal, val billingIncrementMinutes: Int?, val graceMinutes: Int?, val roundingRule: String?,
    val effectiveFrom: LocalDate, val effectiveTo: LocalDate?,
    /** Increment, grace or rounding not yet confirmed by Ship2Ship. */
    val termsPending: Boolean,
)

data class QuotationLineDto(
    val lineNo: Int, val costHeadCode: String, val costHeadName: String, val description: String?,
    val quantity: BigDecimal, val unitPrice: BigDecimal, val amount: BigDecimal, val extraHours: Boolean,
)

data class AcceptanceDto(
    val acceptedAt: Instant, val acceptedByName: String, val workOrderReference: String?, val paymentTerms: String?,
    val specialConditions: String?, val afterValidity: Boolean, val recordedByName: String?, val recordedAt: Instant,
    val jobId: UUID, val jobNumber: String?,
)

data class QuotationVersionDto(
    val id: UUID,
    val versionNo: Int,
    val status: QuotationStatus,
    val estimateId: UUID,
    val estimateVersionNo: Int?,
    val currency: String,
    val inrFxRate: BigDecimal?,
    val totalAmount: BigDecimal,
    val finalAmount: BigDecimal,
    val advancePaid: BigDecimal,
    val advanceRemaining: BigDecimal,
    val advanceStatus: AdvancePaymentStatus,
    val advancePayments: List<AdvancePaymentDto>,
    val validUntil: LocalDate,
    val paymentTerms: String?,
    val advanceReceived: BigDecimal?,
    val advanceGiven: BigDecimal?,
    val termsText: String?,
    val scopeOfServices: String?,
    val tariff: TariffTermsDto?,
    val lines: List<QuotationLineDto>,
    val submittedByName: String?,
    val submittedAt: Instant?,
    val decidedByName: String?,
    val decidedAt: Instant?,
    val decisionReason: String?,
    val issuedByName: String?,
    val issuedAt: Instant?,
    val issuedTo: String?,
    val issueChannel: IssueChannel?,
    val issueNote: String?,
    val pdfSha256: String?,
    val acceptance: AcceptanceDto?,
    val allowedActions: List<QuotationAction>,
    val updatedAt: Instant?,
    val version: Long?,
)

data class JobLinkDto(val id: UUID, val jobNumber: String)

data class QuotationOverviewDto(
    val enquiryId: UUID,
    val enquiryReference: String,
    val enquiryStatus: EnquiryStatus,
    val quotationId: UUID?,
    val reference: String?,
    /** Newest first. */
    val versions: List<QuotationVersionDto>,
    /** True when a new version may be prepared (no draft or pending version is open and the enquiry is still open). */
    val canCreateVersion: Boolean,
    val job: JobLinkDto?,
)

data class PendingApprovalDto(
    val versionId: UUID,
    val enquiryId: UUID,
    val enquiryReference: String,
    val quotationReference: String,
    val versionNo: Int,
    val clientName: String?,
    val currency: String,
    val totalAmount: BigDecimal,
    val submittedByName: String?,
    val submittedAt: Instant?,
)
