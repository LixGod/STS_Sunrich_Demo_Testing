package com.ship2ship.features.commercial

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditDiff
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.persistence.BaseEntity
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.EquipmentLookup
import com.ship2ship.core.security.EquipmentOptionRef
import com.ship2ship.core.security.OrganisationLookup
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.security.ReferenceLookup
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.BusinessRuleException
import com.ship2ship.core.web.FieldError
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.Versioning
import com.ship2ship.features.masters.CostHeadRepository
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.LockModeType
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.hibernate.annotations.BatchSize
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

// Cost estimates (FR-J02): versioned per enquiry, cost and revenue lines by cost head, margin. A quoted version
// can be edited until the final invoice is issued; its accepted quotation is refreshed with the change.

enum class LineKind { COST, REVENUE }

@Entity
@Table(name = "estimate")
class EstimateEntity(
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)") var enquiryId: UUID,
    @Column(nullable = false, updatable = false) var versionNo: Int,
    @Column(nullable = false, columnDefinition = "CHAR(3)") var currency: String,
) : BaseEntity() {
    var notes: String? = null
    @Column(columnDefinition = "BINARY(16)") var tariffId: UUID? = null
    @Column(name = "inr_fx_rate", precision = 19, scale = 8) var inrFxRate: BigDecimal? = null
    @Column(nullable = false, precision = 19, scale = 4) var totalCost: BigDecimal = BigDecimal.ZERO
    @Column(nullable = false, precision = 19, scale = 4) var totalRevenue: BigDecimal = BigDecimal.ZERO
    @Column(nullable = false) var locked: Boolean = false

    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true)
    @JoinColumn(name = "estimate_id", nullable = false, updatable = false)
    @OrderBy("lineNo")
    @BatchSize(size = 100)
    var lines: MutableList<EstimateLineEntity> = mutableListOf()

    fun recalculate() {
        totalCost = lines.filter { it.kind == LineKind.COST }.sumOf { it.amount }
        totalRevenue = lines.filter { it.kind == LineKind.REVENUE }.sumOf { it.amount }
    }

    fun toAuditMap(): Map<String, Any?> = mapOf(
        "currency" to currency, "notes" to notes, "tariffId" to tariffId, "inrFxRate" to inrFxRate?.toPlainString(),
        "totalCost" to totalCost.toPlainString(), "totalRevenue" to totalRevenue.toPlainString(),
        "lines" to lines.sortedBy { it.lineNo }.map {
            "${it.kind}:${it.costHeadId}:${it.quantity.toPlainString()}x${it.unitPrice.toPlainString()}=${it.amount.toPlainString()}:extraHours=${it.extraHours}"
        },
    )
}

@Entity
@Table(name = "estimate_line")
class EstimateLineEntity(
    @Column(nullable = false) var lineNo: Int,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var kind: LineKind,
    @Column(nullable = false, columnDefinition = "BINARY(16)") var costHeadId: UUID,
    var description: String?,
    @Column(nullable = false, precision = 18, scale = 3) var quantity: BigDecimal,
    @Column(nullable = false, precision = 19, scale = 4) var unitPrice: BigDecimal,
    @Column(columnDefinition = "BINARY(16)") var equipmentModelId: UUID? = null,
    @Column(nullable = false) var extraHours: Boolean = false,
) {
    @Id
    @Column(columnDefinition = "BINARY(16)")
    var id: UUID = UuidV7.generate()

    @Column(nullable = false, precision = 19, scale = 4)
    var amount: BigDecimal = Money.lineAmount(quantity, unitPrice)
}

interface EstimateRepository : JpaRepository<EstimateEntity, UUID> {
    fun findByEnquiryIdOrderByVersionNoDesc(enquiryId: UUID): List<EstimateEntity>
}

/** Exact decimal money arithmetic shared by estimates and quotations. */
object Money {
    /** quantity x unit price, half-up to the 4 stored decimals (no business rounding is applied here). */
    fun lineAmount(quantity: BigDecimal, unitPrice: BigDecimal): BigDecimal = quantity.multiply(unitPrice).setScale(4, RoundingMode.HALF_UP)

    fun marginPercent(revenue: BigDecimal, cost: BigDecimal): BigDecimal? =
        if (revenue.signum() <= 0) null
        else revenue.subtract(cost).multiply(BigDecimal(100)).divide(revenue, 2, RoundingMode.HALF_UP)
}

// ---------- API ----------

