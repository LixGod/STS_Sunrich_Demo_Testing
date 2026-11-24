package com.ship2ship.features.commercial

import com.ship2ship.core.web.Versioning
import jakarta.validation.Valid
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
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Quotation endpoints. Actions on a version carry the version's `If-Match`; each returns the whole quotation overview
 * so the screen can redraw from one response.
 */
@RestController
@RequestMapping("/api/v1")
class QuotationController(private val service: QuotationService, private val renderer: QuotationPdfRenderer) {

    @GetMapping("/enquiries/{enquiryId}/quotation")
    fun overview(@PathVariable enquiryId: UUID): QuotationOverviewDto = service.overview(enquiryId)

    @PostMapping("/enquiries/{enquiryId}/quotation/versions")
    fun createVersion(@PathVariable enquiryId: UUID, @Valid @RequestBody request: NewQuotationVersionRequest): ResponseEntity<QuotationOverviewDto> =
        ResponseEntity.status(201).body(service.createVersion(enquiryId, request))

    @GetMapping("/quotation-versions/pending-approval")
    fun pendingApproval(): List<PendingApprovalDto> = service.pendingApproval()

    @PutMapping("/quotation-versions/{id}")
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: QuotationVersionRequest,
    ): QuotationOverviewDto = service.updateVersion(id, Versioning.requireIfMatch(ifMatch), request)

    @PostMapping("/quotation-versions/{id}/submit")
    fun submit(@PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?): QuotationOverviewDto =
        service.submit(id, Versioning.requireIfMatch(ifMatch))

    @PostMapping("/quotation-versions/{id}/decision")
    fun decide(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: DecisionRequest,
    ): QuotationOverviewDto = service.decide(id, Versioning.requireIfMatch(ifMatch), request)

    @PostMapping("/quotation-versions/{id}/issue")
    fun issue(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: IssueRequest,
    ): QuotationOverviewDto = service.issue(id, Versioning.requireIfMatch(ifMatch), request)

    /** Repeating the request with the same `Idempotency-Key` returns the first result instead of creating a second job. */
    @PostMapping("/quotation-versions/{id}/acceptance")
    fun accept(
        @PathVariable id: UUID,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @Valid @RequestBody request: AcceptanceRequest,
    ): QuotationOverviewDto = service.accept(id, request, idempotencyKey)

    @PostMapping("/quotation-versions/{id}/owner-rejection")
    fun rejectByOwner(
        @PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): QuotationOverviewDto = service.rejectByOwner(id, Versioning.requireIfMatch(ifMatch))

    @PostMapping("/quotation-versions/{id}/advances")
    fun recordAdvance(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: RecordAdvanceRequest,
    ): QuotationOverviewDto = service.recordAdvance(id, Versioning.requireIfMatch(ifMatch), request)

    /** The current PDF; replaced issued copies remain available through pdf-revisions. */
    @GetMapping("/quotation-versions/{id}/pdf")
    fun pdf(@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val (name, bytes) = service.pdf(id)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(name).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(bytes)
    }

    @GetMapping("/quotation-versions/{id}/pdf/pages")
    fun pdfPages(@PathVariable id: UUID): Map<String, Int> = mapOf("pages" to renderer.pageCount(service.pdf(id).second))

    @GetMapping("/quotation-versions/{id}/pdf-revisions")
    fun pdfRevisions(@PathVariable id: UUID): List<QuotationService.PdfRevisionDto> = service.pdfRevisions(id)

    @GetMapping("/quotation-versions/{id}/pdf-revisions/{revisionId}")
    fun pdfRevision(@PathVariable id: UUID, @PathVariable revisionId: UUID): ResponseEntity<ByteArray> =
        ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(service.pdfRevision(id, revisionId))

    @GetMapping("/quotation-versions/{id}/pdf/preview/{page}")
    fun pdfPreview(@PathVariable id: UUID, @PathVariable page: Int): ResponseEntity<ByteArray> =
        ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(renderer.previewPage(service.pdf(id).second, page))
}
