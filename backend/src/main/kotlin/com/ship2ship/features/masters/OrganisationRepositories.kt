package com.ship2ship.features.masters

import com.ship2ship.core.security.AccessScope
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Path
import jakarta.persistence.criteria.Predicate
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.util.UUID

interface BranchRepository : JpaRepository<BranchEntity, UUID>, JpaSpecificationExecutor<BranchEntity> {
    fun existsByCode(code: String): Boolean
}

/** Shared list filters: free-text search over code/name, active flag, and a parent-id filter. */
internal object OrganisationFilters {

    fun <T : Any> search(q: String?, active: Boolean?, nameField: String, parent: Pair<String, UUID?>? = null) =
        Specification<T> { root, _, cb ->
            val predicates = mutableListOf<Predicate>()
            if (!q.isNullOrBlank()) {
                val like = "%${q.trim().lowercase()}%"
                predicates += cb.or(cb.like(cb.lower(root.get("code")), like), cb.like(cb.lower(root.get(nameField)), like))
            }
            active?.let { predicates += cb.equal(root.get<Boolean>("active"), it) }
            parent?.second?.let { predicates += cb.equal(root.get<UUID>(parent.first), it) }
            cb.and(*predicates.toTypedArray())
        }

    /** OR of the given predicates; matches nothing when the list is empty. */
    fun anyOf(cb: CriteriaBuilder, predicates: List<Predicate>): Predicate =
        if (predicates.isEmpty()) cb.disjunction() else cb.or(*predicates.toTypedArray())

    /** Branch-owned rows the reader may see. */
    fun branchPredicate(scope: AccessScope, cb: CriteriaBuilder, branchId: Path<UUID>): Predicate =
        if (scope.global) cb.conjunction() else anyOf(cb, listOfNotNull(scope.branchIds.takeIf { it.isNotEmpty() }?.let { branchId.`in`(it) }))
}
