package com.ship2ship.features.finance

import com.ship2ship.core.config.CompanyProperties
import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.jobs.JobLookup
import com.ship2ship.core.jobs.FinalInvoiceDrafts
import com.ship2ship.core.jobs.JobTechnicalRef
import com.ship2ship.core.persistence.NumberContext
import com.ship2ship.core.persistence.NumberSeriesService
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.AccessScope
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
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Propagation
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

/** Scope checks and shared reads for finance: job and document access, currency minor units, list scope clauses. */
@Component
class FinanceAccess(
    private val jdbc: JdbcClient,
    private val jobs: JobLookup,
    private val policy: AccessPolicy,
    private val references: ReferenceLookup,
) {
    fun job(id: UUID, permission: String): JobTechnicalRef {
        val job = jobs.technical(id) ?: throw NotFoundException("Job")
        policy.requireBranch(Permissions.JOB_READ, job.branchId, "JOB", id)
        policy.requireBranch(permission, job.branchId, "JOB", id)
        if (!permission.endsWith(".READ")) jobs.requireWritable(id)
        return job
    }

    fun requireBranch(permission: String, branchId: UUID, type: String, id: UUID) =
        policy.requireBranch(permission, branchId, type, id)

    fun requireGlobal(permission: String, type: String, id: UUID?) = policy.requireGlobal(permission, type, id)

    /** A branch record, or a company-wide record when [branchId] is null. */
    fun can(permission: String, branchId: UUID?) = policy.currentUser().scopeFor(permission).let {
        if (branchId == null) it.global else it.coversBranch(branchId)
    }

    fun scope(permission: String): AccessScope = policy.scope(permission)

    fun actor(): UUID = policy.currentUser().id

    fun minorUnits(currency: String): Int = references.currency(currency)?.minorUnits ?: 2

    /** SQL condition limiting rows to the scope: by `branch` column, or company-wide records when it is null. */
    fun clause(scope: AccessScope, branch: String?, params: MutableMap<String, Any>): String? =
        if (branch == null) scope.companyCondition() else scope.sqlCondition(branch, params)

    fun clientName(id: UUID): String? = references.clients(listOf(id))[id]?.legalName

    fun clientNames(ids: Collection<UUID>): Map<UUID, String> = references.clients(ids.distinct()).mapValues { it.value.legalName }
}

/**
 * Client invoices and credit notes (FR-J11, D06). A draft is pre-filled from the job's accepted quotation; supplementary
 * lines (including extra hours, manual until 5c) are entered; approval is by someone
 * other than the submitter; the number is assigned at issue and the issued PDF never changes. Corrections are credit
 * notes, which can never credit more than is still open on the invoice.
 */
