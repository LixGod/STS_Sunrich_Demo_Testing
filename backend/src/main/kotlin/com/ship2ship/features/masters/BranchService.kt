package com.ship2ship.features.masters

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditDiff
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.ConflictException
import com.ship2ship.core.web.FieldError
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.PageResponse
import com.ship2ship.core.web.Versioning
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Branch master (D02). Branch-scoped grants see only their branch; company-wide grants see every branch. */
@Service
class BranchService(
    private val branches: BranchRepository,
    private val accessPolicy: AccessPolicy,
    private val audit: AuditService,
) {

    @Transactional(readOnly = true)
    fun list(q: String?, active: Boolean?, pageable: Pageable): PageResponse<BranchDto> {
        val scope = accessPolicy.scope(Permissions.MASTER_ORG_READ)
        val inScope = Specification<BranchEntity> { root, _, cb -> OrganisationFilters.branchPredicate(scope, cb, root.get("id")) }
        val spec = OrganisationFilters.search<BranchEntity>(q, active, "name").and(inScope)
        return PageResponse.of(branches.findAll(spec, pageable)) { it.toDto() }
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): BranchDto {
        val branch = load(id)
        accessPolicy.requireBranch(Permissions.MASTER_ORG_READ, id, ENTITY, id)
        return branch.toDto()
    }

    @Transactional
    fun create(request: BranchRequest): BranchDto {
        accessPolicy.requireGlobal(Permissions.MASTER_ORG_WRITE, ENTITY)
        OrganisationValidation.check(OrganisationValidation.timezone("timezone", request.timezone))
        if (branches.existsByCode(request.code)) {
            throw ConflictException("DUPLICATE_CODE", "A branch with this code already exists.",
                listOf(FieldError("code", "duplicate", null)))
        }
        val branch = branches.saveAndFlush(BranchEntity(request.code, request.name.trim(), request.timezone, request.address?.trim()))
        audit.record(AuditEntry(AuditAction.CREATE, ENTITY, branch.id, branch.id, branch.toAuditMap()))
        return branch.toDto()
    }

    @Transactional
    fun update(id: UUID, expectedVersion: Long, request: BranchRequest): BranchDto {
        val branch = load(id)
        accessPolicy.requireBranch(Permissions.MASTER_ORG_WRITE, id, ENTITY, id)
        Versioning.checkCurrent(expectedVersion, branch.version)
        if (request.code != branch.code) {
            throw BadRequestException("CODE_IMMUTABLE", "The code cannot be changed once created.",
                listOf(FieldError("code", "immutable", null)))
        }
        OrganisationValidation.check(OrganisationValidation.timezone("timezone", request.timezone))
        val before = branch.toAuditMap()
        branch.name = request.name.trim()
        branch.timezone = request.timezone
        branch.address = request.address?.trim()
        branches.saveAndFlush(branch)
        audit.record(AuditEntry(AuditAction.UPDATE, ENTITY, id, id, AuditDiff.changes(before, branch.toAuditMap())))
        return branch.toDto()
    }

    @Transactional
    fun changeStatus(id: UUID, expectedVersion: Long, request: ActiveStatusRequest): BranchDto {
        val branch = load(id)
        accessPolicy.requireBranch(Permissions.MASTER_ORG_WRITE, id, ENTITY, id)
        Versioning.checkCurrent(expectedVersion, branch.version)
        OrganisationValidation.check(OrganisationValidation.reasonForDeactivation(request.active, request.reason))
        if (branch.active != request.active) {
            branch.active = request.active
            branches.saveAndFlush(branch)
            audit.record(AuditEntry(AuditAction.STATUS_CHANGE, ENTITY, id, id,
                mapOf("active" to request.active, "reason" to request.reason)))
        }
        return branch.toDto()
    }

    @Transactional
    fun delete(id: UUID, expectedVersion: Long) {
        val branch = load(id)
        accessPolicy.requireBranch(Permissions.MASTER_ORG_WRITE, id, ENTITY, id)
        Versioning.checkCurrent(expectedVersion, branch.version)
        val details = branch.toAuditMap()
        branches.delete(branch)
        branches.flush()
        audit.record(AuditEntry(AuditAction.DELETE, ENTITY, id, id, details = details))
    }

    private fun load(id: UUID) = branches.findByIdOrNull(id) ?: throw NotFoundException("Branch")

    private companion object {
        const val ENTITY = "BRANCH"
    }
}

internal fun BranchEntity.toDto() = BranchDto(id, code, name, timezone, address, active, updatedAt, version)
