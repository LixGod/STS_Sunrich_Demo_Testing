package com.ship2ship.core.audit

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID

/** One audit row as hashed and stored. */
data class AuditRow(
    val id: UUID,
    val occurredAt: Instant,
    val actorUserId: UUID?,
    val actorName: String?,
    val action: String,
    val entityType: String?,
    val entityId: UUID?,
    val branchId: UUID?,
    val details: String?,
    val clientIp: String?,
)

object AuditHash {

    /** SHA-256 over the previous hash and every stored field, in a fixed order with explicit nulls. */
    fun compute(prevHash: String, row: AuditRow): String {
        val canonical = listOf(
            prevHash, row.id, row.occurredAt, row.actorUserId, row.actorName, row.action, row.entityType,
            row.entityId, row.branchId, row.details, row.clientIp,
        ).joinToString(separator = "\u001F") { it?.toString() ?: "␀" }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }
}

/** Conversions for hand-written SQL on BINARY(16) UUID and DATETIME(6) UTC columns. */
object Uuids {
    fun toBytes(uuid: UUID): ByteArray =
        ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()

    fun fromBytes(bytes: ByteArray?): UUID? =
        bytes?.let { ByteBuffer.wrap(it).let { buffer -> UUID(buffer.long, buffer.long) } }

    fun toTimestamp(instant: Instant): LocalDateTime = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)

    fun fromTimestamp(value: LocalDateTime): Instant = value.toInstant(ZoneOffset.UTC)
}