@Service
class InvoiceService(
    private val jdbc: JdbcClient,
    private val access: FinanceAccess,
    private val organisation: OrganisationLookup,
    private val references: ReferenceLookup,
    private val numbers: NumberSeriesService,
    private val users: UserDirectory,
    private val output: InvoiceOutput,
    private val billSummary: BillSummaryBuilder,
    private val audit: AuditService,
    private val clock: Clock,
    private val company: CompanyProperties,
) : FinalInvoiceDrafts {
    // ---------- reads ----------

    @Transactional(readOnly = true)
    fun forJob(jobId: UUID): JobInvoicesDto {
        val job = access.job(jobId, Permissions.FIN_INVOICE_READ)
        val facts = jobFacts(job)
        return JobInvoicesDto(jobId, job.jobNumber, facts.currency, summaries("WHERE i.job_id=:job", mapOf("job" to jobId.bytes()), "ORDER BY i.created_at DESC"),
            access.can(Permissions.FIN_INVOICE_WRITE, job.branchId), facts.quotationVersionId != null)
    }

    @Transactional(readOnly = true)
    fun list(status: String?, docType: String?, clientId: UUID?, jobId: UUID?, overdue: Boolean, q: String?, page: Int, size: Int): PageResponse<InvoiceSummaryDto> {
        val params = mutableMapOf<String, Any>()
        val where = listOfNotNull(
            access.clause(access.scope(Permissions.FIN_INVOICE_READ), "i.branch_id", params),
            status?.let { params["status"] = it; "i.status=:status" },
            docType?.let { params["type"] = it; "i.doc_type=:type" },
            clientId?.let { params["client"] = it.bytes(); "i.client_id=:client" },
            jobId?.let { params["job"] = it.bytes(); "i.job_id=:job" },
            if (overdue) { params["today"] = today(); "i.doc_type='INVOICE' AND i.status='ISSUED' AND i.due_date < :today AND i.gross_amount > " +
                "(SELECT COALESCE(SUM(c.gross_amount),0) FROM invoice c WHERE c.credited_invoice_id=i.id AND c.status='ISSUED') + " +
                "(SELECT COALESCE(SUM(a.amount),0) FROM receipt_allocation a WHERE a.invoice_id=i.id AND a.reversed_at IS NULL)" } else null,
            q?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let {
                params["q"] = "%$it%"
                "(LOWER(i.number) LIKE :q OR LOWER(i.billing_name) LIKE :q OR " +
                    "EXISTS (SELECT 1 FROM sts_job j WHERE j.id=i.job_id AND LOWER(j.job_number) LIKE :q))"
            },
        )
        val clause = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        val total = jdbc.sql("SELECT COUNT(*) FROM invoice i $clause").params(params).query(Long::class.java).single()
        val rows = summaries(clause, params + mapOf("limit" to size, "offset" to page * size),
            "ORDER BY i.invoice_date DESC, i.created_at DESC LIMIT :limit OFFSET :offset")
        return PageResponse(rows, page, size, total)
    }

    /**
     * Summaries in one read: the job number, credited and received amounts come with each row, client names and minor
     * units in one lookup each. The amounts are rounded exactly as [outstanding] rounds them for a single invoice.
     */
    internal fun summaries(clause: String, params: Map<String, Any>, tail: String): List<InvoiceSummaryDto> {
        data class Raw(val id: UUID, val docType: String, val number: String?, val status: InvoiceStatus, val jobId: UUID, val jobNumber: String,
            val clientId: UUID, val currency: String, val invoiceDate: LocalDate, val dueDate: LocalDate?, val gross: BigDecimal,
            val credited: BigDecimal, val received: BigDecimal)
        val rows = jdbc.sql("""SELECT i.id,i.doc_type,i.number,i.status,i.job_id,j.job_number,i.client_id,i.currency,i.invoice_date,i.due_date,i.gross_amount,
              (SELECT COALESCE(SUM(c.gross_amount),0) FROM invoice c WHERE c.credited_invoice_id=i.id AND c.status='ISSUED'),
              (SELECT COALESCE(SUM(a.amount),0) FROM receipt_allocation a WHERE a.invoice_id=i.id AND a.reversed_at IS NULL)
            FROM invoice i JOIN sts_job j ON j.id=i.job_id $clause $tail""").params(params).query { rs, _ ->
            Raw(rs.getBytes(1).uuid()!!, rs.getString(2), rs.getString(3), InvoiceStatus.valueOf(rs.getString(4)), rs.getBytes(5).uuid()!!,
                rs.getString(6), rs.getBytes(7).uuid()!!, rs.getString(8), rs.getObject(9, LocalDate::class.java), rs.getObject(10, LocalDate::class.java),
                rs.getBigDecimal(11), rs.getBigDecimal(12), rs.getBigDecimal(13))
        }.list()
        val clients = access.clientNames(rows.map { it.clientId })
        val minor = rows.map { it.currency }.distinct().associateWith(access::minorUnits)
        val today = today()
        return rows.map { r ->
            val scale = minor.getValue(r.currency)
            val gross = r.gross.setScale(scale, RoundingMode.HALF_UP)
            val open = r.docType == "INVOICE" && r.status == InvoiceStatus.ISSUED
            InvoiceSummaryDto(r.id, r.docType, r.number, r.status, r.jobId, r.jobNumber, clients[r.clientId], r.currency, r.invoiceDate, r.dueDate, gross,
                if (open) (gross - r.credited - r.received).setScale(scale, RoundingMode.HALF_UP) else BigDecimal.ZERO.setScale(scale),
                if (open && r.dueDate != null && r.dueDate.isBefore(today)) ChronoUnit.DAYS.between(r.dueDate, today).toInt() else null, scale)
        }
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): InvoiceDto {
        val row = load(id)
        access.requireBranch(Permissions.FIN_INVOICE_READ, row.branchId, ENTITY, id)
        return toDto(row)
    }

    @Transactional(readOnly = true)
    fun options(): List<CostHeadOption> {
        access.scope(Permissions.FIN_INVOICE_READ)
        return jdbc.sql("SELECT code,name,kind FROM cost_head WHERE active=TRUE AND kind IN ('REVENUE','BOTH') ORDER BY code")
            .query { rs, _ -> CostHeadOption(rs.getString(1), rs.getString(2), rs.getString(3)) }.list()
    }

    @Transactional(readOnly = true)
    fun pdf(id: UUID): Pair<String, ByteArray> {
        val row = load(id)
        access.requireBranch(Permissions.FIN_INVOICE_READ, row.branchId, ENTITY, id)
        val stored = if (row.status == InvoiceStatus.ISSUED) jdbc.sql("SELECT pdf FROM invoice WHERE id=:id").param("id", id.bytes())
            .query { rs, _ -> rs.getBytes(1) }.optional().orElse(null) else null
        val name = (row.number ?: "${row.docType.lowercase()}-draft").replace('/', '-') + ".pdf"
        return name to (stored ?: output.pdf(document(row, draft = true)))
    }

    // ---------- preparing ----------

    /** A new draft for the job: lines from the accepted quotation (or none), billing details from the client. */
    @Transactional
    fun create(jobId: UUID, request: NewInvoiceRequest): InvoiceDto {
        val job = access.job(jobId, Permissions.FIN_INVOICE_WRITE)
        val facts = jobFacts(job)
        val client = references.clients(listOf(facts.clientId))[facts.clientId]
        val id = UuidV7.generate()
        val minor = access.minorUnits(facts.currency)
        val lines = if (request.fromQuotation && facts.quotationVersionId != null) {
            jdbc.sql("SELECT cost_head_code,cost_head_name,description,quantity,unit_price,extra_hours FROM quotation_line WHERE quotation_version_id=:v AND quantity > 0 ORDER BY line_no")
                .param("v", facts.quotationVersionId.bytes()).query { rs, _ ->
                    Line("QUOTATION", rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4).plain(), rs.getBigDecimal(5).plain(), null, null,
                        extraHours = rs.getBoolean(6))
                }.list().map { it.priced(minor) }
        } else emptyList()
        insert(id, job, facts.clientId, "INVOICE", null, facts.currency, today(), null, client?.legalName ?: "", client?.billingAddress,
            facts.paymentTerms, null, lines)
        audit.record(AuditEntry(AuditAction.CREATE, ENTITY, id, job.branchId,
            mapOf("jobId" to jobId, "docType" to "INVOICE", "fromQuotation" to (request.fromQuotation && facts.quotationVersionId != null), "lines" to lines.size)))
        return toDto(load(id))
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun refreshFromQuotation(jobId: UUID) {
        val facts = jdbc.sql("SELECT client_id,currency,quotation_version_id FROM sts_job WHERE id=:id")
            .param("id", jobId.bytes()).query { rs, _ -> Triple(rs.getBytes(1).uuid()!!, rs.getString(2), rs.getBytes(3).uuid()!!) }.single()
        val client = references.clients(listOf(facts.first))[facts.first]
        val terms = jdbc.sql("SELECT payment_terms FROM quotation_version WHERE id=:id")
            .param("id", facts.third.bytes()).query(String::class.java).optional().orElse(null)
        val quoted = jdbc.sql("SELECT cost_head_code,cost_head_name,description,quantity,unit_price,extra_hours FROM quotation_line WHERE quotation_version_id=:id AND quantity > 0 ORDER BY line_no")
            .param("id", facts.third.bytes()).query { rs, _ ->
                Line("QUOTATION", rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4).plain(),
                    rs.getBigDecimal(5).plain(), null, null, extraHours = rs.getBoolean(6))
            }.list()
        val ids = jdbc.sql("""SELECT id FROM invoice WHERE job_id=:job AND doc_type='INVOICE' AND status<>'ISSUED'
            AND id NOT IN (SELECT invoice_id FROM job_extra_invoice WHERE job_id=:job) ORDER BY id""")
            .param("job", jobId.bytes()).query { rs, _ -> rs.getBytes(1).uuid()!! }.list()
        ids.forEach { id ->
            val row = load(id)
            val oldQuoted = row.lines.filter { it.source == "QUOTATION" }
            val refreshed = quoted.mapIndexed { i, line ->
                val old = oldQuoted.getOrNull(i)?.takeIf { it.costHeadCode == line.costHeadCode }
                line.copy(taxCode = old?.taxCode, taxRate = old?.taxRate).priced(access.minorUnits(facts.second))
            } + row.lines.filter { it.source == "MANUAL" }
            val totals = totals(refreshed)
            jdbc.sql("DELETE FROM invoice_line WHERE invoice_id=:id").param("id", id.bytes()).update()
            insertLines(id, refreshed)
            jdbc.sql("""UPDATE invoice SET client_id=:client,billing_name=:name,billing_address=:address,payment_terms=:terms,
                status='DRAFT',submitted_by=NULL,submitted_at=NULL,decided_by=NULL,decided_at=NULL,returned_reason=NULL,
                net_amount=:net,tax_amount=:tax,gross_amount=:gross,updated_at=:at,updated_by=:actor,version=version+1 WHERE id=:id""")
                .param("client", facts.first.bytes()).param("name", (client?.legalName ?: row.billingName).take(200))
                .param("address", client?.billingAddress?.take(1000)).param("terms", terms?.take(500))
                .param("net", totals.first).param("tax", totals.second).param("gross", totals.first + totals.second)
                .param("at", now()).param("actor", access.actor().bytes()).param("id", id.bytes()).update()
            audit.record(AuditEntry(AuditAction.UPDATE, ENTITY, id, row.branchId,
                mapOf("reason" to "Accepted quotation changed", "oldNet" to row.net, "newNet" to totals.first)))
        }
    }

    /** A credit note draft against an issued invoice, starting from a copy of its lines. */
    @Transactional
    fun createCreditNote(invoiceId: UUID): InvoiceDto {
        val invoice = load(invoiceId)
        access.requireBranch(Permissions.FIN_INVOICE_WRITE, invoice.branchId, ENTITY, invoiceId)
        if (invoice.docType != "INVOICE" || invoice.status != InvoiceStatus.ISSUED) {
            throw BusinessRuleException("INVALID_TRANSITION", "Only an issued invoice can be credited.")
        }
        if (outstanding(invoice).signum() <= 0) throw BusinessRuleException("CREDIT_EXCEEDS_OUTSTANDING", "Nothing is left open on this invoice to credit.")
        val job = access.job(invoice.jobId, Permissions.FIN_INVOICE_WRITE)
        val id = UuidV7.generate()
        insert(id, job, invoice.clientId, "CREDIT_NOTE", invoiceId, invoice.currency, today(), null, invoice.billingName, invoice.billingAddress,
            invoice.paymentTerms, null, invoice.lines.map { it.copy(source = "MANUAL") })
        audit.record(AuditEntry(AuditAction.CREATE, ENTITY, id, invoice.branchId,
            mapOf("docType" to "CREDIT_NOTE", "creditedInvoice" to invoice.number)))
        return toDto(load(id))
    }

    @Transactional
    fun update(id: UUID, expectedVersion: Long, input: InvoiceInput): InvoiceDto {
        val row = locked(id, expectedVersion, Permissions.FIN_INVOICE_WRITE)
        requireStatus(row, InvoiceStatus.DRAFT)
        val minor = access.minorUnits(row.currency)
        val (header, lines) = validate(row, input, minor)
        jdbc.sql("DELETE FROM invoice_line WHERE invoice_id=:id").param("id", id.bytes()).update()
        insertLines(id, lines)
        val totals = totals(lines)
        jdbc.sql("""UPDATE invoice SET invoice_date=:date,due_date=:due,billing_name=:name,billing_address=:address,payment_terms=:terms,notes=:notes,
            net_amount=:net,tax_amount=:tax,gross_amount=:gross,updated_at=:t,updated_by=:a,version=version+1 WHERE id=:id""")
            .param("date", header.invoiceDate).param("due", header.dueDate).param("name", header.billingName).param("address", header.billingAddress)
            .param("terms", header.paymentTerms).param("notes", header.notes).param("net", totals.first).param("tax", totals.second)
            .param("gross", totals.first + totals.second).param("t", now()).param("a", access.actor().bytes()).param("id", id.bytes()).update()
        audit.record(AuditEntry(AuditAction.UPDATE, ENTITY, id, row.branchId,
            mapOf("lines" to lines.size, "net" to totals.first, "tax" to totals.second)))
        return toDto(load(id))
    }

    @Transactional
    fun submit(id: UUID, expectedVersion: Long): InvoiceDto {
        val row = locked(id, expectedVersion, Permissions.FIN_INVOICE_WRITE)
        requireStatus(row, InvoiceStatus.DRAFT)
        val blockers = blockers(row)
        if (blockers.isNotEmpty()) {
            throw BadRequestException("INVOICE_INCOMPLETE", "Complete the invoice before submitting it.", blockers.map { FieldError(it.code, "required", it.message) })
        }
        jdbc.sql("""UPDATE invoice SET status='SUBMITTED',submitted_by=:a,submitted_at=:t,returned_reason=NULL,updated_at=:t,updated_by=:a,
            version=version+1 WHERE id=:id""").param("a", access.actor().bytes()).param("t", now()).param("id", id.bytes()).update()
        status(row, InvoiceStatus.DRAFT, InvoiceStatus.SUBMITTED, null)
        return toDto(load(id))
    }

    @Transactional
    fun decide(id: UUID, expectedVersion: Long, request: InvoiceDecision): InvoiceDto {
        val row = locked(id, expectedVersion, Permissions.FIN_INVOICE_APPROVE)
        when (request.decision) {
            "APPROVE" -> {
                requireStatus(row, InvoiceStatus.SUBMITTED)
                if (row.submittedBy == access.actor()) {
                    throw BusinessRuleException("SELF_APPROVAL_FORBIDDEN", "An invoice cannot be approved by the person who submitted it.")
                }
                jdbc.sql("UPDATE invoice SET status='APPROVED',decided_by=:a,decided_at=:t,updated_at=:t,version=version+1 WHERE id=:id")
                    .param("a", access.actor().bytes()).param("t", now()).param("id", id.bytes()).update()
                status(row, InvoiceStatus.SUBMITTED, InvoiceStatus.APPROVED, null)
            }
            "RETURN" -> {
                if (row.status !in setOf(InvoiceStatus.SUBMITTED, InvoiceStatus.APPROVED)) {
                    throw BusinessRuleException("INVALID_TRANSITION", "Only a submitted or approved invoice can be returned.")
                }
                val reason = request.reason?.trim().orEmpty()
                if (reason.isEmpty()) throw BadRequestException("REASON_REQUIRED", "A reason is required for this change.", listOf(FieldError("reason", "required", null)))
                jdbc.sql("UPDATE invoice SET status='DRAFT',returned_reason=:r,decided_by=NULL,decided_at=NULL,updated_at=:t,version=version+1 WHERE id=:id")
                    .param("r", reason.take(500)).param("t", now()).param("id", id.bytes()).update()
                status(row, row.status, InvoiceStatus.DRAFT, reason)
            }
            else -> throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", listOf(FieldError("decision", "allowed", "APPROVE or RETURN.")))
        }
        return toDto(load(id))
    }

    /** Assigns the number from the series, renders and stores the PDF; the document never changes afterwards. */
    @Transactional
    fun issue(id: UUID, expectedVersion: Long): InvoiceDto {
        val jobId = jdbc.sql("SELECT job_id FROM invoice WHERE id=:id").param("id", id.bytes())
            .query { rs, _ -> rs.getBytes(1).uuid()!! }.single()
        jdbc.sql("SELECT id FROM sts_job WHERE id=:id FOR UPDATE").param("id", jobId.bytes())
            .query { rs, _ -> rs.getBytes(1) }.single()
        val row = locked(id, expectedVersion, Permissions.FIN_INVOICE_WRITE)
        requireStatus(row, InvoiceStatus.APPROVED)
        if (row.docType == "CREDIT_NOTE") {
            val credited = load(row.creditedInvoiceId!!)
            jdbc.sql("SELECT id FROM invoice WHERE id=:id FOR UPDATE").param("id", credited.id.bytes()).query { rs, _ -> rs.getBytes(1) }.single()
            if (row.gross > outstanding(load(credited.id))) {
                throw BusinessRuleException("CREDIT_EXCEEDS_OUTSTANDING", "The credit note is larger than what is still open on the invoice.")
            }
        }
        val branch = organisation.branch(row.branchId)
        val number = numbers.next(if (row.docType == "CREDIT_NOTE") "CREDIT_NOTE" else "INVOICE",
            NumberContext(branch?.code, row.invoiceDate.year))
        jdbc.sql("UPDATE invoice SET status='ISSUED',number=:n,issued_by=:a,issued_at=:t,updated_at=:t,version=version+1 WHERE id=:id")
            .param("n", number).param("a", access.actor().bytes()).param("t", now()).param("id", id.bytes()).update()
        val issued = load(id)
        val pdf = output.pdf(document(issued, draft = false))
        val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf))
        jdbc.sql("UPDATE invoice SET pdf=:pdf,pdf_sha256=:sha WHERE id=:id").param("pdf", pdf).param("sha", sha).param("id", id.bytes()).update()
        status(row, InvoiceStatus.APPROVED, InvoiceStatus.ISSUED, null, mapOf("number" to number, "pdfSha256" to sha))
        return toDto(load(id))
    }

    // ---------- shared with receipts ----------

    internal data class Line(
        val source: String, val costHeadCode: String, val costHeadName: String, val description: String?, val quantity: BigDecimal,
        val unitPrice: BigDecimal, val taxCode: String?, val taxRate: BigDecimal?,
        val amount: BigDecimal = BigDecimal.ZERO, val taxAmount: BigDecimal = BigDecimal.ZERO, val extraHours: Boolean = false,
    ) {
        fun priced(minor: Int): Line {
            val amount = (quantity * unitPrice).setScale(minor, RoundingMode.HALF_UP)
            val tax = taxRate?.let { (amount * it).divide(BigDecimal(100), minor, RoundingMode.HALF_UP) } ?: BigDecimal.ZERO.setScale(minor)
            return copy(amount = amount, taxAmount = tax)
        }
    }

    internal data class Row(
        val id: UUID, val branchId: UUID, val jobId: UUID, val clientId: UUID, val docType: String, val creditedInvoiceId: UUID?,
        val number: String?, val status: InvoiceStatus, val currency: String, val invoiceDate: LocalDate, val dueDate: LocalDate?,
        val billingName: String, val billingAddress: String?, val paymentTerms: String?, val notes: String?, val net: BigDecimal, val tax: BigDecimal,
        val gross: BigDecimal, val submittedBy: UUID?, val submittedAt: Instant?, val decidedBy: UUID?, val decidedAt: Instant?, val returnedReason: String?,
        val issuedBy: UUID?, val issuedAt: Instant?, val pdfSha256: String?, val version: Long, val lines: List<Line>,
    )

    internal fun load(id: UUID): Row {
        val lines = jdbc.sql("""SELECT source,cost_head_code,cost_head_name,description,quantity,unit_price,tax_code,tax_rate,amount,tax_amount,extra_hours
            FROM invoice_line WHERE invoice_id=:id ORDER BY line_no""").param("id", id.bytes()).query { rs, _ ->
            Line(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5).plain(), rs.getBigDecimal(6).plain(),
                rs.getString(7), rs.getBigDecimal(8)?.plain(), rs.getBigDecimal(9), rs.getBigDecimal(10), rs.getBoolean(11))
        }.list()
        return jdbc.sql("""SELECT branch_id,job_id,client_id,doc_type,credited_invoice_id,number,status,currency,invoice_date,due_date,
            billing_name,billing_address,payment_terms,notes,net_amount,tax_amount,gross_amount,submitted_by,submitted_at,decided_by,decided_at,
            returned_reason,issued_by,issued_at,pdf_sha256,version FROM invoice WHERE id=:id""").param("id", id.bytes()).query { rs, _ ->
            fun t(i: Int) = rs.getObject(i, LocalDateTime::class.java).instant()
            val minor = access.minorUnits(rs.getString(8))
            fun m(i: Int) = rs.getBigDecimal(i).setScale(minor, RoundingMode.HALF_UP)
            Row(id, rs.getBytes(1).uuid()!!, rs.getBytes(2).uuid()!!, rs.getBytes(3).uuid()!!, rs.getString(4), rs.getBytes(5).uuid(),
                rs.getString(6), InvoiceStatus.valueOf(rs.getString(7)), rs.getString(8), rs.getObject(9, LocalDate::class.java),
                rs.getObject(10, LocalDate::class.java), rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14), m(15), m(16), m(17),
                rs.getBytes(18).uuid(), t(19), rs.getBytes(20).uuid(), t(21), rs.getString(22), rs.getBytes(23).uuid(), t(24), rs.getString(25), rs.getLong(26),
                lines.map { it.copy(amount = it.amount.setScale(minor, RoundingMode.HALF_UP), taxAmount = it.taxAmount.setScale(minor, RoundingMode.HALF_UP)) })
        }.optional().orElseThrow { NotFoundException("Invoice") }
    }

    /** Issued credit notes against an invoice. */
    internal fun credited(invoiceId: UUID): BigDecimal =
        jdbc.sql("SELECT COALESCE(SUM(gross_amount),0) FROM invoice WHERE credited_invoice_id=:id AND status='ISSUED'").param("id", invoiceId.bytes())
            .query(BigDecimal::class.java).single()

    /** Active receipt allocations on an invoice. */
    internal fun received(invoiceId: UUID): BigDecimal =
        jdbc.sql("SELECT COALESCE(SUM(amount),0) FROM receipt_allocation WHERE invoice_id=:id AND reversed_at IS NULL").param("id", invoiceId.bytes())
            .query(BigDecimal::class.java).single()

    internal fun outstanding(row: Row): BigDecimal = if (row.docType != "INVOICE" || row.status != InvoiceStatus.ISSUED) BigDecimal.ZERO.setScale(scale(row))
    else (row.gross - credited(row.id) - received(row.id)).setScale(scale(row), RoundingMode.HALF_UP)

    internal fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)

    // ---------- helpers ----------

    private data class JobFacts(val clientId: UUID, val currency: String, val quotationVersionId: UUID?, val paymentTerms: String?)

    private fun jobFacts(job: JobTechnicalRef): JobFacts {
        val (client, currency) = jdbc.sql("SELECT client_id,currency FROM sts_job WHERE id=:id").param("id", job.id.bytes())
            .query { rs, _ -> rs.getBytes(1).uuid()!! to rs.getString(2) }.single()
        val acceptance = jdbc.sql("""SELECT a.quotation_version_id, COALESCE(a.payment_terms, v.payment_terms) FROM owner_acceptance a
            JOIN quotation_version v ON v.id=a.quotation_version_id WHERE a.job_id=:job""").param("job", job.id.bytes())
            .query { rs, _ -> rs.getBytes(1).uuid() to rs.getString(2) }.list().firstOrNull()
        return JobFacts(client, currency, acceptance?.first, acceptance?.second)
    }

    private fun insert(
        id: UUID, job: JobTechnicalRef, clientId: UUID, type: String, credited: UUID?, currency: String, date: LocalDate, due: LocalDate?,
        name: String, address: String?, terms: String?, notes: String?, lines: List<Line>,
    ) {
        val totals = totals(lines)
        jdbc.sql("""INSERT INTO invoice (id,branch_id,job_id,client_id,doc_type,credited_invoice_id,status,currency,invoice_date,due_date,
            billing_name,billing_address,payment_terms,notes,net_amount,tax_amount,gross_amount,created_at,created_by,updated_at,updated_by,version)
            VALUES (:id,:branch,:job,:client,:type,:credited,'DRAFT',:currency,:date,:due,:name,:address,:terms,:notes,:net,:tax,:gross,:t,:a,:t,:a,0)""")
            .param("id", id.bytes()).param("branch", job.branchId.bytes()).param("job", job.id.bytes())
            .param("client", clientId.bytes()).param("type", type).param("credited", credited?.bytes()).param("currency", currency).param("date", date)
            .param("due", due).param("name", name.take(200)).param("address", address?.take(1000)).param("terms", terms?.take(500)).param("notes", notes)
            .param("net", totals.first).param("tax", totals.second).param("gross", totals.first + totals.second).param("t", now())
            .param("a", access.actor().bytes()).update()
        insertLines(id, lines)
    }

    private fun insertLines(id: UUID, lines: List<Line>) = lines.forEachIndexed { i, l ->
        jdbc.sql("""INSERT INTO invoice_line (id,invoice_id,line_no,source,cost_head_code,cost_head_name,description,quantity,unit_price,amount,
            tax_code,tax_rate,tax_amount,extra_hours) VALUES (:id,:inv,:no,:src,:code,:name,:desc,:qty,:price,:amount,:taxCode,:rate,:tax,:extra)""")
            .param("id", UuidV7.generate().bytes()).param("inv", id.bytes()).param("no", i + 1).param("src", l.source).param("code", l.costHeadCode)
            .param("name", l.costHeadName).param("desc", l.description).param("qty", l.quantity).param("price", l.unitPrice).param("amount", l.amount)
            .param("taxCode", l.taxCode).param("rate", l.taxRate).param("tax", l.taxAmount)
            .param("extra", l.extraHours).update()
    }

    private fun totals(lines: List<Line>): Pair<BigDecimal, BigDecimal> =
        lines.fold(BigDecimal.ZERO) { s, l -> s + l.amount } to lines.fold(BigDecimal.ZERO) { s, l -> s + l.taxAmount }

    private fun validate(row: Row, input: InvoiceInput, minor: Int): Pair<InvoiceInput, List<Line>> {
        val errors = mutableListOf<FieldError>()
        val date = input.invoiceDate
        if (date == null) errors += FieldError("invoiceDate", "required", null)
        if (input.dueDate != null && date != null && input.dueDate.isBefore(date)) errors += FieldError("dueDate", "order", "Cannot be before the invoice date.")
        if (input.billingName.isBlank() || input.billingName.length > 200) errors += FieldError("billingName", "required", "Enter the billing name (max 200).")
        if ((input.billingAddress?.length ?: 0) > 1000) errors += FieldError("billingAddress", "size", null)
        if ((input.paymentTerms?.length ?: 0) > 500) errors += FieldError("paymentTerms", "size", null)
        if ((input.notes?.length ?: 0) > 2000) errors += FieldError("notes", "size", null)
        if (input.lines.size > 100) errors += FieldError("lines", "size", "At most 100 lines.")
        val heads = jdbc.sql("SELECT code,name FROM cost_head WHERE active=TRUE").query { rs, _ -> rs.getString(1) to rs.getString(2) }.list().toMap()
        val existing = row.lines.associate { it.costHeadCode to it.costHeadName }
        val lines = input.lines.mapIndexedNotNull { i, l ->
            val name = heads[l.costHeadCode] ?: existing[l.costHeadCode]
            if (name == null) errors += FieldError("lines[$i].costHeadCode", "unknown", "Choose a cost head.")
            if (l.source !in setOf("QUOTATION", "MANUAL")) errors += FieldError("lines[$i].source", "allowed", null)
            if ((l.description?.length ?: 0) > 300) errors += FieldError("lines[$i].description", "size", null)
            val qty = l.quantity
            if (qty == null || qty.signum() <= 0 || qty.stripTrailingZeros().scale() > 3 || qty >= BigDecimal("1000000000")) {
                errors += FieldError("lines[$i].quantity", "range", "More than 0, at most 3 decimals.")
            }
            val price = l.unitPrice
            if (price == null || price.signum() < 0 || price.stripTrailingZeros().scale() > 4 || price >= BigDecimal("1000000000000")) {
                errors += FieldError("lines[$i].unitPrice", "range", "0 or more, at most 4 decimals.")
            }
            if (name == null || qty == null || price == null) null
            else Line(l.source, l.costHeadCode, name, l.description?.trim()?.ifEmpty { null }, qty.plain(), price.plain(), null, null,
                extraHours = l.extraHours && row.lines.any {
                    it.source == l.source && it.extraHours && it.costHeadCode == l.costHeadCode
                }).priced(minor)
        }
        if (errors.isNotEmpty()) throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", errors)
        fun String?.clean() = this?.trim()?.ifEmpty { null }
        return input.copy(billingName = input.billingName.trim(), billingAddress = input.billingAddress.clean(), paymentTerms = input.paymentTerms.clean(),
            notes = input.notes.clean()) to lines
    }

    private fun blockers(r: Row): List<FinanceIssue> = buildList {
        if (r.lines.isEmpty()) add(FinanceIssue("lines", "Add at least one line."))
        if (r.gross.signum() <= 0) add(FinanceIssue("gross", "The total must be more than zero."))
        if (r.billingName.isBlank()) add(FinanceIssue("billingName", "Enter the billing name."))
        if (r.docType == "INVOICE" && r.dueDate == null) add(FinanceIssue("dueDate", "Enter the due date."))
        if (r.docType == "CREDIT_NOTE" && r.creditedInvoiceId != null && r.gross > outstanding(load(r.creditedInvoiceId))) {
            add(FinanceIssue("credit", "The credit note is larger than what is still open on the invoice."))
        }
    }

    private fun toDto(r: Row): InvoiceDto {
        val names = users.names(listOfNotNull(r.submittedBy, r.decidedBy, r.issuedBy))
        val write = access.can(Permissions.FIN_INVOICE_WRITE, r.branchId)
        val approve = access.can(Permissions.FIN_INVOICE_APPROVE, r.branchId)
        val outstanding = outstanding(r)
        val actions = buildList {
            when (r.status) {
                InvoiceStatus.DRAFT -> if (write) addAll(listOf(InvoiceAction.EDIT, InvoiceAction.SUBMIT))
                InvoiceStatus.SUBMITTED -> if (approve) { if (r.submittedBy != access.actor()) add(InvoiceAction.APPROVE); add(InvoiceAction.RETURN) }
                InvoiceStatus.APPROVED -> { if (write) add(InvoiceAction.ISSUE); if (approve) add(InvoiceAction.RETURN) }
                InvoiceStatus.ISSUED -> if (write && r.docType == "INVOICE" && outstanding.signum() > 0) add(InvoiceAction.CREDIT)
            }
        }
        val isInvoice = r.docType == "INVOICE"
        return InvoiceDto(
            r.id, r.docType, r.number, r.status, r.jobId, jobNumber(r.jobId), r.clientId, access.clientName(r.clientId), r.creditedInvoiceId,
            r.creditedInvoiceId?.let { load(it).number }, r.currency, access.minorUnits(r.currency), r.invoiceDate, r.dueDate, r.billingName, r.billingAddress,
            r.paymentTerms, r.notes,
            r.lines.mapIndexed { i, l -> InvoiceLineDto(i + 1, l.source, l.costHeadCode, l.costHeadName, l.description, l.quantity, l.unitPrice, l.amount,
                l.taxCode, l.taxRate, l.taxAmount, l.extraHours) },
            r.net, r.tax, r.gross,
            if (isInvoice) credited(r.id).setScale(scale(r), RoundingMode.HALF_UP) else BigDecimal.ZERO.setScale(scale(r)),
            if (isInvoice) received(r.id).setScale(scale(r), RoundingMode.HALF_UP) else BigDecimal.ZERO.setScale(scale(r)),
            outstanding, if (r.status == InvoiceStatus.DRAFT) blockers(r) else emptyList(),
            r.submittedBy?.let(names::get), r.submittedAt, r.decidedBy?.let(names::get), r.decidedAt, r.returnedReason, r.issuedBy?.let(names::get),
            r.issuedAt, r.pdfSha256, actions, r.version,
        )
    }

    private fun document(r: Row, draft: Boolean): InvoiceDocument {
        val reference = jdbc.sql("SELECT work_order_reference FROM owner_acceptance WHERE job_id=:job").param("job", r.jobId.bytes())
            .query(String::class.java).list().firstOrNull()
        val charges = r.lines.map { listOfNotNull(it.costHeadName, it.description).joinToString(" - ") to it.amount } +
            if (r.tax.signum() != 0) listOf("Tax" to r.tax) else emptyList()
        val bill = billSummary.build(r.jobId, if (r.docType == "CREDIT_NOTE") "CREDIT NOTE" else "FINAL INVOICE",
            company.name, r.billingName, r.billingAddress, r.currency, r.invoiceDate, charges, r.gross)
        return InvoiceDocument.build(r, company.name, company.billingAddress?.ifBlank { null }, jobNumber(r.jobId), reference,
            r.creditedInvoiceId?.let { load(it).number }, access.minorUnits(r.currency), draft, bill)
    }

    private fun jobNumber(jobId: UUID): String =
        jdbc.sql("SELECT job_number FROM sts_job WHERE id=:id").param("id", jobId.bytes()).query(String::class.java).single()

    private fun locked(id: UUID, expectedVersion: Long, permission: String): Row {
        val row = load(id)
        access.requireBranch(permission, row.branchId, ENTITY, id)
        access.job(row.jobId, permission)
        jdbc.sql("SELECT id FROM invoice WHERE id=:id FOR UPDATE").param("id", id.bytes()).query { rs, _ -> rs.getBytes(1) }.single()
        return load(id).also { Versioning.checkCurrent(expectedVersion, it.version) }
    }

    private fun requireStatus(r: Row, expected: InvoiceStatus) {
        if (r.status != expected) throw BusinessRuleException("INVALID_TRANSITION", "A document in status ${r.status} cannot do this.")
    }

    private fun status(r: Row, from: InvoiceStatus, to: InvoiceStatus, reason: String?, extra: Map<String, Any?> = emptyMap()) =
        audit.record(AuditEntry(AuditAction.STATUS_CHANGE, ENTITY, r.id, r.branchId,
            mapOf("docType" to r.docType, "from" to from.name, "to" to to.name, "reason" to reason) + extra))

    private fun scale(r: Row) = access.minorUnits(r.currency)

    private fun now() = clock.instant().truncatedTo(ChronoUnit.MICROS).stamp()

    companion object {
        const val ENTITY = "INVOICE"
    }
}

internal fun BigDecimal.plain(): BigDecimal = stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
