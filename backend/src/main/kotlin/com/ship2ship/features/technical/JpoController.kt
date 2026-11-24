package com.ship2ship.features.technical

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

data class JpoDecisionRequest(val decision: String = "", val reason: String? = null)

/** JPO endpoints; actions on a revision carry its `If-Match` and return the whole revision. */
@RestController
@RequestMapping("/api/v1/jobs/{jobId}/jpo")
class JpoController(private val service: JpoService) {

    @GetMapping
    fun overview(@PathVariable jobId: UUID): JpoOverviewDto = service.overview(jobId)

    @PostMapping("/revisions")
    fun create(@PathVariable jobId: UUID): ResponseEntity<JpoRevisionDto> =
        service.create(jobId).let { ResponseEntity.status(201).eTag(Versioning.etag(it.version)).body(it) }

    @GetMapping("/revisions/{id}")
    fun get(@PathVariable jobId: UUID, @PathVariable id: UUID): ResponseEntity<JpoRevisionDto> =
        service.get(jobId, id).let { Versioning.ok(it, it.version) }

    @PutMapping("/revisions/{id}")
    fun update(
        @PathVariable jobId: UUID, @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?, @RequestBody content: JpoContent,
    ): ResponseEntity<JpoRevisionDto> = service.update(jobId, id, Versioning.requireIfMatch(ifMatch), content).let { Versioning.ok(it, it.version) }

    @PostMapping("/revisions/{id}/submit")
    fun submit(
        @PathVariable jobId: UUID, @PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<JpoRevisionDto> = service.submit(jobId, id, Versioning.requireIfMatch(ifMatch)).let { Versioning.ok(it, it.version) }

    /** `decision`: APPROVE, or RETURN with a reason. */
    @PostMapping("/revisions/{id}/decision")
    fun decide(
        @PathVariable jobId: UUID, @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?, @RequestBody request: JpoDecisionRequest,
    ): ResponseEntity<JpoRevisionDto> {
        val version = Versioning.requireIfMatch(ifMatch)
        val result = when (request.decision) {
            "APPROVE" -> service.approve(jobId, id, version)
            "RETURN" -> service.returnForCorrection(jobId, id, version, JpoReturnRequest(request.reason.orEmpty()))
            else -> throw com.ship2ship.core.web.BadRequestException("VALIDATION_FAILED", "The request has invalid fields.",
                listOf(com.ship2ship.core.web.FieldError("decision", "allowed", "APPROVE or RETURN.")))
        }
        return Versioning.ok(result, result.version)
    }

    @PostMapping("/revisions/{id}/issue")
    fun issue(
        @PathVariable jobId: UUID, @PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<JpoRevisionDto> = service.issue(jobId, id, Versioning.requireIfMatch(ifMatch)).let { Versioning.ok(it, it.version) }

    @PostMapping("/revisions/{id}/acceptances")
    fun accept(@PathVariable jobId: UUID, @PathVariable id: UUID, @RequestBody request: JpoAcceptanceRequest): ResponseEntity<JpoRevisionDto> =
        service.accept(jobId, id, request).let { Versioning.ok(it, it.version) }

    /** The issued PDF (immutable) or, before issue, a draft rendering with a DRAFT watermark. */
    @GetMapping("/revisions/{id}/pdf")
    fun pdf(@PathVariable jobId: UUID, @PathVariable id: UUID): ResponseEntity<ByteArray> {
        val (name, bytes) = service.pdf(jobId, id)
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(name).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(bytes)
    }

    @GetMapping("/revisions/{id}/xlsx")
    fun xlsx(@PathVariable jobId: UUID, @PathVariable id: UUID): ResponseEntity<ByteArray> {
        val (name, bytes) = service.xlsx(jobId, id)
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(XLSX))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(bytes)
    }

    companion object {
        const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
}
