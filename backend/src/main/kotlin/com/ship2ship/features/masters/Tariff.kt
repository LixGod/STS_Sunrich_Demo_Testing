package com.ship2ship.features.masters

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditDiff
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.persistence.BaseEntity
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.ConflictException
import com.ship2ship.core.web.FieldError
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.PageResponse
import com.ship2ship.core.web.Paging
import com.ship2ship.core.web.Versioning
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.data.domain.Pageable
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
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
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** How chargeable extra time is rounded; null on a tariff = pending Ship2Ship's confirmation (SRS §27). */
enum class RoundingRule { NONE, ROUND_UP_TO_INCREMENT }

/**
 * STS charging tariff (D05, BR-TIME-02): base block of 24, 48 (default) or 72 hours and an extra-hour
 * rate. Each version is effective-dated; versions of the same code may not overlap. The rate applied to a job is frozen
 * when the quotation is accepted (Phase 2c), so later tariff changes never alter accepted work.
 */
@Entity
@Table(name = "tariff")
class TariffEntity(
    @Column(nullable = false, updatable = false) var code: String,
    @Column(nullable = false) var name: String,
    @Column(nullable = false, columnDefinition = "CHAR(3)") var currency: String,
    @Column(nullable = false) var baseHours: Int,
    @Column(nullable = false, precision = 19, scale = 4) var baseCharge: BigDecimal,
    @Column(nullable = false, precision = 19, scale = 4) var extraHourRate: BigDecimal,
    @Column(nullable = false) var effectiveFrom: LocalDate,
) : BaseEntity() {
    var billingIncrementMinutes: Int? = null
    var graceMinutes: Int? = null
    @Enumerated(EnumType.STRING)
    var roundingRule: RoundingRule? = null
    var effectiveTo: LocalDate? = null
    var notes: String? = null

    @Column(nullable = false)
    var active: Boolean = true

    fun toAuditMap(): Map<String, Any?> = mapOf(
        "code" to code, "name" to name, "currency" to currency, "baseHours" to baseHours,
        "baseCharge" to baseCharge.toPlainString(), "extraHourRate" to extraHourRate.toPlainString(),
        "billingIncrementMinutes" to billingIncrementMinutes, "graceMinutes" to graceMinutes, "roundingRule" to roundingRule?.name,
        "effectiveFrom" to effectiveFrom.toString(), "effectiveTo" to effectiveTo?.toString(), "notes" to notes, "active" to active,
    )

    fun overlaps(from: LocalDate, to: LocalDate?): Boolean =
        !(effectiveTo?.isBefore(from) ?: false) && !(to?.isBefore(effectiveFrom) ?: false)
}

interface TariffRepository : JpaRepository<TariffEntity, UUID>, JpaSpecificationExecutor<TariffEntity> {
    fun findByCodeAndActiveTrue(code: String): List<TariffEntity>
}

data class TariffDto(
    val id: UUID, val code: String, val name: String,
    val currency: String, val baseHours: Int, val baseCharge: BigDecimal, val extraHourRate: BigDecimal,
    val billingIncrementMinutes: Int?, val graceMinutes: Int?, val roundingRule: RoundingRule?,
    val effectiveFrom: LocalDate, val effectiveTo: LocalDate?, val notes: String?,
    /** True while increment, grace or rounding is still unset, i.e. the contract terms are not yet confirmed. */
    val termsPending: Boolean,
    val active: Boolean, val updatedAt: Instant?, val version: Long?,
)

data class TariffRequest(
    @field:NotBlank @field:Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9-]{1,19}$", message = "Use 2-20 letters, digits or hyphens.") val code: String,
    @field:NotBlank @field:Size(max = 150) val name: String,
    @field:NotBlank @field:Pattern(regexp = "^[A-Za-z]{3}$") val currency: String,
    @field:NotNull val baseHours: Int = 48,
    @field:NotNull @field:DecimalMin("0") @field:Digits(integer = 15, fraction = 4) val baseCharge: BigDecimal,
    @field:NotNull @field:DecimalMin("0") @field:Digits(integer = 15, fraction = 4) val extraHourRate: BigDecimal,
    @field:Min(1) val billingIncrementMinutes: Int? = null,
    @field:Min(0) val graceMinutes: Int? = null,
    val roundingRule: RoundingRule? = null,
    @field:NotNull val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate? = null,
    @field:Size(max = 500) val notes: String? = null,
)

