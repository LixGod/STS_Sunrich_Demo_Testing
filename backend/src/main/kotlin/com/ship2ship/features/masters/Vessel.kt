package com.ship2ship.features.masters

import java.math.RoundingMode
import org.springframework.transaction.annotation.Propagation
import org.springframework.stereotype.Component
import com.ship2ship.core.vessels.VesselMasterUpdates
import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditDiff
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
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
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.data.domain.Pageable
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
import java.util.UUID

enum class VesselType { CRUDE_TANKER, PRODUCT_TANKER, CHEMICAL_TANKER, LPG_CARRIER, LNG_CARRIER, BULK_CARRIER, OTHER }

/** UNVERIFIED until the technical team reviews the source documents (FR-TECH-01, decision K1). */
enum class ParticularsStatus { UNVERIFIED, VERIFIED }

/** Vessel identity keyed by IMO number (SRS §4, DR-01). Detailed, versioned particulars arrive in Phase 3. */
@Entity
@Table(name = "vessel")
class VesselEntity(
    @Column(nullable = false, updatable = false, columnDefinition = "CHAR(7)")
    var imo: String,
    @Column(nullable = false)
    var name: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    var vesselType: VesselType,
) : BaseEntity() {
    var formerNames: String? = null
    @Column(columnDefinition = "CHAR(2)")
    var flagState: String? = null
    @Column(name = "loa_m", precision = 7, scale = 2)
    var loaM: BigDecimal? = null
    @Column(name = "beam_m", precision = 6, scale = 2)
    var beamM: BigDecimal? = null
    @Column(name = "dwt_t", precision = 10, scale = 1)
    var dwtT: BigDecimal? = null

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    var particularsStatus: ParticularsStatus = ParticularsStatus.UNVERIFIED

    @Column(nullable = false)
    var active: Boolean = true

    fun toAuditMap(): Map<String, Any?> = mapOf(
        "imo" to imo, "name" to name, "vesselType" to vesselType.name, "formerNames" to formerNames, "flagState" to flagState,
        "loaM" to loaM?.toPlainString(), "beamM" to beamM?.toPlainString(), "dwtT" to dwtT?.toPlainString(),
        "particularsStatus" to particularsStatus.name, "active" to active,
    )
}

interface VesselRepository : JpaRepository<VesselEntity, UUID>, JpaSpecificationExecutor<VesselEntity> {
    fun existsByImo(imo: String): Boolean
}

data class VesselDto(
    val id: UUID, val imo: String, val name: String, val vesselType: VesselType, val formerNames: String?, val flagState: String?,
    val loaM: BigDecimal?, val beamM: BigDecimal?, val dwtT: BigDecimal?, val particularsStatus: ParticularsStatus,
    val active: Boolean, val updatedAt: Instant?, val version: Long?,
)

data class VesselRequest(
    @field:NotBlank @field:Pattern(regexp = "^[0-9]{7}$", message = "An IMO number has 7 digits.") val imo: String,
    @field:NotBlank @field:Size(max = 150) val name: String,
    @field:NotNull val vesselType: VesselType,
    @field:Size(max = 500) val formerNames: String? = null,
    @field:Pattern(regexp = "^[A-Za-z]{2}$", message = "Use an ISO 3166 two-letter country code.") val flagState: String? = null,
    @field:DecimalMin("1") @field:DecimalMax("500") @field:Digits(integer = 5, fraction = 2) val loaM: BigDecimal? = null,
    @field:DecimalMin("1") @field:DecimalMax("100") @field:Digits(integer = 4, fraction = 2) val beamM: BigDecimal? = null,
    @field:DecimalMin("0") @field:Digits(integer = 9, fraction = 1) val dwtT: BigDecimal? = null,
)

/** IMO ship identification number check: the 7th digit equals sum(d1*7 … d6*2) mod 10. */
object ImoNumber {
    fun isValid(imo: String): Boolean {
        if (!Regex("^[0-9]{7}$").matches(imo)) return false
        val sum = (0 until 6).sumOf { (imo[it] - '0') * (7 - it) }
        return sum % 10 == imo[6] - '0'
    }
}

