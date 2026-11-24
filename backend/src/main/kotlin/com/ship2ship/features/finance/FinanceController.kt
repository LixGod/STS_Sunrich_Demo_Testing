package com.ship2ship.features.finance

import com.ship2ship.core.web.PageResponse
import com.ship2ship.core.web.Versioning
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

/** A job's invoices: the overview and creating a draft from the accepted quotation. */
@RestController
@RequestMapping("/api/v1/jobs/{jobId}/invoices")
class JobInvoiceController(private val service: InvoiceService) {

    @GetMapping
    fun overview(@PathVariable jobId: UUID): JobInvoicesDto = service.forJob(jobId)

    @PostMapping
    fun create(@PathVariable jobId: UUID, @RequestBody(required = false) request: NewInvoiceRequest?): ResponseEntity<InvoiceDto> =
        service.create(jobId, request ?: NewInvoiceRequest()).let { ResponseEntity.status(201).eTag(Versioning.etag(it.version)).body(it) }
}

/** Invoices and credit notes; actions carry the document's `If-Match` and return the whole document. */
@RestController
@RequestMapping("/api/v1/finance/invoices")
class InvoiceController(private val service: InvoiceService) {

    @GetMapping
    fun list(
        @RequestParam status: InvoiceStatus?, @RequestParam docType: String?, @RequestParam clientId: UUID?, @RequestParam jobId: UUID?,
        @RequestParam overdue: Boolean?, @RequestParam q: String?, @RequestParam page: Int?, @RequestParam size: Int?,
    ): PageResponse<InvoiceSummaryDto> =
        service.list(status?.name, docType, clientId, jobId, overdue == true, q, (page ?: 0).coerceAtLeast(0), (size ?: 25).coerceIn(1, 100))

    @GetMapping("/options")
    fun options(): Map<String, Any> = mapOf("costHeads" to service.options())

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): ResponseEntity<InvoiceDto> = service.get(id).let { Versioning.ok(it, it.version) }

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?, @RequestBody input: InvoiceInput):
        ResponseEntity<InvoiceDto> = service.update(id, Versioning.requireIfMatch(ifMatch), input).let { Versioning.ok(it, it.version) }

    @PostMapping("/{id}/submit")
    fun submit(@PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?): ResponseEntity<InvoiceDto> =
        service.submit(id, Versioning.requireIfMatch(ifMatch)).let { Versioning.ok(it, it.version) }

    /** `decision`: APPROVE, or RETURN with a reason. */
    @PostMapping("/{id}/decision")
    fun decide(@PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?, @RequestBody request: InvoiceDecision):
        ResponseEntity<InvoiceDto> = service.decide(id, Versioning.requireIfMatch(ifMatch), request).let { Versioning.ok(it, it.version) }

    @PostMapping("/{id}/issue")
    fun issue(@PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?): ResponseEntity<InvoiceDto> =
        service.issue(id, Versioning.requireIfMatch(ifMatch)).let { Versioning.ok(it, it.version) }

    @PostMapping("/{id}/credit-notes")
    fun creditNote(@PathVariable id: UUID): ResponseEntity<InvoiceDto> =
        service.createCreditNote(id).let { ResponseEntity.status(201).eTag(Versioning.etag(it.version)).body(it) }

    /** The issued PDF (immutable) or a DRAFT rendering before issue. */
    @GetMapping("/{id}/pdf")
    fun pdf(@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val (name, bytes) = service.pdf(id)
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(name).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(bytes)
    }
}

@RestController
@RequestMapping("/api/v1/finance/receipts")
class ReceiptController(private val service: ReceiptService) {

    @GetMapping
    fun list(@RequestParam clientId: UUID?, @RequestParam status: String?, @RequestParam q: String?, @RequestParam page: Int?, @RequestParam size: Int?):
        PageResponse<ReceiptDto> = service.list(clientId, status, q, (page ?: 0).coerceAtLeast(0), (size ?: 25).coerceIn(1, 100))

    @GetMapping("/parties")
    fun parties(): List<ReceiptParty> = service.parties()

    @GetMapping("/open-invoices")
    fun openInvoices(@RequestParam clientId: UUID, @RequestParam currency: String): List<InvoiceSummaryDto> =
        service.openInvoices(clientId, currency)

    @PostMapping
    fun create(@RequestBody input: ReceiptInput): ResponseEntity<ReceiptDto> =
        service.create(input).let { ResponseEntity.status(201).eTag(Versioning.etag(it.version)).body(it) }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): ResponseEntity<ReceiptDto> = service.get(id).let { Versioning.ok(it, it.version) }

    @PostMapping("/{id}/allocations")
    fun allocate(@PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?, @RequestBody request: AllocationsRequest):
        ResponseEntity<ReceiptDto> = service.addAllocations(id, Versioning.requireIfMatch(ifMatch), request).let { Versioning.ok(it, it.version) }

    @PostMapping("/{id}/reverse")
    fun reverse(@PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?, @RequestBody request: ReverseRequest):
        ResponseEntity<ReceiptDto> = service.reverse(id, Versioning.requireIfMatch(ifMatch), request).let { Versioning.ok(it, it.version) }
}

@RestController
@RequestMapping("/api/v1/finance/receivables")
class ReceivablesController(private val service: ReceiptService) {

    @GetMapping("/page")
    fun page(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) asOf: LocalDate?, @RequestParam clientId: UUID?, @RequestParam currency: String?,
        @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "25") size: Int,
    ): ReceivablesPageDto = service.receivablesPage(asOf, clientId, currency, page, size)

    @GetMapping("/summary")
    fun summary(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) asOf: LocalDate?, @RequestParam clientId: UUID?, @RequestParam currency: String?,
    ): ReceivablesSummaryDto = service.receivablesSummary(asOf, clientId, currency)

    @GetMapping
    fun receivables(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) asOf: LocalDate?, @RequestParam clientId: UUID?, @RequestParam currency: String?,
    ): ReceivablesDto = service.receivables(asOf, clientId, currency)

    @GetMapping("/xlsx")
    fun xlsx(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) asOf: LocalDate?, @RequestParam clientId: UUID?, @RequestParam currency: String?,
    ): ResponseEntity<ByteArray> {
        val (name, bytes) = service.receivablesXlsx(asOf, clientId, currency)
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(XLSX))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(bytes)
    }

    companion object {
        const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
}
