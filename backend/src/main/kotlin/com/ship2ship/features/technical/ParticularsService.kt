package com.ship2ship.features.technical

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditDiff
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.security.UserDirectory
import com.ship2ship.core.vessels.VesselMasterUpdates
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.BusinessRuleException
import com.ship2ship.core.web.ConflictException
import com.ship2ship.core.web.FieldError
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.Versioning
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
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
import jakarta.validation.Valid
import java.math.BigDecimal
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Vessel particulars (FR-TECH-01): manual entry beside the source documents, each value citing document and page,
 * submission, and verification by a different person (or return with a reason). Verification copies LOA, beam and
 * DWT to the vessel master and marks it VERIFIED. Checks produce warnings only; nothing is calculated or invented.
 */
@Service
class ParticularsService(
    private val versions: ParticularsVersionRepository,
    private val fields: ParticularFieldRepository,
    private val documents: VesselDocumentService,
    private val vessels: VesselMasterUpdates,
    private val users: UserDirectory,
    private val accessPolicy: AccessPolicy,
    private val audit: AuditService,
    private val entityManager: EntityManager,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun catalogue(): List<ParticularFieldDto> {
        accessPolicy.requireAny(Permissions.TECH_VESSEL_READ)
        return fields.findByActiveTrueOrderBySortOrder().map {
            ParticularFieldDto(it.code, it.label, it.fieldGroup, it.dataType, it.unit, it.requiredForPlanning)
        }
    }

    @Transactional(readOnly = true)
    fun overview(vesselId: UUID): ParticularsOverviewDto {
        accessPolicy.requireAny(Permissions.TECH_VESSEL_READ)
        if (!vessels.exists(vesselId)) throw NotFoundException("Vessel")
        val list = versions.findByVesselIdOrderByVersionNoDesc(vesselId)
        return ParticularsOverviewDto(
            vesselId,
            list.map { ParticularsSummaryDto(it.id, it.versionNo, it.status, it.verifiedAt, it.updatedAt) },
            canCreateVersion = can(Permissions.TECH_VESSEL_WRITE) && list.none { it.status in OPEN },
        )
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): ParticularsDto {
        accessPolicy.requireAny(Permissions.TECH_VESSEL_READ)
        return toDto(load(id))
    }

    /** A new draft version, copied from the newest one (values and sources; verification flags reset). */
    @Transactional
    fun create(vesselId: UUID): ParticularsDto {
        accessPolicy.requireAny(Permissions.TECH_VESSEL_WRITE)
        if (!vessels.exists(vesselId)) throw NotFoundException("Vessel")
        val existing = versions.findByVesselIdOrderByVersionNoDesc(vesselId)
        if (existing.any { it.status in OPEN }) {
            throw ConflictException("PARTICULARS_VERSION_OPEN", "Finish the open version of this vessel's particulars first.")
        }
        val version = ParticularsVersionEntity(vesselId, (existing.maxOfOrNull { it.versionNo } ?: 0) + 1)
        existing.firstOrNull()?.let { from ->
            version.notes = from.notes
            from.values.forEach { version.values += ParticularValueEntity(it.fieldCode, it.valueText, it.valueNumber, it.sourceDocumentId, it.sourcePage) }
        }
        versions.saveAndFlush(version)
        audit.record(AuditEntry(AuditAction.CREATE, ENTITY, version.id, details = mapOf("vesselId" to vesselId,
            "versionNo" to version.versionNo, "copiedFrom" to existing.firstOrNull()?.versionNo) + version.toAuditMap()))
        return toDto(version)
    }

    @Transactional
    fun update(id: UUID, expectedVersion: Long, request: ParticularsRequest): ParticularsDto {
        accessPolicy.requireAny(Permissions.TECH_VESSEL_WRITE)
        val version = load(id)
        Versioning.checkCurrent(expectedVersion, version.version)
        requireStatus(version, ParticularsStatus.DRAFT)
        val parsed = validate(version.vesselId, request.values)

        val before = version.toAuditMap()
        version.notes = request.notes?.trim()?.ifBlank { null }
        parsed.forEach { input ->
            val existing = version.value(input.fieldCode)
            if (existing == null) {
                version.values += ParticularValueEntity(input.fieldCode, input.text, input.number, input.sourceDocumentId, input.sourcePage)
            } else {
                existing.valueText = input.text
                existing.valueNumber = input.number
                existing.sourceDocumentId = input.sourceDocumentId
                existing.sourcePage = input.sourcePage
                existing.verified = false
            }
        }
        version.values.removeIf { value -> parsed.none { it.fieldCode == value.fieldCode } }
        // Values are child rows: advance the version so If-Match protects the whole set.
        entityManager.lock(version, LockModeType.PESSIMISTIC_FORCE_INCREMENT)
        versions.saveAndFlush(version)
        audit.record(AuditEntry(AuditAction.UPDATE, ENTITY, id, details = AuditDiff.changes(before, version.toAuditMap())))
        return toDto(version)
    }

    @Transactional
    fun submit(id: UUID, expectedVersion: Long): ParticularsDto {
        accessPolicy.requireAny(Permissions.TECH_VESSEL_WRITE)
        val version = load(id)
        Versioning.checkCurrent(expectedVersion, version.version)
        requireStatus(version, ParticularsStatus.DRAFT)
        if (version.values.isEmpty()) throw BusinessRuleException("PARTICULARS_EMPTY", "Enter at least one value before submitting.")
        // Every value must say where it was read (AR-03): unverifiable values cannot go to review.
        val missing = version.values.filter { it.sourceDocumentId == null || it.sourcePage == null }.sortedBy { it.fieldCode }
        if (missing.isNotEmpty()) {
            throw BadRequestException("SOURCE_REQUIRED", "Each value needs its source document and page.",
                missing.map { FieldError("values.${it.fieldCode}", "sourceRequired", null) })
        }
        version.status = ParticularsStatus.SUBMITTED
        version.submittedBy = accessPolicy.currentUser().id
        version.submittedAt = now()
        version.returnedReason = null
        versions.saveAndFlush(version)
        status(version, ParticularsStatus.DRAFT, null)
        return toDto(version)
    }

    @Transactional
    fun returnForCorrection(id: UUID, expectedVersion: Long, request: ReturnRequest): ParticularsDto {
        accessPolicy.requireAny(Permissions.TECH_VESSEL_VERIFY)
        val version = load(id)
        Versioning.checkCurrent(expectedVersion, version.version)
        requireStatus(version, ParticularsStatus.SUBMITTED)
        version.status = ParticularsStatus.DRAFT
        version.returnedReason = request.reason.trim()
        versions.saveAndFlush(version)
        status(version, ParticularsStatus.SUBMITTED, version.returnedReason)
        return toDto(version)
    }

    @Transactional
    fun verify(id: UUID, expectedVersion: Long, request: VerifyRequest): ParticularsDto {
        accessPolicy.requireAny(Permissions.TECH_VESSEL_VERIFY)
        val version = load(id)
        Versioning.checkCurrent(expectedVersion, version.version)
        requireStatus(version, ParticularsStatus.SUBMITTED)
        val actor = accessPolicy.currentUser().id
        if (version.submittedBy == actor) {
            throw BusinessRuleException("SELF_VERIFICATION_FORBIDDEN", "Particulars must be verified by someone other than the person who submitted them.")
        }
        val confirmed = request.confirmedFieldCodes.toSet()
        val unconfirmed = version.values.map { it.fieldCode }.filter { it !in confirmed }.sorted()
        if (unconfirmed.isNotEmpty()) {
            throw BadRequestException("VALIDATION_FAILED", "Confirm every value against its source before verifying.",
                unconfirmed.map { FieldError("values.$it", "notConfirmed", null) })
        }

        versions.findByVesselIdOrderByVersionNoDesc(version.vesselId).filter { it.status == ParticularsStatus.VERIFIED }.forEach { older ->
            older.status = ParticularsStatus.SUPERSEDED
            versions.saveAndFlush(older)
            status(older, ParticularsStatus.VERIFIED, "Superseded by version ${version.versionNo}")
        }
        version.values.forEach { it.verified = true }
        version.status = ParticularsStatus.VERIFIED
        version.verifiedBy = actor
        version.verifiedAt = now()
        versions.saveAndFlush(version)
        vessels.markVerified(version.vesselId, number(version, "LOA"), number(version, "BEAM"), number(version, "DWT"))
        status(version, ParticularsStatus.SUBMITTED, null)
        return toDto(version)
    }

    // ---------- helpers ----------

    private data class Parsed(val fieldCode: String, val text: String, val number: BigDecimal?, val sourceDocumentId: UUID?, val sourcePage: Int?)

    private fun validate(vesselId: UUID, values: List<ParticularValueRequest>): List<Parsed> {
        val catalogue = fields.findByActiveTrueOrderBySortOrder().associateBy { it.code }
        val vesselDocuments = documents.ofVessel(vesselId)
        val errors = mutableListOf<FieldError>()
        val parsed = values.mapNotNull { input ->
            val code = input.fieldCode.trim()
            val field = catalogue[code]
            val path = "values.$code"
            if (field == null) {
                errors += FieldError(path, "unknown", "Unknown or inactive field.")
                return@mapNotNull null
            }
            val text = input.value.trim()
            val number = if (field.dataType == "NUMBER") {
                text.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 && it.scale() <= 3 && it.precision() - it.scale() <= 9 }
                    ?: run { errors += FieldError(path, "number", "Enter a number of at most 9 digits and 3 decimals."); null }
            } else null
            if (input.sourceDocumentId != null && input.sourceDocumentId !in vesselDocuments) {
                errors += FieldError("$path.source", "unknown", "The source must be a document of this vessel.")
            }
            Parsed(code, number?.stripTrailingZeros()?.toPlainString() ?: text, number, input.sourceDocumentId, input.sourcePage)
        }
        if (parsed.map { it.fieldCode }.toSet().size != parsed.size) errors += FieldError("values", "duplicate", "Each field may appear only once.")
        if (errors.isNotEmpty()) throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", errors)
        return parsed
    }

    private fun warnings(version: ParticularsVersionEntity): List<ParticularsWarningDto> {
        val result = mutableListOf<ParticularsWarningDto>()
        val missing = fields.findByActiveTrueOrderBySortOrder().filter { it.requiredForPlanning && version.value(it.code) == null }
        if (missing.isNotEmpty()) {
            result += ParticularsWarningDto("MISSING_FOR_PLANNING", missing.map { it.code },
                "Needed for the JPO and mooring plan: ${missing.joinToString { it.label }}.")
        }
        val loa = number(version, "LOA")
        val bow = number(version, "BOW_TO_MANIFOLD")
        val stern = number(version, "STERN_TO_MANIFOLD")
        if (loa != null && bow != null && stern != null && bow.add(stern).compareTo(loa) != 0) {
            result += ParticularsWarningDto("LOA_MISMATCH", listOf("LOA", "BOW_TO_MANIFOLD", "STERN_TO_MANIFOLD"),
                "Bow to manifold + stern to manifold = ${bow.add(stern).toPlainString()} m, but LOA is ${loa.toPlainString()} m.")
        }
        return result
    }

    private fun number(version: ParticularsVersionEntity, code: String): BigDecimal? = version.value(code)?.valueNumber

    private fun toDto(version: ParticularsVersionEntity): ParticularsDto {
        val names = users.names(listOfNotNull(version.submittedBy, version.verifiedBy))
        val user = accessPolicy.currentUser()
        val actions = buildList {
            when (version.status) {
                ParticularsStatus.DRAFT -> if (can(Permissions.TECH_VESSEL_WRITE)) addAll(listOf(ParticularsAction.EDIT, ParticularsAction.SUBMIT))
                ParticularsStatus.SUBMITTED -> if (can(Permissions.TECH_VESSEL_VERIFY)) {
                    add(ParticularsAction.RETURN)
                    if (version.submittedBy != user.id) add(ParticularsAction.VERIFY)
                }
                else -> Unit
            }
        }
        return ParticularsDto(
            id = version.id,
            vesselId = version.vesselId,
            versionNo = version.versionNo,
            status = version.status,
            notes = version.notes,
            values = version.values.sortedBy { it.fieldCode }.map {
                ParticularValueDto(it.fieldCode, it.valueText, it.sourceDocumentId, it.sourcePage, it.verified)
            },
            warnings = warnings(version),
            submittedByName = version.submittedBy?.let(names::get),
            submittedAt = version.submittedAt,
            verifiedByName = version.verifiedBy?.let(names::get),
            verifiedAt = version.verifiedAt,
            returnedReason = version.returnedReason,
            allowedActions = actions,
            updatedAt = version.updatedAt,
            version = version.version,
        )
    }

    private fun requireStatus(version: ParticularsVersionEntity, expected: ParticularsStatus) {
        if (version.status != expected) {
            throw BusinessRuleException("INVALID_TRANSITION", "Particulars in status ${version.status} cannot do this.")
        }
    }

    private fun status(version: ParticularsVersionEntity, from: ParticularsStatus, reason: String?) =
        audit.record(AuditEntry(AuditAction.STATUS_CHANGE, ENTITY, version.id, details = mapOf(
            "vesselId" to version.vesselId, "versionNo" to version.versionNo, "from" to from.name, "to" to version.status.name, "reason" to reason,
        )))

    private fun can(permission: String) = !accessPolicy.currentUser().scopeFor(permission).isEmpty

    private fun now() = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private fun load(id: UUID) = versions.findByIdOrNull(id) ?: throw NotFoundException("Vessel particulars")

    private companion object {
        const val ENTITY = "VESSEL_PARTICULARS"
        val OPEN = setOf(ParticularsStatus.DRAFT, ParticularsStatus.SUBMITTED)
    }
}