@Service
class VesselService(
    private val vessels: VesselRepository,
    private val accessPolicy: AccessPolicy,
    private val audit: AuditService,
) {

    @Transactional(readOnly = true)
    fun list(q: String?, active: Boolean?, vesselType: VesselType?, pageable: Pageable): PageResponse<VesselDto> {
        accessPolicy.requireAny(Permissions.MASTER_COMMERCIAL_READ)
        val spec = Specification<VesselEntity> { root, _, cb ->
            val predicates = buildList {
                if (!q.isNullOrBlank()) {
                    val like = "%${q.trim().lowercase()}%"
                    add(cb.or(
                        cb.like(root.get("imo"), "%${q.trim()}%"),
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("formerNames")), like),
                    ))
                }
                active?.let { add(cb.equal(root.get<Boolean>("active"), it)) }
                vesselType?.let { add(cb.equal(root.get<VesselType>("vesselType"), it)) }
            }
            cb.and(*predicates.toTypedArray())
        }
        return PageResponse.of(vessels.findAll(spec, pageable)) { it.toDto() }
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): VesselDto {
        accessPolicy.requireAny(Permissions.MASTER_COMMERCIAL_READ)
        return load(id).toDto()
    }

    @Transactional
    fun create(request: VesselRequest): VesselDto {
        if (accessPolicy.currentUser().scopeFor(Permissions.MASTER_COMMERCIAL_WRITE).isEmpty) {
            accessPolicy.requireAny(Permissions.ENQUIRY_WRITE)
        }
        if (!ImoNumber.isValid(request.imo)) {
            throw BadRequestException("VALIDATION_FAILED", "The IMO number check digit is wrong.",
                listOf(FieldError("imo", "checkDigit", "The IMO number check digit is wrong.")))
        }
        if (vessels.existsByImo(request.imo)) {
            throw ConflictException("DUPLICATE_IMO", "A vessel with this IMO number already exists.", listOf(FieldError("imo", "duplicate", null)))
        }
        val vessel = VesselEntity(request.imo, request.name.trim().uppercase(), request.vesselType).also { apply(it, request) }
        vessels.saveAndFlush(vessel)
        audit.record(AuditEntry(AuditAction.CREATE, ENTITY, vessel.id, details = vessel.toAuditMap()))
        return vessel.toDto()
    }

    /** Particulars entered here stay UNVERIFIED; verification is a Phase 3 technical review step. */
    @Transactional
    fun update(id: UUID, expectedVersion: Long, request: VesselRequest): VesselDto {
        accessPolicy.requireAny(Permissions.MASTER_COMMERCIAL_WRITE)
        val vessel = load(id)
        Versioning.checkCurrent(expectedVersion, vessel.version)
        if (request.imo != vessel.imo) {
            throw BadRequestException("CODE_IMMUTABLE", "The IMO number cannot be changed once created.", listOf(FieldError("imo", "immutable", null)))
        }
        val before = vessel.toAuditMap()
        val dimensions = Triple(vessel.loaM, vessel.beamM, vessel.dwtT)
        vessel.name = request.name.trim().uppercase()
        vessel.vesselType = request.vesselType
        apply(vessel, request)
        // Hand-edited dimensions are no longer the verified ones (FR-TECH-01).
        if (!sameValues(dimensions, Triple(vessel.loaM, vessel.beamM, vessel.dwtT))) vessel.particularsStatus = ParticularsStatus.UNVERIFIED
        vessels.saveAndFlush(vessel)
        audit.record(AuditEntry(AuditAction.UPDATE, ENTITY, id, details = AuditDiff.changes(before, vessel.toAuditMap())))
        return vessel.toDto()
    }

    @Transactional
    fun changeStatus(id: UUID, expectedVersion: Long, request: ActiveStatusRequest): VesselDto {
        accessPolicy.requireAny(Permissions.MASTER_COMMERCIAL_WRITE)
        val vessel = load(id)
        Versioning.checkCurrent(expectedVersion, vessel.version)
        OrganisationValidation.check(OrganisationValidation.reasonForDeactivation(request.active, request.reason))
        if (vessel.active != request.active) {
            vessel.active = request.active
            vessels.saveAndFlush(vessel)
            audit.record(AuditEntry(AuditAction.STATUS_CHANGE, ENTITY, id, details = mapOf("active" to request.active, "reason" to request.reason)))
        }
        return vessel.toDto()
    }

    @Transactional
    fun delete(id: UUID, expectedVersion: Long) {
        accessPolicy.requireAny(Permissions.MASTER_COMMERCIAL_WRITE)
        val vessel = load(id)
        Versioning.checkCurrent(expectedVersion, vessel.version)
        val details = vessel.toAuditMap()
        vessels.delete(vessel)
        vessels.flush()
        audit.record(AuditEntry(AuditAction.DELETE, ENTITY, id, details = details))
    }

    private fun sameValues(a: Triple<BigDecimal?, BigDecimal?, BigDecimal?>, b: Triple<BigDecimal?, BigDecimal?, BigDecimal?>): Boolean =
        listOf(a.first to b.first, a.second to b.second, a.third to b.third).all { (x, y) -> if (x == null || y == null) x == y else x.compareTo(y) == 0 }

    private fun apply(vessel: VesselEntity, request: VesselRequest) {
        vessel.formerNames = request.formerNames?.trim()?.ifBlank { null }
        vessel.flagState = request.flagState?.trim()?.uppercase()?.ifBlank { null }
        vessel.loaM = request.loaM
        vessel.beamM = request.beamM
        vessel.dwtT = request.dwtT
    }

    private fun load(id: UUID) = vessels.findByIdOrNull(id) ?: throw NotFoundException("Vessel")

    private companion object {
        const val ENTITY = "VESSEL"
    }
}

