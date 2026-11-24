package com.ship2ship.features.operations

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.security.ReferenceLookup
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.ConflictException
import com.ship2ship.core.web.FieldError
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.PageResponse
import com.ship2ship.core.web.Versioning
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
import java.time.Clock
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/** The operations lists (event types, delay and incident categories, severities). Codes are fixed once created. */
@Service
class OperationListService(private val jdbc: JdbcClient, private val policy: AccessPolicy, private val audit: AuditService, private val clock: Clock) {

    @Transactional(readOnly = true)
    fun list(name: String): List<ListItemDto> {
        policy.requireAny(Permissions.OPS_LIVE_READ)
        return items(table(name))
    }

    @Transactional
    fun create(name: String, input: ListItemInput): ListItemDto {
        policy.requireAny(Permissions.MASTER_OPERATIONS_WRITE)
        val table = table(name)
        val clean = validate(table, input)
        if (!CODE.matches(clean.code)) {
            throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.",
                listOf(FieldError("code", "pattern", "Capital letters, digits and underscores, starting with a letter.")))
        }
        if (items(table).any { it.code == clean.code }) {
            throw ConflictException("DUPLICATE_CODE", "This code is already in use.", listOf(FieldError("code", "duplicate", "This code is already in use.")))
        }
        val now = Uuids.toTimestamp(clock.instant().truncatedTo(ChronoUnit.MICROS))
        if (table == EVENT_TYPES) {
            jdbc.sql("INSERT INTO operation_event_type (code,label,phase,is_interval,sort_order,active,updated_at,version) VALUES (:c,:l,:p,:i,:s,:a,:t,0)")
                .param("c", clean.code).param("l", clean.label).param("p", clean.phase).param("i", clean.interval ?: false)
                .param("s", clean.sortOrder).param("a", clean.active).param("t", now).update()
        } else {
            jdbc.sql("INSERT INTO $table (code,label,sort_order,active,updated_at,version) VALUES (:c,:l,:s,:a,:t,0)")
                .param("c", clean.code).param("l", clean.label).param("s", clean.sortOrder).param("a", clean.active).param("t", now).update()
        }
        audit.record(AuditEntry(AuditAction.CREATE, table.uppercase(), null, details = mapOf("code" to clean.code, "label" to clean.label)))
        return items(table).first { it.code == clean.code }
    }

    @Transactional
    fun update(name: String, code: String, expectedVersion: Long, input: ListItemInput): ListItemDto {
        policy.requireAny(Permissions.MASTER_OPERATIONS_WRITE)
        val table = table(name)
        val current = items(table).firstOrNull { it.code == code } ?: throw NotFoundException("List item")
        Versioning.checkCurrent(expectedVersion, current.version)
        val clean = validate(table, input.copy(code = code))
        val now = Uuids.toTimestamp(clock.instant().truncatedTo(ChronoUnit.MICROS))
        if (table == EVENT_TYPES) {
            jdbc.sql("""UPDATE operation_event_type SET label=:l,phase=:p,is_interval=:i,sort_order=:s,active=:a,updated_at=:t,version=version+1
                WHERE code=:c""").param("l", clean.label).param("p", clean.phase).param("i", clean.interval ?: false).param("s", clean.sortOrder)
                .param("a", clean.active).param("t", now).param("c", code).update()
        } else {
            jdbc.sql("UPDATE $table SET label=:l,sort_order=:s,active=:a,updated_at=:t,version=version+1 WHERE code=:c")
                .param("l", clean.label).param("s", clean.sortOrder).param("a", clean.active).param("t", now).param("c", code).update()
        }
        audit.record(AuditEntry(AuditAction.UPDATE, table.uppercase(), null, details = mapOf("code" to code, "label" to clean.label, "active" to clean.active)))
        return items(table).first { it.code == code }
    }

    internal fun items(table: String): List<ListItemDto> =
        if (table == EVENT_TYPES) {
            jdbc.sql("SELECT code,label,sort_order,active,version,phase,is_interval FROM operation_event_type ORDER BY sort_order,code").query { rs, _ ->
                ListItemDto(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getBoolean(4), rs.getLong(5), rs.getString(6), rs.getBoolean(7))
            }.list()
        } else {
            jdbc.sql("SELECT code,label,sort_order,active,version FROM $table ORDER BY sort_order,code").query { rs, _ ->
                ListItemDto(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getBoolean(4), rs.getLong(5))
            }.list()
        }

    private fun validate(table: String, input: ListItemInput): ListItemInput {
        val errors = mutableListOf<FieldError>()
        if (input.label.isBlank() || input.label.trim().length > 100) errors += FieldError("label", "required", "Enter a label (max 100).")
        if (input.sortOrder !in 0..9999) errors += FieldError("sortOrder", "range", null)
        if (table == EVENT_TYPES && input.phase !in PHASES) errors += FieldError("phase", "allowed", null)
        if (errors.isNotEmpty()) throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", errors)
        return input.copy(code = input.code.trim().uppercase(), label = input.label.trim())
    }

    private fun table(name: String) = TABLES[name] ?: throw NotFoundException("List")

    companion object {
        const val EVENT_TYPES = "operation_event_type"
        val TABLES = mapOf("event-types" to EVENT_TYPES, "delay-categories" to "delay_category", "incident-categories" to "incident_category",
            "incident-severities" to "incident_severity")
        val PHASES = setOf("BERTHING", "TRANSFER", "COMPLETION", "UNMOORING", "OTHER")
        private val CODE = Regex("^[A-Z][A-Z0-9_]{1,39}$")
    }
}

