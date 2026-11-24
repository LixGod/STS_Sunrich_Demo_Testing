package com.ship2ship.core.persistence

import com.ship2ship.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals

/** D02 numbering: concurrent allocations give unique, contiguous numbers; a rolled-back allocation leaves no gap. */
class NumberSeriesIT : IntegrationTest() {

    @Autowired
    lateinit var numbers: NumberSeriesService

    @Autowired
    lateinit var tx: TransactionTemplate

    @Test
    fun `parallel allocations are unique and contiguous`() {
        val type = "TEST-${UUID.randomUUID().toString().take(8)}"
        jdbc.sql("INSERT INTO number_series_format (id, series_type, mask) VALUES (UUID_TO_BIN(UUID()), :t, '{SEQ}')")
            .param("t", type).update()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll((1..40).map {
                Callable { tx.execute { numbers.next(type, NumberContext(null, 2026)) }!! }
            }).map { it.get().toInt() }
            assertEquals((1..40).toList(), results.sorted())
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `a rolled-back allocation is reused`() {
        val type = "TEST-${UUID.randomUUID().toString().take(8)}"
        jdbc.sql("INSERT INTO number_series_format (id, series_type, mask) VALUES (UUID_TO_BIN(UUID()), :t, 'N{SEQ:3}')")
            .param("t", type).update()
        val context = NumberContext(null, 2026)
        assertEquals("N001", tx.execute { numbers.next(type, context) })
        runCatching { tx.executeWithoutResult { numbers.next(type, context); error("roll back") } }
        assertEquals("N002", tx.execute { numbers.next(type, context) })
    }
}
