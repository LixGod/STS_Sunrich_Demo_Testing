package com.ship2ship.features.finance

import com.ship2ship.core.audit.Uuids
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

// Client invoicing and receipts (increment 5a, FR-J11, D06). Money is BigDecimal, rounded only to the currency's minor
// units; one currency per invoice and receipt; nothing issued is changed or deleted.

enum class InvoiceStatus { DRAFT, SUBMITTED, APPROVED, ISSUED }

enum class InvoiceAction { EDIT, SUBMIT, APPROVE, RETURN, ISSUE, CREDIT }

data class InvoiceLineInput(
    val source: String = "MANUAL",
    val costHeadCode: String = "",
    val description: String? = null,
    val quantity: BigDecimal? = null,
    val unitPrice: BigDecimal? = null,
    val extraHours: Boolean = false,
)

data class InvoiceInput(
    val invoiceDate: LocalDate? = null,
    val dueDate: LocalDate? = null,
    val billingName: String = "",
    val billingAddress: String? = null,
    val paymentTerms: String? = null,
    val notes: String? = null,
    val lines: List<InvoiceLineInput> = emptyList(),
)

data class InvoiceLineDto(
    val lineNo: Int, val source: String, val costHeadCode: String, val costHeadName: String, val description: String?,
    val quantity: BigDecimal, val unitPrice: BigDecimal, val amount: BigDecimal, val taxCode: String?,
    val taxRate: BigDecimal?, val taxAmount: BigDecimal,
    val extraHours: Boolean = false,
)

data class InvoiceDto(
    val id: UUID,
    val docType: String,
    val number: String?,
    val status: InvoiceStatus,
    val jobId: UUID,
    val jobNumber: String,
    val clientId: UUID,
    val clientName: String?,
    val creditedInvoiceId: UUID?,
    val creditedInvoiceNumber: String?,
    val currency: String,
    val minorUnits: Int,
    val invoiceDate: LocalDate,
    val dueDate: LocalDate?,
    val billingName: String,
    val billingAddress: String?,
    val paymentTerms: String?,
    val notes: String?,
    val lines: List<InvoiceLineDto>,
    val netAmount: BigDecimal,
    val taxAmount: BigDecimal,
    val grossAmount: BigDecimal,
    /** Issued credit notes against this invoice (invoices only). */
    val creditedAmount: BigDecimal,
    /** Active receipt allocations (invoices only). */
    val receivedAmount: BigDecimal,
    val outstandingAmount: BigDecimal,
    val blockers: List<FinanceIssue>,
    val submittedByName: String?,
    val submittedAt: Instant?,
    val decidedByName: String?,
    val decidedAt: Instant?,
    val returnedReason: String?,
    val issuedByName: String?,
    val issuedAt: Instant?,
    val pdfSha256: String?,
    val allowedActions: List<InvoiceAction>,
    val version: Long,
)

data class InvoiceSummaryDto(
    val id: UUID, val docType: String, val number: String?, val status: InvoiceStatus, val jobId: UUID, val jobNumber: String,
    val clientName: String?, val currency: String, val invoiceDate: LocalDate, val dueDate: LocalDate?, val grossAmount: BigDecimal,
    val outstandingAmount: BigDecimal, val overdueDays: Int?, val minorUnits: Int,
)

data class FinanceIssue(val code: String, val message: String)

data class InvoiceDecision(val decision: String = "", val reason: String? = null)

data class NewInvoiceRequest(val fromQuotation: Boolean = true)

data class CostHeadOption(val code: String, val name: String, val kind: String)

data class JobInvoicesDto(
    val jobId: UUID, val jobNumber: String, val currency: String, val invoices: List<InvoiceSummaryDto>, val canCreate: Boolean,
    val hasAcceptedQuotation: Boolean,
)

// ---------- receipts ----------

data class AllocationInput(val invoiceId: UUID? = null, val amount: BigDecimal? = null)

data class ReceiptInput(
    val clientId: UUID? = null,
    val receivedOn: LocalDate? = null,
    val amount: BigDecimal? = null,
    val currency: String = "",
    val method: String = "BANK_TRANSFER",
    val reference: String? = null,
    val allocations: List<AllocationInput> = emptyList(),
)

data class AllocationDto(val id: UUID, val invoiceId: UUID, val invoiceNumber: String?, val amount: BigDecimal, val reversed: Boolean)

data class ReceiptDto(
    val id: UUID, val clientId: UUID, val clientName: String?, val receivedOn: LocalDate, val amount: BigDecimal,
    val currency: String, val method: String, val reference: String?, val status: String, val allocatedAmount: BigDecimal,
    val onAccountAmount: BigDecimal, val allocations: List<AllocationDto>, val recordedByName: String?, val recordedAt: Instant,
    val reversalReason: String?, val canWrite: Boolean, val minorUnits: Int, val version: Long,
)

/** A client with open issued invoices in a currency: who a receipt can be recorded for. */
data class ReceiptParty(
    val clientId: UUID, val clientName: String?, val currency: String, val outstandingAmount: BigDecimal,
    val minorUnits: Int,
)

data class AllocationsRequest(val allocations: List<AllocationInput> = emptyList())

data class ReverseRequest(val reason: String = "")

// ---------- receivables ----------

data class ReceivableRow(
    val invoiceId: UUID, val number: String?, val clientId: UUID, val clientName: String?, val jobNumber: String, val currency: String,
    val invoiceDate: LocalDate, val dueDate: LocalDate?, val grossAmount: BigDecimal, val creditedAmount: BigDecimal,
    val receivedAmount: BigDecimal, val outstandingAmount: BigDecimal, val overdueDays: Int, val bucket: String, val minorUnits: Int,
)

data class ReceivableTotal(val clientName: String?, val currency: String, val buckets: Map<String, BigDecimal>, val total: BigDecimal, val minorUnits: Int)

data class ReceivablesDto(val asOf: LocalDate, val rows: List<ReceivableRow>, val totals: List<ReceivableTotal>)
data class ReceivablesPageDto(val asOf: LocalDate, val rows: com.ship2ship.core.web.PageResponse<ReceivableRow>, val totals: List<ReceivableTotal>)
data class ReceivablesSummaryDto(val asOf: LocalDate, val totals: List<ReceivableTotal>)

internal fun UUID.bytes(): ByteArray = Uuids.toBytes(this)
internal fun ByteArray?.uuid(): UUID? = Uuids.fromBytes(this)
internal fun LocalDateTime?.instant(): Instant? = this?.let(Uuids::fromTimestamp)
internal fun Instant.stamp(): LocalDateTime = Uuids.toTimestamp(this)
