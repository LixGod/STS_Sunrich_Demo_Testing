package com.ship2ship.features.finance

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

data class BillRow(val number: Int, val particulars: String, val inr: String, val usd: String, val native: String)

data class BillSummary(
    val title: String, val company: String, val jobNumber: String, val client: String, val clientAddress: String?,
    val port: String, val vessels: String, val date: String, val rows: List<BillRow>,
    val totalInr: String, val totalUsd: String, val totalNative: String, val rate: String?, val currency: String,
)

internal fun formatBillMoney(amount: BigDecimal?, indian: Boolean = false): String = amount?.setScale(2, RoundingMode.HALF_UP)?.let {
    if (indian) it.toPlainString().replace(Regex("(?<=\\d)(?=(\\d{2})*\\d{3}\\.)"), ",")
    else String.format(Locale.ENGLISH, "%,.2f", it)
} ?: "—"

@Component
class BillSummaryBuilder(private val jdbc: JdbcClient) {
    fun build(jobId: UUID, title: String, company: String, client: String, clientAddress: String?, currency: String,
              date: LocalDate, entries: List<Pair<String, BigDecimal>>, total: BigDecimal): BillSummary {
        val job = jdbc.sql("""SELECT j.job_number,b.name,q.inr_fx_rate FROM sts_job j JOIN branch b ON b.id=j.branch_id
            JOIN quotation_version q ON q.id=j.quotation_version_id WHERE j.id=:job""")
            .param("job", jobId.bytes()).query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getBigDecimal(3)) }.single()
        val vessels = jdbc.sql("""SELECT v.name FROM job_vessel j JOIN vessel v ON v.id=j.vessel_id
            WHERE j.job_id=:job ORDER BY j.role""").param("job", jobId.bytes()).query(String::class.java).list()
        val rate = job.third?.takeIf { it > BigDecimal.ZERO }
        fun inr(amount: BigDecimal) = formatBillMoney(if (currency == "INR") amount else if (currency == "USD" && rate != null) amount.multiply(rate) else null, true)
        fun usd(amount: BigDecimal) = formatBillMoney(if (currency == "USD") amount else if (currency == "INR" && rate != null) amount.divide(rate, 8, RoundingMode.HALF_UP) else null)
        return BillSummary(title, company, job.first, client, clientAddress, job.second, vessels.joinToString(" / "),
            date.format(DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH)),
            entries.mapIndexed { index, (description, amount) -> BillRow(index + 1, description, inr(amount), usd(amount), formatBillMoney(amount)) },
            inr(total), usd(total), formatBillMoney(total), rate?.stripTrailingZeros()?.toPlainString(), currency)
    }
}