data class EstimateLineRequest(
    @field:NotNull val kind: LineKind,
    @field:NotNull val costHeadId: UUID,
    @field:Size(max = 300) val description: String? = null,
    @field:NotNull @field:DecimalMin("0") @field:Digits(integer = 15, fraction = 3) val quantity: BigDecimal,
    @field:NotNull @field:DecimalMin("0") @field:Digits(integer = 15, fraction = 4) val unitPrice: BigDecimal,
    val equipmentModelId: UUID? = null,
    val extraHours: Boolean = false,
)

data class EstimateRequest(
    @field:NotNull @field:Pattern(regexp = "^[A-Za-z]{3}$") val currency: String,
    @field:Size(max = 2000) val notes: String? = null,
    val tariffId: UUID? = null,
    @field:DecimalMin(value = "0", inclusive = false) @field:Digits(integer = 11, fraction = 8) val inrFxRate: BigDecimal? = null,
    @field:Valid @field:Size(max = 100, message = "An estimate can have at most 100 lines.") val lines: List<EstimateLineRequest> = emptyList(),
)

/** New estimate version: a copy of [copyFromId] (default: the latest version), or empty if there is none. */
data class NewEstimateRequest(
    val copyFromId: UUID? = null,
    @field:Pattern(regexp = "^[A-Za-z]{3}$") val currency: String? = null,
    @field:DecimalMin(value = "0", inclusive = false) @field:Digits(integer = 11, fraction = 8) val inrFxRate: BigDecimal? = null,
)

data class EstimateLineDto(
    val lineNo: Int, val kind: LineKind, val costHeadId: UUID, val costHeadCode: String?, val costHeadName: String?,
    val description: String?, val quantity: BigDecimal, val unitPrice: BigDecimal, val amount: BigDecimal,
    val equipmentModelId: UUID?,
    val extraHours: Boolean,
)

data class EstimateDto(
    val id: UUID,
    val enquiryId: UUID,
    val versionNo: Int,
    val currency: String,
    val notes: String?,
    val tariffId: UUID?,
    val inrFxRate: BigDecimal?,
    val lines: List<EstimateLineDto>,
    val totalCost: BigDecimal,
    val totalRevenue: BigDecimal,
    val margin: BigDecimal,
    val marginPercent: BigDecimal?,
    val locked: Boolean,
    val editable: Boolean,
    val updatedAt: Instant?,
    val version: Long?,
)

data class EstimateSummaryDto(
    val id: UUID, val versionNo: Int, val currency: String, val inrFxRate: BigDecimal?, val totalCost: BigDecimal, val totalRevenue: BigDecimal,
    val marginPercent: BigDecimal?, val locked: Boolean, val updatedAt: Instant?,
)

