package com.ship2ship.features.finance

import com.ship2ship.core.config.CompanyProperties
import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.files.FileAccess
import com.ship2ship.core.files.FileService
import com.ship2ship.core.persistence.IdempotencyService
import com.ship2ship.core.persistence.NumberContext
import com.ship2ship.core.persistence.NumberSeriesService
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.core.security.OrganisationLookup
import com.ship2ship.core.security.ReferenceLookup
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.BusinessRuleException
import com.ship2ship.core.web.ConflictException
import com.ship2ship.core.web.FieldError
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.PageResponse
import com.ship2ship.core.web.Versioning
import com.ship2ship.core.web.Xlsx
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

data class CostLineInput(val costHeadCode: String = "", val description: String = "", val quantity: BigDecimal? = null,
    val unitPrice: BigDecimal? = null)
data class CostInput(val kind: String = "", val vendorId: UUID? = null, val reference: String = "", val documentDate: LocalDate? = null,
    val currency: String = "", val lines: List<CostLineInput> = emptyList(), val dueDate: LocalDate? = null,
    val purchaseOrderId: UUID? = null, val fileId: UUID? = null, val baseRate: BigDecimal? = null,
    val jobRate: BigDecimal? = null, val baseRateSource: String? = null, val jobRateSource: String? = null,
    val notes: String? = null)
data class CostDecision(val decision: String = "", val reason: String? = null)
data class CostLine(val costHeadCode: String, val costHeadName: String, val description: String, val quantity: BigDecimal, val unitPrice: BigDecimal,
    val netAmount: BigDecimal, val taxRate: BigDecimal, val taxAmount: BigDecimal)
data class CostDocument(val id: UUID, val jobId: UUID, val jobNumber: String, val kind: String, val number: String?, val vendorId: UUID,
    val vendorName: String, val reference: String, val documentDate: LocalDate, val dueDate: LocalDate?, val status: String,
    val currency: String, val baseCurrency: String, val jobCurrency: String,
    val baseRate: BigDecimal, val jobRate: BigDecimal, val baseRateDate: LocalDate?, val baseRateSource: String?,
    val jobRateDate: LocalDate?, val jobRateSource: String?, val netAmount: BigDecimal, val taxAmount: BigDecimal,
    val grossAmount: BigDecimal, val baseAmount: BigDecimal, val jobAmount: BigDecimal, val purchaseOrderId: UUID?,
    val fileId: UUID?, val notes: String?, val decisionReason: String?, val submittedBy: UUID?, val version: Long,
    val lines: List<CostLine>, val warnings: List<FinanceIssue> = emptyList())
data class CostHeadBalance(val code: String, val name: String, val budget: BigDecimal, val committed: BigDecimal,
    val actualNet: BigDecimal, val actualTax: BigDecimal, val variance: BigDecimal, val varianceToCommitment: BigDecimal)
data class JobCostSummary(val jobId: UUID, val jobNumber: String, val currency: String, val heads: List<CostHeadBalance>,
    val budget: BigDecimal, val committed: BigDecimal, val actualNet: BigDecimal, val actualTax: BigDecimal,
    val actualGross: BigDecimal, val variance: BigDecimal, val warnings: List<FinanceIssue> = emptyList())
data class CostDocumentRow(val id: UUID, val jobId: UUID, val jobNumber: String, val kind: String, val number: String?, val reference: String,
    val vendorName: String, val status: String, val documentDate: LocalDate, val dueDate: LocalDate?, val currency: String,
    val grossAmount: BigDecimal, val jobCurrency: String, val jobAmount: BigDecimal)

/**
 * Job budgets, purchase orders (commitments) and vendor bills (actuals) — SRS D06, FR-J11. The budget is the cost side of
 * the estimate behind the accepted quotation. POs and bills are approved by a second person; a PO is numbered at approval and
 * closed when nothing more is expected; an approved document is reversed, never deleted. Over-PO and over-budget are
 * warnings for people to judge, never blocks. Entered conversion rates are frozen on the document with their source.
 */
