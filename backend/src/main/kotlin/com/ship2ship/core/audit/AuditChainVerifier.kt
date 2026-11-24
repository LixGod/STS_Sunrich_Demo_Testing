package com.ship2ship.core.audit

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

data class AuditChainVerification(val valid: Boolean, val checkedRows: Long, val firstBrokenSeq: Long?)

/** Re-computes the audit hash chain in batches and reports the first row that does not match. */
@Component
class AuditChainVerifier(private val jdbc: JdbcClient) {

    @Transactional(readOnly = true)
    fun verify(batchSize: Int = 500): AuditChainVerification {
        var expectedPrev = GENESIS_HASH
        var afterSeq = 0L
        var checked = 0L
        while (true) {
            val batch = jdbc.sql(
                """
                SELECT seq, id, occurred_at, actor_user_id, actor_name, action, entity_type, entity_id,
                       branch_id, details, client_ip, prev_hash, row_hash
                FROM audit_event WHERE seq > :after ORDER BY seq LIMIT :limit
                """,
            ).param("after", afterSeq).param("limit", batchSize).query { rs, _ ->
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
                StoredRow(rs.getLong("seq"), row, rs.getString("prev_hash"), rs.getString("row_hash"))
            }.list()
            if (batch.isEmpty()) return AuditChainVerification(true, checked, null)
            for (stored in batch) {
                if (stored.prevHash != expectedPrev || AuditHash.compute(stored.prevHash, stored.row) != stored.rowHash) {
                    return AuditChainVerification(false, checked, stored.seq)
                }
                expectedPrev = stored.rowHash
                checked++
            }
            afterSeq = batch.last().seq
        }
    }

    private data class StoredRow(val seq: Long, val row: AuditRow, val prevHash: String, val rowHash: String)

    companion object {
        val GENESIS_HASH = "0".repeat(64)
    }
}
