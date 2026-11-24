package com.ship2ship.core.persistence

import java.security.SecureRandom
import java.util.UUID

/**
 * Time-ordered UUID version 7 (RFC 9562): 48-bit Unix milliseconds followed by random bits.
 * Ordered keys keep InnoDB clustered-index inserts sequential (SRS DR-01, decision K5).
 */
object UuidV7 {

    private val random = SecureRandom()

    fun generate(epochMillis: Long = System.currentTimeMillis()): UUID {
        val randomBits = ByteArray(10).also(random::nextBytes)
        val msb = (epochMillis shl 16) or
            (0x7000L) or
            ((randomBits[0].toLong() and 0x0F) shl 8) or
            (randomBits[1].toLong() and 0xFF)
        var lsb = 0L
        for (i in 2 until 10) {
            lsb = (lsb shl 8) or (randomBits[i].toLong() and 0xFF)
        }
        lsb = (lsb and 0x3FFFFFFFFFFFFFFFL) or Long.MIN_VALUE // IETF variant 10xx
        return UUID(msb, lsb)
    }
}
