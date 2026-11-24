package com.ship2ship.core.files

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.web.ApiException
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.NotFoundException
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.multipart.MultipartFile
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

enum class ScanState { NOT_SCANNED, CLEAN, INFECTED }

data class StoredFileRef(
    val id: UUID, val originalName: String, val contentType: String, val sizeBytes: Long, val sha256: String, val scanState: ScanState,
)

/** Who may download a file: a permission, optionally limited to a branch (null = company-wide record). */
data class FileAccess(val permission: String, val branchId: UUID? = null)

data class DownloadToken(val token: String, val url: String, val expiresAt: Instant)

class OpenedFile(val ref: StoredFileRef, val stream: InputStream)

/**
 * Uploads and downloads (TR-API-02, SR-01). Only PDF, PNG and JPEG are accepted, recognised by their file signature
 * (the browser's declared type is ignored), up to the configured size. Downloads use short-lived tokens bound to the
 * user who requested them. Malware scanning is not wired yet (ClamAV pending approval, K7): every file is NOT_SCANNED.
 */
@Service
class FileService(
    private val store: FileStore,
    private val jdbc: JdbcClient,
    private val accessPolicy: AccessPolicy,
    private val audit: AuditService,
    private val clock: Clock,
    @Value("\${ship2ship.files.max-bytes}") private val maxBytes: Long,
    @Value("\${ship2ship.files.token-minutes:5}") private val tokenMinutes: Long,
) {
    private val random = SecureRandom()

    /** Stores an upload; call inside the transaction that creates the record owning the file. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun store(upload: MultipartFile, access: FileAccess): StoredFileRef {
        if (upload.isEmpty) throw BadRequestException("FILE_EMPTY", "The file is empty.")
        if (upload.size > maxBytes) throw ApiException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "The file is larger than allowed.")

        val temp = Files.createTempFile("s2s-upload-", ".part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val head = ByteArray(8)
            var headLength = 0
            var size = 0L
            upload.inputStream.use { input ->
                Files.newOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (headLength < head.size) {
                            val take = minOf(read, head.size - headLength)
                            System.arraycopy(buffer, 0, head, headLength, take)
                            headLength += take
                        }
                        size += read
                        if (size > maxBytes) throw ApiException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "The file is larger than allowed.")
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            val contentType = detect(head.copyOf(headLength))
                ?: throw BadRequestException("FILE_TYPE_NOT_ALLOWED", "Only PDF, PNG and JPEG files are accepted.")
            val id = UuidV7.generate()
            val now = clock.instant()
            val key = "%d/%02d/%s".format(now.atZone(ZoneOffset.UTC).year, now.atZone(ZoneOffset.UTC).monthValue, id)
            val sha256 = HexFormat.of().formatHex(digest.digest())
            store.put(key, temp)
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCompletion(status: Int) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) store.delete(key)
                }
            })
            val name = safeName(upload.originalFilename)
            jdbc.sql(
                """
                INSERT INTO stored_file (id, original_name, content_type, size_bytes, sha256, storage_key, scan_state, access_permission,
                                         branch_id, created_at, created_by)
                VALUES (:id, :name, :type, :size, :sha, :key, :scan, :permission, :branch, :at, :by)
                """,
            ).param("id", Uuids.toBytes(id)).param("name", name).param("type", contentType).param("size", size).param("sha", sha256)
                .param("key", key).param("scan", ScanState.NOT_SCANNED.name).param("permission", access.permission)
                .param("branch", access.branchId?.let(Uuids::toBytes))
                .param("at", Uuids.toTimestamp(now.truncatedTo(ChronoUnit.MICROS))).param("by", Uuids.toBytes(accessPolicy.currentUser().id))
                .update()
            audit.record(AuditEntry(AuditAction.CREATE, ENTITY, id, access.branchId,
                mapOf("name" to name, "contentType" to contentType, "size" to size, "sha256" to sha256)))
            return StoredFileRef(id, name, contentType, size, sha256, ScanState.NOT_SCANNED)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    @Transactional(readOnly = true)
    fun refs(ids: Collection<UUID>): Map<UUID, StoredFileRef> =
        if (ids.isEmpty()) emptyMap()
        else ids.toSet().mapNotNull { row(it)?.ref }.associateBy { it.id }

    @Transactional(readOnly = true)
    fun requireReadable(id: UUID):StoredFileRef {
        val file=row(id) ?: throw NotFoundException("File")
        requireAccess(file)
        return file.ref
    }

    /**
     * The bytes of a stored file for server-side rendering (e.g. a diagram embedded in a PDF). The caller must already
     * have checked that the user may see the owning record; nothing here is sent to the browser directly.
     */
    @Transactional(readOnly = true)
    fun readForRendering(fileId: UUID): Pair<StoredFileRef, ByteArray>? {
        val file = row(fileId) ?: return null
        return file.ref to store.open(file.key).use { it.readAllBytes() }
    }

    /** A token for the browser to fetch the file, valid for a few minutes and only for the current user. */
    @Transactional
    fun downloadToken(fileId: UUID): DownloadToken {
        val file = row(fileId) ?: throw NotFoundException("File")
        requireAccess(file)
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val expires = now.plus(tokenMinutes, ChronoUnit.MINUTES)
        jdbc.sql("DELETE FROM file_download_token WHERE expires_at < :now").param("now", Uuids.toTimestamp(now)).update()
        jdbc.sql(
            "INSERT INTO file_download_token (token_hash, file_id, user_id, expires_at, created_at) VALUES (:hash, :file, :user, :expires, :now)",
        ).param("hash", hash(token)).param("file", Uuids.toBytes(fileId)).param("user", Uuids.toBytes(accessPolicy.currentUser().id))
            .param("expires", Uuids.toTimestamp(expires)).param("now", Uuids.toTimestamp(now)).update()
        return DownloadToken(token, "/api/v1/files/download/$token", expires)
    }

    /** Opens a file by token; the token must be unexpired and belong to the signed-in user. */
    @Transactional(readOnly = true)
    fun open(token: String): OpenedFile {
        val userId = accessPolicy.currentUser().id
        val fileId = jdbc.sql("SELECT file_id FROM file_download_token WHERE token_hash = :hash AND user_id = :user AND expires_at >= :now")
            .param("hash", hash(token)).param("user", Uuids.toBytes(userId)).param("now", Uuids.toTimestamp(clock.instant()))
            .query { rs, _ -> Uuids.fromBytes(rs.getBytes(1))!! }.optional().orElseThrow { NotFoundException("File") }
        val file = row(fileId) ?: throw NotFoundException("File")
        requireAccess(file)
        return OpenedFile(file.ref, store.open(file.key))
    }

    private class Row(val ref: StoredFileRef, val key: String, val access: FileAccess)

    private fun row(id: UUID): Row? =
        jdbc.sql(
            """
            SELECT id, original_name, content_type, size_bytes, sha256, storage_key, scan_state, access_permission, branch_id
            FROM stored_file WHERE id = :id
            """,
        ).param("id", Uuids.toBytes(id)).query { rs, _ ->
            Row(
                StoredFileRef(Uuids.fromBytes(rs.getBytes("id"))!!, rs.getString("original_name"), rs.getString("content_type"),
                    rs.getLong("size_bytes"), rs.getString("sha256"), ScanState.valueOf(rs.getString("scan_state"))),
                rs.getString("storage_key"),
                FileAccess(rs.getString("access_permission"), Uuids.fromBytes(rs.getBytes("branch_id"))),
            )
        }.optional().orElse(null)

    private fun requireAccess(file: Row) {
        val access = file.access
        if (access.branchId != null) accessPolicy.requireBranch(access.permission, access.branchId, ENTITY, file.ref.id)
        else accessPolicy.requireAny(access.permission)
    }

    private fun hash(token: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))

    companion object {
        private const val ENTITY = "FILE"

        /** Content type from the file signature (magic bytes); null when not an allowed type. */
        fun detect(head: ByteArray): String? = when {
            head.startsWith(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D)) -> "application/pdf" // %PDF-
            head.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "image/png"
            head.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) -> "image/jpeg"
            else -> null
        }

        /** The original name without any path, control characters or quotes, at most 200 characters. */
        fun safeName(name: String?): String {
            val base = name.orEmpty().substringAfterLast('/').substringAfterLast('\\')
            val cleaned = base.filter { it >= ' ' && it != '"' && it != '\u007f' }.trim().take(200)
            return cleaned.ifBlank { "file" }
        }

        private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    }
}