/** The operations console in one response, and the list of jobs in the user's operations scope. */
@Service
class OperationsConsoleService(
    private val jdbc: JdbcClient,
    private val access: OperationsAccess,
    private val policy: AccessPolicy,
    private val references: ReferenceLookup,
    private val eta: EtaService,
    private val events: OperationEventService,
    private val cargo: CargoService,
    private val incidents: IncidentService,
    private val lists: OperationListService,
) {
    @Transactional(readOnly = true)
    fun overview(jobId: UUID): OperationsOverviewDto {
        val job = access.job(jobId, Permissions.OPS_LIVE_READ)
        val incidentRead = access.can(job, Permissions.OPS_INCIDENT_READ)
        return OperationsOverviewDto(
            jobId = jobId, jobNumber = job.jobNumber, timezone = access.zone(job).id,
            vessels = eta.vessels(job),
            eventTypes = lists.items(OperationListService.EVENT_TYPES),
            events = events.events(job),
            cargo = cargo.grades(job),
            delays = if (incidentRead) incidents.delays(job) else emptyList(),
            incidents = if (incidentRead) incidents.incidents(job) else emptyList(),
            delayCategories = lists.items("delay_category"),
            incidentCategories = lists.items("incident_category"),
            incidentSeverities = lists.items("incident_severity"),
            sofRevisions = events.sofRevisions(job),
            permissions = OperationsPermissionsDto(
                write = access.can(job, Permissions.OPS_LIVE_WRITE), approve = access.can(job, Permissions.OPS_LIVE_APPROVE),
                cargoWrite = access.can(job, Permissions.OPS_CARGO_WRITE), cargoCorrect = access.can(job, Permissions.OPS_CARGO_CORRECT),
                incidentRead = incidentRead, incidentWrite = access.can(job, Permissions.OPS_INCIDENT_WRITE),
                incidentReview = access.can(job, Permissions.OPS_INCIDENT_REVIEW),
            ),
        )
    }

    /** Jobs in the user's operations scope, newest first, with the latest event and open incidents. */
    @Transactional(readOnly = true)
    fun jobs(q: String?, page: Int, size: Int): PageResponse<OperationsJobDto> {
        val scope = policy.scope(Permissions.OPS_LIVE_READ)
        val where = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        scope.sqlCondition("j.branch_id", params)?.let { where += it }
        q?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let {
            where += "(LOWER(j.job_number) LIKE :q OR LOWER(j.enquiry_reference) LIKE :q OR " +
                "EXISTS (SELECT 1 FROM job_vessel jv JOIN vessel v ON v.id=jv.vessel_id WHERE jv.job_id=j.id AND LOWER(v.name) LIKE :q))"
            params["q"] = "%$it%"
        }
        val clause = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        val total = jdbc.sql("SELECT COUNT(*) FROM sts_job j $clause").params(params).query(Long::class.java).single()
        val rows = jdbc.sql("SELECT j.id,j.job_number,j.status FROM sts_job j $clause ORDER BY j.created_at DESC LIMIT :limit OFFSET :offset")
            .params(params).param("limit", size).param("offset", page * size)
            .query { rs, _ -> Triple(rs.getBytes(1).uuid()!!, rs.getString(2), rs.getString(3)) }.list()
        val vesselRows = if (rows.isEmpty()) emptyList() else jdbc.sql("SELECT job_id,role,vessel_id FROM job_vessel WHERE job_id IN (:ids)")
            .param("ids", rows.map { it.first.bytes() }).query { rs, _ -> Triple(rs.getBytes(1).uuid()!!, rs.getString(2), rs.getBytes(3).uuid()!!) }.list()
        val vessels = references.vessels(vesselRows.map { it.third })
        val items = rows.map { (id, number, status) ->
            val last = jdbc.sql("""SELECT t.label, COALESCE(e.occurred_at,e.started_at) FROM operation_event e JOIN operation_event_type t ON t.code=e.type_code
                WHERE e.job_id=:id AND e.status='ACTIVE' ORDER BY COALESCE(e.occurred_at,e.started_at) DESC LIMIT 1""").param("id", id.bytes())
                .query { rs, _ -> rs.getString(1) to rs.getObject(2, LocalDateTime::class.java).instant() }.optional().orElse(null)
            val open = jdbc.sql("SELECT COUNT(*) FROM job_incident WHERE job_id=:id AND status<>'CLOSED'").param("id", id.bytes()).query(Int::class.java).single()
            fun vessel(role: String) = vesselRows.firstOrNull { it.first == id && it.second == role }?.let { vessels[it.third]?.name }
            OperationsJobDto(id, number, status, vessel("MOTHER"), vessel("DAUGHTER"), last?.first, last?.second, open)
        }
        return PageResponse(items, page, size, total)
    }
}

