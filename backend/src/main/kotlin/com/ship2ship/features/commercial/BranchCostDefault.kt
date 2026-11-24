package com.ship2ship.features.commercial

import com.ship2ship.core.persistence.BaseEntity
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.EquipmentLookup
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.security.OrganisationLookup
import com.ship2ship.core.security.ReferenceLookup
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.FieldError
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.Versioning
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotNull
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
import java.math.BigDecimal
import java.util.UUID

@Entity
@Table(name = "branch_cost_default")
class BranchCostDefaultEntity(
    @Column(nullable = false, columnDefinition = "BINARY(16)") var branchId: UUID,
    @Column(nullable = false, columnDefinition = "BINARY(16)") var costHeadId: UUID,
    @Column(length = 300) var description: String?,
    @Column(nullable = false, precision = 18, scale = 3) var quantity: BigDecimal,
    @Column(nullable = false, precision = 19, scale = 4) var unitPrice: BigDecimal,
) : BaseEntity() {
    var active: Boolean = true
    @Column(columnDefinition = "BINARY(16)") var equipmentModelId: UUID? = null
}

interface BranchCostDefaultRepository : JpaRepository<BranchCostDefaultEntity, UUID> {
    fun findByBranchIdAndActiveTrueOrderByCreatedAt(branchId: UUID): List<BranchCostDefaultEntity>
    fun findByBranchIdOrderByCreatedAt(branchId: UUID): List<BranchCostDefaultEntity>
}

data class BranchCostDefaultRequest(
    @field:NotNull val costHeadId: UUID,
    @field:Size(max = 300) val description: String? = null,
    @field:NotNull @field:DecimalMin(value = "0", inclusive = false) @field:Digits(integer = 15, fraction = 3)
    val quantity: BigDecimal,
    @field:NotNull @field:DecimalMin("0") @field:Digits(integer = 15, fraction = 4)
    val unitPrice: BigDecimal,
    val active: Boolean = true,
    val equipmentModelId: UUID? = null,
)

data class BranchCostDefaultDto(
    val id: UUID, val branchId: UUID, val costHeadId: UUID, val description: String?,
    val quantity: BigDecimal, val unitPrice: BigDecimal, val active: Boolean, val equipmentModelId: UUID?, val version: Long?,
)

@Service
class BranchCostDefaultService(
    private val defaults: BranchCostDefaultRepository,
    private val organisation: OrganisationLookup,
    private val references: ReferenceLookup,
    private val equipment: EquipmentLookup,
    private val access: AccessPolicy,
) {
    @Transactional(readOnly = true)
    fun list(branchId: UUID): List<BranchCostDefaultDto> {
        access.requireBranch(Permissions.MASTER_FINANCE_READ, branchId)
        return defaults.findByBranchIdOrderByCreatedAt(branchId).map(::dto)
    }

    @Transactional(readOnly = true)
    fun equipmentOptions(branchId: UUID): List<com.ship2ship.core.security.EquipmentOptionRef> {
        access.requireBranch(Permissions.MASTER_FINANCE_READ, branchId)
        return equipment.equipmentOptions().map { option ->
            option.copy(standardRateUsd = option.standardRate.takeIf { option.standardRateCurrency == "USD" })
        }
    }

    @Transactional
    fun create(branchId: UUID, request: BranchCostDefaultRequest): BranchCostDefaultDto {
        access.requireBranch(Permissions.MASTER_FINANCE_WRITE, branchId)
        validate(branchId, request)
        return dto(defaults.saveAndFlush(BranchCostDefaultEntity(branchId, request.costHeadId,
            request.description?.trim()?.ifBlank { null }, request.quantity, request.unitPrice).also {
                it.active = request.active
                it.equipmentModelId = request.equipmentModelId
            }))
    }

    @Transactional
    fun update(branchId: UUID, id: UUID, version: Long, request: BranchCostDefaultRequest): BranchCostDefaultDto {
        access.requireBranch(Permissions.MASTER_FINANCE_WRITE, branchId)
        val row = defaults.findByIdOrNull(id)?.takeIf { it.branchId == branchId } ?: throw NotFoundException("Branch cost default")
        Versioning.checkCurrent(version, row.version)
        validate(branchId, request)
        row.costHeadId = request.costHeadId
        row.description = request.description?.trim()?.ifBlank { null }
        row.quantity = request.quantity
        row.unitPrice = request.unitPrice
        row.active = request.active
        row.equipmentModelId = request.equipmentModelId
        return dto(defaults.saveAndFlush(row))
    }

    @Transactional
    fun delete(branchId: UUID, id: UUID, version: Long) {
        access.requireBranch(Permissions.MASTER_FINANCE_WRITE, branchId)
        val row = defaults.findByIdOrNull(id)?.takeIf { it.branchId == branchId } ?: throw NotFoundException("Branch cost default")
        Versioning.checkCurrent(version, row.version)
        defaults.delete(row)
        defaults.flush()
    }

    private fun validate(branchId: UUID, request: BranchCostDefaultRequest) {
        val branch = organisation.branch(branchId)
        val head = references.costHeads(listOf(request.costHeadId))[request.costHeadId]
        val model = request.equipmentModelId?.let { id -> equipment.equipmentOptions().firstOrNull { it.modelId == id } }
        val errors = listOfNotNull(
            FieldError("branchId", "unknown", "Choose an active branch.").takeIf { branch?.active != true },
            FieldError("costHeadId", "unknown", "Choose an active expense cost head.")
                .takeIf { head?.active != true || head.kind !in setOf("EXPENSE", "BOTH") },
            FieldError("equipmentModelId", "unknown", "Choose an active equipment model.")
                .takeIf { request.equipmentModelId != null && model == null },
            FieldError("costHeadId", "equipment", "Use the cost head configured for this equipment category.")
                .takeIf { model?.costHeadId != null && model.costHeadId != request.costHeadId },
        )
        if (errors.isNotEmpty()) throw BadRequestException("VALIDATION_FAILED", "Invalid branch default.", errors)
    }

    private fun dto(row: BranchCostDefaultEntity) = BranchCostDefaultDto(
        row.id, row.branchId, row.costHeadId, row.description, row.quantity, row.unitPrice, row.active, row.equipmentModelId, row.version,
    )
}

@RestController
@RequestMapping("/api/v1/commercial/branches/{branchId}/cost-defaults")
class BranchCostDefaultController(private val service: BranchCostDefaultService) {
    @GetMapping("/equipment-options")
    fun equipmentOptions(@PathVariable branchId: UUID) = service.equipmentOptions(branchId)
    @GetMapping
    fun list(@PathVariable branchId: UUID) = service.list(branchId)

    @PostMapping
    fun create(@PathVariable branchId: UUID, @Valid @RequestBody request: BranchCostDefaultRequest): ResponseEntity<BranchCostDefaultDto> =
        ResponseEntity.status(201).body(service.create(branchId, request))

    @PutMapping("/{id}")
    fun update(@PathVariable branchId: UUID, @PathVariable id: UUID,
               @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
               @Valid @RequestBody request: BranchCostDefaultRequest): ResponseEntity<BranchCostDefaultDto> =
        service.update(branchId, id, Versioning.requireIfMatch(ifMatch), request).let { Versioning.ok(it, it.version) }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable branchId: UUID, @PathVariable id: UUID,
               @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?): ResponseEntity<Void> {
        service.delete(branchId, id, Versioning.requireIfMatch(ifMatch))
        return ResponseEntity.noContent().build()
    }
}
