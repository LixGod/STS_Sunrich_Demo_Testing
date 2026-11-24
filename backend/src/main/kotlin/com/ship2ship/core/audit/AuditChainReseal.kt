package com.ship2ship.core.audit

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * One-off Flyway step after V39 dropped `audit_event.legal_entity_id`: every row hash is recomputed in sequence order
 * from the fields that remain, so the chain verifies again. Nothing else in the rows changes.
 * Spring Boot hands Flyway every JavaMigration bean; the version comes from the class name.
 */
@Suppress("ClassName")
@Component
class V40__reseal_audit_chain : BaseJavaMigration() {

    override fun migrate(context: Context) {
        val connection = context.connection
        var prev = AuditChainVerifier.GENESIS_HASH
        var pending = 0
        connection.prepareStatement(
            """SELECT seq, id, occurred_at, actor_user_id, actor_name, action, entity_type, entity_id, branch_id, details, client_ip
               FROM audit_event ORDER BY seq""",
        ).use { select ->
            connection.prepareStatement("UPDATE audit_event SET prev_hash = ?, row_hash = ? WHERE seq = ?").use { update ->
                select.executeQuery().use { rs ->
                    while (rs.next()) {
                        val row = AuditRow(
                            id = Uuids.fromBytes(rs.getBytes("id"))!!,
                            occurredAt = Uuids.fromTimestamp(rs.getObject("occurred_at", LocalDateTime::class.java)),
                            actorUserId = Uuids.fromBytes(rs.getBytes("actor_user_id")),
                            actorName = rs.getString("actor_name"),
                            action = rs.getString("action"),
                            entityType = rs.getString("entity_type"),
                            entityId = Uuids.fromBytes(rs.getBytes("entity_id")),
                            branchId = Uuids.fromBytes(rs.getBytes("branch_id")),
                            details = rs.getString("details"),
                            clientIp = rs.getString("client_ip"),
                        )
                        val hash = AuditHash.compute(prev, row)
                        update.setString(1, prev)
                        update.setString(2, hash)
                        update.setLong(3, rs.getLong("seq"))
                        update.addBatch()
                        prev = hash
                        if (++pending % 500 == 0) update.executeBatch()
                    }
                }
                update.executeBatch()
            }
        }
        connection.prepareStatement("UPDATE audit_chain_head SET last_hash = ? WHERE id = 1").use {
            it.setString(1, prev)
            it.executeUpdate()
        }
    }
}