@Service
class TariffService(
    private val tariffs: TariffRepository,
    private val currencies: CurrencyRepository,
    private val accessPolicy: AccessPolicy,
    private val audit: AuditService,
    private val jdbc: JdbcClient,
) {

    @Transactional(readOnly = true)
    fun list(q: String?, active: Boolean?, pageable: Pageable): PageResponse<TariffDto> {
        // Tariffs are company-wide: only a company-wide grant lists them.
        val global = accessPolicy.scope(Permissions.MASTER_FINANCE_READ).global
        val inScope = Specification<TariffEntity> { _, _, cb -> if (global) cb.conjunction() else cb.disjunction() }
        val spec = OrganisationFilters.search<TariffEntity>(q, active, "name").and(inScope)
        return PageResponse.of(tariffs.findAll(spec, pageable)) { it.toDto() }
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): TariffDto {
        val tariff = load(id)
        accessPolicy.requireGlobal(Permissions.MASTER_FINANCE_READ, ENTITY, id)
        return tariff.toDto()
    }

    @Transactional
    fun create(request: TariffRequest): TariffDto {
        accessPolicy.requireGlobal(Permissions.MASTER_FINANCE_WRITE, ENTITY)
        val code = request.code.trim().uppercase()
        validate(request)
        checkOverlap(code, request.effectiveFrom, request.effectiveTo, null)
        val tariff = TariffEntity(
            code, request.name.trim(), request.currency.uppercase(), request.baseHours,
            request.baseCharge, request.extraHourRate, request.effectiveFrom,
        ).also { apply(it, request) }
        tariffs.saveAndFlush(tariff)
        audit.record(AuditEntry(AuditAction.CREATE, ENTITY, tariff.id, details = tariff.toAuditMap()))
        return tariff.toDto()
    }

    @Transactional
    fun update(id: UUID, expectedVersion: Long, request: TariffRequest): TariffDto {
        val tariff = load(id)
        accessPolicy.requireGlobal(Permissions.MASTER_FINANCE_WRITE, ENTITY, id)
        Versioning.checkCurrent(expectedVersion, tariff.version)
        if (request.code.trim().uppercase() != tariff.code) {
            throw BadRequestException("CODE_IMMUTABLE", "The code cannot be changed once created.",
                listOf(FieldError("code", "immutable", null)))
        }
        validate(request)
        checkOverlap(tariff.code, request.effectiveFrom, request.effectiveTo, id)
        requireTermsUnchangedWhileQuoted(tariff, request)
        val before = tariff.toAuditMap()
        tariff.name = request.name.trim()
        tariff.currency = request.currency.uppercase()
        tariff.baseHours = request.baseHours
        tariff.baseCharge = request.baseCharge
        tariff.extraHourRate = request.extraHourRate
        tariff.effectiveFrom = request.effectiveFrom
        apply(tariff, request)
        tariffs.saveAndFlush(tariff)
        audit.record(AuditEntry(AuditAction.UPDATE, ENTITY, id, details = AuditDiff.changes(before, tariff.toAuditMap())))
        return tariff.toDto()
    }

    @Transactional
    fun changeStatus(id: UUID, expectedVersion: Long, request: ActiveStatusRequest): TariffDto {
        val tariff = load(id)
        accessPolicy.requireGlobal(Permissions.MASTER_FINANCE_WRITE, ENTITY, id)
        Versioning.checkCurrent(expectedVersion, tariff.version)
        OrganisationValidation.check(OrganisationValidation.reasonForDeactivation(request.active, request.reason))
        if (request.active && !tariff.active) {
            checkOverlap(tariff.code, tariff.effectiveFrom, tariff.effectiveTo, id)
        }
        if (tariff.active != request.active) {
            tariff.active = request.active
            tariffs.saveAndFlush(tariff)
            audit.record(AuditEntry(AuditAction.STATUS_CHANGE, ENTITY, id,
                details = mapOf("active" to request.active, "reason" to request.reason)))
        }
        return tariff.toDto()
    }

    @Transactional
    fun delete(id: UUID, expectedVersion: Long) {
        val tariff = load(id)
        accessPolicy.requireGlobal(Permissions.MASTER_FINANCE_WRITE, ENTITY, id)
        Versioning.checkCurrent(expectedVersion, tariff.version)
        val details = tariff.toAuditMap()
        tariffs.delete(tariff)
        tariffs.flush()
        audit.record(AuditEntry(AuditAction.DELETE, ENTITY, id, details = details))
    }

    private fun validate(request: TariffRequest) = OrganisationValidation.check(
        FieldError("baseHours", "allowed", "Choose 24, 48 or 72 hours.").takeIf { request.baseHours !in ALLOWED_BASE_HOURS },
        FieldError("currency", "unknown", "Not an active currency in the currency master.")
            .takeIf { currencies.findByCode(request.currency.uppercase())?.active != true },
        FieldError("effectiveTo", "beforeStart", "The end date is before the start date.")
            .takeIf { request.effectiveTo?.isBefore(request.effectiveFrom) == true },
        FieldError("billingIncrementMinutes", "required", "Rounding up needs a billing increment.")
            .takeIf { request.roundingRule == RoundingRule.ROUND_UP_TO_INCREMENT && request.billingIncrementMinutes == null },
    )

    private fun checkOverlap(code: String, from: LocalDate, to: LocalDate?, exceptId: UUID?) {
        val clash = tariffs.findByCodeAndActiveTrue(code).any { it.id != exceptId && it.overlaps(from, to) }
        if (clash) {
            throw ConflictException("TARIFF_PERIOD_OVERLAP", "Another active version of this tariff covers part of the same period.",
                listOf(FieldError("effectiveFrom", "overlap", null)))
        }
    }

    private fun apply(tariff: TariffEntity, request: TariffRequest) {
        tariff.billingIncrementMinutes = request.billingIncrementMinutes
        tariff.graceMinutes = request.graceMinutes
        tariff.roundingRule = request.roundingRule
        tariff.effectiveTo = request.effectiveTo
        tariff.notes = request.notes?.trim()?.ifBlank { null }
    }

    /**
     * A quotation awaiting approval, approved or issued shows this tariff's charging terms, and acceptance freezes the
     * tariff as it is then (AR-02). So those terms cannot change underneath it; a still-pending term may be filled in.
     */
    private fun requireTermsUnchangedWhileQuoted(tariff: TariffEntity, request: TariffRequest) {
        fun <T> changedOnce(old: T?, new: T?) = old != null && old != new
        val changed = listOfNotNull(
            "currency".takeIf { request.currency.uppercase() != tariff.currency },
            "baseHours".takeIf { request.baseHours != tariff.baseHours },
            "baseCharge".takeIf { request.baseCharge.compareTo(tariff.baseCharge) != 0 },
            "extraHourRate".takeIf { request.extraHourRate.compareTo(tariff.extraHourRate) != 0 },
            "billingIncrementMinutes".takeIf { changedOnce(tariff.billingIncrementMinutes, request.billingIncrementMinutes) },
            "graceMinutes".takeIf { changedOnce(tariff.graceMinutes, request.graceMinutes) },
            "roundingRule".takeIf { changedOnce(tariff.roundingRule, request.roundingRule) },
        )
        if (changed.isEmpty()) return
        val quoted = jdbc.sql("SELECT COUNT(*) FROM quotation_version WHERE tariff_id = :id AND status IN ('PENDING_APPROVAL', 'APPROVED', 'ISSUED')")
            .param("id", Uuids.toBytes(tariff.id)).query(Long::class.java).single()
        if (quoted > 0) {
            throw ConflictException("TARIFF_IN_USE", "An open quotation uses this tariff; create a new tariff version for different terms.",
                changed.map { FieldError(it, "inUse", "Used by an open quotation.") })
        }
    }

    private fun load(id: UUID) = tariffs.findByIdOrNull(id) ?: throw NotFoundException("Tariff")

    private companion object {
        const val ENTITY = "TARIFF"
        val ALLOWED_BASE_HOURS = setOf(24, 48, 72)
    }
}

