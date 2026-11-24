package com.ship2ship.features.technical

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.files.FileAccess
import com.ship2ship.core.files.FileService
import com.ship2ship.core.jobs.JobTechnicalRef
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.security.UserDirectory
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.BusinessRuleException
import com.ship2ship.core.web.ConflictException
import com.ship2ship.core.web.FieldError
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.Versioning
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
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

/**
 * Weather reports of a job (FR-TECH-04, UAT-06): forecasts with issue time and validity, or observations, uploaded as
 * a provider report and/or entered as values. A report is accepted (or rejected with a reason) by someone other than
 * the person who recorded it. Values are compared only with the job's APPROVED operating limits, as warnings.
 */
@Service
class WeatherReportService(
    private val jdbc: JdbcClient,
    private val access: JobTechnicalAccess,
    private val policy: AccessPolicy,
    private val users: UserDirectory,
    private val files: FileService,
    private val limits: OperatingLimitService,
    private val audit: AuditService,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun overview(jobId: UUID): WeatherOverviewDto {
        val job = access.job(jobId, Permissions.TECH_WEATHER_READ)
        val reports = reportsOf(jobId)
        val approved = limits.approved(jobId)
        val zone = zone(job)
        return WeatherOverviewDto(jobId, job.jobNumber, zone.id, parameters(), reports.map { toDto(job, it) },
            warnings(reports, approved?.second.orEmpty(), zone), approved != null, can(job, Permissions.TECH_WEATHER_WRITE))
    }

    @Transactional
    fun record(jobId: UUID, input: WeatherReportInput, upload: MultipartFile?): WeatherReportDto {
        val job = access.job(jobId, Permissions.TECH_WEATHER_WRITE)
        val clean = validate(input, hasFile = upload != null && !upload.isEmpty)
        val file = upload?.takeUnless { it.isEmpty }?.let { files.store(it, FileAccess(Permissions.TECH_WEATHER_READ, job.branchId)) }
        val id = UuidV7.generate()
        jdbc.sql("""INSERT INTO job_weather_report (id,job_id,kind,source,provider_name,issued_at,valid_from,valid_to,observed_at,area,file_id,
            notes,status,recorded_by,recorded_at,updated_at,updated_by,version)
            VALUES (:id,:job,:kind,:source,:provider,:issued,:from,:to,:observed,:area,:file,:notes,'RECORDED',:a,:t,:t,:a,0)""")
            .param("id", id.bytes()).param("job", jobId.bytes()).params(fields(clean)).param("file", file?.id?.bytes())
            .param("a", actor().bytes()).param("t", now()).update()
        insertValues(id, clean.values)
        audit.record(AuditEntry(AuditAction.CREATE, ENTITY, id, job.branchId, summary(clean) + mapOf("fileId" to file?.id)))
        return toDto(job, load(id))
    }

    /** Corrects a report that has not been reviewed yet (the file stays as uploaded). */
    @Transactional
    fun update(jobId: UUID, reportId: UUID, expectedVersion: Long, input: WeatherReportInput): WeatherReportDto {
        val job = access.job(jobId, Permissions.TECH_WEATHER_WRITE)
        val report = locked(jobId, reportId, expectedVersion)
        if (report.status != "RECORDED") throw BusinessRuleException("INVALID_TRANSITION", "A reviewed weather report cannot be changed; record a new one.")
        val clean = validate(input, hasFile = report.fileId != null)
        jdbc.sql("""UPDATE job_weather_report SET kind=:kind,source=:source,provider_name=:provider,issued_at=:issued,valid_from=:from,
            valid_to=:to,observed_at=:observed,area=:area,notes=:notes,updated_at=:t,updated_by=:a,version=version+1 WHERE id=:id""")
            .params(fields(clean)).param("t", now()).param("a", actor().bytes()).param("id", reportId.bytes()).update()
        jdbc.sql("DELETE FROM job_weather_value WHERE report_id=:id").param("id", reportId.bytes()).update()
        insertValues(reportId, clean.values)
        audit.record(AuditEntry(AuditAction.UPDATE, ENTITY, reportId, job.branchId, summary(clean)))
        return toDto(job, load(reportId))
    }

    /** ACCEPT, or REJECT with a reason, by someone other than the person who recorded the report. */
    @Transactional
    fun review(jobId: UUID, reportId: UUID, expectedVersion: Long, request: WeatherReviewRequest): WeatherReportDto {
        val job = access.job(jobId, Permissions.TECH_WEATHER_APPROVE)
        val report = locked(jobId, reportId, expectedVersion)
        if (report.status != "RECORDED") throw BusinessRuleException("INVALID_TRANSITION", "This weather report has already been reviewed.")
        if (report.recordedBy == actor()) {
            throw BusinessRuleException("SELF_APPROVAL_FORBIDDEN", "A weather report cannot be reviewed by the person who recorded it.")
        }
        val reason = request.reason?.trim()?.ifEmpty { null }
        val status = when (request.decision) {
            "ACCEPT" -> "ACCEPTED"
            "REJECT" -> reason?.let { "REJECTED" }
                ?: throw BadRequestException("REASON_REQUIRED", "A reason is required for this change.", listOf(FieldError("reason", "required", null)))
            else -> throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", listOf(FieldError("decision", "allowed", "ACCEPT or REJECT.")))
        }
        jdbc.sql("""UPDATE job_weather_report SET status=:s,reviewed_by=:a,reviewed_at=:t,review_reason=:r,updated_at=:t,version=version+1
            WHERE id=:id""").param("s", status).param("a", actor().bytes()).param("t", now()).param("r", reason?.take(500))
            .param("id", reportId.bytes()).update()
        audit.record(AuditEntry(AuditAction.STATUS_CHANGE, ENTITY, reportId, job.branchId,
            mapOf("jobId" to jobId, "from" to "RECORDED", "to" to status, "reason" to reason)))
        return toDto(job, load(reportId))
    }

    // ---------- for the JPO (callers have already checked access to the job) ----------

    /** Accepted reports, newest first, as printed in the JPO's weather list. */
    internal fun acceptedReports(jobId: UUID): List<SnapshotWeatherReport> =
        reportsOf(jobId).filter { it.status == "ACCEPTED" }.map {
            SnapshotWeatherReport(it.kind, it.source, it.providerName, it.issuedAt, it.validFrom, it.validTo, it.observedAt, it.area)
        }

    /** Current warnings against the approved limits, as text. */
    internal fun warningMessages(job: JobTechnicalRef): List<String> {
        val approved = limits.approved(job.id) ?: return emptyList()
        return warnings(reportsOf(job.id), approved.second, zone(job)).map { it.message }
    }

    // ---------- warnings ----------

    private fun warnings(reports: List<Report>, rows: List<LimitRowDto>, zone: ZoneId): List<WeatherWarningDto> {
        if (rows.isEmpty()) return emptyList()
        val now = clock.instant()
        val format = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH).withZone(zone)
        return reports.filter { it.status != "REJECTED" && !expired(it, now) }.flatMap { report ->
            report.values.flatMap { value ->
                rows.filter { it.parameterCode == value.parameterCode }.mapNotNull { limit ->
                    val outside = if (limit.comparison == "BELOW") value.value < limit.threshold else value.value > limit.threshold
                    if (!outside) return@mapNotNull null
                    val kind = if (report.kind == "FORECAST") "Forecast for" else "Observation at"
                    val side = if (limit.comparison == "BELOW") "below" else "above"
                    WeatherWarningDto(report.id, report.kind, report.status, value.at, value.parameterCode, limit.parameterLabel, limit.unit,
                        value.value, limit.comparison, limit.threshold, limit.operation, limit.action,
                        "$kind ${format.format(value.at)} (${zone.id}): ${limit.parameterLabel.lowercase()} ${value.value.toPlainString()} ${limit.unit} " +
                            "is $side the ${limit.threshold.toPlainString()} ${limit.unit} limit for ${limit.operation}. Action: ${limit.action}")
                }
            }
        }.sortedBy { it.at }
    }

    private fun expired(r: Report, now: Instant) = r.kind == "FORECAST" && r.validTo != null && r.validTo.isBefore(now)

    // ---------- validation ----------

    private fun validate(input: WeatherReportInput, hasFile: Boolean): WeatherReportInput {
        val errors = mutableListOf<FieldError>()
        val latest = clock.instant().plus(5, ChronoUnit.MINUTES)
        fun text(field: String, value: String?, max: Int) { if (value != null && value.trim().length > max) errors += FieldError(field, "size", "At most $max characters.") }
        if (input.kind !in KINDS) errors += FieldError("kind", "allowed", null)
        if (input.source !in SOURCES) errors += FieldError("source", "allowed", null)
        if (input.kind == "OBSERVATION" && input.source != "OBSERVATION" || input.kind == "FORECAST" && input.source == "OBSERVATION") {
            errors += FieldError("source", "allowed", "The source does not match the kind of report.")
        }
        if (input.source in setOf("PROVIDER_REPORT", "OTHER_PROVIDER") && input.providerName.isNullOrBlank()) {
            errors += FieldError("providerName", "required", "Enter the provider.")
        }
        text("providerName", input.providerName, 100)
        text("area", input.area, 200)
        text("notes", input.notes, 2000)
        if (input.kind == "FORECAST") {
            if (input.issuedAt == null) errors += FieldError("issuedAt", "required", null)
            else if (input.issuedAt.isAfter(latest)) errors += FieldError("issuedAt", "future", "Cannot be in the future.")
            if (input.validFrom == null) errors += FieldError("validFrom", "required", null)
            if (input.validTo == null) errors += FieldError("validTo", "required", null)
            if (input.validFrom != null && input.validTo != null && !input.validTo.isAfter(input.validFrom)) {
                errors += FieldError("validTo", "order", "Must be after the start of validity.")
            }
        }
        if (input.kind == "OBSERVATION") {
            if (input.observedAt == null) errors += FieldError("observedAt", "required", null)
            else if (input.observedAt.isAfter(latest)) errors += FieldError("observedAt", "future", "Cannot be in the future.")
        }
        if (input.values.size > MAX_VALUES) errors += FieldError("values", "size", "At most $MAX_VALUES values.")
        if (input.values.isEmpty() && !hasFile) errors += FieldError("values", "required", "Enter values or attach the report file.")
        val parameters = parameters().filter { it.active }.map { it.code }.toSet()
        val seen = mutableSetOf<Pair<Instant, String>>()
        input.values.forEachIndexed { i, v ->
            if (v.parameterCode !in parameters) errors += FieldError("values[$i].parameterCode", "unknown", "Choose an active parameter.")
            val value = v.value
            if (value == null) errors += FieldError("values[$i].value", "required", null)
            else if (value.stripTrailingZeros().scale() > 2 || value.abs() >= BigDecimal(100_000_000)) {
                errors += FieldError("values[$i].value", "range", "At most 2 decimals.")
            }
            val at = v.at
            if (at == null) errors += FieldError("values[$i].at", "required", null)
            else {
                if (input.kind == "FORECAST" && input.validFrom != null && input.validTo != null && (at.isBefore(input.validFrom) || at.isAfter(input.validTo))) {
                    errors += FieldError("values[$i].at", "validity", "Must be within the forecast's validity.")
                }
                if (input.kind == "OBSERVATION" && at.isAfter(latest)) errors += FieldError("values[$i].at", "future", "Cannot be in the future.")
                if (!seen.add(at.truncatedTo(ChronoUnit.MINUTES) to v.parameterCode)) {
                    errors += FieldError("values[$i]", "duplicate", "This parameter already has a value at this time.")
                }
            }
        }
        if (errors.isNotEmpty()) throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", errors)
        fun String?.clean() = this?.trim()?.ifEmpty { null }
        val forecast = input.kind == "FORECAST"
        return input.copy(
            providerName = input.providerName.clean(), area = input.area.clean(), notes = input.notes.clean(),
            issuedAt = input.issuedAt.takeIf { forecast }, validFrom = input.validFrom.takeIf { forecast }, validTo = input.validTo.takeIf { forecast },
            observedAt = input.observedAt.takeIf { !forecast },
            values = input.values.map { it.copy(at = it.at!!.truncatedTo(ChronoUnit.MINUTES), value = it.value!!.plain()) },
        )
    }

    // ---------- persistence ----------

    private data class Report(
        val id: UUID, val kind: String, val source: String, val providerName: String?, val issuedAt: Instant?, val validFrom: Instant?,
        val validTo: Instant?, val observedAt: Instant?, val area: String?, val fileId: UUID?, val notes: String?, val status: String,
        val recordedBy: UUID, val recordedAt: Instant, val reviewedBy: UUID?, val reviewedAt: Instant?, val reviewReason: String?,
        val values: List<WeatherValueDto>, val version: Long,
    )

    private fun fields(c: WeatherReportInput): Map<String, Any?> = mapOf(
        "kind" to c.kind, "source" to c.source, "provider" to c.providerName, "issued" to c.issuedAt?.let(::stamp),
        "from" to c.validFrom?.let(::stamp), "to" to c.validTo?.let(::stamp), "observed" to c.observedAt?.let(::stamp), "area" to c.area,
        "notes" to c.notes,
    )

    private fun summary(c: WeatherReportInput) = mapOf("kind" to c.kind, "source" to c.source, "provider" to c.providerName,
        "issuedAt" to c.issuedAt, "validFrom" to c.validFrom, "validTo" to c.validTo, "observedAt" to c.observedAt, "values" to c.values.size)

    private fun insertValues(reportId: UUID, values: List<WeatherValueInput>) = values.forEach { v ->
        jdbc.sql("INSERT INTO job_weather_value (id,report_id,value_at,parameter_code,value) VALUES (:id,:r,:at,:p,:v)")
            .param("id", UuidV7.generate().bytes()).param("r", reportId.bytes()).param("at", stamp(v.at!!)).param("p", v.parameterCode)
            .param("v", v.value).update()
    }

    private fun toDto(job: JobTechnicalRef, r: Report): WeatherReportDto {
        val names = users.names(listOfNotNull(r.recordedBy, r.reviewedBy))
        val file = r.fileId?.let { files.refs(listOf(it))[it] }?.let { WeatherFileDto(it.id, it.originalName, it.contentType, it.scanState.name) }
        val open = r.status == "RECORDED"
        return WeatherReportDto(r.id, r.kind, r.source, r.providerName, r.issuedAt, r.validFrom, r.validTo, r.observedAt, r.area, r.notes, file,
            r.values, r.status, names[r.recordedBy], r.recordedAt, r.reviewedBy?.let(names::get), r.reviewedAt, r.reviewReason,
            expired(r, clock.instant()), canEdit = open && can(job, Permissions.TECH_WEATHER_WRITE),
            canReview = open && r.recordedBy != actor() && can(job, Permissions.TECH_WEATHER_APPROVE), version = r.version)
    }

    private fun reportsOf(jobId: UUID): List<Report> =
        jdbc.sql("SELECT id FROM job_weather_report WHERE job_id=:job ORDER BY recorded_at DESC").param("job", jobId.bytes())
            .query { rs, _ -> rs.getBytes(1).uuid()!! }.list().map(::load)

    private fun locked(jobId: UUID, reportId: UUID, expectedVersion: Long): Report {
        val owner = jdbc.sql("SELECT job_id FROM job_weather_report WHERE id=:id FOR UPDATE").param("id", reportId.bytes())
            .query { rs, _ -> rs.getBytes(1).uuid() }.optional().orElse(null)
        if (owner != jobId) throw NotFoundException("Weather report")
        return load(reportId).also { Versioning.checkCurrent(expectedVersion, it.version) }
    }

    private fun load(id: UUID): Report {
        val values = jdbc.sql("SELECT value_at,parameter_code,value FROM job_weather_value WHERE report_id=:id ORDER BY value_at,parameter_code")
            .param("id", id.bytes()).query { rs, _ ->
                WeatherValueDto(ts(rs.getObject(1, LocalDateTime::class.java))!!, rs.getString(2), rs.getBigDecimal(3).plain())
            }.list()
        return jdbc.sql("""SELECT kind,source,provider_name,issued_at,valid_from,valid_to,observed_at,area,file_id,notes,status,recorded_by,
            recorded_at,reviewed_by,reviewed_at,review_reason,version FROM job_weather_report WHERE id=:id""").param("id", id.bytes())
            .query { rs, _ ->
                fun t(i: Int) = ts(rs.getObject(i, LocalDateTime::class.java))
                Report(id, rs.getString(1), rs.getString(2), rs.getString(3), t(4), t(5), t(6), t(7), rs.getString(8), rs.getBytes(9).uuid(),
                    rs.getString(10), rs.getString(11), rs.getBytes(12).uuid()!!, t(13)!!, rs.getBytes(14).uuid(), t(15), rs.getString(16), values,
                    rs.getLong(17))
            }.optional().orElseThrow { NotFoundException("Weather report") }
    }

    private fun parameters(): List<WeatherParameterDto> =
        jdbc.sql("SELECT code,label,unit,sort_order,active,version FROM weather_parameter ORDER BY sort_order,code").query { rs, _ ->
            WeatherParameterDto(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getBoolean(5), rs.getLong(6))
        }.list()

    private fun zone(job: JobTechnicalRef): ZoneId =
        jdbc.sql("SELECT timezone FROM branch WHERE id=:id").param("id", job.branchId.bytes())
            .query(String::class.java).optional().map(ZoneId::of).orElse(ZoneId.of("UTC"))

    private fun can(job: JobTechnicalRef, permission: String) = policy.currentUser().scopeFor(permission).coversBranch(job.branchId)

    private fun actor() = policy.currentUser().id

    private fun now() = stamp(clock.instant())

    private fun stamp(i: Instant) = Uuids.toTimestamp(i.truncatedTo(ChronoUnit.MICROS))

    private fun ts(value: LocalDateTime?): Instant? = value?.let(Uuids::fromTimestamp)

    companion object {
        private const val ENTITY = "JOB_WEATHER_REPORT"
        private const val MAX_VALUES = 200
        val KINDS = setOf("FORECAST", "OBSERVATION")
        val SOURCES = setOf("PROVIDER_REPORT", "OTHER_PROVIDER", "OBSERVATION")
    }
}

