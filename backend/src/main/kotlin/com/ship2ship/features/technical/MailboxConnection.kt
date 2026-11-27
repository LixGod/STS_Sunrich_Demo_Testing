package com.ship2ship.features.technical

import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.web.BadRequestException
import jakarta.mail.Folder
import jakarta.mail.Session
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.ship2ship.core.audit.Uuids

data class MailboxConnectionInput(val host: String, val port: Int = 993, val username: String,
    val password: String? = null, val folder: String = "INBOX")
data class MailboxConnectionStatus(val host: String, val port: Int, val username: String, val folder: String,
    val enabled: Boolean, val passwordSet: Boolean, val serverReady: Boolean)
data class MailboxCredentials(val host: String, val port: Int, val username: String, val password: String, val folder: String)

@Service
class MailboxConnectionService(
    private val jdbc: JdbcClient,
    private val policy: AccessPolicy,
    private val clock: Clock,
    @Value("\${IMAP_CREDENTIAL_KEY:}") private val encryptionKey: String,
) {
    private val key: SecretKeySpec? = runCatching {
        Base64.getDecoder().decode(encryptionKey).takeIf { it.size == 32 }?.let { SecretKeySpec(it, "AES") }
    }.getOrNull()

    @Transactional(readOnly = true)
    fun status(): MailboxConnectionStatus {
        policy.requireGlobal(Permissions.MASTER_TECHNICAL_WRITE, "MAILBOX_CONNECTION")
        val row = row()
        return MailboxConnectionStatus(row?.host ?: "", row?.port ?: 993, row?.username ?: "", row?.folder ?: "INBOX",
            row?.enabled == true, row != null, key != null)
    }

    @Transactional(readOnly = true)
    fun enabled(): Boolean = key != null && row()?.enabled == true

    @Transactional(readOnly = true)
    fun active(): MailboxCredentials? {
        val row = row()?.takeIf { it.enabled } ?: return null
        val secret = key ?: return null
        return MailboxCredentials(row.host, row.port, row.username, MailboxCipher.decrypt(row.cipher, secret), row.folder)
    }

    fun test(input: MailboxConnectionInput) {
        policy.requireGlobal(Permissions.MASTER_TECHNICAL_WRITE, "MAILBOX_CONNECTION")
        val credentials = credentials(input)
        try {
            verify(credentials)
        } catch (_: Exception) {
            throw BadRequestException("MAILBOX_CONNECTION_FAILED", "Could not connect to this IMAP mailbox. Check the server, account, password, and INBOX access.")
        }
    }

    @Transactional
    fun save(input: MailboxConnectionInput): MailboxConnectionStatus {
        policy.requireGlobal(Permissions.MASTER_TECHNICAL_WRITE, "MAILBOX_CONNECTION")
        val secret = key ?: throw BadRequestException("MAILBOX_KEY_MISSING", "The server encryption key is not configured yet.")
        val credentials = credentials(input)
        try {
            verify(credentials)
        } catch (_: Exception) {
            throw BadRequestException("MAILBOX_CONNECTION_FAILED", "Could not connect to this IMAP mailbox. Check the server, account, password, and INBOX access.")
        }
        jdbc.sql("""INSERT INTO mailbox_connection (id,host,port,username,folder,password_cipher,enabled,updated_at)
            VALUES (1,:host,:port,:username,:folder,:cipher,TRUE,:updated)
            ON DUPLICATE KEY UPDATE host=:host,port=:port,username=:username,folder=:folder,
            password_cipher=:cipher,enabled=TRUE,updated_at=:updated""")
            .param("host", credentials.host).param("port", credentials.port).param("username", credentials.username)
            .param("folder", credentials.folder).param("cipher", MailboxCipher.encrypt(credentials.password, secret))
            .param("updated", Uuids.toTimestamp(clock.instant())).update()
        return status()
    }

    @Transactional
    fun disconnect(): MailboxConnectionStatus {
        policy.requireGlobal(Permissions.MASTER_TECHNICAL_WRITE, "MAILBOX_CONNECTION")
        jdbc.sql("UPDATE mailbox_connection SET enabled=FALSE,updated_at=:updated WHERE id=1")
            .param("updated", Uuids.toTimestamp(clock.instant())).update()
        return status()
    }

    private fun credentials(input: MailboxConnectionInput): MailboxCredentials {
        val host = input.host.trim()
        val username = input.username.trim()
        val folder = input.folder.trim()
        if (host.isEmpty() || host.length > 255 || !host.matches(Regex("[A-Za-z0-9][A-Za-z0-9.-]*")) ||
            input.port !in 1..65535 || username.isEmpty() || username.length > 254 || folder != "INBOX")
            throw BadRequestException("INVALID_MAILBOX_SETTINGS", "Enter a valid IMAP host, port, account, and INBOX folder.")
        val existing = row()
        val password = input.password?.takeIf { it.isNotEmpty() }
            ?: existing?.takeIf { it.host == host && it.username == username }?.let {
                MailboxCipher.decrypt(it.cipher, key ?: throw BadRequestException("MAILBOX_KEY_MISSING", "The server encryption key is not configured yet."))
            }
            ?: throw BadRequestException("MAILBOX_PASSWORD_REQUIRED", "Enter the mailbox password or app password.")
        if (password.length > 2048) throw BadRequestException("INVALID_MAILBOX_SETTINGS", "The mailbox password is too long.")
        return MailboxCredentials(host, input.port, username, password, folder)
    }

    private fun verify(value: MailboxCredentials) {
        val properties = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.connectiontimeout", "10000")
            put("mail.imaps.timeout", "30000")
            put("mail.imaps.writetimeout", "30000")
            put("mail.imaps.peek", "true")
        }
        Session.getInstance(properties).getStore("imaps").use { store ->
            store.connect(value.host, value.port, value.username, value.password)
            val folder = store.getFolder(value.folder)
            try { folder.open(Folder.READ_ONLY) } finally { if (folder.isOpen) folder.close(false) }
        }
    }

    private data class Row(val host: String, val port: Int, val username: String, val folder: String,
        val cipher: ByteArray, val enabled: Boolean)

    private fun row(): Row? = jdbc.sql("SELECT host,port,username,folder,password_cipher,enabled FROM mailbox_connection WHERE id=1")
        .query { rs, _ -> Row(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getBytes(5), rs.getBoolean(6)) }
        .optional().orElse(null)

}

internal object MailboxCipher {
    fun encrypt(password: String, secret: SecretKeySpec): ByteArray {
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, nonce)) }
        return nonce + cipher.doFinal(password.toByteArray(Charsets.UTF_8))
    }

    fun decrypt(bytes: ByteArray, secret: SecretKeySpec): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
}

@RestController
@RequestMapping("/api/v1/technical/mailbox-connection")
class MailboxConnectionController(private val service: MailboxConnectionService) {
    @GetMapping fun status() = service.status()
    @PostMapping("/test") fun test(@RequestBody input: MailboxConnectionInput): Map<String, Boolean> {
        service.test(input)
        return mapOf("connected" to true)
    }
    @PutMapping fun save(@RequestBody input: MailboxConnectionInput) = service.save(input)
    @PostMapping("/disconnect") fun disconnect() = service.disconnect()
}
