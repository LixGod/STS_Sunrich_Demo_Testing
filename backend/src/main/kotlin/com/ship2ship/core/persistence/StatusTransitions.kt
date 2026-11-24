package com.ship2ship.core.persistence

import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.BusinessRuleException
import com.ship2ship.core.web.FieldError
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Allowed moves of a lifecycle, and which of them must carry a reason (FR-WF-01). */
class TransitionRules<S : Enum<S>>(
    private val allowed: Map<S, Set<S>>,
    private val reasonRequired: Set<S> = emptySet(),
) {
    fun check(from: S, to: S, reason: String?) {
        if (to !in allowed[from].orEmpty()) {
            throw BusinessRuleException("INVALID_TRANSITION", "A record in status $from cannot move to $to.")
        }
        if (to in reasonRequired && reason.isNullOrBlank()) {
            throw BadRequestException("REASON_REQUIRED", "A reason is required for this change.",
                listOf(FieldError("reason", "required", null)))
        }
    }

    fun next(from: S): Set<S> = allowed[from].orEmpty()
}

data class TransitionRecord(
    val fromStatus: String?,
    val toStatus: String,
    val reason: String?,
    val actorUserId: UUID?,
    val occurredAt: Instant,
)

/** Time-stamped status history for any workflow entity; written in the same transaction as the status change. */
@Component
class StatusHistory(private val jdbc: JdbcClient, private val clock: Clock) {

    @Transactional(propagation = Propagation.MANDATORY)
    fun record(entityType: String, entityId: UUID, from: String?, to: String, reason: String?, actorUserId: UUID?) {
        jdbc.sql(
            """
            INSERT INTO workflow_transition (id, entity_type, entity_id, from_status, to_status, reason, actor_user_id, occurred_at)
            VALUES (:id, :type, :entity, :from, :to, :reason, :actor, :at)
            """,
        ).param("id", Uuids.toBytes(UuidV7.generate())).param("type", entityType).param("entity", Uuids.toBytes(entityId))
            .param("from", from).param("to", to).param("reason", reason?.trim())
            .param("actor", actorUserId?.let(Uuids::toBytes))
            .param("at", Uuids.toTimestamp(clock.instant().truncatedTo(ChronoUnit.MICROS))).update()
    }

    @Transactional(readOnly = true)
    fun history(entityType: String, entityId: UUID): List<TransitionRecord> =
        jdbc.sql(
            """
            SELECT from_status, to_status, reason, actor_user_id, occurred_at FROM workflow_transition
            WHERE entity_type = :type AND entity_id = :entity ORDER BY occurred_at, id
            """,
        ).param("type", entityType).param("entity", Uuids.toBytes(entityId)).query { rs, _ ->
            TransitionRecord(
                rs.getString("from_status"), rs.getString("to_status"), rs.getString("reason"),
                Uuids.fromBytes(rs.getBytes("actor_user_id")),
                Uuids.fromTimestamp(rs.getObject("occurred_at", java.time.LocalDateTime::class.java)),
            )
        }.list()
}