@RestController
@RequestMapping("/api/v1/technical")
class ParticularsController(private val service: ParticularsService) {

    @GetMapping("/particular-fields")
    fun catalogue(): List<ParticularFieldDto> = service.catalogue()

    @GetMapping("/vessels/{vesselId}/particulars")
    fun overview(@PathVariable vesselId: UUID): ParticularsOverviewDto = service.overview(vesselId)

    @PostMapping("/vessels/{vesselId}/particulars")
    fun create(@PathVariable vesselId: UUID): ResponseEntity<ParticularsDto> =
        service.create(vesselId).let { ResponseEntity.status(201).eTag(Versioning.etag(it.version)).body(it) }

    @GetMapping("/particulars/{id}")
    fun get(@PathVariable id: UUID): ResponseEntity<ParticularsDto> = service.get(id).let { Versioning.ok(it, it.version) }

    @PutMapping("/particulars/{id}")
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: ParticularsRequest,
    ): ResponseEntity<ParticularsDto> = service.update(id, Versioning.requireIfMatch(ifMatch), request).let { Versioning.ok(it, it.version) }

    @PostMapping("/particulars/{id}/submit")
    fun submit(@PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?): ResponseEntity<ParticularsDto> =
        service.submit(id, Versioning.requireIfMatch(ifMatch)).let { Versioning.ok(it, it.version) }

    @PostMapping("/particulars/{id}/return")
    fun returnForCorrection(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: ReturnRequest,
    ): ResponseEntity<ParticularsDto> = service.returnForCorrection(id, Versioning.requireIfMatch(ifMatch), request).let { Versioning.ok(it, it.version) }

    @PostMapping("/particulars/{id}/verify")
    fun verify(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: VerifyRequest,
    ): ResponseEntity<ParticularsDto> = service.verify(id, Versioning.requireIfMatch(ifMatch), request).let { Versioning.ok(it, it.version) }
}