@Service
class JobCostsService(private val jdbc: JdbcClient, private val access: FinanceAccess,
    private val references: ReferenceLookup, private val organisation: OrganisationLookup, private val numbers: NumberSeriesService,
    private val files: FileService, private val audit: AuditService, private val clock: Clock,
    private val idempotency: IdempotencyService, private val company: CompanyProperties) {
    private fun permission(action: String) = "FIN.COST.$action"

    @Transactional(readOnly = true)
    fun list(jobId: UUID, page: Int, size: Int): PageResponse<CostDocument> {
        access.job(jobId, permission("READ"))
        val ids = jdbc.sql("SELECT id FROM job_cost_document WHERE job_id=:job ORDER BY created_at DESC,id LIMIT :size OFFSET :offset")
            .param("job", jobId.bytes()).param("size", size).param("offset", page.toLong() * size).query { rs, _ -> rs.getBytes(1).uuid()!! }.list()
        val count = jdbc.sql("SELECT COUNT(*) FROM job_cost_document WHERE job_id=:job").param("job", jobId.bytes()).query(Long::class.java).single()
        return PageResponse(ids.map { load(jobId, it, withWarnings = true) }, page, size, count)
    }

    /** POs or vendor bills across the jobs in the user's scope. */
    @Transactional(readOnly = true)
    fun search(kind: String?, status: String?, vendorId: UUID?, jobId: UUID?, q: String?, page: Int, size: Int): PageResponse<CostDocumentRow> {
        val params = mutableMapOf<String, Any>()
        val where = listOfNotNull(
            access.clause(access.scope(permission("READ")), "j.branch_id", params),
            kind?.takeIf { it in KINDS }?.let { params["kind"] = it; "d.kind=:kind" },
            status?.takeIf { it.isNotBlank() }?.let { params["status"] = it; "d.status=:status" },
            vendorId?.let { params["vendor"] = it.bytes(); "d.vendor_id=:vendor" },
            jobId?.let { params["job"] = it.bytes(); "d.job_id=:job" },
            q?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let {
                params["q"] = "%$it%"; "(LOWER(d.reference) LIKE :q OR LOWER(d.number) LIKE :q OR LOWER(v.name) LIKE :q OR LOWER(j.job_number) LIKE :q)"
            },
        )
        val clause = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        val from = "FROM job_cost_document d JOIN sts_job j ON j.id=d.job_id JOIN resource_vendor v ON v.id=d.vendor_id $clause"
        val total = jdbc.sql("SELECT COUNT(*) $from").params(params).query(Long::class.java).single()
        val rows = jdbc.sql("""SELECT d.id,d.job_id,j.job_number,d.kind,d.number,d.reference,v.name,d.status,d.document_date,d.due_date,d.currency,
            d.gross_amount,d.job_currency,d.job_amount $from ORDER BY d.document_date DESC,d.created_at DESC LIMIT :limit OFFSET :offset""")
            .params(params).param("limit", size).param("offset", page.toLong() * size).query { rs, _ ->
                CostDocumentRow(rs.getBytes(1).uuid()!!, rs.getBytes(2).uuid()!!, rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                    rs.getString(7), rs.getString(8), rs.getObject(9, LocalDate::class.java), rs.getObject(10, LocalDate::class.java), rs.getString(11),
                    rs.getBigDecimal(12).setScale(access.minorUnits(rs.getString(11)), RoundingMode.HALF_UP), rs.getString(13),
                    rs.getBigDecimal(14).setScale(access.minorUnits(rs.getString(13)), RoundingMode.HALF_UP))
            }.list()
        return PageResponse(rows, page, size, total)
    }

    @Transactional(readOnly = true)
    fun options(jobId: UUID, query: String): Map<String, Any?> {
        val job = access.job(jobId, permission("READ"))
        val vendors = jdbc.sql("SELECT id,name FROM resource_vendor WHERE active=TRUE AND LOWER(name) LIKE :q ORDER BY name LIMIT 100")
            .param("q", "%${query.trim().lowercase()}%")
            .query { rs, _ -> mapOf("id" to rs.getBytes(1).uuid()!!, "name" to rs.getString(2)) }.list()
        val heads = jdbc.sql("SELECT code,name FROM cost_head WHERE active=TRUE AND kind IN ('EXPENSE','BOTH') ORDER BY code LIMIT 200")
            .query { rs, _ -> mapOf("code" to rs.getString(1), "name" to rs.getString(2)) }.list()
        val currencies = jdbc.sql("SELECT code,name,minor_units FROM currency WHERE active=TRUE ORDER BY code")
            .query { rs, _ -> mapOf("code" to rs.getString(1), "name" to rs.getString(2), "minorUnits" to rs.getInt(3)) }.list()
        val purchaseOrders = jdbc.sql("SELECT id,number,reference,vendor_id FROM job_cost_document WHERE job_id=:job AND kind='PO' AND status='APPROVED' ORDER BY number")
            .param("job", jobId.bytes()).query { rs, _ -> mapOf("id" to rs.getBytes(1).uuid()!!, "number" to rs.getString(2), "reference" to rs.getString(3),
                "vendorId" to rs.getBytes(4).uuid()!!) }.list()
        val jobCurrency = jdbc.sql("SELECT currency FROM sts_job WHERE id=:job").param("job", jobId.bytes()).query(String::class.java).single()
        val estimateLines = jdbc.sql("""SELECT h.code, l.description, l.quantity, l.unit_price
            FROM sts_job j JOIN quotation_version q ON q.id=j.quotation_version_id
            JOIN estimate_line l ON l.estimate_id=q.estimate_id JOIN cost_head h ON h.id=l.cost_head_id
            WHERE j.id=:job AND l.kind='COST' ORDER BY l.line_no""").param("job", jobId.bytes())
            .query { rs, _ -> mapOf("costHeadCode" to rs.getString(1), "description" to (rs.getString(2) ?: ""),
                "quantity" to rs.getBigDecimal(3).plain().toPlainString(), "unitPrice" to rs.getBigDecimal(4).plain().toPlainString()) }.list()
        return mapOf("vendors" to vendors, "heads" to heads, "currencies" to currencies, "purchaseOrders" to purchaseOrders,
            "jobCurrency" to jobCurrency, "baseCurrency" to company.baseCurrency, "estimateLines" to estimateLines)
    }

    @Transactional
    fun upload(jobId: UUID, file: MultipartFile) = access.job(jobId, permission("WRITE")).let {
        files.store(file, FileAccess(permission("READ"), it.branchId))
    }

    @Transactional
    fun save(jobId: UUID, id: UUID?, expected: Long?, input: CostInput, key: String? = null): CostDocument {
        val job = access.job(jobId, permission("WRITE"))
        lockJob(jobId)
        if (id == null) idempotency.existing(access.actor(), key, "JOB_COST:$jobId")?.let { return load(jobId, it) }
        val old = id?.let { load(jobId, it, lock = true) }
        if (old != null) {
            Versioning.checkCurrent(expected ?: throw BadRequestException("VERSION_REQUIRED", "The If-Match header is required."), old.version)
            if (old.status != "DRAFT") throw BusinessRuleException("INVALID_TRANSITION", "Only draft documents can be edited.")
        }

        val errors = mutableListOf<FieldError>()
        fun error(field: String, message: String) { errors += FieldError(field, "invalid", message) }
        if (input.kind !in KINDS) error("kind", "Choose a purchase order or a vendor bill.")
        if (old != null && old.kind != input.kind) error("kind", "The document type cannot change.")
        val reference = input.reference.trim()
        if (reference.isEmpty() || reference.length > 100) error("reference", "Enter the reference (at most 100 characters).")
        val day = input.documentDate
        if (day == null) error("documentDate", "Enter the document date.")
        else if (day.isAfter(LocalDate.now(clock))) error("documentDate", "Cannot be in the future.")
        if (day != null && input.dueDate?.isBefore(day) == true) error("dueDate", "Cannot be before the document date.")
        if (input.lines.isEmpty() || input.lines.size > 200) error("lines", "Add between 1 and 200 lines.")
        if ((input.notes?.length ?: 0) > 2000) error("notes", "Use at most 2000 characters.")
        val currency = input.currency.trim().uppercase()
        if (references.currency(currency)?.active != true) error("currency", "Choose an active currency.")
        val vendorOk = input.vendorId != null && jdbc.sql("SELECT COUNT(*) FROM resource_vendor WHERE id=:id AND active=TRUE")
            .param("id", input.vendorId.bytes()).query(Long::class.java).single() == 1L
        if (!vendorOk) error("vendorId", "Choose an active vendor.")
        if (input.purchaseOrderId != null) {
            val po = runCatching { load(jobId, input.purchaseOrderId) }.getOrNull()
            if (input.kind != "BILL" || po == null || po.kind != "PO" || po.vendorId != input.vendorId || po.status != "APPROVED")
                error("purchaseOrderId", "Choose an open approved purchase order of this job and vendor.")
        }
        input.fileId?.let { runCatching { files.requireReadable(it) }.onFailure { _ -> error("fileId", "Attach a file you can open.") } }
        val heads = jdbc.sql("SELECT code,name FROM cost_head WHERE active=TRUE AND kind IN ('EXPENSE','BOTH')")
            .query { rs, _ -> rs.getString(1) to rs.getString(2) }.list().toMap() + old?.lines.orEmpty().associate { it.costHeadCode to it.costHeadName }
        val minor = access.minorUnits(currency)
        val lines = input.lines.mapIndexedNotNull { i, line ->
            val name = heads[line.costHeadCode]
            if (name == null) error("lines[$i].costHeadCode", "Choose an active expense cost head.")
            if (line.description.isBlank() || line.description.length > 300) error("lines[$i].description", "Enter a description (at most 300 characters).")
            val qty = line.quantity
            if (qty == null || qty.signum() <= 0 || qty.stripTrailingZeros().scale() > 3 || qty >= BigDecimal("1000000000"))
                error("lines[$i].quantity", "More than 0, at most 3 decimals.")
            val price = line.unitPrice
            if (price == null || price.signum() < 0 || price.stripTrailingZeros().scale() > 4 || price >= BigDecimal("1000000000000"))
                error("lines[$i].unitPrice", "0 or more, at most 4 decimals.")
            if (name == null || qty == null || price == null) null else {
                val net = qty.multiply(price).setScale(minor, RoundingMode.HALF_UP)
                CostLine(line.costHeadCode, name, line.description.trim(), qty.plain(), price.plain(), net, BigDecimal.ZERO,
                    BigDecimal.ZERO.setScale(minor))
            }
        }
        val jobCurrency = jdbc.sql("SELECT currency FROM sts_job WHERE id=:job").param("job", jobId.bytes()).query(String::class.java).single()
        val baseCurrency = company.baseCurrency
        val baseFx = conversion("baseRate", input.baseRate, input.baseRateSource, currency, baseCurrency, day, errors)
        val jobFx = conversion("jobRate", input.jobRate, input.jobRateSource, currency, jobCurrency, day, errors)
        val net = lines.fold(BigDecimal.ZERO) { t, l -> t + l.netAmount }
        val tax = lines.fold(BigDecimal.ZERO) { t, l -> t + l.taxAmount }
        val gross = net + tax
        if (errors.isEmpty() && (gross.signum() <= 0 || gross.precision() - gross.scale() > 15)) error("lines", "The total must be more than zero.")
        if (errors.isNotEmpty()) throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", errors)
        val duplicate = jdbc.sql("""SELECT COUNT(*) FROM job_cost_document WHERE vendor_id=:vendor AND kind=:kind AND reference=:ref AND job_id=:job
            AND id<>:id""").param("vendor", input.vendorId!!.bytes()).param("kind", input.kind).param("ref", reference).param("job", jobId.bytes())
            .param("id", (id ?: UUID(0, 0)).bytes()).query(Long::class.java).single()
        if (duplicate > 0) throw ConflictException("DUPLICATE_REFERENCE", "This vendor reference is already entered on this job.",
            listOf(FieldError("reference", "duplicate", "Already entered on this job.")))

        val docId = id ?: UuidV7.generate()
        val params = mapOf("id" to docId.bytes(), "job" to jobId.bytes(), "vendor" to input.vendorId.bytes(), "kind" to input.kind,
            "ref" to reference, "day" to day, "due" to input.dueDate, "po" to input.purchaseOrderId?.bytes(),
            "file" to input.fileId?.bytes(), "currency" to currency, "base" to baseCurrency, "jobCurrency" to jobCurrency,
            "baseRate" to baseFx.rate, "jobRate" to jobFx.rate,
            "baseRateDate" to baseFx.date, "baseRateSource" to baseFx.source, "jobRateDate" to jobFx.date, "jobRateSource" to jobFx.source,
            "net" to net, "tax" to tax, "gross" to gross,
            "baseAmount" to gross.multiply(baseFx.rate).setScale(access.minorUnits(baseCurrency), RoundingMode.HALF_UP),
            "jobAmount" to gross.multiply(jobFx.rate).setScale(access.minorUnits(jobCurrency), RoundingMode.HALF_UP),
            "notes" to input.notes?.trim()?.ifEmpty { null }, "now" to now(), "actor" to access.actor().bytes())
        if (old == null) jdbc.sql("""INSERT INTO job_cost_document(id,job_id,vendor_id,kind,reference,document_date,due_date,purchase_order_id,file_id,
            currency,base_currency,job_currency,base_rate,job_rate,base_rate_date,base_rate_source,job_rate_date,job_rate_source,
            net_amount,tax_amount,gross_amount,base_amount,job_amount,notes,created_at,created_by,updated_at)
            VALUES(:id,:job,:vendor,:kind,:ref,:day,:due,:po,:file,:currency,:base,:jobCurrency,:baseRate,:jobRate,:baseRateDate,
            :baseRateSource,:jobRateDate,:jobRateSource,:net,:tax,:gross,:baseAmount,:jobAmount,:notes,:now,:actor,:now)""").params(params).update()
        else {
            jdbc.sql("""UPDATE job_cost_document SET vendor_id=:vendor,reference=:ref,document_date=:day,due_date=:due,purchase_order_id=:po,file_id=:file,
                currency=:currency,base_currency=:base,job_currency=:jobCurrency,base_rate=:baseRate,job_rate=:jobRate,
                base_rate_date=:baseRateDate,base_rate_source=:baseRateSource,job_rate_date=:jobRateDate,job_rate_source=:jobRateSource,
                net_amount=:net,tax_amount=:tax,gross_amount=:gross,base_amount=:baseAmount,job_amount=:jobAmount,notes=:notes,updated_at=:now,
                version=version+1 WHERE id=:id""").params(params).update()
            jdbc.sql("DELETE FROM job_cost_line WHERE document_id=:id").param("id", docId.bytes()).update()
        }
        lines.forEachIndexed { index, line -> jdbc.sql("""INSERT INTO job_cost_line(id,document_id,line_no,cost_head_code,cost_head_name,description,
            quantity,unit_price,net_amount,tax_rate,tax_amount) VALUES(:id,:doc,:n,:head,:headName,:text,:qty,:price,:net,:rate,:tax)""")
            .param("id", UuidV7.generate().bytes()).param("doc", docId.bytes()).param("n", index + 1).param("head", line.costHeadCode)
            .param("headName", line.costHeadName).param("text", line.description).param("qty", line.quantity).param("price", line.unitPrice)
            .param("net", line.netAmount).param("rate", line.taxRate).param("tax", line.taxAmount).update() }
        audit.record(AuditEntry(if (old == null) AuditAction.CREATE else AuditAction.UPDATE, "JOB_COST", docId, job.branchId,
            mapOf("jobId" to jobId, "kind" to input.kind, "reference" to reference, "gross" to gross, "currency" to currency)))
        if (id == null) idempotency.remember(access.actor(), key, "JOB_COST:$jobId", docId)
        return load(jobId, docId, withWarnings = true)
    }

    /** SUBMIT (preparer); APPROVE or RETURN (second person); CLOSE an approved PO or REVERSE an approved document (approver, with a reason). */
    @Transactional
    fun action(jobId: UUID, id: UUID, expected: Long, input: CostDecision): CostDocument {
        val decision = input.decision
        if (decision !in setOf("SUBMIT", "APPROVE", "RETURN", "CLOSE", "REVERSE"))
            throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", listOf(FieldError("decision", "allowed", "SUBMIT, APPROVE, RETURN, CLOSE or REVERSE.")))
        val job = access.job(jobId, permission(if (decision == "SUBMIT") "WRITE" else "READ"))
        lockJob(jobId)
        val doc = load(jobId, id, lock = true)
        if (decision != "SUBMIT") access.job(jobId, if (doc.kind == "PO") "FIN.COST.PO.APPROVE" else "FIN.COST.APPROVE")
        Versioning.checkCurrent(expected, doc.version)
        val reason = input.reason?.trim()?.takeIf { it.isNotEmpty() }
        if ((reason?.length ?: 0) > 500) throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", listOf(FieldError("reason", "size", "At most 500 characters.")))
        fun needReason() { if (reason == null) throw BadRequestException("REASON_REQUIRED", "A reason is required for this change.", listOf(FieldError("reason", "required", null))) }
        fun transition(message: String): Nothing = throw BusinessRuleException("INVALID_TRANSITION", message)
        var number = doc.number
        val target = when (decision) {
            "SUBMIT" -> {
                if (doc.status != "DRAFT") transition("Only a draft can be submitted.")
                if (doc.kind == "BILL" && doc.fileId == null) throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.",
                    listOf(FieldError("fileId", "required", "Attach the vendor's invoice before submitting.")))
                "SUBMITTED"
            }
            "APPROVE", "RETURN" -> {
                if (doc.status != "SUBMITTED") transition("The document is not awaiting approval.")
                if (doc.submittedBy == access.actor()) throw BusinessRuleException("SELF_APPROVAL_FORBIDDEN", "A different person must decide the document.")
                if (decision == "RETURN") { needReason(); "DRAFT" } else {
                    if (doc.kind == "PO") {
                        number = numbers.next("PURCHASE_ORDER", NumberContext(organisation.branch(job.branchId)?.code,
                            LocalDate.now(clock).year))
                    }
                    // Locks the PO so two bills approved at the same time see each other in its remaining amount.
                    doc.purchaseOrderId?.let { load(jobId, it, lock = true) }
                    "APPROVED"
                }
            }
            "CLOSE" -> {
                if (doc.kind != "PO" || doc.status != "APPROVED") transition("Only an approved purchase order can be closed.")
                needReason(); "CLOSED"
            }
            else -> {
                if (doc.status !in setOf("APPROVED", "CLOSED")) transition("Only an approved document can be reversed.")
                needReason()
                if (doc.kind == "PO" && jdbc.sql("SELECT COUNT(*) FROM job_cost_document WHERE purchase_order_id=:id AND status<>'REVERSED'")
                        .param("id", id.bytes()).query(Long::class.java).single() > 0) transition("Reverse the bills against this purchase order first.")
                "REVERSED"
            }
        }
        jdbc.sql("""UPDATE job_cost_document SET status=:status,number=:number,decision_reason=:reason,updated_at=:now,version=version+1,
            submitted_by=CASE WHEN :submit THEN :actor ELSE submitted_by END,submitted_at=CASE WHEN :submit THEN :now ELSE submitted_at END,
            approved_by=CASE WHEN :approve THEN :actor ELSE approved_by END,approved_at=CASE WHEN :approve THEN :now ELSE approved_at END,
            closed_by=CASE WHEN :close THEN :actor ELSE closed_by END,closed_at=CASE WHEN :close THEN :now ELSE closed_at END,
            reversed_by=CASE WHEN :reverse THEN :actor ELSE reversed_by END,reversed_at=CASE WHEN :reverse THEN :now ELSE reversed_at END WHERE id=:id""")
            .param("status", target).param("number", number).param("reason", reason).param("now", now()).param("actor", access.actor().bytes())
            .param("id", id.bytes()).param("submit", decision == "SUBMIT").param("approve", decision == "APPROVE")
            .param("close", decision == "CLOSE").param("reverse", decision == "REVERSE").update()
        audit.record(AuditEntry(AuditAction.STATUS_CHANGE, "JOB_COST", id, job.branchId,
            mapOf("kind" to doc.kind, "from" to doc.status, "to" to target, "reason" to reason, "number" to number)))
        return load(jobId, id, withWarnings = true)
    }

    @Transactional(readOnly = true)
    fun summary(jobId: UUID): JobCostSummary {
        val job = access.job(jobId, permission("READ"))
        return summaryForJob(jobId, job.jobNumber)
    }

    /** Budget versus actual as an Excel workbook; every export is audited. */
    @Transactional
    fun summaryXlsx(jobId: UUID): Pair<String, ByteArray> {
        val job = access.job(jobId, permission("READ"))
        val s = summaryForJob(jobId, job.jobNumber)
        val bytes = workbook("Budget vs actual", "${s.jobNumber} — budget versus actual (${s.currency})",
            listOf("Cost head", "Name", "Budget", "Committed", "Actual net", "Actual tax", "Actual − budget", "Actual − max(budget, committed)"),
            s.heads.map { listOf(it.code, it.name, it.budget, it.committed, it.actualNet, it.actualTax, it.variance, it.varianceToCommitment) } +
                listOf(listOf("Total", "", s.budget, s.committed, s.actualNet, s.actualTax, s.variance, null)))
        audit.record(AuditEntry(AuditAction.EXPORT, "JOB_COSTS", jobId, job.branchId, mapOf("format" to "xlsx")))
        return "${s.jobNumber.replace('/', '-')}-costs.xlsx" to bytes
    }

    internal fun summaryForJob(jobId: UUID, jobNumber: String): JobCostSummary {
        val currency = jdbc.sql("SELECT currency FROM sts_job WHERE id=:job").param("job", jobId.bytes()).query(String::class.java).single()
        val minor = access.minorUnits(currency)
        val budgets = jdbc.sql("""SELECT h.code,h.name,SUM(l.amount) FROM sts_job j JOIN quotation_version q ON q.id=j.quotation_version_id
            JOIN estimate_line l ON l.estimate_id=q.estimate_id JOIN cost_head h ON h.id=l.cost_head_id
            WHERE j.id=:job AND l.kind='COST' GROUP BY h.code,h.name""").param("job", jobId.bytes())
            .query { rs, _ -> rs.getString(1) to (rs.getString(2) to rs.getBigDecimal(3)) }.list().toMap()
        val docs = jdbc.sql("SELECT id FROM job_cost_document WHERE job_id=:job AND status IN ('APPROVED','CLOSED')").param("job", jobId.bytes())
            .query { rs, _ -> rs.getBytes(1).uuid()!! }.list().map { load(jobId, it) }
        val converted = docs.associate { it.id to convertedCostLines(it.lines, it.jobRate, it.jobAmount, minor) }
        val names = budgets.mapValues { it.value.first } + docs.flatMap { d -> d.lines.map { it.costHeadCode to it.costHeadName } }
        fun amount(doc: CostDocument, code: String, tax: Boolean = false) = doc.lines.withIndex().filter { it.value.costHeadCode == code }
            .fold(BigDecimal.ZERO) { t, (i, _) -> t + converted.getValue(doc.id)[i].let { if (tax) it.second else it.first } }
        val openPos = docs.filter { it.kind == "PO" && it.status == "APPROVED" }
        val heads = names.keys.toSortedSet().map { code ->
            val bills = docs.filter { it.kind == "BILL" }
            val actual = bills.fold(BigDecimal.ZERO) { t, d -> t + amount(d, code) }
            val committed = openPos.fold(BigDecimal.ZERO) { total, po ->
                val billed = bills.filter { it.purchaseOrderId == po.id }.fold(BigDecimal.ZERO) { sum, bill -> sum + amount(bill, code) }
                total + (amount(po, code) - billed).max(BigDecimal.ZERO)
            }
            val budget = (budgets[code]?.second ?: BigDecimal.ZERO).setScale(minor, RoundingMode.HALF_UP)
            CostHeadBalance(code, names.getValue(code), budget, committed, actual, bills.fold(BigDecimal.ZERO) { t, d -> t + amount(d, code, true) },
                actual - budget, actual - budget.max(committed))
        }
        fun sum(f: (CostHeadBalance) -> BigDecimal) = heads.fold(BigDecimal.ZERO) { t, h -> t + f(h) }.setScale(minor, RoundingMode.HALF_UP)
        val warnings = heads.filter { it.budget.signum() > 0 && it.actualNet > it.budget || it.budget.signum() == 0 && it.actualNet.signum() > 0 }
            .map { FinanceIssue("OVER_BUDGET", "Actual cost for ${it.code} (${it.actualNet}) is above its budget (${it.budget}).") }
        return JobCostSummary(jobId, jobNumber, currency, heads, sum { it.budget }, sum { it.committed }, sum { it.actualNet }, sum { it.actualTax },
            sum { it.actualNet + it.actualTax }, sum { it.variance }, warnings)
    }

    internal fun load(jobId: UUID, id: UUID, lock: Boolean = false, withWarnings: Boolean = false): CostDocument {
        val lines = jdbc.sql("SELECT * FROM job_cost_line WHERE document_id=:id ORDER BY line_no").param("id", id.bytes()).query { rs, _ ->
            CostLine(rs.getString("cost_head_code"), rs.getString("cost_head_name"), rs.getString("description"), rs.getBigDecimal("quantity").plain(),
                rs.getBigDecimal("unit_price").plain(), rs.getBigDecimal("net_amount"), rs.getBigDecimal("tax_rate").plain(),
                rs.getBigDecimal("tax_amount")) }.list()
        val doc = jdbc.sql("""SELECT d.*,v.name vendor_name,j.job_number FROM job_cost_document d JOIN resource_vendor v ON v.id=d.vendor_id
            JOIN sts_job j ON j.id=d.job_id WHERE d.id=:id AND d.job_id=:job${if (lock) " FOR UPDATE" else ""}""")
            .param("id", id.bytes()).param("job", jobId.bytes()).query { rs, _ ->
                CostDocument(id, jobId, rs.getString("job_number"), rs.getString("kind"), rs.getString("number"), rs.getBytes("vendor_id").uuid()!!,
                    rs.getString("vendor_name"), rs.getString("reference"), rs.getObject("document_date", LocalDate::class.java),
                    rs.getObject("due_date", LocalDate::class.java), rs.getString("status"), rs.getString("currency"), rs.getString("base_currency"),
                    rs.getString("job_currency"), rs.getBigDecimal("base_rate").plain(),
                    rs.getBigDecimal("job_rate").plain(), rs.getObject("base_rate_date", LocalDate::class.java), rs.getString("base_rate_source"),
                    rs.getObject("job_rate_date", LocalDate::class.java), rs.getString("job_rate_source"), rs.getBigDecimal("net_amount"),
                    rs.getBigDecimal("tax_amount"), rs.getBigDecimal("gross_amount"), rs.getBigDecimal("base_amount"), rs.getBigDecimal("job_amount"),
                    rs.getBytes("purchase_order_id").uuid(), rs.getBytes("file_id").uuid(), rs.getString("notes"), rs.getString("decision_reason"),
                    rs.getBytes("submitted_by").uuid(), rs.getLong("version"), lines)
            }.optional().orElseThrow { NotFoundException("Job cost") }
        return if (withWarnings) doc.copy(warnings = warnings(doc)) else doc
    }

    /** What a person should look at before approving: never a block. */
    private fun warnings(doc: CostDocument): List<FinanceIssue> = buildList {
        if (doc.status == "REVERSED") return@buildList
        val elsewhere = jdbc.sql("""SELECT j.job_number FROM job_cost_document d JOIN sts_job j ON j.id=d.job_id WHERE d.vendor_id=:vendor AND d.kind=:kind
            AND d.reference=:ref AND d.job_id<>:job AND d.status<>'REVERSED'""").param("vendor", doc.vendorId.bytes()).param("kind", doc.kind)
            .param("ref", doc.reference).param("job", doc.jobId.bytes()).query(String::class.java).list()
        if (elsewhere.isNotEmpty()) add(FinanceIssue("REFERENCE_ON_OTHER_JOB", "The same vendor reference is also entered on ${elsewhere.joinToString()}."))
        val po = doc.purchaseOrderId?.let { load(doc.jobId, it) } ?: return@buildList
        val billed = jdbc.sql("SELECT COALESCE(SUM(job_amount),0) FROM job_cost_document WHERE purchase_order_id=:po AND status='APPROVED' AND id<>:id")
            .param("po", po.id.bytes()).param("id", doc.id.bytes()).query(BigDecimal::class.java).single()
        val remaining = po.jobAmount - billed
        if (doc.jobAmount > remaining) add(FinanceIssue("OVER_PURCHASE_ORDER",
            "This bill (${doc.jobCurrency} ${doc.jobAmount}) is more than what is left on ${po.number ?: po.reference} (${doc.jobCurrency} ${remaining.max(BigDecimal.ZERO)})."))
    }

    private data class Conversion(val rate: BigDecimal, val date: LocalDate?, val source: String?)

    /** A document-level rate; the same currency always uses one. */
    private fun conversion(field: String, rate: BigDecimal?, source: String?, from: String, to: String,
                           day: LocalDate?, errors: MutableList<FieldError>): Conversion {
        if (from == to) {
            if (rate != null && rate.compareTo(BigDecimal.ONE) != 0) errors += FieldError(field, "invalid", "Use 1 for the same currency.")
            return Conversion(BigDecimal.ONE, null, null)
        }
        if (rate == null || rate.signum() <= 0 || rate.scale() > 8 || rate.precision() - rate.scale() > 11) {
            errors += FieldError(field, "invalid", "Enter a positive $from to $to rate with at most 8 decimals.")
        }
        val cleanSource = source?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanSource == null || cleanSource.length > 100) {
            errors += FieldError("${field}Source", "invalid", "Enter the rate source (at most 100 characters).")
        }
        return Conversion(rate ?: BigDecimal.ONE, day, cleanSource)
    }

    private fun lockJob(id: UUID) {
        val status = jdbc.sql("SELECT status FROM sts_job WHERE id=:id FOR UPDATE").param("id", id.bytes()).query(String::class.java).single()
        if (status in setOf("CLOSED", "CANCELLED")) throw BusinessRuleException("JOB_LOCKED", "Reopen the job before changing finance records.")
    }

    private fun now() = com.ship2ship.core.audit.Uuids.toTimestamp(clock.instant())

    companion object {
        val KINDS = setOf("PO", "BILL")

        /** A one-sheet workbook: title row, header row, then the rows (numbers stay numbers). */
        internal fun workbook(sheetName: String, title: String, headers: List<String>, rows: List<List<Any?>>): ByteArray =
            Xlsx.workbook(sheetName, title, headers, rows)
    }
}

