package com.ship2ship.features.technical

import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.ConflictException
import com.ship2ship.core.web.NotFoundException
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

data class IncomingAttachmentDto(
    val id: UUID, val fileName: String, val contentType: String, val sizeBytes: Int,
    val sender: String?, val subject: String?, val receivedAt: Instant?,
)

data class IncomingAttachmentAssignment(val jobId: UUID, val itemId: UUID)

data class ParsedMailAttachment(val number: Int, val fileName: String, val contentType: String, val bytes: ByteArray)

@Service
class IncomingMailService(
    private val jdbc: JdbcClient,
    private val policy: AccessPolicy,
    private val documents: JobDocumentService,
    private val clock: Clock,
    @Value("\${ship2ship.incoming-mail.enabled:false}") private val enabled: Boolean,
) {
    @Transactional(readOnly = true)
    fun enabled(): Boolean {
        policy.requireGlobal(Permissions.DOCUMENT_WRITE, "INCOMING_MAIL")
        return enabled
    }

    @Transactional(readOnly = true)
    fun pending(): List<IncomingAttachmentDto> {
        policy.requireGlobal(Permissions.DOCUMENT_WRITE, "INCOMING_MAIL")
        return jdbc.sql("""SELECT id,file_name,content_type,size_bytes,sender,subject,received_at
            FROM incoming_mail_attachment WHERE status='PENDING' ORDER BY created_at DESC LIMIT 200""")
            .query { rs, _ -> IncomingAttachmentDto(
                Uuids.fromBytes(rs.getBytes(1))!!, rs.getString(2), rs.getString(3), rs.getInt(4),
                rs.getString(5), rs.getString(6), rs.getObject(7, LocalDateTime::class.java)?.let(Uuids::fromTimestamp),
            ) }.list()
    }

    @Transactional
    fun file(id: UUID, assignment: IncomingAttachmentAssignment): JobDocumentDto {
        policy.requireGlobal(Permissions.DOCUMENT_WRITE, "INCOMING_MAIL", id)
        val row = jdbc.sql("""SELECT file_name,content_type,payload,status FROM incoming_mail_attachment
            WHERE id=:id FOR UPDATE""").param("id", Uuids.toBytes(id))
            .query { rs, _ -> Array<Any>(4) { index -> when (index) {
                0 -> rs.getString(1); 1 -> rs.getString(2); 2 -> rs.getBytes(3); else -> rs.getString(4)
            } } }.optional().orElseThrow { NotFoundException("Incoming attachment") }
        if (row[3] != "PENDING") throw ConflictException("ALREADY_FILED", "This attachment has already been filed.")
        val item = documents.request(assignment.jobId).items.firstOrNull { it.id == assignment.itemId }
            ?: throw BadRequestException("WRONG_REQUEST_ITEM", "Select a document item on the chosen job.")
        if (item.kind !in setOf("DOCUMENT", "CHECKLIST"))
            throw BadRequestException("WRONG_REQUEST_ITEM", "Select a document or checklist item.")
        val upload = BytesMultipartFile(row[0] as String, row[1] as String, row[2] as ByteArray)
        val document = documents.upload(assignment.jobId, item.jobVesselId, item.id,
            item.documentTypeId ?: UUID(0, 0), null, upload)
        jdbc.sql("""UPDATE incoming_mail_attachment SET status='FILED',payload=X'',imported_document_id=:document
            WHERE id=:id""").param("document", Uuids.toBytes(document.id)).param("id", Uuids.toBytes(id)).update()
        return document
    }

    @Transactional
    fun ingest(mailboxKey: String, uid: Long, sender: String?, subject: String?, receivedAt: Instant?, parts: List<ParsedMailAttachment>) {
        val at = Uuids.toTimestamp(clock.instant())
        parts.forEach { part ->
            jdbc.sql("""INSERT IGNORE INTO incoming_mail_attachment
                (id,mailbox_key,message_uid,attachment_no,file_name,content_type,size_bytes,payload,sender,subject,received_at,status,created_at)
                VALUES (:id,:mailbox,:uid,:number,:name,:type,:size,:payload,:sender,:subject,:received,'PENDING',:created)""")
                .param("id", Uuids.toBytes(UuidV7.generate())).param("mailbox", mailboxKey).param("uid", uid)
                .param("number", part.number).param("name", part.fileName.take(255)).param("type", part.contentType)
                .param("size", part.bytes.size).param("payload", part.bytes)
                .param("sender", sender?.take(254)).param("subject", subject?.take(500))
                .param("received", receivedAt?.let(Uuids::toTimestamp)).param("created", at).update()
        }
    }

    @Transactional(readOnly = true)
    fun alreadyIngested(mailboxKey: String, uid: Long): Boolean = jdbc.sql("""SELECT COUNT(*) FROM incoming_mail_attachment
        WHERE mailbox_key=:mailbox AND message_uid=:uid""").param("mailbox", mailboxKey).param("uid", uid)
        .query(Long::class.java).single() > 0
}

private class BytesMultipartFile(private val filename: String, private val mime: String, private val content: ByteArray) : MultipartFile {
    override fun getName() = "file"
    override fun getOriginalFilename() = filename
    override fun getContentType() = mime
    override fun isEmpty() = content.isEmpty()
    override fun getSize() = content.size.toLong()
    override fun getBytes() = content
    override fun getInputStream(): InputStream = ByteArrayInputStream(content)
    override fun transferTo(dest: File) { dest.writeBytes(content) }
}

@RestController
@RequestMapping("/api/v1/technical/incoming-mail")
class IncomingMailController(private val service: IncomingMailService) {
    @GetMapping("/status") fun status(): Map<String, Boolean> = mapOf("enabled" to service.enabled())
    @GetMapping fun pending(): List<IncomingAttachmentDto> = service.pending()
    @PostMapping("/{id}/file") fun file(@PathVariable id: UUID, @RequestBody assignment: IncomingAttachmentAssignment): ResponseEntity<JobDocumentDto> =
        ResponseEntity.status(201).body(service.file(id, assignment))
}
