package com.ship2ship.features.masters

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditDiff
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.persistence.BaseEntity
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.ConflictException
import com.ship2ship.core.web.FieldError
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.PageResponse
import com.ship2ship.core.web.Paging
import com.ship2ship.core.web.Versioning
import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.repository.NoRepositoryBean
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.util.UUID

/**
 * Shared behaviour for simple group-wide code lists (units, cargo grades, currencies, cost heads):
 * paged search, read, create, versioned update and activate/deactivate, all audited. Codes are immutable and
 * referenced rows are never deleted (FR-MD-01/02).
 */
@MappedSuperclass
abstract class CodeListEntity(
    @Column(nullable = false, updatable = false)
    var code: String,
    @Column(nullable = false)
    var name: String,
) : BaseEntity() {

    @Column(nullable = false)
    var active: Boolean = true

    /** Type-specific fields, for the audit trail. */
    abstract fun details(): Map<String, Any?>

    fun toAuditMap(): Map<String, Any?> = mapOf("code" to code, "name" to name, "active" to active) + details()
}

interface CodeListRequest {
    val code: String
    val name: String
}

@NoRepositoryBean
interface CodeListRepository<E : CodeListEntity> : JpaRepository<E, UUID>, JpaSpecificationExecutor<E> {
    fun existsByCode(code: String): Boolean
}

abstract class CodeListService<E : CodeListEntity, R : CodeListRequest, D : Any>(
    private val repository: CodeListRepository<E>,
    private val accessPolicy: AccessPolicy,
    private val audit: AuditService,
    private val entityType: String,
    private val readPermission: String,
    private val writePermission: String,
) {
    protected abstract fun newEntity(request: R): E
    protected abstract fun apply(entity: E, request: R)
    abstract fun toDto(entity: E): D

    /** Extra checks beyond Bean Validation; return field errors to reject the request. */
    protected open fun validate(request: R): List<FieldError> = emptyList()

    /** Normalises the code before it is stored or compared. */
    protected open fun normaliseCode(code: String): String = code.trim().uppercase()

    @Transactional(readOnly = true)
    open fun list(q: String?, active: Boolean?, pageable: Pageable): PageResponse<D> {
        accessPolicy.requireAny(readPermission)
        return PageResponse.of(repository.findAll(OrganisationFilters.search<E>(q, active, "name"), pageable)) { toDto(it) }
    }

    @Transactional(readOnly = true)
    open fun get(id: UUID): D {
        accessPolicy.requireAny(readPermission)
        return toDto(load(id))
    }

    @Transactional
    open fun create(request: R): D {
        accessPolicy.requireAny(writePermission)
        check(request)
        if (repository.existsByCode(normaliseCode(request.code))) {
            throw ConflictException("DUPLICATE_CODE", "This code is already in use.", listOf(FieldError("code", "duplicate", null)))
        }
        val entity = repository.saveAndFlush(newEntity(request).also { it.code = normaliseCode(request.code) })
        audit.record(AuditEntry(AuditAction.CREATE, entityType, entity.id, details = entity.toAuditMap()))
        return toDto(entity)
    }

    @Transactional
    open fun update(id: UUID, expectedVersion: Long, request: R): D {
        accessPolicy.requireAny(writePermission)
        val entity = load(id)
        Versioning.checkCurrent(expectedVersion, entity.version)
        if (normaliseCode(request.code) != entity.code) {
            throw BadRequestException("CODE_IMMUTABLE", "The code cannot be changed once created.",
                listOf(FieldError("code", "immutable", null)))
        }
        check(request)
        val before = entity.toAuditMap()
        entity.name = request.name.trim()
        apply(entity, request)
        repository.saveAndFlush(entity)
        audit.record(AuditEntry(AuditAction.UPDATE, entityType, id, details = AuditDiff.changes(before, entity.toAuditMap())))
        return toDto(entity)
    }

    @Transactional
    open fun changeStatus(id: UUID, expectedVersion: Long, request: ActiveStatusRequest): D {
        accessPolicy.requireAny(writePermission)
        val entity = load(id)
        Versioning.checkCurrent(expectedVersion, entity.version)
        OrganisationValidation.check(OrganisationValidation.reasonForDeactivation(request.active, request.reason))
        if (entity.active != request.active) {
            entity.active = request.active
            repository.saveAndFlush(entity)
            audit.record(AuditEntry(AuditAction.STATUS_CHANGE, entityType, id,
                details = mapOf("active" to request.active, "reason" to request.reason)))
        }
        return toDto(entity)
    }

    @Transactional
    open fun delete(id: UUID, expectedVersion: Long) {
        accessPolicy.requireAny(writePermission)
        val entity = load(id)
        Versioning.checkCurrent(expectedVersion, entity.version)
        val details = entity.toAuditMap()
        repository.delete(entity)
        repository.flush()
        audit.record(AuditEntry(AuditAction.DELETE, entityType, id, details = details))
    }

    private fun check(request: R) = OrganisationValidation.check(*validate(request).toTypedArray())

    private fun load(id: UUID): E = repository.findByIdOrNull(id) ?: throw NotFoundException(entityType)
}

/** REST endpoints for a code list; concrete subclasses supply the path via `@RequestMapping`. */
abstract class CodeListController<D : Any, R : CodeListRequest>(
    private val service: CodeListService<*, R, D>,
    private val paging: Paging,
    private val versionOf: (D) -> Long?,
    private val idOf: (D) -> UUID,
) {
    @GetMapping
    fun list(
        @RequestParam page: Int?, @RequestParam size: Int?, @RequestParam sort: String?,
        @RequestParam q: String?, @RequestParam active: Boolean?,
    ): PageResponse<D> = service.list(q, active, paging.pageable(page, size, sort, SORTS, Sort.by("code")))

    @PostMapping
    fun create(@Valid @RequestBody request: R): ResponseEntity<D> {
        val created = service.create(request)
        val location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(idOf(created)).toUri()
        return ResponseEntity.created(location).eTag(Versioning.etag(versionOf(created))).body(created)
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): ResponseEntity<D> = service.get(id).let { Versioning.ok(it, versionOf(it)) }

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: R,
    ): ResponseEntity<D> = service.update(id, Versioning.requireIfMatch(ifMatch), request).let { Versioning.ok(it, versionOf(it)) }

    @PatchMapping("/{id}/status")
    fun changeStatus(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: ActiveStatusRequest,
    ): ResponseEntity<D> =
        service.changeStatus(id, Versioning.requireIfMatch(ifMatch), request).let { Versioning.ok(it, versionOf(it)) }

    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<Void> {
        service.delete(id, Versioning.requireIfMatch(ifMatch))
        return ResponseEntity.noContent().build()
    }

    private companion object {
        val SORTS = mapOf("code" to "code", "name" to "name", "active" to "active", "updatedAt" to "updatedAt")
    }
}
