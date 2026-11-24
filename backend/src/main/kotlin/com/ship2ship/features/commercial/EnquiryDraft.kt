package com.ship2ship.features.commercial

import com.ship2ship.core.persistence.BaseEntity
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.Versioning
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Size
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "commercial_enquiry_draft")
class EnquiryDraftEntity(
    @Column(nullable = false, columnDefinition = "BINARY(16)") var ownerId: UUID,
    @Column(nullable = false, columnDefinition = "LONGTEXT") var payload: String,
) : BaseEntity()

interface EnquiryDraftRepository : JpaRepository<EnquiryDraftEntity, UUID> {
    fun findByOwnerIdOrderByUpdatedAtDesc(ownerId: UUID): List<EnquiryDraftEntity>
}

data class DraftCargo(
    val cargoGradeId: UUID? = null,
    @field:DecimalMin(value = "0", inclusive = false) @field:Digits(integer = 15, fraction = 3) val quantity: BigDecimal? = null,
    val unitId: UUID? = null,
)

data class DraftVessel(val vesselId: UUID? = null, val etaLocal: LocalDateTime? = null)
data class DraftSpecialItem(
    @field:Size(max = 300) val description: String? = null,
    val paid: Boolean = false,
    @field:DecimalMin(value = "0", inclusive = false) @field:Digits(integer = 15, fraction = 4) val salePriceUsd: BigDecimal? = null,
)

/** All fields may be absent while the user gathers the information for a numbered enquiry. */
data class EnquiryDraftRequest(
    val branchId: UUID? = null,
    val receivedAt: Instant? = null,
    val clientId: UUID? = null,
    @field:Size(max = 250) val clientLabel: String? = null,
    val clientContactId: UUID? = null,
    @field:Size(max = 20) val operationType: String? = null,
    @field:Size(max = 20) val cargoTypes: List<String> = emptyList(),
    @field:DecimalMin(value = "0", inclusive = false) @field:Digits(integer = 5, fraction = 1) val expectedDurationHours: BigDecimal? = null,
    @field:Size(max = 4000) val scopeOfServices: String? = null,
    @field:Size(max = 4000) val specialRequirements: String? = null,
    @field:Size(max = 255) val sourceReference: String? = null,
    @field:Valid val motherVessel: DraftVessel? = null,
    @field:Size(max = 250) val motherVesselLabel: String? = null,
    @field:Valid val daughterVessel: DraftVessel? = null,
    @field:Size(max = 250) val daughterVesselLabel: String? = null,
    @field:Valid @field:Size(max = 10) val cargo: List<DraftCargo> = emptyList(),
    @field:Valid @field:Size(max = 20) val specialItems: List<DraftSpecialItem> = emptyList(),
)

data class EnquiryDraftDto(val id: UUID, val data: EnquiryDraftRequest, val updatedAt: Instant?, val version: Long?)

@Service
class EnquiryDraftService(
    private val drafts: EnquiryDraftRepository,
    private val access: AccessPolicy,
    private val mapper: JsonMapper,
) {
    @Transactional(readOnly = true)
    fun list(): List<EnquiryDraftDto> = drafts.findByOwnerIdOrderByUpdatedAtDesc(user()).map(::dto)

    @Transactional(readOnly = true)
    fun get(id: UUID): EnquiryDraftDto = dto(owned(id))

    @Transactional
    fun create(request: EnquiryDraftRequest): EnquiryDraftDto = dto(drafts.saveAndFlush(EnquiryDraftEntity(user(), mapper.writeValueAsString(request))))

    @Transactional
    fun update(id: UUID, version: Long, request: EnquiryDraftRequest): EnquiryDraftDto {
        val draft = owned(id)
        Versioning.checkCurrent(version, draft.version)
        draft.payload = mapper.writeValueAsString(request)
        return dto(drafts.saveAndFlush(draft))
    }

    @Transactional
    fun delete(id: UUID, version: Long) {
        val draft = owned(id)
        Versioning.checkCurrent(version, draft.version)
        drafts.delete(draft)
    }

    private fun owned(id: UUID): EnquiryDraftEntity = drafts.findByIdOrNull(id)?.takeIf { it.ownerId == user() }
        ?: throw NotFoundException("Enquiry draft")

    private fun user(): UUID {
        access.requireAny(Permissions.ENQUIRY_WRITE)
        return access.currentUser().id
    }

    private fun dto(draft: EnquiryDraftEntity) = EnquiryDraftDto(
        draft.id, mapper.readValue(draft.payload, EnquiryDraftRequest::class.java), draft.updatedAt, draft.version,
    )
}

@RestController
@RequestMapping("/api/v1/enquiry-drafts")
class EnquiryDraftController(private val service: EnquiryDraftService) {
    @GetMapping
    fun list(): List<EnquiryDraftDto> = service.list()

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): ResponseEntity<EnquiryDraftDto> = service.get(id).let { Versioning.ok(it, it.version) }

    @PostMapping
    fun create(@Valid @RequestBody request: EnquiryDraftRequest): ResponseEntity<EnquiryDraftDto> =
        ResponseEntity.status(201).body(service.create(request))

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
               @Valid @RequestBody request: EnquiryDraftRequest): ResponseEntity<EnquiryDraftDto> =
        service.update(id, Versioning.requireIfMatch(ifMatch), request).let { Versioning.ok(it, it.version) }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?): ResponseEntity<Void> {
        service.delete(id, Versioning.requireIfMatch(ifMatch))
        return ResponseEntity.noContent().build()
    }
}
