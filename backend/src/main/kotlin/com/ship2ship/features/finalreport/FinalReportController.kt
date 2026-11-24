package com.ship2ship.features.finalreport

import com.ship2ship.core.web.Versioning
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

/** Final report endpoints; actions carry the revision's `If-Match` and return the whole revision. */
@RestController
@RequestMapping("/api/v1/jobs/{jobId}/final-report")
class FinalReportController(private val service: FinalReportService) {

    @GetMapping
    fun overview(@PathVariable jobId: UUID): ReportOverviewDto = service.overview(jobId)

    @PostMapping("/revisions")
    fun create(@PathVariable jobId: UUID): ResponseEntity<ReportRevisionDto> =
        service.create(jobId).let { ResponseEntity.status(201).eTag(Versioning.etag(it.version)).body(it) }

    @GetMapping("/revisions/{id}")
    fun get(@PathVariable jobId: UUID, @PathVariable id: UUID): ResponseEntity<ReportRevisionDto> =
        service.get(jobId, id).let { Versioning.ok(it, it.version) }

    @PutMapping("/revisions/{id}")
    fun update(
        @PathVariable jobId: UUID, @PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @RequestBody content: ReportContent,
    ): ResponseEntity<ReportRevisionDto> = service.update(jobId, id, Versioning.requireIfMatch(ifMatch), content).let { Versioning.ok(it, it.version) }

    @PostMapping("/revisions/{id}/regenerate-summary")
    fun regenerate(@PathVariable jobId: UUID, @PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?):
        ResponseEntity<ReportRevisionDto> = service.regenerateSummary(jobId, id, Versioning.requireIfMatch(ifMatch)).let { Versioning.ok(it, it.version) }

    @PostMapping("/revisions/{id}/submit")
    fun submit(@PathVariable jobId: UUID, @PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?):
        ResponseEntity<ReportRevisionDto> = service.submit(jobId, id, Versioning.requireIfMatch(ifMatch)).let { Versioning.ok(it, it.version) }

    /** `decision`: APPROVE, or RETURN with a reason. */
    @PostMapping("/revisions/{id}/decision")
    fun decide(
        @PathVariable jobId: UUID, @PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @RequestBody request: ReportDecision,
    ): ResponseEntity<ReportRevisionDto> = service.decide(jobId, id, Versioning.requireIfMatch(ifMatch), request).let { Versioning.ok(it, it.version) }

    @PostMapping("/revisions/{id}/issue")
    fun issue(@PathVariable jobId: UUID, @PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?):
        ResponseEntity<ReportRevisionDto> = service.issue(jobId, id, Versioning.requireIfMatch(ifMatch)).let { Versioning.ok(it, it.version) }

    /** The issued PDF (immutable) or a DRAFT rendering before issue. */
    @GetMapping("/revisions/{id}/pdf")
    fun pdf(@PathVariable jobId: UUID, @PathVariable id: UUID): ResponseEntity<ByteArray> {
        val (name, bytes) = service.pdf(jobId, id)
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(name).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(bytes)
    }
}
