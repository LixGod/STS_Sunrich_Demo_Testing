package com.ship2ship.features.masters

import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.web.FieldError
import com.ship2ship.core.web.Paging
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

// Group-wide reference lists (SRS §4 masters). Each list = entity + repository + DTO + request + service + controller.

private const val CODE_PATTERN = "^[A-Za-z0-9][A-Za-z0-9-]{0,19}$"
private const val CODE_MESSAGE = "Use 1-20 letters, digits or hyphens."

enum class UnitDimension { MASS, VOLUME, OTHER }
enum class CargoCategory { PETROLEUM, LPG, LNG, CHEMICAL, OTHER }
enum class CostHeadKind { EXPENSE, REVENUE, BOTH }

// ---------- Units of measure ----------

@Entity
@Table(name = "unit_of_measure")
class UnitOfMeasureEntity(code: String, name: String, @Enumerated(EnumType.STRING) @Column(nullable = false) var dimension: UnitDimension) :
    CodeListEntity(code, name) {
    override fun details() = mapOf("dimension" to dimension.name)
}

interface UnitOfMeasureRepository : CodeListRepository<UnitOfMeasureEntity>

data class UnitDto(val id: UUID, val code: String, val name: String, val dimension: UnitDimension, val active: Boolean, val updatedAt: Instant?, val version: Long?)

data class UnitRequest(
    @field:NotBlank @field:Pattern(regexp = CODE_PATTERN, message = CODE_MESSAGE) override val code: String,
    @field:NotBlank @field:Size(max = 100) override val name: String,
    @field:NotNull val dimension: UnitDimension,
) : CodeListRequest

@Service
class UnitService(repository: UnitOfMeasureRepository, accessPolicy: AccessPolicy, audit: AuditService) :
    CodeListService<UnitOfMeasureEntity, UnitRequest, UnitDto>(
        repository, accessPolicy, audit, "UNIT_OF_MEASURE", Permissions.MASTER_COMMERCIAL_READ, Permissions.MASTER_COMMERCIAL_WRITE,
    ) {
    override fun newEntity(request: UnitRequest) = UnitOfMeasureEntity(request.code, request.name.trim(), request.dimension)
    override fun apply(entity: UnitOfMeasureEntity, request: UnitRequest) { entity.dimension = request.dimension }
    override fun toDto(entity: UnitOfMeasureEntity) =
        UnitDto(entity.id, entity.code, entity.name, entity.dimension, entity.active, entity.updatedAt, entity.version)
}

@RestController
@RequestMapping("/api/v1/masters/units")
class UnitController(service: UnitService, paging: Paging) :
    CodeListController<UnitDto, UnitRequest>(service, paging, { it.version }, { it.id })

// ---------- Cargo grades ----------

@Entity
@Table(name = "cargo_grade")
class CargoGradeEntity(
    code: String,
    name: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var category: CargoCategory,
    @Column(columnDefinition = "BINARY(16)") var defaultUnitId: UUID?,
) : CodeListEntity(code, name) {
    override fun details() = mapOf("category" to category.name, "defaultUnitId" to defaultUnitId)
}

interface CargoGradeRepository : CodeListRepository<CargoGradeEntity>

data class CargoGradeDto(
    val id: UUID, val code: String, val name: String, val category: CargoCategory, val defaultUnitId: UUID?,
    val active: Boolean, val updatedAt: Instant?, val version: Long?,
)

data class CargoGradeRequest(
    @field:NotBlank @field:Pattern(regexp = CODE_PATTERN, message = CODE_MESSAGE) override val code: String,
    @field:NotBlank @field:Size(max = 100) override val name: String,
    @field:NotNull val category: CargoCategory,
    val defaultUnitId: UUID? = null,
) : CodeListRequest

@Service
class CargoGradeService(
    repository: CargoGradeRepository, accessPolicy: AccessPolicy, audit: AuditService,
    private val units: UnitOfMeasureRepository,
) : CodeListService<CargoGradeEntity, CargoGradeRequest, CargoGradeDto>(
    repository, accessPolicy, audit, "CARGO_GRADE", Permissions.MASTER_COMMERCIAL_READ, Permissions.MASTER_COMMERCIAL_WRITE,
) {
    override fun validate(request: CargoGradeRequest) = listOfNotNull(
        request.defaultUnitId?.takeIf { !units.existsById(it) }?.let { FieldError("defaultUnitId", "unknown", "Unknown unit.") },
    )
    override fun newEntity(request: CargoGradeRequest) =
        CargoGradeEntity(request.code, request.name.trim(), request.category, request.defaultUnitId)
    override fun apply(entity: CargoGradeEntity, request: CargoGradeRequest) {
        entity.category = request.category
        entity.defaultUnitId = request.defaultUnitId
    }
    override fun toDto(entity: CargoGradeEntity) = CargoGradeDto(
        entity.id, entity.code, entity.name, entity.category, entity.defaultUnitId, entity.active, entity.updatedAt, entity.version,
    )
}

