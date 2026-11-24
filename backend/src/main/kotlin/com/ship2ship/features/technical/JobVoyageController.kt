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
@RequestMapping("/api/v1/jobs/{jobId}/technical")
class JobVoyageController(private val service: JobVoyageService) {
    @GetMapping("/voyage-fields") fun fields(@PathVariable jobId: UUID): List<VoyageFieldDto> = service.fields(jobId)
    @GetMapping("/vessels/{jobVesselId}/voyage")
    fun overview(@PathVariable jobId: UUID,@PathVariable jobVesselId: UUID): VoyageOverviewDto =
        service.overview(jobId,jobVesselId)
    @PostMapping("/vessels/{jobVesselId}/voyage")
    fun create(@PathVariable jobId: UUID,@PathVariable jobVesselId: UUID): ResponseEntity<VoyageDto> =
        ResponseEntity.status(201).body(service.create(jobId,jobVesselId))
    @PostMapping("/vessels/{jobVesselId}/voyage/from-document")
    fun populateFromDocument(@PathVariable jobId: UUID,@PathVariable jobVesselId: UUID): VoyageDto =
        service.populateFromDocument(jobId,jobVesselId)
    @PutMapping("/vessels/{jobVesselId}/voyage/from-document")
    fun saveFromDocument(@PathVariable jobId: UUID,@PathVariable jobVesselId: UUID,@RequestBody input: VoyageUpdate): VoyageDto =
        service.saveFromDocument(jobId,jobVesselId,input)
    @GetMapping("/voyage/{id}") fun get(@PathVariable jobId: UUID,@PathVariable id: UUID): ResponseEntity<VoyageDto> =
        service.get(jobId,id).let { Versioning.ok(it,it.version) }
    @PutMapping("/voyage/{id}")
    fun update(@PathVariable jobId: UUID,@PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH,required=false) ifMatch: String?,@RequestBody input: VoyageUpdate):
        ResponseEntity<VoyageDto> = service.update(jobId,id,Versioning.requireIfMatch(ifMatch),input)
            .let { Versioning.ok(it,it.version) }
    @PostMapping("/voyage/{id}/submit")
    fun submit(@PathVariable jobId: UUID,@PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH,required=false) ifMatch: String?): ResponseEntity<VoyageDto> =
        service.submit(jobId,id,Versioning.requireIfMatch(ifMatch)).let { Versioning.ok(it,it.version) }
    @PostMapping("/voyage/{id}/verify")
    fun verify(@PathVariable jobId: UUID,@PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH,required=false) ifMatch: String?,@RequestBody input: VoyageVerify):
        ResponseEntity<VoyageDto> = service.verify(jobId,id,Versioning.requireIfMatch(ifMatch),input)
            .let { Versioning.ok(it,it.version) }
    @PostMapping("/voyage/{id}/return")
    fun returnForCorrection(@PathVariable jobId: UUID,@PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH,required=false) ifMatch: String?,@RequestBody input: VoyageReturn):
        ResponseEntity<VoyageDto> = service.returnForCorrection(jobId,id,Versioning.requireIfMatch(ifMatch),input)
            .let { Versioning.ok(it,it.version) }
    @GetMapping("/review") fun review(@PathVariable jobId: UUID): CompatibilityReviewDto = service.review(jobId)
}

@RestController
@RequestMapping("/api/v1/technical/compatibility-rules")
class CompatibilityRuleController(private val service: JobVoyageService) {
    @GetMapping("/{code}") fun get(@PathVariable code: String): ResponseEntity<CompatibilityRuleDto> =
        service.rule(code).let { Versioning.ok(it,it.version) }
    @PutMapping("/{code}") fun update(@PathVariable code: String,
        @RequestHeader(HttpHeaders.IF_MATCH,required=false) ifMatch: String?,@RequestBody input: CompatibilityRuleInput):
        ResponseEntity<CompatibilityRuleDto> = service.updateRule(code,Versioning.requireIfMatch(ifMatch),input)
            .let { Versioning.ok(it,it.version) }
}
