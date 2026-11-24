package com.ship2ship.core.persistence

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UuidV7Test {

    @Test
    fun `has version 7 and IETF variant`() {
        val uuid = UuidV7.generate()
        assertEquals(7, uuid.version())
        assertEquals(2, uuid.variant())
    }

    @Test
    fun `encodes the timestamp so keys sort by creation time`() {
        val earlier = UuidV7.generate(1_700_000_000_000)
        val later = UuidV7.generate(1_700_000_000_001)
        assertEquals(1_700_000_000_000, earlier.mostSignificantBits ushr 16)
        assertTrue(earlier.toString() < later.toString())
    }
}
