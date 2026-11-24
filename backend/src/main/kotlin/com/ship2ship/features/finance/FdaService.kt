package com.ship2ship.features.finance

import com.ship2ship.core.config.CompanyProperties
import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.files.FileService
import com.ship2ship.core.jobs.JobFinancialClosure
import com.ship2ship.core.movements.ChargeClockLookup
import com.ship2ship.core.persistence.IdempotencyService
import com.ship2ship.core.persistence.NumberContext
import com.ship2ship.core.persistence.NumberSeriesService
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.core.security.UserDirectory
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.BusinessRuleException
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.Versioning
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import java.util.Locale
import java.util.UUID

data class FdaAdjustment(val kind: String, val description: String, val amount: BigDecimal, val fileId: UUID)
data class FdaInput(val reconciliationNote: String = "", val noExternalCostsReason: String = "", val notes: String = "",
    val adjustments: List<FdaAdjustment> = emptyList(), val extraHoursNote: String = "")
data class FdaRevision(val id: UUID, val revisionNo: Int, val number: String?, val status: String, val content: FdaInput,
    val snapshot: String?, val submittedBy: UUID?, val reason: String?, val pdfSha256: String?, val version: Long,
    val submittedAt: Instant? = null, val approvedBy: UUID? = null, val approvedAt: Instant? = null, val issuedBy: UUID? = null,
    val issuedAt: Instant? = null)
data class FdaBilling(val id: UUID, val number: String?, val type: String, val status: String, val net: BigDecimal, val tax: BigDecimal, val gross: BigDecimal, val currency: String, val version: Long)
data class FdaPayment(val id: UUID, val receiptId: UUID, val invoiceId: UUID, val amount: BigDecimal)
data class FdaSnapshot(val costs: JobCostSummary, val bills: List<CostDocument>, val billing: List<FdaBilling>, val payments: List<FdaPayment>,
    val clock: com.ship2ship.core.movements.ChargeClockResult, val adjustments: List<FdaAdjustment>, val income: BigDecimal, val expenses: BigDecimal,
    val received: BigDecimal, val outstanding: BigDecimal, val margin: BigDecimal)
/** The live figures for the FDA screen, plus the client's money on account (client level, so outside the frozen snapshot). */
data class FdaPreview(val snapshot: FdaSnapshot, val clientOnAccount: BigDecimal)

/** Everything printed on the FDA PDF, as display text. */
data class FdaDocument(
    val number: String, val documentId: String, val revisionNo: Int, val draft: Boolean, val facts: List<Pair<String, String>>,
    val totals: List<Pair<String, String>>, val billing: List<List<String>>, val bills: List<List<String>>, val noCostsReason: String,
    val heads: List<List<String>>, val clock: List<Pair<String, String>>, val extraHoursNote: String?, val adjustments: List<List<String>>,
    val payments: List<List<String>>, val onAccount: String, val reconciliation: String, val notes: String?, val approval: List<Pair<String, String>>,
    val bill: BillSummary,
)

/**
 * The Final Disbursement Account per job (SRS BR-48H-04, D06): vendor actuals, customer income, the extra-hours charge,
 * adjustments with evidence, receipts and reconciliation. Revisions are prepared, frozen at submission, approved by someone
 * else and issued as a fixed PDF; approval is refused if any source changed after submission.
 */
