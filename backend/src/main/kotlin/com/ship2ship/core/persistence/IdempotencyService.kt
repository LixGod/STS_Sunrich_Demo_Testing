package com.ship2ship.core.persistence

import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.web.BadRequestException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * `Idempotency-Key` support for create endpoints (TR-API-01): a retried or double-submitted request with the same key
 * from the same user returns the record created the first time instead of creating a second one.
 */
@Service
class IdempotencyService(private val jdbc: JdbcClient, private val clock: Clock) {

    /** The record previously created for this key, if any. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun existing(userId: UUID, key: String?, operation: String): UUID? {
        if (key == null) return null
        validate(key)
        return jdbc.sql("SELECT resource_id FROM idempotency_key WHERE user_id = :user AND operation = :op AND idem_key = :key")
            .param("user", Uuids.toBytes(userId)).param("op", operation).param("key", key)
            .query { rs, _ -> Uuids.fromBytes(rs.getBytes(1))!! }.optional().orElse(null)
    }

    /** Records the key in the same transaction as the create; a concurrent duplicate fails on the unique key. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun remember(userId: UUID, key: String?, operation: String, resourceId: UUID) {
        if (key == null) return
        jdbc.sql(
            """
            INSERT INTO idempotency_key (id, user_id, idem_key, operation, resource_id, created_at)
            VALUES (:id, :user, :key, :op, :resource, :at)
            """,
        ).param("id", Uuids.toBytes(UuidV7.generate())).param("user", Uuids.toBytes(userId)).param("key", key)
            .param("op", operation).param("resource", Uuids.toBytes(resourceId))
            .param("at", Uuids.toTimestamp(clock.instant().truncatedTo(ChronoUnit.MICROS))).update()
    }

    private fun validate(key: String) {
        if (key.isBlank() || key.length > 100) {
            throw BadRequestException("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must be 1-100 characters.")
        }
    }
}
