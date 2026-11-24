package com.ship2ship.features.finance

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.core.security.OrganisationLookup
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.security.ReferenceLookup
import com.ship2ship.core.security.UserDirectory
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.BusinessRuleException
import com.ship2ship.core.web.FieldError
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.PageResponse
import com.ship2ship.core.web.Versioning
import com.ship2ship.core.web.Xlsx
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Receipts from clients and their allocation to issued invoices (FR-J11). One currency per receipt, the invoice
 * currency (owner question 6); what is not allocated stays on account; a wrong receipt is reversed with a reason, never
 * deleted. Receipts belong to the company, so they need a company-wide grant.
 */
@Service
class ReceiptService(
    private val jdbc: JdbcClient,
    private val access: FinanceAccess,
    private val invoices: InvoiceService,
    private val organisation: OrganisationLookup,
    private val references: ReferenceLookup,
    private val users: UserDirectory,
    private val audit: AuditService,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(clientId: UUID?, status: String?, q: String?, page: Int, size: Int): PageResponse<ReceiptDto> {
        val params = mutableMapOf<String, Any>()
        val where = listOfNotNull(
            access.clause(access.scope(Permissions.FIN_RECEIPT_READ), null, params),
            clientId?.let { params["client"] = it.bytes(); "client_id=:client" },
            status?.let { params["status"] = it; "status=:status" },
            q?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let {
                params["q"] = "%$it%"
                "(LOWER(reference) LIKE :q OR EXISTS (SELECT 1 FROM client c WHERE c.id=receipt.client_id AND LOWER(c.legal_name) LIKE :q))"
            },
        )
        val clause = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        val total = jdbc.sql("SELECT COUNT(*) FROM receipt $clause").params(params).query(Long::class.java).single()
        val ids = jdbc.sql("SELECT id FROM receipt $clause ORDER BY received_on DESC, recorded_at DESC LIMIT :limit OFFSET :offset").params(params)
            .param("limit", size).param("offset", page * size).query { rs, _ -> rs.getBytes(1).uuid()!! }.list()
        return PageResponse(toDtos(loadAll(ids)), page, size, total)
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): ReceiptDto {
        val row = load(id)
        access.requireGlobal(Permissions.FIN_RECEIPT_READ, ENTITY, id)
        return toDto(row)
    }

    /** Clients with open issued invoices, per currency, within the receipt-writing scope. */
    @Transactional(readOnly = true)
    fun parties(): List<ReceiptParty> {
        val params = mutableMapOf<String, Any>()
        val scope = access.clause(access.scope(Permissions.FIN_RECEIPT_WRITE), null, params)
        val rows = jdbc.sql("""SELECT client_id,currency,SUM(open_amount) FROM (
            SELECT i.client_id,i.currency,i.gross_amount
              - (SELECT COALESCE(SUM(c.gross_amount),0) FROM invoice c WHERE c.credited_invoice_id=i.id AND c.status='ISSUED')
              - (SELECT COALESCE(SUM(a.amount),0) FROM receipt_allocation a WHERE a.invoice_id=i.id AND a.reversed_at IS NULL) AS open_amount
            FROM invoice i WHERE i.doc_type='INVOICE' AND i.status='ISSUED'${scope?.let { " AND $it" } ?: ""}
            ) open_invoice GROUP BY client_id,currency""").params(params).query { rs, _ ->
            (rs.getBytes(1).uuid()!! to rs.getString(2)) to rs.getBigDecimal(3)
        }.list().filter { it.second.signum() > 0 }
        val clients = references.clients(rows.map { it.first.first }.distinct())
        val units = rows.map { it.first.second }.distinct().associateWith(access::minorUnits)
        return rows.map { (key, outstanding) ->
            val minor = units.getValue(key.second)
            ReceiptParty(key.first, clients[key.first]?.legalName, key.second, outstanding.setScale(minor, RoundingMode.HALF_UP), minor)
        }.sortedWith(compareBy({ it.clientName ?: "" }, { it.currency }))
    }

    /** Issued invoices of the client in the currency that still have an amount open. */
    @Transactional(readOnly = true)
    fun openInvoices(clientId: UUID, currency: String): List<InvoiceSummaryDto> {
        access.requireGlobal(Permissions.FIN_RECEIPT_READ, ENTITY, null)
        return invoices.summaries("WHERE i.client_id=:c AND i.currency=:cur AND i.doc_type='INVOICE' AND i.status='ISSUED'",
            mapOf("c" to clientId.bytes(), "cur" to currency.uppercase()), "ORDER BY i.due_date, i.invoice_date")
            .filter { it.outstandingAmount.signum() > 0 }
    }

    @Transactional
    fun create(input: ReceiptInput): ReceiptDto {
        val errors = mutableListOf<FieldError>()
        if (input.clientId == null) errors += FieldError("clientId", "required", null)
        if (input.receivedOn == null) errors += FieldError("receivedOn", "required", null)
        else if (input.receivedOn.isAfter(invoices.today())) errors += FieldError("receivedOn", "future", "Cannot be in the future.")
        val currency = references.currency(input.currency.trim().uppercase())
        if (currency == null || !currency.active) errors += FieldError("currency", "unknown", "Choose an active currency.")
        val minor = currency?.minorUnits ?: 2
        if (input.amount == null || input.amount.signum() <= 0 || input.amount.stripTrailingZeros().scale() > minor || input.amount >= BigDecimal("1000000000000000")) {
            errors += FieldError("amount", "range", "More than 0, at most $minor decimals.")
        }
        if (input.method !in METHODS) errors += FieldError("method", "allowed", null)
        if ((input.reference?.length ?: 0) > 100) errors += FieldError("reference", "size", null)
        if (input.clientId != null && references.clients(listOf(input.clientId))[input.clientId] == null) errors += FieldError("clientId", "unknown", null)
        if (errors.isNotEmpty()) throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", errors)
        access.requireGlobal(Permissions.FIN_RECEIPT_WRITE, ENTITY, null)
        val id = UuidV7.generate()
        jdbc.sql("""INSERT INTO receipt (id,client_id,received_on,amount,currency,method,reference,status,recorded_by,recorded_at,version)
            VALUES (:id,:client,:on,:amount,:currency,:method,:ref,'ACTIVE',:a,:t,0)""")
            .param("id", id.bytes()).param("client", input.clientId!!.bytes()).param("on", input.receivedOn)
            .param("amount", input.amount!!.setScale(minor)).param("currency", currency!!.code).param("method", input.method)
            .param("ref", input.reference?.trim()?.ifEmpty { null }).param("a", access.actor().bytes()).param("t", now()).update()
        audit.record(AuditEntry(AuditAction.CREATE, ENTITY, id, null,
            mapOf("amount" to input.amount, "currency" to currency.code, "method" to input.method)))
        allocate(load(id), input.allocations)
        return toDto(load(id))
    }

    @Transactional
    fun addAllocations(id: UUID, expectedVersion: Long, request: AllocationsRequest): ReceiptDto {
        val row = locked(id, expectedVersion)
        if (request.allocations.isEmpty()) throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.",
            listOf(FieldError("allocations", "required", "Add at least one allocation.")))
        allocate(row, request.allocations)
        jdbc.sql("UPDATE receipt SET version=version+1 WHERE id=:id").param("id", id.bytes()).update()
        return toDto(load(id))
    }

    @Transactional
    fun reverse(id: UUID, expectedVersion: Long, request: ReverseRequest): ReceiptDto {
        val row = locked(id, expectedVersion)
        val reason = request.reason.trim()
        if (reason.isEmpty()) throw BadRequestException("REASON_REQUIRED", "A reason is required for this change.", listOf(FieldError("reason", "required", null)))
        val t = now()
        jdbc.sql("""UPDATE receipt SET status='REVERSED',reversal_reason=:r,reversed_by=:a,reversed_at=:t,version=version+1 WHERE id=:id""")
            .param("r", reason.take(500)).param("a", access.actor().bytes()).param("t", t).param("id", id.bytes()).update()
        jdbc.sql("UPDATE receipt_allocation SET reversed_at=:t WHERE receipt_id=:id AND reversed_at IS NULL").param("t", t).param("id", id.bytes()).update()
        audit.record(AuditEntry(AuditAction.STATUS_CHANGE, ENTITY, id, null,
            mapOf("from" to "ACTIVE", "to" to "REVERSED", "reason" to reason, "allocations" to row.allocations.count { !it.reversed })))
        return toDto(load(id))
    }

    // ---------- receivables ----------

    /**
     * Open issued invoices as at [asOf]: issued on or before that day, less credit notes dated and receipts received on or
     * before it. Ageing from the due date in the candidate buckets (owner question 8).
     */
    @Transactional(readOnly = true)
    fun receivables(asOf: LocalDate?, clientId: UUID?, currency: String?): ReceivablesDto {
        val day = asOf ?: invoices.today()
        val params = mutableMapOf<String, Any>()
        val where = receivablesWhere(day, clientId, currency, params)
        val rows = jdbc.sql("""SELECT o.id, o.number, o.client_id, j.job_number, o.currency, o.invoice_date, o.due_date, o.gross_amount,
              o.credited, o.received, o.minor_units FROM (${openAsOf(where)}) o JOIN sts_job j ON j.id=o.job_id
            ORDER BY o.due_date, o.invoice_date, o.id""").params(params).query { rs, _ ->
            val minor = rs.getInt(11)
            fun m(i: Int) = rs.getBigDecimal(i).setScale(minor, RoundingMode.HALF_UP)
            val due = rs.getObject(7, LocalDate::class.java)
            val overdue = if (due != null && due.isBefore(day)) ChronoUnit.DAYS.between(due, day).toInt() else 0
            ReceivableRow(rs.getBytes(1).uuid()!!, rs.getString(2), rs.getBytes(3).uuid()!!, null, rs.getString(4), rs.getString(5),
                rs.getObject(6, LocalDate::class.java), due, m(8), m(9), m(10), m(8) - m(9) - m(10), overdue, bucket(overdue), minor)
        }.list()
        val names = references.clients(rows.map { it.clientId }.distinct()).mapValues { it.value.legalName }
        val named = rows.map { it.copy(clientName = names[it.clientId]) }
        val totals = named.groupBy { it.clientId to it.currency }.map { (k, list) ->
            ReceivableTotal(names[k.first], k.second, BUCKETS.associateWith { b -> list.filter { it.bucket == b }.fold(BigDecimal.ZERO) { s, r -> s + r.outstandingAmount } },
                list.fold(BigDecimal.ZERO) { s, r -> s + r.outstandingAmount }, list.first().minorUnits)
        }.sortedWith(compareBy({ it.clientName ?: "" }, { it.currency }))
        return ReceivablesDto(day, named, totals)
    }

    @Transactional(readOnly = true)
    fun receivablesPage(asOf: LocalDate?, clientId: UUID?, currency: String?, page: Int, size: Int): ReceivablesPageDto {
        if (page < 0) throw BadRequestException("INVALID_PAGE", "page must be zero or greater.")
        val limit = size.coerceIn(1, 100)
        val day = asOf ?: invoices.today()
        val params = mutableMapOf<String, Any>()
        val where = receivablesWhere(day, clientId, currency, params)
        data class Aggregate(val clientId: UUID, val currency: String, val minor: Int, val bucket: String, val amount: BigDecimal, val count: Long)
        val aggregates = jdbc.sql("""SELECT client_id, currency, minor_units, bucket, SUM(outstanding), COUNT(*) FROM (
            SELECT client_id, currency, minor_units,
                CASE WHEN due_date IS NULL OR due_date >= :asOf THEN 'NOT_DUE'
                    WHEN DATEDIFF(:asOf, due_date) <= 30 THEN 'D1_30'
                    WHEN DATEDIFF(:asOf, due_date) <= 60 THEN 'D31_60'
                    WHEN DATEDIFF(:asOf, due_date) <= 90 THEN 'D61_90' ELSE 'D90_PLUS' END AS bucket,
                ROUND(gross_amount, minor_units) - ROUND(credited, minor_units) - ROUND(received, minor_units) AS outstanding
            FROM (${openAsOf(where)}) o) amounts
            GROUP BY client_id, currency, minor_units, bucket""").params(params).query { rs, _ ->
            Aggregate(rs.getBytes(1).uuid()!!, rs.getString(2), rs.getInt(3), rs.getString(4), rs.getBigDecimal(5), rs.getLong(6))
        }.list()
        val names = references.clients(aggregates.map { it.clientId }.distinct()).mapValues { it.value.legalName }
        val totals = aggregates.groupBy { it.clientId to it.currency }.map { (key, values) ->
            val buckets = BUCKETS.associateWith { bucket -> values.firstOrNull { it.bucket == bucket }?.amount ?: BigDecimal.ZERO }
            ReceivableTotal(names[key.first], key.second, buckets, buckets.values.fold(BigDecimal.ZERO, BigDecimal::add), values.first().minor)
        }.sortedWith(compareBy({ it.clientName ?: "" }, { it.currency }))
        val rows = jdbc.sql("""SELECT o.id, o.number, o.client_id, j.job_number, o.currency, o.invoice_date, o.due_date, o.gross_amount,
              o.credited, o.received, o.minor_units FROM (${openAsOf(where)}) o JOIN sts_job j ON j.id=o.job_id
            ORDER BY o.due_date, o.invoice_date, o.id LIMIT :limit OFFSET :offset""")
            .params(params + mapOf("limit" to limit, "offset" to page.toLong() * limit)).query { rs, _ ->
                val minor = rs.getInt(11)
                fun m(i: Int) = rs.getBigDecimal(i).setScale(minor, RoundingMode.HALF_UP)
                val due = rs.getObject(7, LocalDate::class.java)
                val overdue = if (due != null && due.isBefore(day)) ChronoUnit.DAYS.between(due, day).toInt() else 0
                val id = rs.getBytes(3).uuid()!!
                ReceivableRow(rs.getBytes(1).uuid()!!, rs.getString(2), id, names[id], rs.getString(4), rs.getString(5),
                    rs.getObject(6, LocalDate::class.java), due, m(8), m(9), m(10), m(8) - m(9) - m(10), overdue, bucket(overdue), minor)
            }.list()
        return ReceivablesPageDto(day, PageResponse(rows, page, limit, aggregates.sumOf { it.count }), totals)
    }

    @Transactional(readOnly = true)
    fun receivablesSummary(asOf: LocalDate?, clientId: UUID?, currency: String?): ReceivablesSummaryDto {
        val day = asOf ?: invoices.today()
        val params = mutableMapOf<String, Any>()
        val where = receivablesWhere(day, clientId, currency, params)
        val rows = jdbc.sql("""SELECT currency, bucket, SUM(outstanding) FROM (
            SELECT currency, CASE WHEN due_date IS NULL OR due_date >= :asOf THEN 'NOT_DUE'
                WHEN DATEDIFF(:asOf, due_date) <= 30 THEN 'D1_30'
                WHEN DATEDIFF(:asOf, due_date) <= 60 THEN 'D31_60'
                WHEN DATEDIFF(:asOf, due_date) <= 90 THEN 'D61_90' ELSE 'D90_PLUS' END AS bucket,
                ROUND(gross_amount, minor_units) - ROUND(credited, minor_units) - ROUND(received, minor_units) AS outstanding
            FROM (${openAsOf(where)}) o) open_amounts GROUP BY currency, bucket""").params(params)
            .query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getBigDecimal(3)) }.list()
        val totals = rows.groupBy { it.first }.map { (code, amounts) ->
            val buckets = BUCKETS.associateWith { bucket -> amounts.firstOrNull { it.second == bucket }?.third ?: BigDecimal.ZERO }
            ReceivableTotal(null, code, buckets, buckets.values.fold(BigDecimal.ZERO, BigDecimal::add), access.minorUnits(code))
        }.sortedBy { it.currency }
        return ReceivablesSummaryDto(day, totals)
    }

    /**
     * Issued invoices matching [where] with what was credited and received as at the day, and only those with something
     * still open once each amount is rounded to the currency's minor units (all amounts are non-negative, so ROUND is half
     * up as in Kotlin). The detail and the summary both read it, so their totals always agree.
     */
    private fun openAsOf(where: String) = """SELECT * FROM (SELECT i.id, i.number, i.client_id, i.job_id, i.currency, i.invoice_date, i.due_date,
            i.gross_amount, COALESCE(cur.minor_units, 2) AS minor_units,
            (SELECT COALESCE(SUM(c.gross_amount),0) FROM invoice c WHERE c.credited_invoice_id=i.id AND c.status='ISSUED'
                AND c.invoice_date <= :asOf AND c.issued_at < :end) AS credited,
            (SELECT COALESCE(SUM(a.amount),0) FROM receipt_allocation a JOIN receipt r ON r.id=a.receipt_id
                WHERE a.invoice_id=i.id AND r.received_on <= :asOf AND r.recorded_at < :end AND a.allocated_at < :end
                AND (a.reversed_at IS NULL OR a.reversed_at >= :end)) AS received
        FROM invoice i LEFT JOIN currency cur ON cur.code=i.currency WHERE $where) amounts
        WHERE ROUND(gross_amount, minor_units) - ROUND(credited, minor_units) - ROUND(received, minor_units) > 0"""

    private fun receivablesWhere(day: LocalDate, clientId: UUID?, currency: String?, params: MutableMap<String, Any>): String {
        params["asOf"] = day
        params["end"] = day.plusDays(1).atStartOfDay()
        return listOfNotNull(
            "i.doc_type='INVOICE'", "i.status='ISSUED'", "i.invoice_date <= :asOf", "i.issued_at < :end",
            access.clause(access.scope(Permissions.FIN_INVOICE_READ), "i.branch_id", params),
            clientId?.let { params["client"] = it.bytes(); "i.client_id=:client" },
            currency?.takeIf { it.isNotBlank() }?.let { params["currency"] = it.uppercase(); "i.currency=:currency" },
        ).joinToString(" AND ")
    }

    /** The receivables as an Excel workbook; every export is written to the audit trail (SR-01). */
    @Transactional
    fun receivablesXlsx(asOf: LocalDate?, clientId: UUID?, currency: String?): Pair<String, ByteArray> {
        val data = receivables(asOf, clientId, currency)
        val bytes = Xlsx.streaming { book ->
            val bold = book.createCellStyle().apply { setFont(book.createFont().apply { setBold(true) }) }
            val heading = book.createCellStyle().apply {
                setFont(book.createFont().apply { setBold(true) }); fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
                fillPattern = FillPatternType.SOLID_FOREGROUND
            }
            val sheet = book.createSheet("Receivables")
            var r = 0
            sheet.createRow(r++).createCell(0).apply { setCellValue("Receivables as at ${data.asOf}"); cellStyle = bold }
            r++
            fun row(values: List<Any?>, style: CellStyle? = null) {
                val x = sheet.createRow(r++)
                values.forEachIndexed { i, v ->
                    x.createCell(i).apply {
                        when (v) { is BigDecimal -> setCellValue(v.toDouble()); is Number -> setCellValue(v.toDouble()); else -> setCellValue(v?.toString() ?: "") }
                        if (style != null) cellStyle = style
                    }
                }
            }
            row(listOf("Invoice", "Client", "Job", "Currency", "Invoice date", "Due date", "Gross", "Credited", "Received", "Outstanding", "Days overdue", "Bucket"), heading)
            data.rows.forEach { row(listOf(it.number, it.clientName, it.jobNumber, it.currency, it.invoiceDate, it.dueDate, it.grossAmount, it.creditedAmount,
                it.receivedAmount, it.outstandingAmount, it.overdueDays, BUCKET_LABELS[it.bucket])) }
            r++
            row(listOf("Client", "Currency") + BUCKETS.map { BUCKET_LABELS[it] } + "Total", heading)
            data.totals.forEach { t -> row(listOf(t.clientName, t.currency) + BUCKETS.map { t.buckets[it] } + t.total) }
            (0..11).forEach { sheet.setColumnWidth(it, if (it < 3) 26 * 256 else 14 * 256) }
        }
        audit.record(AuditEntry(AuditAction.EXPORT, "RECEIVABLES", null, null,
            mapOf("format" to "xlsx", "asOf" to data.asOf.toString(), "clientId" to clientId, "currency" to currency, "rows" to data.rows.size)))
        return "receivables-${data.asOf}.xlsx" to bytes
    }

    // ---------- helpers ----------

    private data class Allocation(val id: UUID, val invoiceId: UUID, val invoiceNumber: String?, val amount: BigDecimal, val reversed: Boolean)

    private data class Row(
        val id: UUID, val clientId: UUID, val receivedOn: LocalDate, val amount: BigDecimal, val currency: String, val method: String,
        val reference: String?, val status: String, val reversalReason: String?, val recordedBy: UUID, val recordedAt: java.time.Instant,
        val version: Long, val allocations: List<Allocation>,
    ) {
        val allocated: BigDecimal get() = allocations.filter { !it.reversed }.fold(BigDecimal.ZERO) { s, a -> s + a.amount }
    }

    private fun load(id: UUID): Row = loadAll(listOf(id)).singleOrNull() ?: throw NotFoundException("Receipt")

    /** The receipts in the given order, with their allocations, amounts scaled to the currency's minor units. */
    private fun loadAll(ids: List<UUID>): List<Row> {
        if (ids.isEmpty()) return emptyList()
        val keys = ids.map { it.bytes() }
        val allocations = jdbc.sql("""SELECT a.receipt_id,a.id,a.invoice_id,i.number,a.amount,a.reversed_at FROM receipt_allocation a
            JOIN invoice i ON i.id=a.invoice_id WHERE a.receipt_id IN (:ids) ORDER BY a.allocated_at, a.id""").param("ids", keys).query { rs, _ ->
            rs.getBytes(1).uuid()!! to Allocation(rs.getBytes(2).uuid()!!, rs.getBytes(3).uuid()!!, rs.getString(4), rs.getBigDecimal(5), rs.getObject(6) != null)
        }.list().groupBy({ it.first }, { it.second })
        val rows = jdbc.sql("""SELECT id,client_id,received_on,amount,currency,method,reference,status,reversal_reason,recorded_by,recorded_at,version
            FROM receipt WHERE id IN (:ids)""").param("ids", keys).query { rs, _ ->
            val id = rs.getBytes(1).uuid()!!
            Row(id, rs.getBytes(2).uuid()!!, rs.getObject(3, LocalDate::class.java), rs.getBigDecimal(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getString(9), rs.getBytes(10).uuid()!!, rs.getObject(11, LocalDateTime::class.java).instant()!!,
                rs.getLong(12), allocations[id].orEmpty())
        }.list().associateBy { it.id }
        val minor = rows.values.map { it.currency }.distinct().associateWith(access::minorUnits)
        return ids.mapNotNull(rows::get).map { r ->
            val scale = minor.getValue(r.currency)
            r.copy(amount = r.amount.setScale(scale, RoundingMode.HALF_UP),
                allocations = r.allocations.map { it.copy(amount = it.amount.setScale(scale, RoundingMode.HALF_UP)) })
        }
    }

    private fun locked(id: UUID, expectedVersion: Long): Row {
        val row = load(id)
        access.requireGlobal(Permissions.FIN_RECEIPT_WRITE, ENTITY, id)
        jdbc.sql("SELECT id FROM receipt WHERE id=:id FOR UPDATE").param("id", id.bytes()).query { rs, _ -> rs.getBytes(1) }.single()
        return load(id).also {
            Versioning.checkCurrent(expectedVersion, it.version)
            if (it.status != "ACTIVE") throw BusinessRuleException("INVALID_TRANSITION", "A reversed receipt cannot be changed.")
        }
    }

    /** Validates and stores allocations: issued invoices of the same client and currency, each within what is open. */
    private fun allocate(receipt: Row, inputs: List<AllocationInput>) {
        if (inputs.isEmpty()) return
        val minor = access.minorUnits(receipt.currency)
        val errors = mutableListOf<FieldError>()
        if (inputs.size > 50) errors += FieldError("allocations", "size", "At most 50 allocations.")
        inputs.forEachIndexed { i, a ->
            if (a.invoiceId == null) errors += FieldError("allocations[$i].invoiceId", "required", null)
            if (a.amount == null || a.amount.signum() <= 0 || a.amount.stripTrailingZeros().scale() > minor) {
                errors += FieldError("allocations[$i].amount", "range", "More than 0, at most $minor decimals.")
            }
        }
        if (inputs.mapNotNull { it.invoiceId }.let { it.size != it.distinct().size }) errors += FieldError("allocations", "duplicate", "Each invoice once.")
        if (errors.isNotEmpty()) throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", errors)

        // Lock the invoices in a fixed order so concurrent receipts cannot over-allocate or deadlock.
        inputs.map { it.invoiceId!! }.sorted().forEach { id ->
            jdbc.sql("SELECT id FROM invoice WHERE id=:id FOR UPDATE").param("id", id.bytes()).query { rs, _ -> rs.getBytes(1) }.list()
        }
        val total = inputs.fold(receipt.allocated) { s, a -> s + a.amount!! }
        if (total > receipt.amount) throw BusinessRuleException("ALLOCATION_EXCEEDS_RECEIPT", "The allocations are more than the receipt amount.")
        inputs.forEachIndexed { i, a ->
            val invoice = runCatching { invoices.load(a.invoiceId!!) }.getOrNull()
            if (invoice == null) {
                throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", listOf(FieldError("allocations[$i].invoiceId", "unknown", null)))
            }
            if (invoice.docType != "INVOICE" || invoice.status != InvoiceStatus.ISSUED || invoice.clientId != receipt.clientId) {
                throw BusinessRuleException("ALLOCATION_NOT_ALLOWED", "Receipts can only be allocated to issued invoices of the same client.")
            }
            if (invoice.currency != receipt.currency) {
                throw BusinessRuleException("CURRENCY_MISMATCH", "The receipt and the invoice must be in the same currency.")
            }
            if (a.amount!! > invoices.outstanding(invoice)) {
                throw BusinessRuleException("ALLOCATION_EXCEEDS_OUTSTANDING", "The allocation is more than what is open on invoice ${invoice.number}.")
            }
            jdbc.sql("""INSERT INTO receipt_allocation (id,receipt_id,invoice_id,amount,allocated_by,allocated_at) VALUES (:id,:r,:i,:amount,:a,:t)""")
                .param("id", UuidV7.generate().bytes()).param("r", receipt.id.bytes()).param("i", invoice.id.bytes()).param("amount", a.amount.setScale(minor))
                .param("a", access.actor().bytes()).param("t", now()).update()
            audit.record(AuditEntry(AuditAction.UPDATE, ENTITY, receipt.id, null,
                mapOf("allocatedTo" to invoice.number, "amount" to a.amount)))
        }
    }

    private fun toDto(r: Row): ReceiptDto = toDtos(listOf(r)).single()

    /** Client and recorder names in one lookup each; [loadAll] already scaled every amount to the currency's minor units. */
    private fun toDtos(rows: List<Row>): List<ReceiptDto> {
        val clients = access.clientNames(rows.map { it.clientId })
        val recorders = users.names(rows.map { it.recordedBy }.distinct())
        val canWrite = access.can(Permissions.FIN_RECEIPT_WRITE, null)
        return rows.map { r ->
            val allocated = r.allocated
            ReceiptDto(r.id, r.clientId, clients[r.clientId], r.receivedOn, r.amount, r.currency, r.method, r.reference, r.status,
                allocated, if (r.status == "ACTIVE") r.amount - allocated else BigDecimal.ZERO.setScale(r.amount.scale()),
                r.allocations.map { AllocationDto(it.id, it.invoiceId, it.invoiceNumber, it.amount, it.reversed) },
                recorders[r.recordedBy], r.recordedAt, r.reversalReason, r.status == "ACTIVE" && canWrite, r.amount.scale(), r.version)
        }
    }

    private fun now() = clock.instant().truncatedTo(ChronoUnit.MICROS).stamp()

    companion object {
        const val ENTITY = "RECEIPT"
        val METHODS = setOf("BANK_TRANSFER", "OTHER")
        val BUCKETS = listOf("NOT_DUE", "D1_30", "D31_60", "D61_90", "D90_PLUS")
        val BUCKET_LABELS = mapOf("NOT_DUE" to "Not due", "D1_30" to "1–30 days", "D31_60" to "31–60 days", "D61_90" to "61–90 days", "D90_PLUS" to "Over 90 days")

        fun bucket(daysOverdue: Int) = when {
            daysOverdue <= 0 -> "NOT_DUE"
            daysOverdue <= 30 -> "D1_30"
            daysOverdue <= 60 -> "D31_60"
            daysOverdue <= 90 -> "D61_90"
            else -> "D90_PLUS"
        }
    }
}
