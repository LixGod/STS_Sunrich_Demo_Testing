package com.ship2ship.core.persistence

import com.ship2ship.core.audit.Uuids
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Values available to a numbering mask. */
data class NumberContext(val branchCode: String?, val year: Int, val date: LocalDate? = null)

/**
 * Human-readable sequential references (D02), e.g. `ENQ/2026/00001`. Each series is counted per year under a row lock,
 * so numbers are unique and, because the counter rolls back with a failed transaction, gap-free.
 * Masks come from `number_series_format`.
 */
@Service
class NumberSeriesService(private val jdbc: JdbcClient, transactionManager: PlatformTransactionManager) {

    private val separate = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun next(seriesType: String, context: NumberContext): String {
        // A new series row is created and committed on its own first. Creating it inside the caller's transaction
        // makes concurrent first allocations deadlock on the unique-index gap locks.
        if (!exists(seriesType, context.year)) {
            separate.executeWithoutResult {
                jdbc.sql(
                    """
                    INSERT IGNORE INTO number_series (id, series_type, period_year, next_value)
                    VALUES (:id, :type, :year, 1)
                    """,
                ).param("id", Uuids.toBytes(UuidV7.generate())).param("type", seriesType)
                    .param("year", context.year).update()
            }
        }

        val value = jdbc.sql(
            "SELECT next_value FROM number_series WHERE series_type = :type AND period_year = :year FOR UPDATE",
        ).param("type", seriesType).param("year", context.year).query(Long::class.java).single()

        jdbc.sql(
            "UPDATE number_series SET next_value = next_value + 1 WHERE series_type = :type AND period_year = :year",
        ).param("type", seriesType).param("year", context.year).update()

        return format(mask(seriesType), context, value)
    }

    /** Plain (non-locking) read, so no gap lock is held before the separate insert. */
    private fun exists(seriesType: String, year: Int): Boolean =
        jdbc.sql("SELECT COUNT(*) FROM number_series WHERE series_type = :type AND period_year = :year")
            .param("type", seriesType).param("year", year).query(Long::class.java).single() > 0

    private fun mask(seriesType: String): String =
        jdbc.sql("SELECT mask FROM number_series_format WHERE series_type = :type")
            .param("type", seriesType).query(String::class.java).optional()
            .orElseThrow { IllegalStateException("No number format configured for $seriesType") }

    companion object {
        private val SEQ = Regex("""\{SEQ(?::(\d+))?}""")
        private val DDMMYYYY = DateTimeFormatter.ofPattern("ddMMyyyy")

        fun format(mask: String, context: NumberContext, value: Long): String =
            mask.replace("{BRANCH}", context.branchCode ?: "")
                .replace("{DDMMYYYY}", context.date?.format(DDMMYYYY) ?: "")
                .replace("{YYYY}", context.year.toString())
                .replace("{YY}", (context.year % 100).toString().padStart(2, '0'))
                .replace(SEQ) { match -> value.toString().padStart(match.groupValues[1].toIntOrNull() ?: 1, '0') }
    }
}
