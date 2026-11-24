package com.ship2ship.features.operations

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.jobs.JobTechnicalRef
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.security.ReferenceLookup
import com.ship2ship.core.security.UserDirectory
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.BusinessRuleException
import com.ship2ship.core.web.FieldError
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.Versioning
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

/** Keeps the values before every correction of an event, delay or incident, with the reason (BR-02: nothing silent). */
@Component
class OperationHistory(private val jdbc: JdbcClient, private val json: JsonMapper, private val users: UserDirectory, private val clock: Clock) {

    fun record(type: String, recordId: UUID, versionNo: Long, before: Map<String, Any?>, reason: String, actor: UUID) {
        jdbc.sql("""INSERT INTO operation_change (id,record_type,record_id,version_no,before_json,reason,changed_by,changed_at)
            VALUES (:id,:type,:record,:v,:before,:reason,:actor,:t)""")
            .param("id", UuidV7.generate().bytes()).param("type", type).param("record", recordId.bytes()).param("v", versionNo)
            .param("before", json.writeValueAsString(before)).param("reason", reason.take(500)).param("actor", actor.bytes())
            .param("t", clock.instant().truncatedTo(ChronoUnit.MICROS).stamp()).update()
    }

    fun changes(type: String, recordId: UUID): List<ChangeDto> {
        val rows = jdbc.sql("""SELECT version_no,before_json,reason,changed_by,changed_at FROM operation_change
            WHERE record_type=:type AND record_id=:id ORDER BY version_no""").param("type", type).param("id", recordId.bytes())
            .query { rs, _ -> Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBytes(4).uuid()!!, rs.getObject(5, LocalDateTime::class.java).instant()!!) }
            .list()
        val names = users.names(rows.map { it.by })
        @Suppress("UNCHECKED_CAST")
        return rows.map { ChangeDto(it.version, json.readValue(it.before, Map::class.java) as Map<String, Any?>, it.reason, names[it.by], it.at) }
    }

    /** Ids of records (of a type) that have at least one correction. */
    fun corrected(type: String, ids: Collection<UUID>): Set<UUID> =
        if (ids.isEmpty()) emptySet()
        else jdbc.sql("SELECT DISTINCT record_id FROM operation_change WHERE record_type=:type AND record_id IN (:ids)")
            .param("type", type).param("ids", ids.map { it.bytes() }).query { rs, _ -> rs.getBytes(1).uuid()!! }.list().toSet()

    private data class Row(val version: Long, val before: String, val reason: String, val by: UUID, val at: Instant)
}

