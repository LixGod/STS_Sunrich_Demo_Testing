package com.ship2ship.features.technical

import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.UIDFolder
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.Properties

/** Reads the configured mailbox without changing messages. Filing always requires an operator. */
@Component
@ConditionalOnProperty(prefix = "ship2ship.incoming-mail", name = ["enabled"], havingValue = "true")
class ImapPoller(
    private val intake: IncomingMailService,
    @Value("\${ship2ship.incoming-mail.host}") private val host: String,
    @Value("\${ship2ship.incoming-mail.port:993}") private val port: Int,
    @Value("\${ship2ship.incoming-mail.username}") private val username: String,
    @Value("\${ship2ship.incoming-mail.password}") private val password: String,
    @Value("\${ship2ship.incoming-mail.folder:INBOX}") private val folderName: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        require(host.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
            "IMAP_ENABLED requires IMAP_HOST, IMAP_USERNAME, and IMAP_PASSWORD."
        }
    }

    @Scheduled(fixedDelayString = "\${ship2ship.incoming-mail.poll-ms:120000}", initialDelay = 10000)
    fun poll() {
        val properties = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.connectiontimeout", "10000")
            put("mail.imaps.timeout", "30000")
            put("mail.imaps.writetimeout", "30000")
            put("mail.imaps.peek", "true")
        }
        val store = Session.getInstance(properties).getStore("imaps")
        try {
            store.connect(host, port, username, password)
            val folder = store.getFolder(folderName)
            try {
                folder.open(Folder.READ_ONLY)
                val uidFolder = folder as? UIDFolder ?: error("The configured IMAP folder has no stable message IDs.")
                val mailboxKey = sha256("$host:$port:$username:$folderName:${uidFolder.uidValidity}")
                val total = folder.messageCount
                if (total == 0) return
                for (message in folder.getMessages(maxOf(1, total - 99), total)) {
                    val uid = uidFolder.getUID(message)
                    if (uid <= 0 || intake.alreadyIngested(mailboxKey, uid)) continue
                    try {
                        val attachments = MailAttachmentParser.parse(message)
                        if (attachments.isNotEmpty()) {
                            intake.ingest(mailboxKey, uid, message.from?.joinToString(", ") { it.toString() },
                                message.subject, message.receivedDate?.toInstant(), attachments)
                        }
                    } catch (e: Exception) {
                        log.warn("Could not import IMAP message UID {}: {}", uid, e.message)
                    }
                }
            } finally {
                if (folder.isOpen) folder.close(false)
            }
        } catch (e: Exception) {
            log.warn("Incoming mail poll failed for configured mailbox: {}", e.message)
        } finally {
            if (store.isConnected) store.close()
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

object MailAttachmentParser {
    private const val MAX_PART_BYTES = 15 * 1024 * 1024
    private const val MAX_ATTACHMENTS = 20

    fun parse(message: Message): List<ParsedMailAttachment> {
        val attachments = mutableListOf<ParsedMailAttachment>()
        visit(message, attachments)
        return attachments
    }

    private fun visit(part: Part, attachments: MutableList<ParsedMailAttachment>) {
        if (attachments.size >= MAX_ATTACHMENTS) return
        if (part.isMimeType("multipart/*")) {
            val multipart = part.content as Multipart
            for (index in 0 until multipart.count) visit(multipart.getBodyPart(index), attachments)
            return
        }
        val filename = part.fileName?.substringAfterLast('/')?.substringAfterLast('\\')?.trim() ?: return
        if (filename.isEmpty() || Part.INLINE.equals(part.disposition, ignoreCase = true)) return
        val extension = filename.substringAfterLast('.', "").lowercase()
        if (extension !in setOf("pdf", "png", "jpg", "jpeg")) return
        val bytes = part.inputStream.use { it.readNBytes(MAX_PART_BYTES + 1) }
        if (bytes.isEmpty() || bytes.size > MAX_PART_BYTES) return
        val contentType = when {
            bytes.size >= 5 && bytes.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray()) -> "application/pdf"
            bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> "image/png"
            bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
            else -> return
        }
        attachments += ParsedMailAttachment(attachments.size, filename.take(255), contentType, bytes)
    }
}