@RestController
@RequestMapping("/api/v1/masters/cargo-grades")
class CargoGradeController(service: CargoGradeService, paging: Paging) :
    CodeListController<CargoGradeDto, CargoGradeRequest>(service, paging, { it.version }, { it.id })

// ---------- Currencies ----------

@Entity
@Table(name = "currency")
class CurrencyEntity(code: String, name: String, @Column(nullable = false) var minorUnits: Int) : CodeListEntity(code, name) {
    override fun details() = mapOf("minorUnits" to minorUnits)
}

interface CurrencyRepository : CodeListRepository<CurrencyEntity> {
    fun findByCode(code: String): CurrencyEntity?
}

data class CurrencyDto(val id: UUID, val code: String, val name: String, val minorUnits: Int, val active: Boolean, val updatedAt: Instant?, val version: Long?)

data class CurrencyRequest(
    @field:NotBlank @field:Pattern(regexp = "^[A-Za-z]{3}$", message = "Use an ISO 4217 code.") override val code: String,
    @field:NotBlank @field:Size(max = 100) override val name: String,
    @field:NotNull @field:Min(0) @field:Max(4) val minorUnits: Int,
) : CodeListRequest

@Service
class CurrencyService(repository: CurrencyRepository, accessPolicy: AccessPolicy, audit: AuditService) :
    CodeListService<CurrencyEntity, CurrencyRequest, CurrencyDto>(
        repository, accessPolicy, audit, "CURRENCY", Permissions.MASTER_FINANCE_READ, Permissions.MASTER_FINANCE_WRITE,
    ) {
    override fun validate(request: CurrencyRequest) =
        listOfNotNull(OrganisationValidation.currency("code", request.code.trim().uppercase()))
    override fun newEntity(request: CurrencyRequest) = CurrencyEntity(request.code, request.name.trim(), request.minorUnits)
    override fun apply(entity: CurrencyEntity, request: CurrencyRequest) { entity.minorUnits = request.minorUnits }
    override fun toDto(entity: CurrencyEntity) =
        CurrencyDto(entity.id, entity.code, entity.name, entity.minorUnits, entity.active, entity.updatedAt, entity.version)
}

@RestController
@RequestMapping("/api/v1/masters/currencies")
class CurrencyController(service: CurrencyService, paging: Paging) :
    CodeListController<CurrencyDto, CurrencyRequest>(service, paging, { it.version }, { it.id })

// ---------- Cost heads ----------

@Entity
@Table(name = "cost_head")
class CostHeadEntity(code: String, name: String, @Enumerated(EnumType.STRING) @Column(nullable = false) var kind: CostHeadKind) :
    CodeListEntity(code, name) {
    override fun details() = mapOf("kind" to kind.name)
}

interface CostHeadRepository : CodeListRepository<CostHeadEntity> {
    fun findByCode(code: String): CostHeadEntity?
}

data class CostHeadDto(val id: UUID, val code: String, val name: String, val kind: CostHeadKind, val active: Boolean, val updatedAt: Instant?, val version: Long?)

data class CostHeadRequest(
    @field:NotBlank @field:Pattern(regexp = CODE_PATTERN, message = CODE_MESSAGE) override val code: String,
    @field:NotBlank @field:Size(max = 100) override val name: String,
    @field:NotNull val kind: CostHeadKind,
) : CodeListRequest

@Service
class CostHeadService(repository: CostHeadRepository, accessPolicy: AccessPolicy, audit: AuditService) :
    CodeListService<CostHeadEntity, CostHeadRequest, CostHeadDto>(
        repository, accessPolicy, audit, "COST_HEAD", Permissions.MASTER_FINANCE_READ, Permissions.MASTER_FINANCE_WRITE,
    ) {
    override fun newEntity(request: CostHeadRequest) = CostHeadEntity(request.code, request.name.trim(), request.kind)
    override fun apply(entity: CostHeadEntity, request: CostHeadRequest) { entity.kind = request.kind }
    override fun toDto(entity: CostHeadEntity) =
        CostHeadDto(entity.id, entity.code, entity.name, entity.kind, entity.active, entity.updatedAt, entity.version)
}

@RestController
@RequestMapping("/api/v1/masters/cost-heads")
class CostHeadController(service: CostHeadService, paging: Paging) :
    CodeListController<CostHeadDto, CostHeadRequest>(service, paging, { it.version }, { it.id })

