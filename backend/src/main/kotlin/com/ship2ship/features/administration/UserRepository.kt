package com.ship2ship.features.administration

import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface UserRepository : JpaRepository<UserEntity, UUID>, JpaSpecificationExecutor<UserEntity> {
    fun findByExternalSubjectId(externalSubjectId: String): UserEntity?
    fun existsByExternalSubjectId(externalSubjectId: String): Boolean
    fun existsByWorkEmailIgnoreCase(workEmail: String): Boolean
    fun existsByWorkEmailIgnoreCaseAndIdNot(workEmail: String, id: UUID): Boolean

    companion object {
        fun matching(q: String?, status: UserStatus?) = Specification<UserEntity> { root, _, cb ->
            val predicates = buildList {
                add(cb.notEqual(root.get<UserStatus>("status"), UserStatus.DELETED))
                if (!q.isNullOrBlank()) {
                    val like = "%${q.trim().lowercase()}%"
                    add(cb.or(cb.like(cb.lower(root.get("fullName")), like), cb.like(cb.lower(root.get("workEmail")), like)))
                }
                status?.let { add(cb.equal(root.get<UserStatus>("status"), it)) }
            }
            cb.and(*predicates.toTypedArray())
        }
    }
}

interface RoleRepository : JpaRepository<RoleEntity, UUID> {
    @Query("select distinct r from RoleEntity r left join fetch r.permissionCodes order by r.name")
    fun findAllWithPermissions(): List<RoleEntity>

    fun findByCode(code: String): RoleEntity?
}

interface PermissionRepository : JpaRepository<PermissionEntity, String>

interface UserRoleScopeRepository : JpaRepository<UserRoleScopeEntity, UUID> {
    fun findByUserIdOrderByCreatedAt(userId: UUID): List<UserRoleScopeEntity>

    @Query(
        """
        select count(urs) > 0 from UserRoleScopeEntity urs, RoleEntity r
        where urs.roleId = r.id and r.code = :roleCode and urs.branchId is null
          and urs.validFrom <= :today and (urs.validTo is null or urs.validTo >= :today)
          and exists (select u from UserEntity u where u.id = urs.userId and u.status = com.ship2ship.features.administration.UserStatus.ACTIVE)
        """,
    )
    fun existsActiveGlobalGrant(roleCode: String, today: java.time.LocalDate): Boolean
}
