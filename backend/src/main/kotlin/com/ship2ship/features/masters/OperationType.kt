package com.ship2ship.features.masters

import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.web.Paging
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "operation_type")
class OperationTypeEntity(code: String, name: String) : CodeListEntity(code, name) {
    override fun details(): Map<String, Any?> = emptyMap()
}

interface OperationTypeRepository : CodeListRepository<OperationTypeEntity> {
    fun findByCode(code: String): OperationTypeEntity?
}

data class OperationTypeDto(val id: UUID, val code: String, val name: String, val active: Boolean, val updatedAt: Instant?, val version: Long?)

data class OperationTypeRequest(
    @field:NotBlank @field:Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9-]{0,19}$") override val code: String,
    @field:NotBlank @field:Size(max = 100) override val name: String,
) : CodeListRequest

@org.springframework.stereotype.Service
class OperationTypeService(repository: OperationTypeRepository, access: AccessPolicy, audit: AuditService) :
    CodeListService<OperationTypeEntity, OperationTypeRequest, OperationTypeDto>(
        repository, access, audit, "OPERATION_TYPE", Permissions.MASTER_COMMERCIAL_READ, Permissions.MASTER_COMMERCIAL_WRITE,
    ) {
    override fun newEntity(request: OperationTypeRequest) = OperationTypeEntity(request.code, request.name.trim())
    override fun apply(entity: OperationTypeEntity, request: OperationTypeRequest) = Unit
    override fun toDto(entity: OperationTypeEntity) = OperationTypeDto(
        entity.id, entity.code, entity.name, entity.active, entity.updatedAt, entity.version,
    )
}

@RestController
@RequestMapping("/api/v1/masters/operation-types")
class OperationTypeController(service: OperationTypeService, paging: Paging) :
    CodeListController<OperationTypeDto, OperationTypeRequest>(service, paging, { it.version }, { it.id })