internal fun TariffEntity.toDto() = TariffDto(
    id, code, name, currency, baseHours, baseCharge, extraHourRate, billingIncrementMinutes,
    graceMinutes, roundingRule, effectiveFrom, effectiveTo, notes,
    termsPending = roundingRule == null || graceMinutes == null || (roundingRule == RoundingRule.ROUND_UP_TO_INCREMENT && billingIncrementMinutes == null),
    active, updatedAt, version,
)

@RestController
@RequestMapping("/api/v1/masters/tariffs")
class TariffController(private val service: TariffService, private val paging: Paging) {

    @GetMapping
    fun list(
        @RequestParam page: Int?, @RequestParam size: Int?, @RequestParam sort: String?,
        @RequestParam q: String?, @RequestParam active: Boolean?,
    ): PageResponse<TariffDto> = service.list(q, active, paging.pageable(page, size, sort, SORTS, Sort.by("code")))

    @PostMapping
    fun create(@Valid @RequestBody request: TariffRequest): ResponseEntity<TariffDto> {
        val created = service.create(request)
        val location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id).toUri()
        return ResponseEntity.created(location).eTag(Versioning.etag(created.version)).body(created)
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): ResponseEntity<TariffDto> = service.get(id).let { Versioning.ok(it, it.version) }

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: TariffRequest,
    ): ResponseEntity<TariffDto> = service.update(id, Versioning.requireIfMatch(ifMatch), request).let { Versioning.ok(it, it.version) }

    @PatchMapping("/{id}/status")
    fun changeStatus(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: ActiveStatusRequest,
    ): ResponseEntity<TariffDto> = service.changeStatus(id, Versioning.requireIfMatch(ifMatch), request).let { Versioning.ok(it, it.version) }

    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<Void> {
        service.delete(id, Versioning.requireIfMatch(ifMatch))
        return ResponseEntity.noContent().build()
    }

    private companion object {
        val SORTS = mapOf("code" to "code", "name" to "name", "effectiveFrom" to "effectiveFrom", "active" to "active")
    }
}
