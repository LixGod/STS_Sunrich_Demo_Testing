package com.ship2ship.features.finance

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.web.PageResponse
import com.ship2ship.core.web.Xlsx
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

data class ProfitabilityRow(
    val jobId: UUID, val jobNumber: String, val clientName: String?, val status: String, val currency: String, val minorUnits: Int,
    val jobDate: LocalDate, val incomeNet: BigDecimal, val costNet: BigDecimal, val adjustmentsNet: BigDecimal, val margin: BigDecimal,
    val marginPercent: BigDecimal?, val fdaIssued: Boolean,
)

/**
 * Income, actual cost and margin per job (SRS D06), in each job's own currency: no cross-currency total until the owner
 * names an FX source (Phase-5-Proposal question 3). Income is issued invoices less issued credit notes, net of tax; cost is
 * approved vendor bills, net, converted at their frozen job rate; adjustments come from the job's issued FDA, the same rule
 * as the FDA margin.
 */
@Service
class ProfitabilityService(private val jdbc: JdbcClient, private val access: FinanceAccess, private val json: JsonMapper,
    private val audit: AuditService) {

    @Transactional(readOnly = true)
    fun list(clientId: UUID?, status: String?, from: LocalDate?, to: LocalDate?, page: Int, size: Int): PageResponse<ProfitabilityRow> {
        val params = mutableMapOf<String, Any>()
        val where = listOfNotNull(
            access.clause(access.scope(PERMISSION), "j.branch_id", params),
            clientId?.let { params["client"] = it.bytes(); "j.client_id=:client" },
            status?.takeIf { it.isNotBlank() }?.let { params["status"] = it; "j.status=:status" },
            from?.let { params["from"] = it.atStartOfDay(); "j.created_at>=:from" },
            to?.let { params["to"] = it.plusDays(1).atStartOfDay(); "j.created_at<:to" },
        )
        val clause = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        val total = jdbc.sql("SELECT COUNT(*) FROM sts_job j $clause").params(params).query(Long::class.java).single()
        data class Job(val id: UUID, val number: String, val client: UUID, val status: String, val currency: String, val created: LocalDate)
        val jobs = jdbc.sql("SELECT j.id,j.job_number,j.client_id,j.status,j.currency,j.created_at FROM sts_job j $clause ORDER BY j.created_at DESC,j.id DESC LIMIT :limit OFFSET :offset")
            .params(params).param("limit", size).param("offset", page.toLong() * size).query { rs, _ ->
                Job(rs.getBytes(1).uuid()!!, rs.getString(2), rs.getBytes(3).uuid()!!, rs.getString(4), rs.getString(5),
                    rs.getObject(6, LocalDateTime::class.java).toLocalDate())
            }.list()
        if (jobs.isEmpty()) return PageResponse(emptyList(), page, size, total)
        val ids = jobs.map { it.id.bytes() }
        val income = jdbc.sql("""SELECT job_id,SUM(CASE WHEN doc_type='INVOICE' THEN net_amount ELSE -net_amount END) FROM invoice
            WHERE job_id IN (:ids) AND status='ISSUED' GROUP BY job_id""").param("ids", ids)
            .query { rs, _ -> rs.getBytes(1).uuid()!! to rs.getBigDecimal(2) }.list().toMap()
        val bills = jdbc.sql("SELECT job_id,net_amount,job_rate FROM job_cost_document WHERE job_id IN (:ids) AND kind='BILL' AND status='APPROVED'")
            .param("ids", ids).query { rs, _ -> Triple(rs.getBytes(1).uuid()!!, rs.getBigDecimal(2), rs.getBigDecimal(3)) }.list()
        val fdas = jdbc.sql("SELECT job_id,content_json FROM fda_revision WHERE job_id IN (:ids) AND status='ISSUED'").param("ids", ids)
            .query { rs, _ -> rs.getBytes(1).uuid()!! to json.readValue(rs.getString(2), FdaInput::class.java) }.list().toMap()
        val clients = access.clientNames(jobs.map { it.client })
        val units = jobs.map { it.currency }.distinct().associateWith(access::minorUnits)
        val billsByJob = bills.groupBy { it.first }
        val rows = jobs.map { j ->
            val minor = units.getValue(j.currency)
            val incomeNet = (income[j.id] ?: BigDecimal.ZERO).setScale(minor, RoundingMode.HALF_UP)
            // Same total as CostMoney's per-document conversion: net × frozen job rate, rounded once per document.
            val costNet = billsByJob[j.id].orEmpty().fold(BigDecimal.ZERO) { t, b -> t + b.second.multiply(b.third).setScale(minor, RoundingMode.HALF_UP) }
            val adjustments = fdas[j.id]?.adjustments.orEmpty().fold(BigDecimal.ZERO) { t, a -> if (a.kind == "INCOME") t + a.amount else t - a.amount }
                .setScale(minor, RoundingMode.HALF_UP)
            val margin = incomeNet + adjustments - costNet
            val percent = if (incomeNet.signum() > 0) margin.multiply(BigDecimal(100)).divide(incomeNet, 1, RoundingMode.HALF_UP) else null
            ProfitabilityRow(j.id, j.number, clients[j.client], j.status, j.currency, minor, j.created, incomeNet, costNet, adjustments, margin, percent, j.id in fdas)
        }
        return PageResponse(rows, page, size, total)
    }

    /** All matching jobs as an Excel workbook; every export is audited. */
    @Transactional
    fun xlsx(clientId: UUID?, status: String?, from: LocalDate?, to: LocalDate?): Pair<String, ByteArray> {
        var count = 0
        val rows = sequence {
            var page = 0
            do {
                val batch = list(clientId, status, from, to, page++, 250).items
                for (row in batch) {
                    count++
                    yield(listOf(row.jobNumber, row.clientName, row.status, row.jobDate.toString(), row.currency,
                        row.incomeNet, row.costNet, row.adjustmentsNet, row.margin, row.marginPercent, if (row.fdaIssued) "yes" else "no"))
                }
            } while (batch.size == 250)
        }
        val bytes = Xlsx.workbook("Profitability", "Job profitability (each job in its own currency)",
            listOf("Job", "Client", "Status", "Date", "Currency", "Income net", "Cost net", "FDA adjustments", "Margin", "Margin %", "FDA issued"),
            rows.asIterable())
        audit.record(AuditEntry(AuditAction.EXPORT, "PROFITABILITY", null, null,
            mapOf("format" to "xlsx", "rows" to count, "clientId" to clientId, "status" to status, "from" to from?.toString(), "to" to to?.toString())))
        return "profitability.xlsx" to bytes
    }

    companion object {
        const val PERMISSION = "FIN.PROFIT.READ"
    }
}

@RestController
@RequestMapping("/api/v1/finance/profitability")
class ProfitabilityController(private val service: ProfitabilityService) {
    @GetMapping
    fun list(@RequestParam clientId: UUID?, @RequestParam status: String?, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?, @RequestParam page: Int?, @RequestParam size: Int?) =
        service.list(clientId, status, from, to, (page ?: 0).coerceAtLeast(0), (size ?: 25).coerceIn(1, 100))

    @GetMapping("/xlsx")
    fun xlsx(@RequestParam clientId: UUID?, @RequestParam status: String?, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?): ResponseEntity<ByteArray> =
        service.xlsx(clientId, status, from, to).let { (name, bytes) -> xlsx(name, bytes) }
}