@Service
class FdaService(private val jdbc: JdbcClient, private val access: FinanceAccess, private val costs: JobCostsService,
    private val clocks: ChargeClockLookup, private val files: FileService, private val numbers: NumberSeriesService,
    private val json: JsonMapper, private val audit: AuditService, private val clock: Clock, private val idempotency: IdempotencyService,
    private val extraInvoices: ExtraHourInvoices, private val users: UserDirectory, private val output: InvoiceOutput,
    private val billSummary: BillSummaryBuilder,
    private val company: CompanyProperties) : JobFinancialClosure {
    private fun invalid(message: String): Nothing = throw BadRequestException("VALIDATION_FAILED", message)

    @Transactional(readOnly = true)
    fun list(jobId: UUID): List<FdaRevision> {
        access.job(jobId, "FIN.FDA.READ")
        return jdbc.sql("SELECT id FROM fda_revision WHERE job_id=:job ORDER BY revision_no DESC").param("job", jobId.bytes())
            .query { rs, _ -> rs.getBytes(1).uuid()!! }.list().map { load(jobId, it) }
    }

    @Transactional(readOnly = true)
    fun preview(jobId: UUID, input: FdaInput = FdaInput()): FdaPreview {
        access.job(jobId, "FIN.FDA.READ")
        return FdaPreview(snapshot(jobId, input), onAccount(jobId))
    }

    @Transactional
    fun create(jobId: UUID, key: String? = null): FdaRevision {
        val job = access.job(jobId, "FIN.FDA.WRITE"); lockJob(jobId)
        idempotency.existing(access.actor(), key, "FDA:$jobId")?.let { return load(jobId, it) }
        if (jdbc.sql("SELECT COUNT(*) FROM fda_revision WHERE job_id=:job AND status IN ('DRAFT','SUBMITTED','APPROVED')")
            .param("job", jobId.bytes()).query(Long::class.java).single() > 0) throw BusinessRuleException("FDA_REVISION_OPEN", "Finish the open FDA revision first.")
        val previous = list(jobId).firstOrNull(); val id = UuidV7.generate()
        jdbc.sql("INSERT INTO fda_revision(id,job_id,revision_no,content_json,created_at,created_by,updated_at) VALUES(:id,:job,:n,:content,:now,:actor,:now)")
            .param("id", id.bytes()).param("job", jobId.bytes()).param("n", (previous?.revisionNo ?: 0) + 1)
            .param("content", json.writeValueAsString(previous?.content ?: FdaInput())).param("now", now()).param("actor", access.actor().bytes()).update()
        audit.record(AuditEntry(AuditAction.CREATE, "FDA", id, job.branchId, mapOf("jobId" to jobId)))
        idempotency.remember(access.actor(), key, "FDA:$jobId", id)
        return load(jobId, id)
    }

    @Transactional
    fun save(jobId: UUID, id: UUID, expected: Long, input: FdaInput): FdaRevision {
        val job = access.job(jobId, "FIN.FDA.WRITE"); lockJob(jobId)
        val current = load(jobId, id, true); Versioning.checkCurrent(expected, current.version)
        if (current.status != "DRAFT") throw BusinessRuleException("INVALID_TRANSITION", "Only draft FDA revisions can be edited.")
        validate(input)
        jdbc.sql("UPDATE fda_revision SET content_json=:content,updated_at=:now,version=version+1 WHERE id=:id")
            .param("content", json.writeValueAsString(input)).param("now", now()).param("id", id.bytes()).update()
        audit.record(AuditEntry(AuditAction.UPDATE, "FDA", id, job.branchId, mapOf("before" to current.content, "after" to input)))
        return load(jobId, id)
    }

    @Transactional
    fun action(jobId: UUID, id: UUID, expected: Long, input: CostDecision): FdaRevision {
        val job = access.job(jobId, if (input.decision in setOf("APPROVE", "RETURN")) "FIN.FDA.APPROVE" else "FIN.FDA.WRITE")
        lockJob(jobId)
        val current = load(jobId, id, true); Versioning.checkCurrent(expected, current.version)
        val why = input.reason?.trim()?.takeIf { it.isNotEmpty() }
        if ((why?.length ?: 0) > 500) invalid("Use at most 500 characters for the reason.")
        var frozen = current.snapshot
        val target = when (input.decision) {
            "SUBMIT" -> {
                if (current.status != "DRAFT") invalid("Submit a draft FDA.")
                validate(current.content)
                if (current.content.reconciliationNote.isBlank()) invalid("Record how the FDA was reconciled to its source evidence.")
                val pending = jdbc.sql("SELECT (SELECT COUNT(*) FROM job_cost_document WHERE job_id=:job AND status IN ('DRAFT','SUBMITTED'))+(SELECT COUNT(*) FROM invoice WHERE job_id=:job AND status<>'ISSUED')")
                    .param("job", jobId.bytes()).query(Long::class.java).single()
                if (pending > 0) invalid("Finish all pending bills, purchase orders and invoices before submitting the FDA.")
                val data = snapshot(jobId, current.content)
                if (data.clock.final && data.clock.tariffTermsStatus == "CONFIRMED") {
                    if (extraInvoices.charged(jobId).compareTo(data.clock.additionalCharge ?: BigDecimal.ZERO) != 0)
                        invalid("Reconcile the finalized extra-hours charge through linked invoices and credit notes first.")
                } else if (current.content.extraHoursNote.isBlank()) {
                    // Provisional rule (Phase-5-Proposal §3.8, owner question 8): the FDA may go ahead, but must say why extra hours are not billed.
                    invalid("Explain why extra hours are not billed: the charge clock is not final or the tariff terms are still pending.")
                }
                if (data.bills.isEmpty() && current.content.noExternalCostsReason.isBlank()) invalid("Explain why this job has no third-party actual costs.")
                frozen = json.writeValueAsString(data); "SUBMITTED"
            }
            "APPROVE", "RETURN" -> {
                if (current.status != "SUBMITTED" && !(input.decision == "RETURN" && current.status == "APPROVED")) invalid("The FDA is not awaiting approval.")
                if (current.submittedBy == access.actor()) throw BusinessRuleException("SELF_APPROVAL_FORBIDDEN", "A different person must decide the FDA.")
                if (input.decision == "RETURN" && why == null) throw BadRequestException("REASON_REQUIRED", "A reason is required for this change.")
                if (input.decision == "APPROVE") { unchanged(jobId, current); "APPROVED" } else "DRAFT"
            }
            "ISSUE" -> { if (current.status != "APPROVED") invalid("Approve the FDA before issuing it."); unchanged(jobId, current); "ISSUED" }
            else -> invalid("Choose SUBMIT, APPROVE, RETURN or ISSUE.")
        }
        var number = current.number; var pdf: ByteArray? = null; var hash: String? = null
        if (target == "ISSUED") {
            val branch = jdbc.sql("SELECT code FROM branch WHERE id=:id").param("id", job.branchId.bytes()).query(String::class.java).single()
            number = numbers.next("FDA", NumberContext(branch, LocalDate.now(clock).year))
            val issuedAt = clock.instant()
            pdf = render(jobId, current.copy(status = "ISSUED", number = number, issuedBy = access.actor(), issuedAt = issuedAt), false)
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf))
            jdbc.sql("UPDATE fda_revision SET status='SUPERSEDED',version=version+1 WHERE job_id=:job AND status='ISSUED'").param("job", jobId.bytes()).update()
        }
        jdbc.sql("""UPDATE fda_revision SET status=:status,snapshot_json=:snapshot,reason=:why,number=:number,
            submitted_by=CASE WHEN :submit THEN :actor ELSE submitted_by END,submitted_at=CASE WHEN :submit THEN :now ELSE submitted_at END,
            approved_by=CASE WHEN :approve THEN :actor WHEN :returned OR :submit THEN NULL ELSE approved_by END,
            approved_at=CASE WHEN :approve THEN :now WHEN :returned OR :submit THEN NULL ELSE approved_at END,
            issued_by=CASE WHEN :issue THEN :actor ELSE issued_by END,issued_at=CASE WHEN :issue THEN :now ELSE issued_at END,
            pdf=COALESCE(:pdf,pdf),pdf_sha256=COALESCE(:hash,pdf_sha256),updated_at=:now,version=version+1 WHERE id=:id""")
            .param("status", target).param("snapshot", if (target == "DRAFT") null else frozen).param("why", why).param("number", number)
            .param("actor", access.actor().bytes()).param("now", now()).param("submit", input.decision == "SUBMIT")
            .param("approve", target == "APPROVED").param("returned", target == "DRAFT").param("issue", target == "ISSUED").param("pdf", pdf).param("hash", hash).param("id", id.bytes()).update()
        audit.record(AuditEntry(AuditAction.STATUS_CHANGE, "FDA", id, job.branchId, mapOf("from" to current.status, "to" to target, "reason" to why, "sha256" to hash)))
        return load(jobId, id)
    }

    @Transactional
    fun pdf(jobId: UUID, id: UUID): Pair<String, ByteArray> {
        val job = access.job(jobId, "FIN.FDA.READ"); val row = load(jobId, id)
        val stored = jdbc.sql("SELECT pdf FROM fda_revision WHERE id=:id").param("id", id.bytes()).query { rs, _ -> rs.getBytes(1) }.optional().orElse(null)
        audit.record(AuditEntry(AuditAction.EXPORT, "FDA", id, job.branchId, mapOf("format" to "PDF")))
        return "FDA-${job.jobNumber.replace('/', '-')}-r${row.revisionNo}.pdf" to (stored ?: render(jobId, row, true))
    }

    private fun snapshot(jobId: UUID, input: FdaInput): FdaSnapshot {
        val number = jdbc.sql("SELECT job_number FROM sts_job WHERE id=:id").param("id", jobId.bytes()).query(String::class.java).single()
        val summary = costs.summaryForJob(jobId, number)
        val bills = jdbc.sql("SELECT id FROM job_cost_document WHERE job_id=:job AND status='APPROVED' AND kind='BILL' ORDER BY id")
            .param("job", jobId.bytes()).query { rs, _ -> costs.load(jobId, rs.getBytes(1).uuid()!!) }.list()
        val billing = jdbc.sql("SELECT id,number,doc_type,status,net_amount,tax_amount,gross_amount,currency,version FROM invoice WHERE job_id=:job ORDER BY id")
            .param("job", jobId.bytes()).query { rs, _ -> FdaBilling(rs.getBytes(1).uuid()!!, rs.getString(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getString(8), rs.getLong(9)) }.list()
        if (billing.any { it.currency != summary.currency }) invalid("Reconcile billing in the job's accepted currency.")
        val payments = jdbc.sql("""SELECT a.id,a.receipt_id,a.invoice_id,a.amount FROM receipt_allocation a JOIN invoice i ON i.id=a.invoice_id
            JOIN receipt r ON r.id=a.receipt_id WHERE i.job_id=:job AND a.reversed_at IS NULL AND r.status='ACTIVE' ORDER BY a.id""")
            .param("job", jobId.bytes()).query { rs, _ -> FdaPayment(rs.getBytes(1).uuid()!!, rs.getBytes(2).uuid()!!, rs.getBytes(3).uuid()!!, rs.getBigDecimal(4)) }.list()
        val income = billing.filter { it.status == "ISSUED" }.fold(BigDecimal.ZERO) { t, d -> t + if (d.type == "INVOICE") d.gross else -d.gross }
        val netIncome = billing.filter { it.status == "ISSUED" }.fold(BigDecimal.ZERO) { t, d -> t + if (d.type == "INVOICE") d.net else -d.net }
        val received = payments.fold(BigDecimal.ZERO) { t, p -> t + p.amount }
        val extraIncome = input.adjustments.filter { it.kind == "INCOME" }.fold(BigDecimal.ZERO) { t, a -> t + a.amount }
        val extraExpense = input.adjustments.filter { it.kind == "EXPENSE" }.fold(BigDecimal.ZERO) { t, a -> t + a.amount }
        return FdaSnapshot(summary, bills, billing, payments, clocks.forJob(jobId), input.adjustments, income + extraIncome,
            summary.actualGross + extraExpense, received, income - received, netIncome + extraIncome - summary.actualNet - extraExpense)
    }

    /** Receipts of the job's client (same currency) not yet allocated to any invoice: advances held on account. */
    private fun onAccount(jobId: UUID): BigDecimal = jdbc.sql("""SELECT COALESCE(SUM(r.amount-COALESCE((SELECT SUM(a.amount) FROM receipt_allocation a
            WHERE a.receipt_id=r.id AND a.reversed_at IS NULL),0)),0) FROM receipt r JOIN sts_job j ON j.client_id=r.client_id
            AND j.currency=r.currency WHERE j.id=:job AND r.status='ACTIVE'""")
        .param("job", jobId.bytes()).query(BigDecimal::class.java).single()

    private fun validate(input: FdaInput) {
        if (input.notes.length > 4000 || input.reconciliationNote.length > 2000 || input.noExternalCostsReason.length > 1000 ||
            input.extraHoursNote.length > 1000 || input.adjustments.size > 100) invalid("Check the FDA notes and adjustments.")
        input.adjustments.forEach {
            if (it.kind !in setOf("INCOME", "EXPENSE") || it.description.isBlank() || it.description.length > 300 || it.amount.signum() <= 0 || it.amount.scale() > 4 || it.amount.precision() - it.amount.scale() > 15) invalid("Each adjustment needs an income/expense type, description and positive amount.")
            files.requireReadable(it.fileId)
        }
    }

    private fun unchanged(jobId: UUID, row: FdaRevision) {
        if (jdbc.sql("SELECT COUNT(*) FROM job_cost_document WHERE job_id=:job AND status IN ('DRAFT','SUBMITTED')")
            .param("job", jobId.bytes()).query(Long::class.java).single() > 0) throw BusinessRuleException("FDA_SOURCES_CHANGED", "Pending cost documents were added; return and resubmit this FDA.")
        if (row.snapshot != json.writeValueAsString(snapshot(jobId, row.content))) throw BusinessRuleException("FDA_SOURCES_CHANGED", "Financial sources changed after submission; return and resubmit this FDA.")
    }

    @Transactional(readOnly = true)
    override fun reconciled(jobId: UUID): Boolean {
        val issued = jdbc.sql("SELECT id FROM fda_revision WHERE job_id=:job AND status IN ('APPROVED','ISSUED')").param("job", jobId.bytes())
            .query { rs, _ -> rs.getBytes(1).uuid()!! }.optional().orElse(null) ?: return false
        if (jdbc.sql("""SELECT (SELECT COUNT(*) FROM job_cost_document WHERE job_id=:job AND status IN ('DRAFT','SUBMITTED'))
            +(SELECT COUNT(*) FROM fda_revision WHERE job_id=:job AND status IN ('DRAFT','SUBMITTED'))""")
            .param("job", jobId.bytes()).query(Long::class.java).single() > 0) return false
        val row = load(jobId, issued)
        val current = snapshot(jobId, row.content)
        return row.snapshot == json.writeValueAsString(current) && current.costs.committed.signum() == 0
    }

    private fun load(jobId: UUID, id: UUID, lock: Boolean = false): FdaRevision = jdbc.sql("SELECT * FROM fda_revision WHERE id=:id AND job_id=:job${if (lock) " FOR UPDATE" else ""}")
        .param("id", id.bytes()).param("job", jobId.bytes()).query { rs, _ ->
            fun at(column: String) = rs.getObject(column, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)
            FdaRevision(id, rs.getInt("revision_no"), rs.getString("number"), rs.getString("status"), json.readValue(rs.getString("content_json"), FdaInput::class.java),
                rs.getString("snapshot_json"), rs.getBytes("submitted_by").uuid(), rs.getString("reason"), rs.getString("pdf_sha256"), rs.getLong("version"),
                at("submitted_at"), rs.getBytes("approved_by").uuid(), at("approved_at"), rs.getBytes("issued_by").uuid(), at("issued_at"))
        }.optional().orElseThrow { NotFoundException("FDA revision") }

    private fun lockJob(id: UUID) {
        val status = jdbc.sql("SELECT status FROM sts_job WHERE id=:id FOR UPDATE").param("id", id.bytes()).query(String::class.java).single()
        if (status in setOf("CLOSED", "CANCELLED")) throw BusinessRuleException("JOB_LOCKED", "Reopen this job before changing its FDA.")
    }

    private fun now() = Uuids.toTimestamp(clock.instant())

    private fun render(jobId: UUID, row: FdaRevision, draft: Boolean): ByteArray {
        val data = if (row.snapshot != null) json.readValue(row.snapshot, FdaSnapshot::class.java) else snapshot(jobId, row.content)
        val facts = jdbc.sql("""SELECT j.job_number,c.legal_name,:company,j.currency FROM sts_job j JOIN client c ON c.id=j.client_id
            WHERE j.id=:job""").param("job", jobId.bytes()).param("company", company.name)
            .query { rs, _ -> listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) }.single()
        val currency = facts[3]
        val minor = access.minorUnits(currency)
        fun m(v: BigDecimal?) = v?.setScale(minor, RoundingMode.HALF_UP)?.toPlainString() ?: "—"
        val time = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC)
        val day = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
        val names = users.names(listOfNotNull(row.submittedBy, row.approvedBy, row.issuedBy))
        val evidence = files.refs(data.bills.mapNotNull { it.fileId } + data.adjustments.map { it.fileId })
        val invoiceNumbers = data.billing.associate { it.id to (it.number ?: "Draft") }
        val c = data.clock
        val onAccount = onAccount(jobId)
        val chargeRows = data.billing.filter { it.status == "ISSUED" }.map {
            "${if (it.type == "CREDIT_NOTE") "Credit note" else "Invoice"} ${it.number ?: "Draft"}" to
                (if (it.type == "CREDIT_NOTE") it.gross.negate() else it.gross)
        } + data.adjustments.filter { it.kind == "INCOME" }.map { it.description to it.amount }
        val clientAddress = jdbc.sql("SELECT billing_address FROM client WHERE id=(SELECT client_id FROM sts_job WHERE id=:job)")
            .param("job", jobId.bytes()).query(String::class.java).list().firstOrNull()
        val bill = billSummary.build(jobId, "SUMMARY OF DISBURSEMENT ACCOUNT", company.name, facts[1], clientAddress, currency,
            row.issuedAt?.atZone(ZoneOffset.UTC)?.toLocalDate() ?: LocalDate.now(clock), chargeRows, data.income)
        val document = FdaDocument(
            number = row.number ?: "Draft — numbered at issue", documentId = row.id.toString(), revisionNo = row.revisionNo, draft = draft,
            facts = listOf("Job" to facts[0], "Client" to facts[1], "Issued by" to facts[2], "Currency" to currency),
            totals = listOf("Income incl. tax" to m(data.income), "Actual expenses incl. tax" to m(data.expenses), "Received" to m(data.received),
                "Outstanding" to m(data.outstanding), "Margin excl. tax" to m(data.margin)),
            billing = data.billing.map { listOf(it.number ?: "Draft", if (it.type == "CREDIT_NOTE") "Credit note" else "Invoice", it.status.lowercase(), m(it.net), m(it.tax), m(it.gross)) },
            bills = data.bills.map { listOf(it.vendorName, it.reference, day.format(it.documentDate),
                "${it.currency} ${it.grossAmount.setScale(access.minorUnits(it.currency), RoundingMode.HALF_UP).toPlainString()}",
                "${it.jobCurrency} ${m(it.jobAmount)}", it.fileId?.let { f -> evidence[f]?.originalName } ?: "—") },
            noCostsReason = row.content.noExternalCostsReason.ifBlank { "No third-party costs." },
            heads = data.costs.heads.map { listOf("${it.code} — ${it.name}", m(it.budget), m(it.committed), m(it.actualNet), m(it.variance)) },
            clock = listOf("Basis" to "${c.basisHours} h", "Elapsed" to (c.elapsedHours?.setScale(2, RoundingMode.HALF_UP)?.toPlainString()?.let { "$it h" } ?: "—"),
                "Extra hours" to (c.extraHours?.setScale(2, RoundingMode.HALF_UP)?.toPlainString()?.let { "$it h" } ?: "—"),
                "Clock" to if (c.final) "final" else "not final",
                "Tariff terms" to if (c.tariffTermsStatus == "CONFIRMED") "confirmed" else "pending confirmation",
                "Additional charge" to (c.additionalCharge?.let { "${c.currency} ${m(it)}" } ?: "not billed")),
            extraHoursNote = row.content.extraHoursNote.ifBlank { null },
            adjustments = data.adjustments.map { listOf(if (it.kind == "INCOME") "Income" else "Expense", it.description, m(it.amount), evidence[it.fileId]?.originalName ?: "—") },
            payments = data.payments.map { listOf(invoiceNumbers[it.invoiceId] ?: "—", m(it.amount)) },
            onAccount = "Client receipts held on account (not allocated to any invoice): $currency ${m(onAccount)}.",
            reconciliation = row.content.reconciliationNote.ifBlank { "—" }, notes = row.content.notes.ifBlank { null },
            approval = listOfNotNull(
                row.submittedBy?.let { "Prepared" to "${names[it] ?: "—"}${row.submittedAt?.let { t -> ", " + time.format(t) } ?: ""}" },
                row.approvedBy?.let { "Approved" to "${names[it] ?: "—"}${row.approvedAt?.let { t -> ", " + time.format(t) } ?: ""}" },
                row.issuedBy?.let { "Issued" to "${names[it] ?: "—"}${row.issuedAt?.let { t -> ", " + time.format(t) } ?: ""}" },
            ).ifEmpty { listOf("Status" to row.status.lowercase()) },
            bill = bill,
        )
        return output.render("fda", document)
    }
}

@RestController
@RequestMapping("/api/v1/jobs/{jobId}/fda")
class FdaController(private val service: FdaService) {
    @GetMapping fun list(@PathVariable jobId: UUID) = service.list(jobId)
    @GetMapping("/preview") fun preview(@PathVariable jobId: UUID) = service.preview(jobId)
    @PostMapping fun create(@PathVariable jobId: UUID, @RequestHeader("Idempotency-Key", required = false) key: String?) = ResponseEntity.status(201).body(service.create(jobId, key))
    @PutMapping("/{id}") fun save(@PathVariable jobId: UUID, @PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) version: String?, @RequestBody input: FdaInput) =
        service.save(jobId, id, Versioning.requireIfMatch(version), input)
    @PostMapping("/{id}/actions") fun action(@PathVariable jobId: UUID, @PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) version: String?, @RequestBody input: CostDecision) =
        service.action(jobId, id, Versioning.requireIfMatch(version), input)
    @GetMapping("/{id}/pdf") fun pdf(@PathVariable jobId: UUID, @PathVariable id: UUID): ResponseEntity<ByteArray> = service.pdf(jobId, id).let { (name, bytes) ->
        ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"$name\"")
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(bytes) }
}
