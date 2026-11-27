package com.ship2ship.features.technical

import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import javax.crypto.AEADBadTagException
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals

class MailboxCipherTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    @Test
    fun `password is encrypted with a fresh nonce and tampering is rejected`() {
        val first = MailboxCipher.encrypt("app password", key)
        val second = MailboxCipher.encrypt("app password", key)
        assertNotEquals(first.toList(), second.toList())
        assertEquals("app password", MailboxCipher.decrypt(first, key))
        first[first.lastIndex] = (first.last().toInt() xor 1).toByte()
        assertThrows(AEADBadTagException::class.java) { MailboxCipher.decrypt(first, key) }
    }
}
