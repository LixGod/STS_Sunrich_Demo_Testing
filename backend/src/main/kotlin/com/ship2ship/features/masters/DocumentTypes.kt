package com.ship2ship.features.masters

import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.web.Paging
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** What a document type is attached to. */
enum class DocumentOwner { VESSEL, JOB, EQUIPMENT, PERSONNEL }

@Entity
@Table(name = "document_type")
class DocumentTypeEntity(
    code: String,
    name: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var appliesTo: DocumentOwner,
    @Column(nullable = false) var required: Boolean,
) : CodeListEntity(code, name) {
    override fun details() = mapOf("appliesTo" to appliesTo.name, "required" to required)
}

interface DocumentTypeRepository : CodeListRepository<DocumentTypeEntity>

data class DocumentTypeDto(
    val id: UUID, val code: String, val name: String, val appliesTo: DocumentOwner, val required: Boolean, val active: Boolean,
    val updatedAt: Instant?, val version: Long?,
)

data class DocumentTypeRequest(
    @field:NotBlank @field:Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9-]{0,19}$", message = "Use 1-20 letters, digits or hyphens.") override val code: String,
    @field:NotBlank @field:Size(max = 100) override val name: String,
    @field:NotNull val appliesTo: DocumentOwner,
    val required: Boolean = false,
) : CodeListRequest

/** Document types (D03, FR-DOC-01): what can be uploaded for a vessel, job, equipment item or person. */
@Service
class DocumentTypeService(repository: DocumentTypeRepository, accessPolicy: AccessPolicy, audit: AuditService) :
    CodeListService<DocumentTypeEntity, DocumentTypeRequest, DocumentTypeDto>(
        repository, accessPolicy, audit, "DOCUMENT_TYPE", Permissions.MASTER_TECHNICAL_READ, Permissions.MASTER_TECHNICAL_WRITE,
    ) {
    override fun newEntity(request: DocumentTypeRequest) =
        DocumentTypeEntity(request.code, request.name.trim(), request.appliesTo, request.required)

    override fun apply(entity: DocumentTypeEntity, request: DocumentTypeRequest) {
        entity.appliesTo = request.appliesTo
        entity.required = request.required
    }

    override fun toDto(entity: DocumentTypeEntity) = DocumentTypeDto(
        entity.id, entity.code, entity.name, entity.appliesTo, entity.required, entity.active, entity.updatedAt, entity.version,
    )
}

@RestController
@RequestMapping("/api/v1/masters/document-types")
class DocumentTypeController(service: DocumentTypeService, paging: Paging) :
    CodeListController<DocumentTypeDto, DocumentTypeRequest>(service, paging, { it.version }, { it.id })