@RestController
@RequestMapping("/api/v1/jobs/{jobId}/operations")
class OperationsController(
    private val console: OperationsConsoleService,
    private val eta: EtaService,
    private val events: OperationEventService,
    private val cargo: CargoService,
    private val incidents: IncidentService,
) {
    @GetMapping
    fun overview(@PathVariable jobId: UUID): OperationsOverviewDto = console.overview(jobId)

    @PostMapping("/eta")
    fun eta(@PathVariable jobId: UUID, @RequestBody input: EtaInput): List<VesselEtaDto> = eta.record(jobId, input)

    @PostMapping("/events")
    fun createEvent(@PathVariable jobId: UUID, @RequestBody input: EventInput): ResponseEntity<OperationEventDto> =
        ResponseEntity.status(201).body(events.create(jobId, input))

    @PutMapping("/events/{id}")
    fun correctEvent(
        @PathVariable jobId: UUID, @PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @RequestBody input: EventInput,
    ): OperationEventDto = events.correct(jobId, id, Versioning.requireIfMatch(ifMatch), input)

    @PostMapping("/events/{id}/void")
    fun voidEvent(
        @PathVariable jobId: UUID, @PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @RequestBody input: ReasonInput,
    ): OperationEventDto = events.void(jobId, id, Versioning.requireIfMatch(ifMatch), input)

    @GetMapping("/events/{id}/history")
    fun eventHistory(@PathVariable jobId: UUID, @PathVariable id: UUID): List<ChangeDto> = events.changes(jobId, id)

    @PostMapping("/sof")
    fun prepareSof(@PathVariable jobId: UUID): List<SofRevisionDto> = events.prepareSof(jobId)

    @PostMapping("/sof/{id}/issue")
    fun issueSof(@PathVariable jobId: UUID, @PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?):
        List<SofRevisionDto> = events.issueSof(jobId, id, Versioning.requireIfMatch(ifMatch))

    @PostMapping("/sof/{id}/withdraw")
    fun withdrawSof(@PathVariable jobId: UUID, @PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?):
        List<SofRevisionDto> = events.withdrawSof(jobId, id, Versioning.requireIfMatch(ifMatch))

    /** The draft SOF from the current events (DRAFT watermark). */
    @GetMapping("/sof/draft/pdf")
    fun draftSof(@PathVariable jobId: UUID): ResponseEntity<ByteArray> = pdf(events.sofPdf(jobId, null))

    /** The issued SOF (immutable) or, before issue, a draft of the prepared revision. */
    @GetMapping("/sof/{id}/pdf")
    fun sofPdf(@PathVariable jobId: UUID, @PathVariable id: UUID): ResponseEntity<ByteArray> = pdf(events.sofPdf(jobId, id))

    @PostMapping("/cargo/{lineNo}/readings")
    fun reading(@PathVariable jobId: UUID, @PathVariable lineNo: Int, @RequestBody input: CargoReadingInput): CargoGradeDto =
        cargo.record(jobId, lineNo, input)

    @PostMapping("/delays")
    fun createDelay(@PathVariable jobId: UUID, @RequestBody input: DelayInput): ResponseEntity<DelayDto> =
        ResponseEntity.status(201).body(incidents.createDelay(jobId, input))

    @PutMapping("/delays/{id}")
    fun updateDelay(
        @PathVariable jobId: UUID, @PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @RequestBody input: DelayInput,
    ): DelayDto = incidents.updateDelay(jobId, id, Versioning.requireIfMatch(ifMatch), input)

    @PostMapping("/delays/{id}/void")
    fun voidDelay(
        @PathVariable jobId: UUID, @PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @RequestBody input: ReasonInput,
    ): DelayDto = incidents.voidDelay(jobId, id, Versioning.requireIfMatch(ifMatch), input)

    @GetMapping("/delays/{id}/history")
    fun delayHistory(@PathVariable jobId: UUID, @PathVariable id: UUID): List<ChangeDto> = incidents.changes(jobId, IncidentService.DELAY, id)

    @PostMapping("/incidents")
    fun createIncident(@PathVariable jobId: UUID, @RequestBody input: IncidentInput): ResponseEntity<IncidentDto> =
        ResponseEntity.status(201).body(incidents.createIncident(jobId, input))

    @PutMapping("/incidents/{id}")
    fun updateIncident(
        @PathVariable jobId: UUID, @PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @RequestBody input: IncidentInput,
    ): IncidentDto = incidents.updateIncident(jobId, id, Versioning.requireIfMatch(ifMatch), input)

    @PostMapping("/incidents/{id}/review")
    fun reviewIncident(
        @PathVariable jobId: UUID, @PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @RequestBody input: IncidentReviewInput,
    ): IncidentDto = incidents.review(jobId, id, Versioning.requireIfMatch(ifMatch), input)

    @GetMapping("/incidents/{id}/history")
    fun incidentHistory(@PathVariable jobId: UUID, @PathVariable id: UUID): List<ChangeDto> = incidents.changes(jobId, IncidentService.INCIDENT, id)

    /** Evidence for a delay or incident (`ownerType` DELAY or INCIDENT): PDF, PNG or JPEG. */
    @PostMapping("/files", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun attach(
        @PathVariable jobId: UUID, @RequestParam ownerType: String, @RequestParam ownerId: UUID, @RequestPart("file") file: MultipartFile,
    ): ResponseEntity<FileRefDto> = ResponseEntity.status(201).body(incidents.attach(jobId, ownerType, ownerId, file))

    private fun pdf(file: Pair<String, ByteArray>): ResponseEntity<ByteArray> = ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(file.first).build().toString())
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(file.second)
}

@RestController
@RequestMapping("/api/v1/operations")
class OperationsListController(private val lists: OperationListService, private val console: OperationsConsoleService) {

    @GetMapping("/jobs")
    fun jobs(@RequestParam q: String?, @RequestParam page: Int?, @RequestParam size: Int?): PageResponse<OperationsJobDto> =
        console.jobs(q, (page ?: 0).coerceAtLeast(0), (size ?: 25).coerceIn(1, 100))

    @GetMapping("/lists/{name}")
    fun list(@PathVariable name: String): List<ListItemDto> = lists.list(name)

    @PostMapping("/lists/{name}")
    fun create(@PathVariable name: String, @RequestBody input: ListItemInput): ResponseEntity<ListItemDto> =
        ResponseEntity.status(201).body(lists.create(name, input))

    @PutMapping("/lists/{name}/{code}")
    fun update(
        @PathVariable name: String, @PathVariable code: String, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @RequestBody input: ListItemInput,
    ): ResponseEntity<ListItemDto> = lists.update(name, code, Versioning.requireIfMatch(ifMatch), input).let { Versioning.ok(it, it.version) }
}

