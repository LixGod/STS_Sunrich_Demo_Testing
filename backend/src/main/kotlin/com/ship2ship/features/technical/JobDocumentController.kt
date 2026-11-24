package com.ship2ship.features.technical

import com.ship2ship.core.web.Versioning
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/api/v1/jobs/{jobId}/technical")
class JobDocumentController(private val service: JobDocumentService, private val voyage: JobVoyageService) {
    @GetMapping("/request")
    fun request(@PathVariable jobId: UUID): JobRequestDto = service.request(jobId)

    @PostMapping("/request")
    fun initialise(@PathVariable jobId: UUID): JobRequestDto = service.initialise(jobId)

    @GetMapping("/request/email-preview")
    fun email(@PathVariable jobId: UUID): EmailPreviewDto = service.emailPreview(jobId)

    @PatchMapping("/request/items/{itemId}")
    fun change(@PathVariable jobId: UUID, @PathVariable itemId: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH,required=false) ifMatch: String?,
        @RequestBody input: RequestItemChange): JobRequestDto =
        service.changeItem(jobId,itemId,Versioning.requireIfMatch(ifMatch),input)

    @PostMapping("/request/items")
    fun add(@PathVariable jobId: UUID,@RequestBody input: RequestItemAdd): JobRequestDto =
        service.addItem(jobId,input)

    @GetMapping("/documents")
    fun documents(@PathVariable jobId: UUID): List<JobDocumentDto> = service.documents(jobId)

    @PostMapping("/documents",consumes=[MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(@PathVariable jobId: UUID, @RequestParam(required=false) jobVesselId: UUID?,
        @RequestParam itemId: UUID, @RequestParam(required=false) documentTypeId: UUID?,
        @RequestParam(required=false) title: String?,
        @RequestParam(required=false, defaultValue="false") extractVoyage: Boolean,
        @RequestPart("file") file: MultipartFile): ResponseEntity<JobDocumentDto> {
        val doc = service.upload(jobId,jobVesselId,itemId,documentTypeId ?: UUID(0, 0),title,file)
        if (extractVoyage && jobVesselId != null) {
            voyage.populateFromDocument(jobId, jobVesselId, doc.id)
        }
        return ResponseEntity.status(201).body(doc)
    }
}

@RestController
@RequestMapping("/api/v1/technical/request-templates")
class RequestTemplateController(private val service: JobDocumentService) {
    @GetMapping
    fun list(): List<RequestTemplateDto> = service.templates()

    @PostMapping
    fun create(@RequestBody input: RequestTemplateInput): ResponseEntity<RequestTemplateDto> =
        ResponseEntity.status(201).body(service.createTemplate(input))

    @PutMapping("/{id}")
    fun revise(@PathVariable id: UUID,@RequestHeader(HttpHeaders.IF_MATCH,required=false) ifMatch: String?,
        @RequestBody input: RequestTemplateInput): ResponseEntity<RequestTemplateDto> =
        service.reviseTemplate(id,Versioning.requireIfMatch(ifMatch),input).let { Versioning.ok(it,it.version) }

    @PostMapping("/{id}/publish")
    fun publish(@PathVariable id: UUID,@RequestHeader(HttpHeaders.IF_MATCH,required=false) ifMatch: String?,
        @RequestBody input: RequestTemplatePublish):
        ResponseEntity<RequestTemplateDto> = service.publishTemplate(id,Versioning.requireIfMatch(ifMatch),input)
            .let { Versioning.ok(it,it.version) }
}