/** Weather parameter catalogue (technical masters). Codes are fixed once created; a unit cannot change while in use. */
@Service
class WeatherParameterService(private val jdbc: JdbcClient, private val policy: AccessPolicy, private val audit: AuditService, private val clock: Clock) {

    @Transactional(readOnly = true)
    fun list(): List<WeatherParameterDto> {
        policy.requireAny(Permissions.MASTER_TECHNICAL_READ)
        return all()
    }

    @Transactional
    fun create(input: WeatherParameterInput): WeatherParameterDto {
        policy.requireAny(Permissions.MASTER_TECHNICAL_WRITE)
        val clean = validate(input)
        if (!CODE.matches(clean.code)) {
            throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.",
                listOf(FieldError("code", "pattern", "Capital letters, digits and underscores, starting with a letter.")))
        }
        if (all().any { it.code == clean.code }) {
            throw ConflictException("DUPLICATE_CODE", "This code is already in use.", listOf(FieldError("code", "duplicate", "This code is already in use.")))
        }
        jdbc.sql("INSERT INTO weather_parameter (code,label,unit,sort_order,active,updated_at,version) VALUES (:c,:l,:u,:s,:a,:t,0)")
            .param("c", clean.code).param("l", clean.label).param("u", clean.unit).param("s", clean.sortOrder).param("a", clean.active)
            .param("t", Uuids.toTimestamp(clock.instant().truncatedTo(ChronoUnit.MICROS))).update()
        audit.record(AuditEntry(AuditAction.CREATE, ENTITY, null, details = mapOf("code" to clean.code, "label" to clean.label, "unit" to clean.unit)))
        return all().first { it.code == clean.code }
    }

    @Transactional
    fun update(code: String, expectedVersion: Long, input: WeatherParameterInput): WeatherParameterDto {
        policy.requireAny(Permissions.MASTER_TECHNICAL_WRITE)
        val current = all().firstOrNull { it.code == code } ?: throw NotFoundException("Weather parameter")
        Versioning.checkCurrent(expectedVersion, current.version)
        val clean = validate(input.copy(code = code))
        if (clean.unit != current.unit) {
            val used = jdbc.sql("SELECT (SELECT COUNT(*) FROM job_limit WHERE parameter_code=:c) + (SELECT COUNT(*) FROM job_weather_value WHERE parameter_code=:c)")
                .param("c", code).query(Long::class.java).single()
            if (used > 0) throw BusinessRuleException("PARAMETER_IN_USE", "The unit cannot change while limits or weather values use this parameter.")
        }
        jdbc.sql("UPDATE weather_parameter SET label=:l,unit=:u,sort_order=:s,active=:a,updated_at=:t,version=version+1 WHERE code=:c")
            .param("l", clean.label).param("u", clean.unit).param("s", clean.sortOrder).param("a", clean.active)
            .param("t", Uuids.toTimestamp(clock.instant().truncatedTo(ChronoUnit.MICROS))).param("c", code).update()
        audit.record(AuditEntry(AuditAction.UPDATE, ENTITY, null, details = mapOf("code" to code, "label" to clean.label, "unit" to clean.unit,
            "active" to clean.active)))
        return all().first { it.code == code }
    }

    private fun validate(input: WeatherParameterInput): WeatherParameterInput {
        val errors = mutableListOf<FieldError>()
        if (input.label.isBlank() || input.label.trim().length > 100) errors += FieldError("label", "required", "Enter a label (max 100).")
        if (input.unit.isBlank() || input.unit.trim().length > 20) errors += FieldError("unit", "required", "Enter the unit (max 20).")
        if (input.sortOrder !in 0..9999) errors += FieldError("sortOrder", "range", null)
        if (errors.isNotEmpty()) throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", errors)
        return input.copy(code = input.code.trim().uppercase(), label = input.label.trim(), unit = input.unit.trim())
    }

    private fun all(): List<WeatherParameterDto> =
        jdbc.sql("SELECT code,label,unit,sort_order,active,version FROM weather_parameter ORDER BY sort_order,code").query { rs, _ ->
            WeatherParameterDto(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getBoolean(5), rs.getLong(6))
        }.list()

    companion object {
        private const val ENTITY = "WEATHER_PARAMETER"
        private val CODE = Regex("^[A-Z][A-Z0-9_]{1,39}$")
    }
}

