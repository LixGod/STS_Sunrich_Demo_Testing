package com.ship2ship.features.technical

import com.ship2ship.core.web.Versioning
import org.springframework.http.HttpHeaders
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

@RestController
@RequestMapping("/api/v1/technical/checklist-templates")
class ChecklistTemplateController(private val service: ChecklistService) {
    @GetMapping fun list(): List<ChecklistTemplateDto> = service.templates()
    @PostMapping fun create(@RequestBody input: ChecklistTemplateInput): ResponseEntity<ChecklistTemplateDto> =
        ResponseEntity.status(201).body(service.createTemplate(input))
    @PutMapping("/{id}") fun revise(@PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH,required=false) ifMatch: String?,@RequestBody input: ChecklistTemplateInput):
        ResponseEntity<ChecklistTemplateDto> = service.reviseTemplate(id,Versioning.requireIfMatch(ifMatch),input)
            .let { Versioning.ok(it,it.version) }
    @PostMapping("/{id}/publish")
    fun publish(@PathVariable id: UUID,@RequestHeader(HttpHeaders.IF_MATCH,required=false) ifMatch: String?):
        ResponseEntity<ChecklistTemplateDto> = service.publish(id,Versioning.requireIfMatch(ifMatch))
            .let { Versioning.ok(it,it.version) }
}

@RestController
@RequestMapping("/api/v1/jobs/{jobId}/technical/checklists")
class JobChecklistController(private val service: ChecklistService) {
    @GetMapping fun list(@PathVariable jobId: UUID): List<ChecklistResponseDto> = service.forJob(jobId)
    @PostMapping fun create(@PathVariable jobId: UUID,@RequestBody input: ChecklistResponseInput):
        ResponseEntity<ChecklistResponseDto> = ResponseEntity.status(201).body(service.create(jobId,input))
    @GetMapping("/{id}") fun get(@PathVariable jobId: UUID,@PathVariable id: UUID): ResponseEntity<ChecklistResponseDto> =
        service.get(jobId,id).let { Versioning.ok(it,it.version) }
    @PutMapping("/{id}") fun update(@PathVariable jobId: UUID,@PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH,required=false) ifMatch: String?,
        @RequestBody input: ChecklistResponseUpdate): ResponseEntity<ChecklistResponseDto> =
        service.update(jobId,id,Versioning.requireIfMatch(ifMatch),input).let { Versioning.ok(it,it.version) }
}
