package com.ship2ship.core.audit

import com.ship2ship.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SRS SR-03: audit records are tamper-evident. */
class AuditChainIT : IntegrationTest() {

    @Autowired
    lateinit var auditService: AuditService

    @Autowired
    lateinit var verifier: AuditChainVerifier

    @Autowired
    lateinit var tx: TransactionTemplate

    @Test
    fun `chain verifies, detects an edited row, and recovers when the row is restored`() {
        val entityId = UUID.randomUUID()
        repeat(3) { i ->
            tx.executeWithoutResult {
                auditService.record(AuditEntry(AuditAction.UPDATE, "TEST", entityId, details = mapOf("step" to i, "note" to "ünïcode ✓")))
            }
        }
        assertTrue(verifier.verify().valid)

        val target = jdbc.sql("SELECT seq, details FROM audit_event WHERE entity_id = :id ORDER BY seq LIMIT 1 OFFSET 1")
            .param("id", Uuids.toBytes(entityId))
            .query { rs, _ -> rs.getLong("seq") to rs.getString("details") }.single()

        jdbc.sql("UPDATE audit_event SET details = :d WHERE seq = :seq")
            .param("d", """{"step": 99}""").param("seq", target.first).update()
        val tampered = verifier.verify()
        assertFalse(tampered.valid)
        assertEquals(target.first, tampered.firstBrokenSeq)

        jdbc.sql("UPDATE audit_event SET details = :d WHERE seq = :seq")
            .param("d", target.second).param("seq", target.first).update()
        assertTrue(verifier.verify().valid)
    }

    @Test
    fun `independent records survive a rolled-back business transaction`() {
        val entityId = UUID.randomUUID()
        runCatching {
            tx.executeWithoutResult {
                auditService.record(AuditEntry(AuditAction.UPDATE, "TEST", entityId))
                auditService.recordIndependently(AuditEntry(AuditAction.ACCESS_DENIED, "TEST", entityId))
                error("roll back")
            }
        }
        val actions = jdbc.sql("SELECT action FROM audit_event WHERE entity_id = :id")
            .param("id", Uuids.toBytes(entityId)).query(String::class.java).list()
        assertEquals(listOf("ACCESS_DENIED"), actions)
    }
}
