package com.ship2ship.features.movements

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

class ChargeClockMathTest {
    @Test
    fun `basis grace and increment do not alter actual time`() {
        val elapsed = 49 * 3600L + 7 * 60
        assertEquals(BigDecimal("1.250000"), ChargeClockService.billableUnits(elapsed, 48, 15, 2, "ROUND_UP_TO_INCREMENT"))
        assertEquals(BigDecimal("25.250000"), ChargeClockService.billableUnits(elapsed, 24, 15, 2, "ROUND_UP_TO_INCREMENT"))
        assertEquals(BigDecimal("0.000000"), ChargeClockService.billableUnits(elapsed, 72, 15, 2, "ROUND_UP_TO_INCREMENT"))
        assertEquals(BigDecimal("0.500000"), ChargeClockService.billableUnits(48 * 3600L + 30 * 60, 48, 15, 0, "NONE"))
        assertEquals(BigDecimal("0.750000"), ChargeClockService.billableUnits(elapsed,48,15,2,"ROUND_UP_TO_INCREMENT",BigDecimal("-0.5")))
        assertEquals(BigDecimal("0.000000"), ChargeClockService.billableUnits(elapsed,48,15,2,"ROUND_UP_TO_INCREMENT",BigDecimal("-10")))
    }
}