internal fun VesselEntity.toDto() = VesselDto(
    id, imo, name, vesselType, formerNames, flagState, loaM, beamM, dwtT, particularsStatus, active, updatedAt, version,
)

@RestController
@RequestMapping("/api/v1/masters/vessels")
class VesselController(private val service: VesselService, private val paging: Paging) {

    @GetMapping
    fun list(
        @RequestParam page: Int?, @RequestParam size: Int?, @RequestParam sort: String?,
        @RequestParam q: String?, @RequestParam active: Boolean?, @RequestParam vesselType: VesselType?,
    ): PageResponse<VesselDto> = service.list(q, active, vesselType, paging.pageable(page, size, sort, SORTS, Sort.by("name")))

    @PostMapping
    fun create(@Valid @RequestBody request: VesselRequest): ResponseEntity<VesselDto> {
        val created = service.create(request)
        val location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id).toUri()
        return ResponseEntity.created(location).eTag(Versioning.etag(created.version)).body(created)
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): ResponseEntity<VesselDto> = service.get(id).let { Versioning.ok(it, it.version) }

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: VesselRequest,
    ): ResponseEntity<VesselDto> = service.update(id, Versioning.requireIfMatch(ifMatch), request).let { Versioning.ok(it, it.version) }

    @PatchMapping("/{id}/status")
    fun changeStatus(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: ActiveStatusRequest,
    ): ResponseEntity<VesselDto> = service.changeStatus(id, Versioning.requireIfMatch(ifMatch), request).let { Versioning.ok(it, it.version) }

    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<Void> {
        service.delete(id, Versioning.requireIfMatch(ifMatch))
        return ResponseEntity.noContent().build()
    }

    private companion object {
        val SORTS = mapOf("imo" to "imo", "name" to "name", "vesselType" to "vesselType", "active" to "active", "updatedAt" to "updatedAt")
    }
}

/** Verified particulars from the technical review update the vessel master here (core.vessels.VesselMasterUpdates). */
@Component
class VesselMasterUpdatesImpl(private val vessels: VesselRepository, private val audit: AuditService) : VesselMasterUpdates {

    @Transactional(readOnly = true)
    override fun exists(vesselId: UUID): Boolean = vessels.existsById(vesselId)

    @Transactional(propagation = Propagation.MANDATORY)
    override fun markVerified(vesselId: UUID, loaM: BigDecimal?, beamM: BigDecimal?, dwtT: BigDecimal?) {
        val vessel = vessels.findByIdOrNull(vesselId) ?: throw NotFoundException("Vessel")
        val before = vessel.toAuditMap()
        loaM?.let { vessel.loaM = it.setScale(2, RoundingMode.HALF_UP) }
        beamM?.let { vessel.beamM = it.setScale(2, RoundingMode.HALF_UP) }
        dwtT?.let { vessel.dwtT = it.setScale(1, RoundingMode.HALF_UP) }
        vessel.particularsStatus = ParticularsStatus.VERIFIED
        vessels.saveAndFlush(vessel)
        audit.record(AuditEntry(AuditAction.UPDATE, "VESSEL", vesselId, details = AuditDiff.changes(before, vessel.toAuditMap()) +
            mapOf("source" to "verified particulars")))
    }
}