/** ETA revisions per job vessel (FR-J07). The enquiry's ETA is shown as revision 0. */
@Service
class EtaService(
    private val jdbc: JdbcClient,
    private val access: OperationsAccess,
    private val references: ReferenceLookup,
    private val users: UserDirectory,
    private val audit: AuditService,
    private val clock: Clock,
) {
    @Transactional
    fun record(jobId: UUID, input: EtaInput): List<VesselEtaDto> {
        val job = access.job(jobId, Permissions.OPS_LIVE_WRITE)
        val errors = mutableListOf<FieldError>()
        val vessel = job.vessels.firstOrNull { it.id == input.jobVesselId }
        if (vessel == null) errors += FieldError("jobVesselId", "unknown", "Choose a vessel of this job.")
        if (input.etaUtc == null) errors += FieldError("etaUtc", "required", null)
        if (runCatching { ZoneId.of(input.timezone) }.isFailure) errors += FieldError("timezone", "allowed", "Choose a time zone.")
        if (input.source !in ETA_SOURCES) errors += FieldError("source", "allowed", null)
        if ((input.note?.length ?: 0) > 500) errors += FieldError("note", "size", "At most 500 characters.")
        if (errors.isNotEmpty()) throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", errors)
        jdbc.sql("SELECT id FROM job_vessel WHERE id=:id FOR UPDATE").param("id", vessel!!.id.bytes()).query { rs, _ -> rs.getBytes(1) }.single()
        val next = jdbc.sql("SELECT COALESCE(MAX(revision_no),0)+1 FROM eta_revision WHERE job_vessel_id=:v").param("v", vessel.id.bytes())
            .query(Int::class.java).single()
        jdbc.sql("""INSERT INTO eta_revision (id,job_vessel_id,revision_no,eta_utc,timezone,source,note,recorded_by,recorded_at)
            VALUES (:id,:v,:no,:eta,:tz,:src,:note,:a,:t)""")
            .param("id", UuidV7.generate().bytes()).param("v", vessel.id.bytes()).param("no", next)
            .param("eta", input.etaUtc!!.truncatedTo(ChronoUnit.MINUTES).stamp()).param("tz", input.timezone).param("src", input.source)
            .param("note", input.note?.trim()?.ifEmpty { null }).param("a", access.actor().bytes())
            .param("t", clock.instant().truncatedTo(ChronoUnit.MICROS).stamp()).update()
        audit.record(AuditEntry(AuditAction.CREATE, "ETA_REVISION", vessel.id, job.branchId,
            mapOf("jobId" to jobId, "role" to vessel.role, "revisionNo" to next, "etaUtc" to input.etaUtc, "source" to input.source)))
        return vessels(job)
    }

    internal fun vessels(job: JobTechnicalRef): List<VesselEtaDto> {
        val refs = references.vessels(job.vessels.map { it.vesselId })
        val rows = job.vessels.associate { v ->
            v.id to jdbc.sql("""SELECT revision_no,eta_utc,timezone,source,note,recorded_by,recorded_at FROM eta_revision
                WHERE job_vessel_id=:v ORDER BY revision_no""").param("v", v.id.bytes()).query { rs, _ ->
                Rev(rs.getInt(1), rs.getObject(2, LocalDateTime::class.java).instant()!!, rs.getString(3), rs.getString(4), rs.getString(5),
                    rs.getBytes(6).uuid(), rs.getObject(7, LocalDateTime::class.java).instant())
            }.list()
        }
        val names = users.names(rows.values.flatten().mapNotNull { it.by })
        return job.vessels.sortedBy { if (it.role == "MOTHER") 0 else 1 }.map { v ->
            val original = v.etaUtc?.let { Rev(0, it, v.etaTimezone ?: "UTC", "ORIGINAL", null, null, null) }
            val all = listOfNotNull(original) + rows[v.id].orEmpty()
            val history = all.mapIndexed { i, r ->
                val previous = all.getOrNull(i - 1)?.eta
                EtaRevisionDto(r.no, r.eta, r.tz, r.source, r.note, r.by?.let(names::get), r.at,
                    previous?.let { BigDecimal(ChronoUnit.MINUTES.between(it, r.eta)).divide(BigDecimal(60), 1, RoundingMode.HALF_UP) })
            }
            VesselEtaDto(v.id, v.role, refs[v.vesselId]?.name, history.lastOrNull(), history.reversed())
        }
    }

    private data class Rev(val no: Int, val eta: Instant, val tz: String, val source: String, val note: String?, val by: UUID?, val at: Instant?)

    companion object {
        val ETA_SOURCES = setOf("VESSEL_MASTER", "AGENT", "OWNER_CHARTERER", "OTHER")
    }
}

/**
 * Operation event log (FR-OPS-01, FR-J08) and the Statement of Facts (D13). Events are corrected with a reason (the
 * previous values are kept) or voided with a reason; never deleted. A SOF revision freezes the events when prepared and
 * is issued (immutable PDF with SHA-256) by someone other than the preparer; a newer issue supersedes it.
 */
