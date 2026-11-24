package com.ship2ship.features.commercial

import com.ship2ship.core.web.PageResponse
import com.ship2ship.core.web.Paging
import com.ship2ship.core.web.Versioning
import com.ship2ship.core.web.Xlsx
import jakarta.validation.Valid
import org.springframework.data.domain.Sort
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpHeaders
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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/v1/enquiries")
class EnquiryController(private val service: EnquiryService, private val paging: Paging) {

    @GetMapping
    fun list(
        @RequestParam page: Int?, @RequestParam size: Int?, @RequestParam sort: String?,
        @RequestParam q: String?, @RequestParam status: EnquiryStatus?, @RequestParam clientId: UUID?,
        @RequestParam branchId: UUID?,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) receivedFrom: LocalDate?,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) receivedTo: LocalDate?,
        @RequestParam timeZone: String?,
    ): PageResponse<EnquirySummaryDto> = service.list(
        EnquiryFilters(q, status, clientId, branchId, receivedFrom, receivedTo, timeZone),
        paging.pageable(page, size, sort, SORTS, Sort.by(Sort.Order.desc("receivedAt"), Sort.Order.desc("createdAt"))),
    )

    @GetMapping("/xlsx")
    fun exportXlsx(
        @RequestParam sort: String?,
        @RequestParam q: String?, @RequestParam status: EnquiryStatus?, @RequestParam clientId: UUID?,
        @RequestParam branchId: UUID?,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) receivedFrom: LocalDate?,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) receivedTo: LocalDate?,
        @RequestParam timeZone: String?,
    ): ResponseEntity<ByteArray> = service.exportXlsx(
        EnquiryFilters(q, status, clientId, branchId, receivedFrom, receivedTo, timeZone),
        paging.pageable(0, 1, sort, SORTS, Sort.by(Sort.Order.desc("receivedAt"), Sort.Order.desc("createdAt"))).sort,
    ).let { (name, bytes) -> Xlsx.response(name, bytes) }

    /** Repeating the request with the same `Idempotency-Key` returns the enquiry created the first time. */
    @PostMapping
    fun create(
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @Valid @RequestBody request: EnquiryRequest,
    ): ResponseEntity<EnquiryDetailDto> {
        val created = service.create(request, idempotencyKey)
        val location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id).toUri()
        return ResponseEntity.created(location).eTag(Versioning.etag(created.version)).body(created)
    }

    @GetMapping("/duplicates")
    fun duplicates(
        @RequestParam motherVesselId: UUID, @RequestParam daughterVesselId: UUID, @RequestParam excludeId: UUID?,
    ): List<EnquirySummaryDto> = service.duplicates(motherVesselId, daughterVesselId, excludeId)

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): ResponseEntity<EnquiryDetailDto> = service.get(id).let { Versioning.ok(it, it.version) }

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: EnquiryRequest,
    ): ResponseEntity<EnquiryDetailDto> = service.update(id, Versioning.requireIfMatch(ifMatch), request).let { Versioning.ok(it, it.version) }

    @PostMapping("/{id}/transitions")
    fun transition(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: TransitionRequest,
    ): ResponseEntity<EnquiryDetailDto> = service.transition(id, Versioning.requireIfMatch(ifMatch), request).let { Versioning.ok(it, it.version) }

    private companion object {
        val SORTS = mapOf("reference" to "reference", "receivedAt" to "receivedAt", "status" to "status", "updatedAt" to "updatedAt")
    }
}
