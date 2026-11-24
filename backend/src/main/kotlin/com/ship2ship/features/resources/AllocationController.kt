package com.ship2ship.features.resources

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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class AllocationCancelInput(val reason:String)

@RestController
@RequestMapping("/api/v1/jobs/{jobId}/resources/allocations")
class AllocationController(private val service:AllocationService) {
    @GetMapping("/context") fun context(@PathVariable jobId:UUID)=service.context(jobId)
    @GetMapping("/choices") fun choices(@PathVariable jobId:UUID,@RequestParam(required=false) q:String?)=service.choices(jobId,q)
    @GetMapping fun list(@PathVariable jobId:UUID,@RequestParam(required=false) page:Int?,
                         @RequestParam(required=false) size:Int?)=service.list(jobId,page?:0,size?:25)
    @GetMapping("/{id}") fun get(@PathVariable jobId:UUID,@PathVariable id:UUID)=
        service.get(jobId,id).let { Versioning.ok(it,it.version) }
    @PostMapping fun create(@PathVariable jobId:UUID,@RequestBody input:AllocationInput):ResponseEntity<AllocationDto> =
        service.create(jobId,input).let { ResponseEntity.status(201).eTag(Versioning.etag(it.version)).body(it) }
    @PutMapping("/{id}") fun amend(@PathVariable jobId:UUID,@PathVariable id:UUID,
        @RequestHeader(HttpHeaders.IF_MATCH,required=false) ifMatch:String?,@RequestBody input:AllocationInput)=
        service.amend(jobId,id,Versioning.requireIfMatch(ifMatch),input).let { Versioning.ok(it,it.version) }
    @PostMapping("/{id}/decision") fun decide(@PathVariable jobId:UUID,@PathVariable id:UUID,
        @RequestHeader(HttpHeaders.IF_MATCH,required=false) ifMatch:String?,@RequestBody input:AllocationDecisionInput)=
        service.decide(jobId,id,Versioning.requireIfMatch(ifMatch),input).let { Versioning.ok(it,it.version) }
    @PostMapping("/{id}/cancel") fun cancel(@PathVariable jobId:UUID,@PathVariable id:UUID,
        @RequestHeader(HttpHeaders.IF_MATCH,required=false) ifMatch:String?,@RequestBody input:AllocationCancelInput)=
        service.cancel(jobId,id,Versioning.requireIfMatch(ifMatch),input.reason).let { Versioning.ok(it,it.version) }
}
