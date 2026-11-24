package com.ship2ship.features.movements

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
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/api/v1/jobs/{jobId}/movements")
class MovementController(private val movements: MovementService, private val clock: ChargeClockService) {
    @GetMapping("/context") fun context(@PathVariable jobId: UUID) = movements.context(jobId)
    @GetMapping fun list(@PathVariable jobId: UUID, @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?) = movements.list(jobId, page ?: 0, size ?: 25)
    @GetMapping("/{id}") fun get(@PathVariable jobId: UUID, @PathVariable id: UUID) =
        movements.get(jobId, id).let { Versioning.ok(it, it.version) }
    @PostMapping fun create(@PathVariable jobId: UUID, @RequestBody input: MovementInput): ResponseEntity<MovementDto> =
        movements.create(jobId, input).let { ResponseEntity.status(201).eTag(Versioning.etag(it.version)).body(it) }
    @PostMapping("/dispatch") fun dispatch(@PathVariable jobId: UUID, @RequestBody input: DispatchInput): ResponseEntity<MovementDto> =
        movements.dispatch(jobId, input).let { ResponseEntity.status(201).eTag(Versioning.etag(it.version)).body(it) }
    @PostMapping("/{id}/return") fun returned(@PathVariable jobId: UUID, @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?, @RequestBody input: ReturnInput) =
        movements.returnToJetty(jobId, id, Versioning.requireIfMatch(ifMatch), input.returnedAt)
            .let { Versioning.ok(it, it.version) }
    @PutMapping("/{id}") fun correct(@PathVariable jobId: UUID, @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?, @RequestBody input: MovementInput) =
        movements.correct(jobId, id, Versioning.requireIfMatch(ifMatch), input).let { Versioning.ok(it, it.version) }
    @PostMapping("/{id}/void") fun void(@PathVariable jobId: UUID, @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?, @RequestBody input: VoidInput) =
        movements.void(jobId, id, Versioning.requireIfMatch(ifMatch), input.reason).let { Versioning.ok(it, it.version) }
    @PostMapping("/{id}/decision") fun decideMovement(@PathVariable jobId: UUID, @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?, @RequestBody input: MovementDecisionInput) =
        movements.decide(jobId, id, Versioning.requireIfMatch(ifMatch), input).let { Versioning.ok(it, it.version) }
    @GetMapping("/{id}/history") fun history(@PathVariable jobId: UUID, @PathVariable id: UUID) = movements.history(jobId, id)
    @PostMapping("/{id}/files") fun file(@PathVariable jobId: UUID, @PathVariable id: UUID,
        @RequestPart("file") upload: MultipartFile) = ResponseEntity.status(201).body(movements.upload(jobId, id, upload))

    @GetMapping("/clock") fun clock(@PathVariable jobId: UUID) = clock.overview(jobId)
    @PostMapping("/clock/selection") fun select(@PathVariable jobId: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?, @RequestBody input: ClockSelectionInput) =
        clock.select(jobId, ifMatch?.let(Versioning::requireIfMatch), input).let { Versioning.ok(it, it.version) }
    @PostMapping("/clock/decision") fun decide(@PathVariable jobId: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?, @RequestBody input: ClockDecisionInput) =
        clock.decide(jobId, Versioning.requireIfMatch(ifMatch), input).let { Versioning.ok(it, it.version) }
    @PostMapping("/clock/basis") fun basis(@PathVariable jobId: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?, @RequestBody input: BasisInput) =
        clock.overrideBasis(jobId, Versioning.requireIfMatch(ifMatch), input).let { Versioning.ok(it, it.version) }
    @PostMapping("/clock/adjustments") fun adjustment(@PathVariable jobId: UUID, @RequestBody input: AdjustmentInput) =
        ResponseEntity.status(201).body(clock.requestAdjustment(jobId, input))
    @PostMapping("/clock/adjustments/{id}/decision") fun decideAdjustment(@PathVariable jobId: UUID, @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?, @RequestBody input: ClockDecisionInput) =
        clock.decideAdjustment(jobId, id, Versioning.requireIfMatch(ifMatch), input).let { Versioning.ok(it, it.version) }
    @PostMapping("/clock/notice/release") fun release(@PathVariable jobId: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?) =
        clock.release(jobId, Versioning.requireIfMatch(ifMatch)).let { Versioning.ok(it, it.version) }
    @PostMapping("/clock/notice/delivery") fun delivery(@PathVariable jobId: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?, @RequestBody input: DeliveryInput) =
        clock.delivery(jobId, Versioning.requireIfMatch(ifMatch), input).let { Versioning.ok(it, it.version) }
}

@RestController
@RequestMapping("/api/v1/jobs/{jobId}/recovery")
class RecoveryController(private val recovery: RecoveryService) {
    @GetMapping fun overview(@PathVariable jobId: UUID) = recovery.overview(jobId)
    @PostMapping("/disembark") fun disembark(@PathVariable jobId: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?, @RequestBody input: DisembarkInput) =
        recovery.disembark(jobId, Versioning.requireIfMatch(ifMatch), input)
            .let { Versioning.ok(it, it.version) }
    @PutMapping("/assets/{allocationId}") fun record(@PathVariable jobId: UUID, @PathVariable allocationId: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?, @RequestBody input: RecoveryInput) =
        recovery.record(jobId, allocationId, ifMatch?.let(Versioning::requireIfMatch), input)
    @PostMapping("/assets/{allocationId}/resolve") fun resolve(@PathVariable jobId: UUID, @PathVariable allocationId: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?, @RequestBody input: ResolutionInput) =
        recovery.resolve(jobId, allocationId, Versioning.requireIfMatch(ifMatch), input)
    @PostMapping("/assets/{allocationId}/files") fun file(@PathVariable jobId: UUID, @PathVariable allocationId: UUID,
        @RequestPart("file") upload: MultipartFile) = recovery.upload(jobId, allocationId, upload)
    @PostMapping("/close") fun close(@PathVariable jobId: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?) =
        recovery.close(jobId, Versioning.requireIfMatch(ifMatch)).let { Versioning.ok(it, it.version) }
    @GetMapping("/history") fun history(@PathVariable jobId: UUID) = recovery.history(jobId)
}
