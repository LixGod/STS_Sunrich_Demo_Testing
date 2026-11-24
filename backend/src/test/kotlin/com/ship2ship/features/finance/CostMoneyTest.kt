package com.ship2ship.features.finance

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

class CostMoneyTest {
    @Test fun `converted lines conserve document amounts when rounding crosses cost heads`() {
        val lines=listOf("A","B").map { CostLine(it,it,it,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO) }
        val result=convertedCostLines(lines,BigDecimal("1.3333"),BigDecimal("2.67"),2)
        assertEquals(BigDecimal("2.67"),result.fold(BigDecimal.ZERO) { t,line->t+line.first+line.second })
        assertEquals(listOf(BigDecimal("1.34"),BigDecimal("1.33")),result.map { it.first })
    }
}
