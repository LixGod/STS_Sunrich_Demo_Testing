package com.ship2ship.features.administration

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.persistence.UuidV7
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@ConfigurationProperties("ship2ship.bootstrap")
data class BootstrapProperties(
    val adminSubjectId: String = "",
    val adminFullName: String = "",
    val adminEmail: String = "",
)

/**
 * Creates the first administrator on a fresh installation, so that someone can sign in and manage user access.
 * Runs only when configured and when no active global administrator exists; the action is audited.
 */
@Component
class AdminBootstrap(
    private val properties: BootstrapProperties,
    private val users: UserRepository,
    private val roles: RoleRepository,
    private val grants: UserRoleScopeRepository,
    private val audit: AuditService,
    private val clock: Clock,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun run(args: ApplicationArguments) {
        if (properties.adminSubjectId.isBlank()) return
        val today = LocalDate.now(clock)
        if (grants.existsActiveGlobalGrant(ADMIN_ROLE, today)) return

        val role = roles.findByCode(ADMIN_ROLE) ?: error("Role $ADMIN_ROLE is missing; check database migrations.")
        val user = users.findByExternalSubjectId(properties.adminSubjectId)
            ?: users.saveAndFlush(UserEntity(properties.adminSubjectId, properties.adminFullName.ifBlank { "Admin" },
                properties.adminEmail.ifBlank { "${properties.adminSubjectId}@invalid" }.lowercase()))
        user.status = UserStatus.ACTIVE
        grants.save(UserRoleScopeEntity(UuidV7.generate(), user.id, role.id, null, today, null,
            clock.instant().truncatedTo(ChronoUnit.MICROS), null))
        audit.record(AuditEntry(AuditAction.BOOTSTRAP, "USER", user.id, actorName = "SYSTEM",
            details = mapOf("grantedRole" to ADMIN_ROLE)))
        log.info("Bootstrapped Admin for subject {}", properties.adminSubjectId)
    }

    private companion object {
        const val ADMIN_ROLE = "SYSTEM_ADMIN"
    }
}