@RestController
@RequestMapping("/api/v1/jobs/{jobId}/costs")
class JobCostsController(private val service: JobCostsService) {
    @GetMapping fun list(@PathVariable jobId: UUID, @RequestParam page: Int?, @RequestParam size: Int?) =
        service.list(jobId, (page ?: 0).coerceAtLeast(0), (size ?: 25).coerceIn(1, 100))
    @GetMapping("/summary") fun summary(@PathVariable jobId: UUID) = service.summary(jobId)
    @GetMapping("/summary.xlsx") fun summaryXlsx(@PathVariable jobId: UUID): ResponseEntity<ByteArray> = service.summaryXlsx(jobId).let { (name, bytes) -> xlsx(name, bytes) }
    @GetMapping("/options") fun options(@PathVariable jobId: UUID, @RequestParam q: String?) = service.options(jobId, q ?: "")
    @PostMapping("/files") fun upload(@PathVariable jobId: UUID, @RequestParam file: MultipartFile) = service.upload(jobId, file)
    @PostMapping fun create(@PathVariable jobId: UUID, @RequestHeader("Idempotency-Key", required = false) key: String?, @RequestBody input: CostInput) =
        ResponseEntity.status(201).body(service.save(jobId, null, null, input, key))
    @PutMapping("/{id}") fun save(@PathVariable jobId: UUID, @PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) version: String?,
        @RequestBody input: CostInput) = service.save(jobId, id, Versioning.requireIfMatch(version), input)
    @PostMapping("/{id}/actions") fun action(@PathVariable jobId: UUID, @PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) version: String?,
        @RequestBody input: CostDecision) = service.action(jobId, id, Versioning.requireIfMatch(version), input)
}

/** Purchase orders and vendor bills across jobs (Finance & FDA lists). */
@RestController
@RequestMapping("/api/v1/finance/cost-documents")
class CostDocumentListController(private val service: JobCostsService) {
    @GetMapping fun list(@RequestParam kind: String?, @RequestParam status: String?, @RequestParam vendorId: UUID?, @RequestParam jobId: UUID?,
        @RequestParam q: String?, @RequestParam page: Int?, @RequestParam size: Int?) =
        service.search(kind, status, vendorId, jobId, q, (page ?: 0).coerceAtLeast(0), (size ?: 25).coerceIn(1, 100))
}

internal fun xlsx(name: String, bytes: ByteArray): ResponseEntity<ByteArray> = Xlsx.response(name, bytes)
