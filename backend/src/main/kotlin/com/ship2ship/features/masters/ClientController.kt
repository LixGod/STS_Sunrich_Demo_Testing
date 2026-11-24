package com.ship2ship.features.masters

import com.ship2ship.core.web.PageResponse
import com.ship2ship.core.web.Paging
import com.ship2ship.core.web.Versioning
import jakarta.validation.Valid
import org.springframework.data.domain.Sort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
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
@RequestMapping("/api/v1/masters/clients")
class ClientController(private val service: ClientService, private val paging: Paging) {

    @GetMapping
    fun list(
        @RequestParam page: Int?, @RequestParam size: Int?, @RequestParam sort: String?,
        @RequestParam q: String?, @RequestParam active: Boolean?, @RequestParam role: ClientRole?,
    ): PageResponse<ClientDto> = service.list(q, active, role, paging.pageable(page, size, sort, SORTS, Sort.by("legalName")))

    @PostMapping
    fun create(@Valid @RequestBody request: ClientRequest): ResponseEntity<ClientDto> {
        val created = service.create(request)
        val location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id).toUri()
        return ResponseEntity.created(location).eTag(Versioning.etag(created.version)).body(created)
    }

    @PostMapping("/quick-add")
    fun quickCreate(@Valid @RequestBody request: QuickClientRequest): ResponseEntity<QuickClientDto> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.quickCreate(request))

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): ResponseEntity<ClientDto> = service.get(id).let { Versioning.ok(it, it.version) }

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: ClientRequest,
    ): ResponseEntity<ClientDto> = service.update(id, Versioning.requireIfMatch(ifMatch), request).let { Versioning.ok(it, it.version) }

    @PatchMapping("/{id}/status")
    fun changeStatus(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: ActiveStatusRequest,
    ): ResponseEntity<ClientDto> = service.changeStatus(id, Versioning.requireIfMatch(ifMatch), request).let { Versioning.ok(it, it.version) }

    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<Void> {
        service.delete(id, Versioning.requireIfMatch(ifMatch))
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{id}/contacts")
    fun contacts(@PathVariable id: UUID): List<ClientContactDto> = service.contacts(id)

    @PostMapping("/{id}/contacts")
    fun addContact(@PathVariable id: UUID, @Valid @RequestBody request: ClientContactRequest): ResponseEntity<ClientContactDto> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.addContact(id, request))

    @PutMapping("/{id}/contacts/{contactId}")
    fun updateContact(
        @PathVariable id: UUID, @PathVariable contactId: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: ClientContactRequest,
    ): ClientContactDto = service.updateContact(id, contactId, Versioning.requireIfMatch(ifMatch), request)

    @PatchMapping("/{id}/contacts/{contactId}/status")
    fun changeContactStatus(
        @PathVariable id: UUID, @PathVariable contactId: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: ActiveStatusRequest,
    ): ClientContactDto = service.changeContactStatus(id, contactId, Versioning.requireIfMatch(ifMatch), request)

    @DeleteMapping("/{id}/contacts/{contactId}")
    fun deleteContact(
        @PathVariable id: UUID, @PathVariable contactId: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<Void> {
        service.deleteContact(id, contactId, Versioning.requireIfMatch(ifMatch))
        return ResponseEntity.noContent().build()
    }

    private companion object {
        val SORTS = mapOf("code" to "code", "legalName" to "legalName", "active" to "active", "updatedAt" to "updatedAt")
    }
}