@Service
class OperationEventService(
    private val jdbc: JdbcClient,
    private val access: OperationsAccess,
    private val references: ReferenceLookup,
    private val users: UserDirectory,
    private val history: OperationHistory,
    private val output: SofOutput,
    private val json: JsonMapper,
    private val audit: AuditService,
    private val clock: Clock,
) {
    @Transactional
    fun create(jobId: UUID, input: EventInput): OperationEventDto {
        val job = access.job(jobId, Permissions.OPS_LIVE_WRITE)
        val fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(json.writeValueAsBytes(mapOf("jobId" to jobId,"event" to input.copy(conflictReason=null)))))
        input.clientEventId?.let { key ->
            val existing=jdbc.sql("SELECT event_id,payload_sha256 FROM operation_event_capture WHERE recorded_by=:actor AND client_event_id=:key")
                .param("actor",access.actor().bytes()).param("key",key.bytes()).query { rs,_->rs.getBytes(1).uuid()!! to rs.getString(2) }.optional().orElse(null)
            if(existing!=null) {
                if(existing.second!=fingerprint) throw BusinessRuleException("OFFLINE_EVENT_CONFLICT","This device event was already synchronized with different values. Review the recorded event.")
                return events(job).first { it.id==existing.first }
            }
        }
        val clean = validate(job, input)
        if((input.clientEventId==null)!=(input.deviceRecordedAt==null) || input.deviceRecordedAt?.isAfter(clock.instant().plusSeconds(300))==true)
            throw BadRequestException("VALIDATION_FAILED","A device event needs its original capture time and unique identifier.")
        val why=input.conflictReason?.trim()?.takeIf { it.isNotEmpty() }
        if((why?.length?:0)>500) throw BadRequestException("VALIDATION_FAILED","Use at most 500 characters for conflict review.")
        if(input.clientEventId!=null) {
            val duplicate=jdbc.sql("""SELECT COUNT(*) FROM operation_event WHERE job_id=:job AND status='ACTIVE' AND type_code=:type
                AND job_vessel_id <=> :v AND occurred_at <=> :at AND started_at <=> :start""")
                .param("job",jobId.bytes()).params(fields(clean)).query(Long::class.java).single()>0
            val issuedSince=jdbc.sql("SELECT COUNT(*) FROM sof_revision WHERE job_id=:job AND status='ISSUED' AND issued_at>=:device")
                .param("job",jobId.bytes()).param("device",input.deviceRecordedAt!!.stamp()).query(Long::class.java).single()>0
            if((duplicate || issuedSince) && why==null) throw BusinessRuleException("OFFLINE_EVENT_CONFLICT","A matching event or a later issued SOF exists. Review it and enter a reason before synchronizing this event.")
            if(why!=null) access.job(jobId,Permissions.OPS_LIVE_APPROVE)
        }
        val id = UuidV7.generate()
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS).stamp()
        jdbc.sql("""INSERT INTO operation_event (id,job_id,type_code,job_vessel_id,occurred_at,started_at,ended_at,remarks,source,status,
            recorded_by,recorded_at,updated_at,version) VALUES (:id,:job,:type,:v,:at,:start,:end,:remarks,:source,'ACTIVE',:a,:t,:t,0)""")
            .param("id", id.bytes()).param("job", jobId.bytes()).params(fields(clean)).param("a", access.actor().bytes()).param("t", now).update()
        if(input.clientEventId!=null) jdbc.sql("""INSERT INTO operation_event_capture(event_id,recorded_by,client_event_id,device_recorded_at,payload_sha256,conflict_reason)
            VALUES(:id,:actor,:key,:device,:hash,:why)""").param("id",id.bytes()).param("actor",access.actor().bytes())
            .param("key",input.clientEventId.bytes()).param("device",input.deviceRecordedAt!!.stamp()).param("hash",fingerprint).param("why",why).update()
        audit.record(AuditEntry(AuditAction.CREATE, ENTITY, id, job.branchId, summary(clean)+mapOf("clientEventId" to input.clientEventId,"deviceRecordedAt" to input.deviceRecordedAt,"conflictReason" to why)))
        return events(job).first { it.id == id }
    }

    /** Corrects an event; the values before the change are kept with the reason. */
    @Transactional
    fun correct(jobId: UUID, eventId: UUID, expectedVersion: Long, input: EventInput): OperationEventDto {
        val job = access.job(jobId, Permissions.OPS_LIVE_WRITE)
        val current = locked(jobId, eventId, expectedVersion)
        if (current.status != "ACTIVE") throw BusinessRuleException("INVALID_TRANSITION", "A voided event cannot be changed.")
        val reason = requireReason(input.reason)
        val clean = validate(job, input)
        history.record(HISTORY, eventId, current.version, mapOf("typeCode" to current.typeCode, "jobVesselId" to current.jobVesselId,
            "occurredAt" to current.occurredAt, "startedAt" to current.startedAt, "endedAt" to current.endedAt, "remarks" to current.remarks,
            "source" to current.source), reason, access.actor())
        jdbc.sql("""UPDATE operation_event SET type_code=:type,job_vessel_id=:v,occurred_at=:at,started_at=:start,ended_at=:end,remarks=:remarks,
            source=:source,updated_by=:a,updated_at=:t,version=version+1 WHERE id=:id""")
            .params(fields(clean)).param("a", access.actor().bytes()).param("t", clock.instant().truncatedTo(ChronoUnit.MICROS).stamp())
            .param("id", eventId.bytes()).update()
        audit.record(AuditEntry(AuditAction.UPDATE, ENTITY, eventId, job.branchId, summary(clean) + mapOf("reason" to reason)))
        return events(job).first { it.id == eventId }
    }

    @Transactional
    fun void(jobId: UUID, eventId: UUID, expectedVersion: Long, input: ReasonInput): OperationEventDto {
        val job = access.job(jobId, Permissions.OPS_LIVE_WRITE)
        val current = locked(jobId, eventId, expectedVersion)
        if (current.status != "ACTIVE") throw BusinessRuleException("INVALID_TRANSITION", "This event is already voided.")
        val reason = requireReason(input.reason)
        jdbc.sql("UPDATE operation_event SET status='VOIDED',void_reason=:r,updated_by=:a,updated_at=:t,version=version+1 WHERE id=:id")
            .param("r", reason).param("a", access.actor().bytes()).param("t", clock.instant().truncatedTo(ChronoUnit.MICROS).stamp())
            .param("id", eventId.bytes()).update()
        audit.record(AuditEntry(AuditAction.STATUS_CHANGE, ENTITY, eventId, job.branchId,
            mapOf("jobId" to jobId, "to" to "VOIDED", "reason" to reason)))
        return events(job).first { it.id == eventId }
    }

    @Transactional(readOnly = true)
    fun changes(jobId: UUID, eventId: UUID): List<ChangeDto> {
        access.job(jobId, Permissions.OPS_LIVE_READ)
        row(jobId, eventId)
        return history.changes(HISTORY, eventId)
    }

    // ---------- SOF ----------

    /** Freezes the job's active events into a new SOF revision, to be issued by someone else. */
    @Transactional
    fun prepareSof(jobId: UUID): List<SofRevisionDto> {
        val job = access.job(jobId, Permissions.OPS_LIVE_WRITE)
        jdbc.sql("SELECT id FROM sts_job WHERE id=:id FOR UPDATE").param("id", jobId.bytes()).query { rs, _ -> rs.getBytes(1) }.single()
        if (sofRows(jobId).any { it.status == "PREPARED" }) {
            throw BusinessRuleException("SOF_REVISION_OPEN", "Issue or withdraw the prepared Statement of Facts first.")
        }
        val events = sofEvents(job)
        if (events.isEmpty()) throw BusinessRuleException("SOF_EMPTY", "Record operation events before preparing the Statement of Facts.")
        val id = UuidV7.generate()
        val no = (sofRows(jobId).maxOfOrNull { it.revisionNo } ?: 0) + 1
        jdbc.sql("""INSERT INTO sof_revision (id,job_id,revision_no,status,events_json,prepared_by,prepared_at,version)
            VALUES (:id,:job,:no,'PREPARED',:events,:a,:t,0)""")
            .param("id", id.bytes()).param("job", jobId.bytes()).param("no", no).param("events", json.writeValueAsString(events))
            .param("a", access.actor().bytes()).param("t", clock.instant().truncatedTo(ChronoUnit.MICROS).stamp()).update()
        audit.record(AuditEntry(AuditAction.CREATE, SOF_ENTITY, id, job.branchId,
            mapOf("jobId" to jobId, "revisionNo" to no, "events" to events.size)))
        return sofRevisions(job)
    }

    @Transactional
    fun issueSof(jobId: UUID, sofId: UUID, expectedVersion: Long): List<SofRevisionDto> {
        val job = access.job(jobId, Permissions.OPS_LIVE_APPROVE)
        val sof = lockedSof(jobId, sofId, expectedVersion)
        if (sof.status != "PREPARED") throw BusinessRuleException("INVALID_TRANSITION", "Only a prepared Statement of Facts can be issued.")
        if (sof.preparedBy == access.actor()) {
            throw BusinessRuleException("SELF_APPROVAL_FORBIDDEN", "A Statement of Facts cannot be issued by the person who prepared it.")
        }
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val pdf = output.pdf(document(job, sof.revisionNo, readEvents(sof.eventsJson), draft = false, issuedAt = now,
            issuedBy = users.names(listOf(access.actor()))[access.actor()]))
        val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf))
        sofRows(jobId).filter { it.status == "ISSUED" }.forEach { older ->
            jdbc.sql("UPDATE sof_revision SET status='SUPERSEDED',version=version+1 WHERE id=:id").param("id", older.id.bytes()).update()
        }
        jdbc.sql("UPDATE sof_revision SET status='ISSUED',issued_by=:a,issued_at=:t,pdf=:pdf,pdf_sha256=:sha,version=version+1 WHERE id=:id")
            .param("a", access.actor().bytes()).param("t", now.stamp()).param("pdf", pdf).param("sha", sha).param("id", sofId.bytes()).update()
        audit.record(AuditEntry(AuditAction.STATUS_CHANGE, SOF_ENTITY, sofId, job.branchId,
            mapOf("jobId" to jobId, "revisionNo" to sof.revisionNo, "to" to "ISSUED", "pdfSha256" to sha)))
        return sofRevisions(job)
    }

    @Transactional
    fun withdrawSof(jobId: UUID, sofId: UUID, expectedVersion: Long): List<SofRevisionDto> {
        val job = access.job(jobId, Permissions.OPS_LIVE_WRITE)
        val sof = lockedSof(jobId, sofId, expectedVersion)
        if (sof.status != "PREPARED") throw BusinessRuleException("INVALID_TRANSITION", "Only a prepared Statement of Facts can be withdrawn.")
        jdbc.sql("UPDATE sof_revision SET status='WITHDRAWN',version=version+1 WHERE id=:id").param("id", sofId.bytes()).update()
        audit.record(AuditEntry(AuditAction.STATUS_CHANGE, SOF_ENTITY, sofId, job.branchId,
            mapOf("jobId" to jobId, "revisionNo" to sof.revisionNo, "to" to "WITHDRAWN")))
        return sofRevisions(job)
    }

    /** The issued PDF of a revision, or a DRAFT rendering (prepared revision, or the live events when no id is given). */
    @Transactional(readOnly = true)
    fun sofPdf(jobId: UUID, sofId: UUID?): Pair<String, ByteArray> {
        val job = access.job(jobId, Permissions.OPS_LIVE_READ)
        val base = "${job.jobNumber.replace('/', '-')}-SOF"
        if (sofId == null) return "$base-draft.pdf" to output.pdf(document(job, null, sofEvents(job), draft = true, null, null))
        val sof = sofRow(jobId, sofId)
        val stored = jdbc.sql("SELECT pdf FROM sof_revision WHERE id=:id").param("id", sofId.bytes()).query { rs, _ -> rs.getBytes(1) }.optional().orElse(null)
        return if (stored != null) "$base-r${sof.revisionNo}.pdf" to stored
        else "$base-r${sof.revisionNo}-draft.pdf" to output.pdf(document(job, sof.revisionNo, readEvents(sof.eventsJson), draft = true, null, null))
    }

    // ---------- reads for the console ----------

    internal fun events(job: JobTechnicalRef): List<OperationEventDto> {
        val types = types()
        val rows = jdbc.sql("""SELECT id,type_code,job_vessel_id,occurred_at,started_at,ended_at,remarks,source,status,void_reason,recorded_by,
            recorded_at,version FROM operation_event WHERE job_id=:job""").param("job", job.id.bytes()).query { rs, _ ->
            EventRow(rs.getBytes(1).uuid()!!, rs.getString(2), rs.getBytes(3).uuid(), rs.getObject(4, LocalDateTime::class.java).instant(),
                rs.getObject(5, LocalDateTime::class.java).instant(), rs.getObject(6, LocalDateTime::class.java).instant(), rs.getString(7),
                rs.getString(8), rs.getString(9), rs.getString(10), rs.getBytes(11).uuid()!!, rs.getObject(12, LocalDateTime::class.java).instant()!!,
                rs.getLong(13))
        }.list()
        val names = users.names(rows.map { it.recordedBy })
        val corrected = history.corrected(HISTORY, rows.map { it.id })
        val captured=jdbc.sql("SELECT c.event_id,c.client_event_id,c.device_recorded_at FROM operation_event_capture c JOIN operation_event e ON e.id=c.event_id WHERE e.job_id=:job")
            .param("job",job.id.bytes()).query { rs,_->rs.getBytes(1).uuid()!! to (rs.getBytes(2).uuid()!! to rs.getObject(3,LocalDateTime::class.java).instant()!!) }.list().toMap()
        return rows.sortedWith(compareBy({ it.occurredAt ?: it.startedAt }, { it.recordedAt })).map { r ->
            val t = types[r.typeCode]
            OperationEventDto(r.id, r.typeCode, t?.label ?: r.typeCode, t?.phase ?: "OTHER", t?.interval ?: false, r.jobVesselId, r.occurredAt,
                r.startedAt, r.endedAt, r.remarks, r.source, r.status, r.voidReason, names[r.recordedBy], r.recordedAt, r.id in corrected, r.version,
                captured[r.id]?.first,captured[r.id]?.second)
        }
    }

    internal fun sofRevisions(job: JobTechnicalRef): List<SofRevisionDto> {
        val rows = sofRows(job.id)
        val names = users.names(rows.flatMap { listOfNotNull(it.preparedBy, it.issuedBy) })
        val canIssue = access.can(job, Permissions.OPS_LIVE_APPROVE)
        val canWrite = access.can(job, Permissions.OPS_LIVE_WRITE)
        return rows.sortedByDescending { it.revisionNo }.map { s ->
            SofRevisionDto(s.id, s.revisionNo, s.status, readEvents(s.eventsJson).size, names[s.preparedBy], s.preparedAt, s.issuedBy?.let(names::get),
                s.issuedAt, s.pdfSha256, canIssue = s.status == "PREPARED" && canIssue && s.preparedBy != access.actor(),
                canWithdraw = s.status == "PREPARED" && canWrite, version = s.version)
        }
    }

    internal fun types(): Map<String, ListItemDto> =
        jdbc.sql("SELECT code,label,sort_order,active,version,phase,is_interval FROM operation_event_type ORDER BY sort_order,code").query { rs, _ ->
            ListItemDto(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getBoolean(4), rs.getLong(5), rs.getString(6), rs.getBoolean(7))
        }.list().associateBy { it.code }

    // ---------- helpers ----------

    private data class EventRow(
        val id: UUID, val typeCode: String, val jobVesselId: UUID?, val occurredAt: Instant?, val startedAt: Instant?, val endedAt: Instant?,
        val remarks: String?, val source: String, val status: String, val voidReason: String?, val recordedBy: UUID, val recordedAt: Instant,
        val version: Long,
    )

    private data class SofRow(
        val id: UUID, val revisionNo: Int, val status: String, val eventsJson: String, val preparedBy: UUID, val preparedAt: Instant,
        val issuedBy: UUID?, val issuedAt: Instant?, val pdfSha256: String?, val version: Long,
    )

    private fun validate(job: JobTechnicalRef, input: EventInput): EventInput {
        val errors = mutableListOf<FieldError>()
        val type = types()[input.typeCode]
        val latest = clock.instant().plus(5, ChronoUnit.MINUTES)
        if (type == null || !type.active) errors += FieldError("typeCode", "unknown", "Choose an event type.")
        if (input.jobVesselId != null && job.vessels.none { it.id == input.jobVesselId }) errors += FieldError("jobVesselId", "unknown", null)
        if (input.source !in EVENT_SOURCES) errors += FieldError("source", "allowed", null)
        if ((input.remarks?.length ?: 0) > 1000) errors += FieldError("remarks", "size", "At most 1000 characters.")
        if (type?.interval == true) {
            if (input.startedAt == null) errors += FieldError("startedAt", "required", null)
            else if (input.startedAt.isAfter(latest)) errors += FieldError("startedAt", "future", "Cannot be in the future.")
            if (input.endedAt != null) {
                if (input.endedAt.isAfter(latest)) errors += FieldError("endedAt", "future", "Cannot be in the future.")
                if (input.startedAt != null && input.endedAt.isBefore(input.startedAt)) errors += FieldError("endedAt", "order", "Must be after the start.")
            }
        } else if (type != null) {
            if (input.occurredAt == null) errors += FieldError("occurredAt", "required", null)
            else if (input.occurredAt.isAfter(latest)) errors += FieldError("occurredAt", "future", "Cannot be in the future.")
        }
        if (errors.isNotEmpty()) throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", errors)
        val interval = type!!.interval == true
        return input.copy(
            occurredAt = if (interval) null else input.occurredAt!!.truncatedTo(ChronoUnit.MINUTES),
            startedAt = if (interval) input.startedAt!!.truncatedTo(ChronoUnit.MINUTES) else null,
            endedAt = if (interval) input.endedAt?.truncatedTo(ChronoUnit.MINUTES) else null,
            remarks = input.remarks?.trim()?.ifEmpty { null },
        )
    }

    private fun fields(e: EventInput): Map<String, Any?> = mapOf("type" to e.typeCode, "v" to e.jobVesselId?.bytes(),
        "at" to e.occurredAt?.stamp(), "start" to e.startedAt?.stamp(), "end" to e.endedAt?.stamp(), "remarks" to e.remarks, "source" to e.source)

    private fun summary(e: EventInput) = mapOf("typeCode" to e.typeCode, "jobVesselId" to e.jobVesselId, "occurredAt" to e.occurredAt,
        "startedAt" to e.startedAt, "endedAt" to e.endedAt, "source" to e.source)

    private fun row(jobId: UUID, eventId: UUID): EventRow {
        val owner = jdbc.sql("SELECT job_id FROM operation_event WHERE id=:id").param("id", eventId.bytes())
            .query { rs, _ -> rs.getBytes(1).uuid() }.optional().orElse(null)
        if (owner != jobId) throw NotFoundException("Operation event")
        return jdbc.sql("""SELECT id,type_code,job_vessel_id,occurred_at,started_at,ended_at,remarks,source,status,void_reason,recorded_by,
            recorded_at,version FROM operation_event WHERE id=:id""").param("id", eventId.bytes()).query { rs, _ ->
            EventRow(rs.getBytes(1).uuid()!!, rs.getString(2), rs.getBytes(3).uuid(), rs.getObject(4, LocalDateTime::class.java).instant(),
                rs.getObject(5, LocalDateTime::class.java).instant(), rs.getObject(6, LocalDateTime::class.java).instant(), rs.getString(7),
                rs.getString(8), rs.getString(9), rs.getString(10), rs.getBytes(11).uuid()!!, rs.getObject(12, LocalDateTime::class.java).instant()!!,
                rs.getLong(13))
        }.single()
    }

    private fun locked(jobId: UUID, eventId: UUID, expectedVersion: Long): EventRow {
        row(jobId, eventId)
        jdbc.sql("SELECT id FROM operation_event WHERE id=:id FOR UPDATE").param("id", eventId.bytes()).query { rs, _ -> rs.getBytes(1) }.single()
        return row(jobId, eventId).also { Versioning.checkCurrent(expectedVersion, it.version) }
    }

    private fun sofRows(jobId: UUID): List<SofRow> =
        jdbc.sql("""SELECT id,revision_no,status,events_json,prepared_by,prepared_at,issued_by,issued_at,pdf_sha256,version FROM sof_revision
            WHERE job_id=:job""").param("job", jobId.bytes()).query { rs, _ ->
            SofRow(rs.getBytes(1).uuid()!!, rs.getInt(2), rs.getString(3), rs.getString(4), rs.getBytes(5).uuid()!!,
                rs.getObject(6, LocalDateTime::class.java).instant()!!, rs.getBytes(7).uuid(), rs.getObject(8, LocalDateTime::class.java).instant(),
                rs.getString(9), rs.getLong(10))
        }.list()

    private fun sofRow(jobId: UUID, sofId: UUID): SofRow = sofRows(jobId).firstOrNull { it.id == sofId } ?: throw NotFoundException("Statement of Facts")

    private fun lockedSof(jobId: UUID, sofId: UUID, expectedVersion: Long): SofRow {
        sofRow(jobId, sofId)
        jdbc.sql("SELECT id FROM sof_revision WHERE id=:id FOR UPDATE").param("id", sofId.bytes()).query { rs, _ -> rs.getBytes(1) }.single()
        return sofRow(jobId, sofId).also { Versioning.checkCurrent(expectedVersion, it.version) }
    }

    /** Active events as printed on the SOF, in time order. */
    private fun sofEvents(job: JobTechnicalRef): List<SofEvent> {
        val vessels = references.vessels(job.vessels.map { it.vesselId })
        val names = job.vessels.associate { it.id to (vessels[it.vesselId]?.name ?: it.role) }
        return events(job).filter { it.status == "ACTIVE" }.map {
            SofEvent(it.typeLabel, it.phase, it.jobVesselId?.let(names::get), it.occurredAt, it.startedAt, it.endedAt, it.remarks, it.source)
        }
    }

    private fun readEvents(text: String): List<SofEvent> = json.readValue(text, Array<SofEvent>::class.java).toList()

    private fun document(job: JobTechnicalRef, revisionNo: Int?, events: List<SofEvent>, draft: Boolean, issuedAt: Instant?, issuedBy: String?): SofDocument {
        val vessels = references.vessels(job.vessels.map { it.vesselId })
        return SofDocument.build(job.jobNumber, revisionNo, job.vessels.sortedBy { if (it.role == "MOTHER") 0 else 1 }
            .map { (if (it.role == "MOTHER") "Discharging vessel" else "Receiving vessel") to (vessels[it.vesselId]?.name ?: "—") },
            events, access.zone(job), draft, issuedAt, issuedBy)
    }

    private fun requireReason(reason: String?): String {
        val r = reason?.trim().orEmpty()
        if (r.isEmpty()) throw BadRequestException("REASON_REQUIRED", "A reason is required for this change.", listOf(FieldError("reason", "required", null)))
        return r.take(500)
    }

    companion object {
        private const val ENTITY = "OPERATION_EVENT"
        private const val SOF_ENTITY = "SOF_REVISION"
        const val HISTORY = "EVENT"
        val EVENT_SOURCES = setOf("LIVE", "PAPER_LOG", "RADIO", "OTHER")
    }
}
