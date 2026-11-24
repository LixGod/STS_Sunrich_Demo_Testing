package com.ship2ship.integrations.auth

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.security.MessageDigest

/** Identity confirmed by the existing authentication system. */
data class ExternalIdentity(
    val subjectId: String,
    val mfaVerified: Boolean = false,
    /** Kept server-side in the session only; needed if the provider requires an explicit logout call. */
    val sessionToken: String? = null,
)

class ExternalAuthUnavailableException(message: String) : RuntimeException(message)

/**
 * Adapter to the client's existing authentication system (decision K6). The production implementation will be
 * written once the authentication contract is supplied; Ship2Ship keeps roles and scopes locally (D01).
 */
interface ExternalAuthClient {
    /** Returns the identity for valid credentials, or null when they are rejected. */
    fun authenticate(username: String, password: String): ExternalIdentity?

    fun logout(identity: ExternalIdentity) {}

    /** Keep sign-in working when the local account's authentication subject changes. */
    fun prepareUsernameChange(userId: java.util.UUID, oldSubject: String, newSubject: String) {
        throw ExternalAuthUnavailableException("Username changes are unavailable with this authentication provider.")
    }

    fun isReservedUsername(username: String): Boolean = false
}

/** Active outside local/test until the real adapter exists: refuses every login with a clear error. */
@Component
@Profile("!local & !test & !demo")
class UnconfiguredExternalAuthClient : ExternalAuthClient {

    init {
        LoggerFactory.getLogger(javaClass).warn("No external authentication provider is configured; all logins will be refused.")
    }

    override fun authenticate(username: String, password: String): ExternalIdentity? =
        throw ExternalAuthUnavailableException("The authentication provider is not configured.")
}

/** Railway demo sign-in uses only active accounts with a stored BCrypt credential. */
@Component
@Profile("demo & !local & !test")
class DemoDatabaseAuthClient(private val jdbc: JdbcClient) : ExternalAuthClient {
    private val encoder = BCryptPasswordEncoder()

    override fun authenticate(username: String, password: String): ExternalIdentity? {
        val hash = jdbc.sql("""
            SELECT c.password_hash FROM user_local_credential c
            JOIN app_user u ON u.id = c.user_id
            WHERE u.external_subject_id = :username AND u.status = 'ACTIVE'
        """).param("username", username).query(String::class.java).optional().orElse(null) ?: return null
        return if (encoder.matches(password, hash)) ExternalIdentity(subjectId = username) else null
    }

    override fun prepareUsernameChange(userId: java.util.UUID, oldSubject: String, newSubject: String) {
        // The credential is linked to the user ID, so changing the username needs no credential update.
    }
}

@ConfigurationProperties("ship2ship.dev-auth")
data class DevAuthProperties(val users: List<DevUser> = emptyList()) {
    data class DevUser(val username: String, val password: String, val subjectId: String)
}

/** Development and automated-test login stub with synthetic accounts. Never loaded with the prod profile. */
@Component
@Profile("(local | test) & !prod")
class DevStubAuthClient(private val properties: DevAuthProperties, private val jdbc: JdbcClient) : ExternalAuthClient {
    private val encoder = BCryptPasswordEncoder()

    init {
        LoggerFactory.getLogger(javaClass).warn("DEVELOPMENT LOGIN STUB ACTIVE - synthetic users only.")
    }

    override fun authenticate(username: String, password: String): ExternalIdentity? {
        val user = properties.users.firstOrNull { it.username == username }
        if (user != null) {
            val matches = MessageDigest.isEqual(user.password.toByteArray(), password.toByteArray())
            return if (matches) ExternalIdentity(subjectId = user.subjectId) else null
        }
        val hash = jdbc.sql("""
            SELECT c.password_hash FROM user_local_credential c
            JOIN app_user u ON u.id = c.user_id
            WHERE u.external_subject_id = :username AND u.status = 'ACTIVE'
        """).param("username", username).query(String::class.java).optional().orElse(null) ?: return null
        return if (encoder.matches(password, hash)) ExternalIdentity(subjectId = username) else null
    }

    override fun prepareUsernameChange(userId: java.util.UUID, oldSubject: String, newSubject: String) {
        if (isReservedUsername(newSubject)) {
            throw com.ship2ship.core.web.ConflictException("DUPLICATE_SUBJECT", "This username is reserved for a demo account.")
        }
        val existing = jdbc.sql("SELECT COUNT(*) FROM user_local_credential WHERE user_id=UUID_TO_BIN(:id)")
            .param("id", userId.toString()).query(Long::class.java).single()
        if (existing > 0) return
        val demo = properties.users.firstOrNull { it.subjectId == oldSubject }
            ?: throw ExternalAuthUnavailableException("This account has no local sign-in password.")
        jdbc.sql("INSERT INTO user_local_credential (user_id, password_hash) VALUES (UUID_TO_BIN(:id), :hash)")
            .param("id", userId.toString()).param("hash", encoder.encode(demo.password)).update()
    }

    override fun isReservedUsername(username: String): Boolean =
        properties.users.any { it.username.equals(username, ignoreCase = true) || it.subjectId.equals(username, ignoreCase = true) }
}
