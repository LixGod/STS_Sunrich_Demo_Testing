package com.ship2ship.core.persistence

import com.ship2ship.features.masters.ImoNumber
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NumberSeriesTest {

    private val context = NumberContext("MUM", 2026, LocalDate.of(2026, 3, 12))

    @Test
    fun `formats every mask token`() {
        assertEquals("ENQ/2026/00042", NumberSeriesService.format("ENQ/{YYYY}/{SEQ:5}", context, 42))
        assertEquals("STS/MUM/26-7", NumberSeriesService.format("STS/{BRANCH}/{YY}-{SEQ}", context, 7))
        assertEquals("Q123456", NumberSeriesService.format("Q{SEQ:3}", context, 123456))
        assertEquals("ENQ-STS/MUM/12032026/001", NumberSeriesService.format("ENQ-STS/{BRANCH}/{DDMMYYYY}/{SEQ:3}", context, 1))
        assertEquals("JOB-STS/MUM/12032026/001", NumberSeriesService.format("JOB-STS/{BRANCH}/{DDMMYYYY}/{SEQ:3}", context, 1))
    }
}

class ImoNumberTest {

    @Test
    fun `accepts valid check digits and rejects others`() {
        assertTrue(ImoNumber.isValid("1000007"))
        assertTrue(ImoNumber.isValid("1000033"))
        assertFalse(ImoNumber.isValid("1000008"))
        assertFalse(ImoNumber.isValid("123456"))
        assertFalse(ImoNumber.isValid("12345AB"))
    }
}