@RestController
@RequestMapping("/api/v1/jobs/{jobId}/weather")
class WeatherReportController(private val service: WeatherReportService, private val json: JsonMapper) {

    @GetMapping
    fun overview(@PathVariable jobId: UUID): WeatherOverviewDto = service.overview(jobId)

    /** Multipart: `report` (the report as JSON text, with or without a JSON content type) and an optional `file` (PDF, PNG or JPEG). */
    @PostMapping("/reports", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun record(
        @PathVariable jobId: UUID, @RequestPart("report") report: String, @RequestPart("file", required = false) file: MultipartFile?,
    ): ResponseEntity<WeatherReportDto> {
        val input = try {
            json.readValue(report, WeatherReportInput::class.java)
        } catch (e: JacksonException) {
            throw BadRequestException("MALFORMED_REQUEST", "The request body could not be read.")
        }
        return service.record(jobId, input, file).let { ResponseEntity.status(201).eTag(Versioning.etag(it.version)).body(it) }
    }

    @PutMapping("/reports/{id}")
    fun update(
        @PathVariable jobId: UUID, @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?, @RequestBody input: WeatherReportInput,
    ): ResponseEntity<WeatherReportDto> = service.update(jobId, id, Versioning.requireIfMatch(ifMatch), input).let { Versioning.ok(it, it.version) }

    @PostMapping("/reports/{id}/review")
    fun review(
        @PathVariable jobId: UUID, @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?, @RequestBody request: WeatherReviewRequest,
    ): ResponseEntity<WeatherReportDto> = service.review(jobId, id, Versioning.requireIfMatch(ifMatch), request).let { Versioning.ok(it, it.version) }
}

@RestController
@RequestMapping("/api/v1/technical/weather-parameters")
class WeatherParameterController(private val service: WeatherParameterService) {

    @GetMapping
    fun list(): List<WeatherParameterDto> = service.list()

    @PostMapping
    fun create(@RequestBody input: WeatherParameterInput): ResponseEntity<WeatherParameterDto> = ResponseEntity.status(201).body(service.create(input))

    @PutMapping("/{code}")
    fun update(
        @PathVariable code: String, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?, @RequestBody input: WeatherParameterInput,
    ): ResponseEntity<WeatherParameterDto> = service.update(code, Versioning.requireIfMatch(ifMatch), input).let { Versioning.ok(it, it.version) }
}