@Service
class EstimateService(
    private val estimates: EstimateRepository,
    private val enquiries: EnquiryRepository,
    private val workflow: EnquiryWorkflow,
    private val references: ReferenceLookup,
    private val organisation: OrganisationLookup,
    private val accessPolicy: AccessPolicy,
    private val audit: AuditService,
    private val entityManager: EntityManager,
    private val equipment: EquipmentLookup,
    private val branchDefaults: BranchCostDefaultRepository,
    private val costHeads: CostHeadRepository,
    private val quotationService: QuotationService,
) {
    /** Equipment models for the estimate line picker, from the company stock. */
    @Transactional(readOnly = true)
    fun equipmentOptions(id: UUID): List<EquipmentOptionRef> {
        val enquiry = enquiry(load(id).enquiryId)
        accessPolicy.requireBranch(Permissions.ESTIMATE_READ, enquiry.branchId, ENTITY, id)
        return equipment.equipmentOptions()
    }


    @Transactional(readOnly = true)
    fun list(enquiryId: UUID): List<EstimateSummaryDto> {
        val enquiry = enquiry(enquiryId)
        accessPolicy.requireBranch(Permissions.ESTIMATE_READ, enquiry.branchId, ENQUIRY, enquiryId)
        return estimates.findByEnquiryIdOrderByVersionNoDesc(enquiryId).map {
            EstimateSummaryDto(it.id, it.versionNo, it.currency, it.inrFxRate, it.totalCost, it.totalRevenue,
                Money.marginPercent(it.totalRevenue, it.totalCost), it.locked, it.updatedAt)
        }
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): EstimateDto {
        val estimate = load(id)
        val enquiry = enquiry(estimate.enquiryId)
        accessPolicy.requireBranch(Permissions.ESTIMATE_READ, enquiry.branchId, ENTITY, id)
        return toDto(estimate, enquiry)
    }

    @Transactional
    fun create(enquiryId: UUID, request: NewEstimateRequest): EstimateDto {
        val probe = enquiry(enquiryId)
        accessPolicy.requireBranch(Permissions.ESTIMATE_WRITE, probe.branchId, ENQUIRY, enquiryId)
        val actor = accessPolicy.currentUser().id
        val enquiry = workflow.lock(enquiryId)
        val existing = estimates.findByEnquiryIdOrderByVersionNoDesc(enquiryId)
        val source = request.copyFromId?.let { id -> existing.firstOrNull { it.id == id } ?: throw NotFoundException("Estimate") }
            ?: existing.firstOrNull()
        val currency = (request.currency ?: source?.currency ?: "USD").uppercase()
        checkCurrency(currency)
        if (currency != "USD" && enquiry.specialItems.any { it.paid })
            throw BusinessRuleException("USD_REQUIRED", "Paid special requirements are priced in USD; use a USD estimate.")
        if (source != null && currency != source.currency && source.lines.isNotEmpty())
            throw BusinessRuleException("ESTIMATE_CURRENCY_LOCKED", "Copied pricing lines retain their currency; use the source estimate currency.")

        val estimate = EstimateEntity(enquiryId, (existing.maxOfOrNull { it.versionNo } ?: 0) + 1, currency)
        if (source != null) {
            val from = source
            estimate.notes = from.notes
            estimate.tariffId = from.tariffId
            estimate.inrFxRate = if (currency == "USD") request.inrFxRate ?: from.inrFxRate else null
            from.lines.sortedBy { it.lineNo }.forEach {
                estimate.lines += EstimateLineEntity(it.lineNo, it.kind, it.costHeadId, it.description, it.quantity, it.unitPrice, it.equipmentModelId, it.extraHours)
            }
        } else {
            estimate.inrFxRate = if (currency == "USD") request.inrFxRate else null
            if (currency == "USD") {
                branchDefaults.findByBranchIdAndActiveTrueOrderByCreatedAt(enquiry.branchId).forEachIndexed { index, line ->
                    estimate.lines += EstimateLineEntity(index + 1, LineKind.COST, line.costHeadId,
                        line.description, line.quantity, line.unitPrice, line.equipmentModelId)
                }
            }
        }
        applyPaidSpecialItems(estimate, enquiry)
        estimate.recalculate()
        estimates.saveAndFlush(estimate)
        if (enquiry.status == EnquiryStatus.DRAFT) workflow.move(enquiry, EnquiryStatus.COSTING, "Estimate v${estimate.versionNo} started", actor)
        audit.record(AuditEntry(AuditAction.CREATE, ENTITY, estimate.id, enquiry.branchId,
            mapOf("enquiryId" to enquiryId, "versionNo" to estimate.versionNo, "copiedFrom" to source?.versionNo) + estimate.toAuditMap()))
        return toDto(estimate, enquiry)
    }

    /** Refresh SPECIAL-REQ lines on the active USD estimate when the enquiry's special items change. */
    @Transactional
    fun syncSpecialItems(enquiry: EnquiryEntity): Boolean {
        val acceptedId = if (enquiry.status == EnquiryStatus.ACCEPTED) quotationService.acceptedEstimateId(enquiry.id) else null
        val estimate = estimates.findByEnquiryIdOrderByVersionNoDesc(enquiry.id)
            .firstOrNull { it.currency == "USD" && (acceptedId == null || it.id == acceptedId) } ?: return false
        applyPaidSpecialItems(estimate, enquiry)
        estimate.recalculate()
        entityManager.lock(estimate, LockModeType.PESSIMISTIC_FORCE_INCREMENT)
        estimates.saveAndFlush(estimate)
        return quotationService.syncFromEstimate(estimate, enquiry)
    }

    private fun applyPaidSpecialItems(estimate: EstimateEntity, enquiry: EnquiryEntity) {
        val specialHead = costHeads.findByCode("SPECIAL-REQ")
        if (specialHead != null) estimate.lines.removeIf { it.costHeadId == specialHead.id }
        estimate.lines.forEachIndexed { index, line -> line.lineNo = index + 1 }
        if (enquiry.specialItems.any { it.paid }) {
            val head = specialHead?.takeIf { it.active }
                ?: throw BusinessRuleException("SPECIAL_HEAD_MISSING", "Activate the special requirement revenue head before costing paid requirements.")
            enquiry.specialItems.filter { it.paid }.sortedBy { it.lineNo }.forEach { item ->
                estimate.lines += EstimateLineEntity(estimate.lines.size + 1, LineKind.REVENUE, head.id,
                    item.description, BigDecimal.ONE, item.salePriceUsd!!, null)
            }
        }
    }

    @Transactional
    fun update(id: UUID, expectedVersion: Long, request: EstimateRequest): EstimateDto {
        val estimate = load(id)
        val probe = enquiry(estimate.enquiryId)
        accessPolicy.requireBranch(Permissions.ESTIMATE_WRITE, probe.branchId, ENTITY, id)
        val enquiry = workflow.lock(estimate.enquiryId)
        Versioning.checkCurrent(expectedVersion, estimate.version)
        val currency = request.currency.uppercase()
        checkCurrency(currency)
        if (currency != "USD" && enquiry.specialItems.any { it.paid })
            throw BusinessRuleException("USD_REQUIRED", "Paid special requirements are priced in USD; use a USD estimate.")
        if (currency != estimate.currency && estimate.lines.isNotEmpty())
            throw BusinessRuleException("ESTIMATE_CURRENCY_LOCKED", "Create a new estimate version to change currency after pricing lines.")
        val (equipmentModels, heads) = validateLines(request.lines)
        checkTariff(request.tariffId, currency)

        val before = estimate.toAuditMap()
        estimate.currency = currency
        estimate.notes = request.notes?.trim()?.ifBlank { null }
        estimate.tariffId = request.tariffId
        estimate.inrFxRate = if (currency == "USD") request.inrFxRate else null
        // Lines are matched by line number and updated in place, like enquiry cargo rows.
        request.lines.forEachIndexed { i, input ->
            val lineNo = i + 1
            val line = estimate.lines.firstOrNull { it.lineNo == lineNo }
            val description = input.equipmentModelId?.let { equipmentModels[it]?.name }
                ?: input.description?.trim()?.ifBlank { null }
            val isDeferredTariffRate = input.kind == LineKind.REVENUE && heads[input.costHeadId]?.code == "ADD-TARIFF" && !input.extraHours
            val quantity = if (isDeferredTariffRate) BigDecimal.ZERO else input.quantity
            if (line == null) {
                estimate.lines += EstimateLineEntity(lineNo, input.kind, input.costHeadId, description, quantity, input.unitPrice, input.equipmentModelId, input.extraHours)
            } else {
                line.kind = input.kind
                line.costHeadId = input.costHeadId
                line.description = description
                line.equipmentModelId = input.equipmentModelId
                line.extraHours = input.extraHours
                line.quantity = quantity
                line.unitPrice = input.unitPrice
                line.amount = Money.lineAmount(quantity, input.unitPrice)
            }
        }
        estimate.lines.removeIf { it.lineNo > request.lines.size }
        estimate.recalculate()
        entityManager.lock(estimate, LockModeType.PESSIMISTIC_FORCE_INCREMENT)
        estimates.saveAndFlush(estimate)
        quotationService.syncFromEstimate(estimate, enquiry)
        audit.record(AuditEntry(AuditAction.UPDATE, ENTITY, id, enquiry.branchId,
            AuditDiff.changes(before, estimate.toAuditMap())))
        return toDto(estimate, enquiry)
    }

    private fun validateLines(lines: List<EstimateLineRequest>): Pair<Map<UUID, EquipmentOptionRef>, Map<UUID, com.ship2ship.core.security.CostHeadRef>> {
        val heads = references.costHeads(lines.map { it.costHeadId })
        val models = if (lines.any { it.equipmentModelId != null }) equipment.equipmentOptions().associateBy { it.modelId } else emptyMap()
        val errors = lines.flatMapIndexed { i, line ->
            val head = heads[line.costHeadId]
            val model = line.equipmentModelId?.let(models::get)
            val isDeferredTariffRate = line.kind == LineKind.REVENUE && head?.code == "ADD-TARIFF" && !line.extraHours
            listOfNotNull(
                FieldError("lines[$i].costHeadId", "unknown", "Unknown or inactive cost head.").takeIf { head?.active != true },
                FieldError("lines[$i].costHeadId", "kind", "This cost head cannot be used on a ${line.kind.name.lowercase()} line.")
                    .takeIf { head != null && head.active && !fits(head.kind, line.kind) },
                FieldError("lines[$i].quantity", "min", "Quantity must be greater than zero.")
                    .takeIf { !isDeferredTariffRate && line.quantity.signum() <= 0 },
                FieldError("lines[$i].equipmentModelId", "unknown", "Choose active equipment from the inventory.")
                    .takeIf { line.equipmentModelId != null && model == null },
                FieldError("lines[$i].kind", "equipment", "Inventory equipment must be a cost line.")
                    .takeIf { model != null && line.kind != LineKind.COST },
                FieldError("lines[$i].costHeadId", "equipment", "Choose the cost head configured for this equipment category.")
                    .takeIf { model?.costHeadId != null && line.costHeadId != model.costHeadId },
                FieldError("lines[$i].extraHours", "tariff", "Extra hours require the additional tariff revenue head.")
                    .takeIf { line.extraHours && (line.kind != LineKind.REVENUE || head?.code != "ADD-TARIFF") },
            )
        }
        if (lines.count { it.extraHours } > 1) throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.",
            listOf(FieldError("lines", "extraHours", "Add only one extra-hours line.")))
        if (errors.isNotEmpty()) throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", errors)
        return models to heads
    }

    private fun checkCurrency(code: String) {
        if (references.currency(code)?.active != true) {
            throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.",
                listOf(FieldError("currency", "unknown", "Not an active currency in the currency master.")))
        }
    }

    private fun checkTariff(tariffId: UUID?, currency: String) {
        if (tariffId == null) return
        val tariff = references.tariffs(listOf(tariffId))[tariffId]
        val error = when {
            tariff == null || !tariff.active -> FieldError("tariffId", "unknown", "Unknown or inactive tariff.")
            tariff.currency != currency -> FieldError("tariffId", "currency", "The tariff currency differs from the estimate currency.")
            else -> null
        }
        if (error != null) throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", listOf(error))
    }

    private fun toDto(estimate: EstimateEntity, enquiry: EnquiryEntity): EstimateDto {
        val heads = references.costHeads(estimate.lines.map { it.costHeadId })
        return EstimateDto(
            id = estimate.id,
            enquiryId = estimate.enquiryId,
            versionNo = estimate.versionNo,
            currency = estimate.currency,
            notes = estimate.notes,
            tariffId = estimate.tariffId,
            inrFxRate = estimate.inrFxRate,
            lines = estimate.lines.sortedBy { it.lineNo }.map {
                EstimateLineDto(it.lineNo, it.kind, it.costHeadId, heads[it.costHeadId]?.code, heads[it.costHeadId]?.name,
                    it.description, it.quantity, it.unitPrice, it.amount, it.equipmentModelId, it.extraHours)
            },
            totalCost = estimate.totalCost,
            totalRevenue = estimate.totalRevenue,
            margin = estimate.totalRevenue.subtract(estimate.totalCost),
            marginPercent = Money.marginPercent(estimate.totalRevenue, estimate.totalCost),
            locked = estimate.locked,
            editable = true,
            updatedAt = estimate.updatedAt,
            version = estimate.version,
        )
    }

    private fun load(id: UUID) = estimates.findByIdOrNull(id) ?: throw NotFoundException("Estimate")

    private fun enquiry(id: UUID) = enquiries.findByIdOrNull(id) ?: throw NotFoundException("Enquiry")

    private companion object {
        const val ENTITY = "ESTIMATE"
        const val ENQUIRY = "ENQUIRY"

        fun fits(headKind: String, lineKind: LineKind): Boolean =
            headKind == "BOTH" || (lineKind == LineKind.COST && headKind == "EXPENSE") || (lineKind == LineKind.REVENUE && headKind == "REVENUE")
    }
}

@RestController
@RequestMapping("/api/v1")
class EstimateController(private val service: EstimateService) {

    @GetMapping("/enquiries/{enquiryId}/estimates")
    fun list(@PathVariable enquiryId: UUID): List<EstimateSummaryDto> = service.list(enquiryId)

    @PostMapping("/enquiries/{enquiryId}/estimates")
    fun create(@PathVariable enquiryId: UUID, @Valid @RequestBody request: NewEstimateRequest): ResponseEntity<EstimateDto> {
        val created = service.create(enquiryId, request)
        return ResponseEntity.status(201).eTag(Versioning.etag(created.version)).body(created)
    }

    @GetMapping("/estimates/{id}/equipment-options")
    fun equipmentOptions(@PathVariable id: UUID): List<EquipmentOptionRef> = service.equipmentOptions(id)

    @GetMapping("/estimates/{id}")
    fun get(@PathVariable id: UUID): ResponseEntity<EstimateDto> = service.get(id).let { Versioning.ok(it, it.version) }

    @PutMapping("/estimates/{id}")
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: EstimateRequest,
    ): ResponseEntity<EstimateDto> = service.update(id, Versioning.requireIfMatch(ifMatch), request).let { Versioning.ok(it, it.version) }
}
