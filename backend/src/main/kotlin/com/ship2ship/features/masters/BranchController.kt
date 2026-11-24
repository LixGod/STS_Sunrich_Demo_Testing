package com.ship2ship.features.masters

import com.ship2ship.core.web.PageResponse
import com.ship2ship.core.web.Paging
import com.ship2ship.core.web.Versioning
import jakarta.validation.Valid
import org.springframework.data.domain.Sort
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.util.UUID

@RestController
@RequestMapping("/api/v1/masters/branches")
class BranchController(private val service: BranchService, private val paging: Paging) {

    @GetMapping
    fun list(
        @RequestParam page: Int?, @RequestParam size: Int?, @RequestParam sort: String?,
        @RequestParam q: String?, @RequestParam active: Boolean?,
    ): PageResponse<BranchDto> =
        service.list(q, active, paging.pageable(page, size, sort, SORTS, Sort.by("name")))

    @PostMapping
    fun create(@Valid @RequestBody request: BranchRequest): ResponseEntity<BranchDto> {
        val created = service.create(request)
        val location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id).toUri()
        return ResponseEntity.created(location).eTag(Versioning.etag(created.version)).body(created)
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): ResponseEntity<BranchDto> = service.get(id).let { Versioning.ok(it, it.version) }

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: BranchRequest,
    ): ResponseEntity<BranchDto> =
        service.update(id, Versioning.requireIfMatch(ifMatch), request).let { Versioning.ok(it, it.version) }

    @PatchMapping("/{id}/status")
    fun changeStatus(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: ActiveStatusRequest,
    ): ResponseEntity<BranchDto> =
        service.changeStatus(id, Versioning.requireIfMatch(ifMatch), request).let { Versioning.ok(it, it.version) }

    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<Void> {
        service.delete(id, Versioning.requireIfMatch(ifMatch))
        return ResponseEntity.noContent().build()
    }

    private companion object {
        val SORTS = mapOf("code" to "code", "name" to "name", "active" to "active", "updatedAt" to "updatedAt")
    }
}
