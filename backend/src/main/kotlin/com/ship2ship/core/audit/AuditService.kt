package com.ship2ship.core.audit

import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.core.security.SessionUser
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class AuditAction {
    CREATE, UPDATE, DELETE, STATUS_CHANGE, ROLE_SCOPES_CHANGE,
    LOGIN, LOGIN_FAILED, LOGIN_REJECTED, LOGOUT, ACCESS_DENIED, BOOTSTRAP, EXPORT,
}

data class AuditEntry(
    val action: AuditAction,
    val entityType: String? = null,
    val entityId: UUID? = null,
    val branchId: UUID? = null,
    val details: Map<String, Any?>? = null,
    /** Overrides the session user (e.g. a failed login has no session yet). */
    val actorName: String? = null,
)

/**
 * Appends to the tamper-evident audit trail (SRS FR-UM-04, SR-03). Each row's hash covers the previous row's
 * hash, so editing or deleting any row breaks the chain; [AuditChainVerifier] detects that.
 * Appends are serialised through a row lock on `audit_chain_head`.
 */
@Service
class AuditService(
    private val jdbc: JdbcClient,
    private val clock: Clock,
    private val jsonMapper: JsonMapper,
    transactionManager: PlatformTransactionManager,
) {

    private val newTransaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    /** Records within the caller's transaction: the entry is kept only if the business change commits. */
    @Transactional(propagation = Propagation.REQUIRED)
    fun record(entry: AuditEntry) = append(buildRow(entry))

    /**
     * Records an event that must survive a rollback (denials, failed logins). If the caller's transaction already
     * holds the chain lock, the write waits until that transaction ends; otherwise it would block on its own lock.
     */
    fun recordIndependently(entry: AuditEntry) {
        val row = buildRow(entry)
        if (TransactionSynchronizationManager.hasResource(CHAIN_LOCK_KEY)) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCompletion(status: Int) {
                    newTransaction.executeWithoutResult { append(row) }
                }
            })
        } else {
            newTransaction.executeWithoutResult { append(row) }
        }
    }

    private fun buildRow(entry: AuditEntry): AuditRow {
        val sessionUser = SecurityContextHolder.getContext().authentication?.principal as? SessionUser
        return AuditRow(
            id = UuidV7.generate(),
            occurredAt = clock.instant().truncatedTo(ChronoUnit.MICROS),
            actorUserId = sessionUser?.userId,
            actorName = entry.actorName ?: sessionUser?.fullName,
            action = entry.action.name,
            entityType = entry.entityType,
            entityId = entry.entityId,
            branchId = entry.branchId,
            details = entry.details?.let { jsonMapper.writeValueAsString(it) },
            clientIp = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request?.remoteAddr,
        )
    }

    private fun append(row: AuditRow) {
        val prevHash = jdbc.sql("SELECT last_hash FROM audit_chain_head WHERE id = 1 FOR UPDATE")
            .query(String::class.java).single()
        markChainLockHeld()
        val rowHash = AuditHash.compute(prevHash, row)
        jdbc.sql(
            """
            INSERT INTO audit_event (id, occurred_at, actor_user_id, actor_name, action, entity_type, entity_id,
                                     branch_id, details, client_ip, prev_hash, row_hash)
            VALUES (:id, :occurredAt, :actorUserId, :actorName, :action, :entityType, :entityId,
                    :branchId, :details, :clientIp, :prevHash, :rowHash)
            """,
        )
            .param("id", Uuids.toBytes(row.id))
            .param("occurredAt", Uuids.toTimestamp(row.occurredAt))
            .param("actorUserId", row.actorUserId?.let(Uuids::toBytes))
            .param("actorName", row.actorName)
            .param("action", row.action)
            .param("entityType", row.entityType)
            .param("entityId", row.entityId?.let(Uuids::toBytes))
            .param("branchId", row.branchId?.let(Uuids::toBytes))
            .param("details", row.details)
            .param("clientIp", row.clientIp)
            .param("prevHash", prevHash)
            .param("rowHash", rowHash)
            .update()
        jdbc.sql("UPDATE audit_chain_head SET last_hash = :hash WHERE id = 1").param("hash", rowHash).update()
    }

    /**
     * Remembers on this thread that its transaction holds the chain lock until that transaction ends.
     * The marker deliberately stays bound while the transaction is suspended, because the database lock does too.
     */
    private fun markChainLockHeld() {
        if (!TransactionSynchronizationManager.isSynchronizationActive() ||
            TransactionSynchronizationManager.hasResource(CHAIN_LOCK_KEY)
        ) return
        TransactionSynchronizationManager.bindResource(CHAIN_LOCK_KEY, true)
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCompletion(status: Int) {
                TransactionSynchronizationManager.unbindResourceIfPossible(CHAIN_LOCK_KEY)
            }
        })
    }

    private companion object {
        val CHAIN_LOCK_KEY = Any()
    }
}

/** Builds before/after field maps for UPDATE events, keeping only the fields that changed. */
object AuditDiff {
    fun changes(before: Map<String, Any?>, after: Map<String, Any?>): Map<String, Any?> =
        (before.keys + after.keys)
            .filter { before[it]?.toString() != after[it]?.toString() }
            .associateWith { mapOf("old" to before[it], "new" to after[it]) }
}
