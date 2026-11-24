package com.ship2ship.features.movements

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.files.FileAccess
import com.ship2ship.core.files.FileService
import com.ship2ship.core.jobs.JobLookup
import com.ship2ship.core.jobs.JobTechnicalRef
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.BusinessRuleException
import com.ship2ship.core.web.FieldError
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.Versioning
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

internal fun UUID.bytes() = Uuids.toBytes(this)
internal fun ByteArray?.uuid() = Uuids.fromBytes(this)
internal fun ResultSet.instant(name: String): Instant? = getObject(name, LocalDateTime::class.java)?.atZone(ZoneOffset.UTC)?.toInstant()
internal fun bad(field: String, detail: String): Nothing = throw BadRequestException("VALIDATION_FAILED", detail,
    listOf(FieldError(field, "invalid", detail)))
internal fun reason(value: String?): String = value?.trim()?.takeIf { it.isNotEmpty() && it.length <= 500 }
    ?: bad("reason", "Enter a reason of at most 500 characters.")
internal fun note(value: String?, field: String): String? = value?.trim()?.takeIf { it.isNotEmpty() }?.also {
    if (it.length > 2000) bad(field, "Use at most 2000 characters.")
}

@Component
class MovementAccess(private val jobs: JobLookup, private val policy: AccessPolicy,
                     private val clock: Clock, private val jdbc: JdbcClient) {
    fun job(id: UUID, permission: String): JobTechnicalRef {
        val job = jobs.technical(id) ?: throw NotFoundException("Job")
        policy.requireBranch(Permissions.JOB_READ, job.branchId, "JOB", id)
        policy.requireBranch(permission, job.branchId, "JOB", id)
        if (!permission.endsWith(".READ")) jobs.requireWritable(id)
        return job
    }
    fun actor() = policy.currentUser().id
    fun now(): LocalDateTime = Uuids.toTimestamp(clock.instant().truncatedTo(ChronoUnit.MICROS))
    fun instant() = clock.instant().truncatedTo(ChronoUnit.MICROS)
    fun zone(job: JobTechnicalRef): ZoneId {
        val name = jdbc.sql("SELECT timezone FROM branch WHERE id=:id").param("id", job.branchId.bytes())
            .query(String::class.java).single()
        return ZoneId.of(name)
    }
}

data class MovementInput(val craftId: UUID? = null, val departedAt: Instant, val returnedAt: Instant? = null,
    val allocationIds: List<UUID> = emptyList(), val serviceNotes: String? = null, val standbyNotes: String? = null,
    val delayNotes: String? = null, val damageNotes: String? = null, val reason: String? = null)
data class DispatchInput(val departedAt: Instant)
data class MovementAssetDto(val allocationId: UUID, val assetId: UUID, val identifier: String, val category: String)
data class MovementFileDto(val id: UUID, val name: String, val scanState: String)
data class MovementDto(val id: UUID, val jobId: UUID, val craftId: UUID?, val craftIdentifier: String?,
    val vendorId: UUID?, val vendorName: String?, val departedAt: Instant, val returnedAt: Instant?,
    val timeZone: String, val assets: List<MovementAssetDto>, val serviceNotes: String?, val standbyNotes: String?,
    val delayNotes: String?, val damageNotes: String?, val status: String, val files: List<MovementFileDto>,
    val pendingAction: String?, val pendingJson: String?, val pendingReason: String?, val pendingBy: UUID?, val version: Long)
data class MovementPage(val items: List<MovementDto>, val page: Int, val size: Int, val totalElements: Long)
data class MovementChangeDto(val beforeJson: String, val reason: String, val changedAt: Instant, val changedBy: UUID)
data class ReturnInput(val returnedAt: Instant)
data class VoidInput(val reason: String)
data class MovementDecisionInput(val decision: String, val reason: String)
data class MovementContext(val jobNumber: String, val timeZone: String)

@Service
class MovementService(private val jdbc: JdbcClient, private val access: MovementAccess,
    private val files: FileService, private val json: JsonMapper, private val audit: AuditService) {

    @Transactional(readOnly = true)
    fun context(jobId: UUID): MovementContext {
        val job = access.job(jobId, Permissions.OPS_MOVEMENT_READ)
        return MovementContext(job.jobNumber, access.zone(job).id)
    }

    @Transactional(readOnly = true)
    fun list(jobId: UUID, page: Int, size: Int): MovementPage {
        access.job(jobId, Permissions.OPS_MOVEMENT_READ)
        if (page < 0) bad("page", "Page must be non-negative.")
        val limit = size.coerceIn(1, 100)
        val total = jdbc.sql("SELECT COUNT(*) FROM craft_movement WHERE job_id=:job")
            .param("job", jobId.bytes()).query(Long::class.java).single()
        val ids = jdbc.sql("SELECT id FROM craft_movement WHERE job_id=:job ORDER BY departed_at DESC,id DESC LIMIT :limit OFFSET :offset")
            .param("job", jobId.bytes()).param("limit", limit).param("offset", page * limit)
            .query { rs, _ -> rs.getBytes(1).uuid()!! }.list()
        return MovementPage(ids.map { row(jobId, it) }, page, limit, total)
    }

    @Transactional(readOnly = true)
    fun get(jobId: UUID, id: UUID): MovementDto {
        access.job(jobId, Permissions.OPS_MOVEMENT_READ)
        return row(jobId, id)
    }

    @Transactional
    fun dispatch(jobId: UUID, input: DispatchInput): MovementDto {
        access.job(jobId, Permissions.OPS_MOVEMENT_WRITE)
        val at = input.departedAt
        val allocationIds = jdbc.sql("""SELECT a.id FROM job_resource_allocation a
            JOIN equipment_asset e ON e.id=a.asset_id JOIN equipment_model m ON m.id=e.model_id
            WHERE a.job_id=:job AND a.status='ACTIVE' AND a.start_at<=:at AND a.end_at>:at
            AND m.category_code IN ('PRIMARY_FENDER','SECONDARY_FENDER','CARGO_HOSE','VAPOUR_HOSE')""")
            .param("job", jobId.bytes()).param("at", Uuids.toTimestamp(at))
            .query { rs, _ -> rs.getBytes(1).uuid()!! }.list()
        return create(jobId, MovementInput(departedAt = at, allocationIds = allocationIds))
    }

    @Transactional
    fun create(jobId: UUID, input: MovementInput): MovementDto {
        val job = access.job(jobId, Permissions.OPS_MOVEMENT_WRITE)
        lockRecoveryOpen(jobId)
        if (jdbc.sql("SELECT COUNT(*) FROM craft_movement WHERE job_id=:job AND status='ACTIVE'")
                .param("job", jobId.bytes()).query(Long::class.java).single() > 0)
            throw BusinessRuleException("INVALID_TRANSITION", "Only one active jetty departure is allowed per job.")
        validate(input)
        val vendor = input.craftId?.let { vendor(job, it, input.departedAt) }
        val assets = assets(jobId, input.allocationIds, input.departedAt)
        val id = UuidV7.generate()
        val now = access.now()
        jdbc.sql("""INSERT INTO craft_movement
            (id,job_id,craft_id,vendor_id,departed_at,returned_at,time_zone,service_notes,standby_notes,delay_notes,damage_notes,
             created_at,created_by,updated_at,updated_by,version)
            VALUES (:id,:job,:craft,:vendor,:departed,:returned,:zone,:service,:standby,:delay,:damage,:now,:actor,:now,:actor,0)""")
            .params(mapOf("id" to id.bytes(), "job" to jobId.bytes(), "craft" to input.craftId?.bytes(),
                "vendor" to vendor?.bytes(), "departed" to Uuids.toTimestamp(input.departedAt),
                "returned" to input.returnedAt?.let(Uuids::toTimestamp), "zone" to access.zone(job).id,
                "service" to note(input.serviceNotes, "serviceNotes"), "standby" to note(input.standbyNotes, "standbyNotes"),
                "delay" to note(input.delayNotes, "delayNotes"), "damage" to note(input.damageNotes, "damageNotes"),
                "now" to now, "actor" to access.actor().bytes())).update()
        putAssets(id, assets)
        syncClock(jobId, id, input.returnedAt, now)
        audit.record(AuditEntry(AuditAction.CREATE, "CRAFT_MOVEMENT", id, job.branchId,
            mapOf("jobId" to jobId, "craftId" to input.craftId)))
        return row(jobId, id)
    }

    @Transactional
    fun returnToJetty(jobId: UUID, id: UUID, expected: Long, returnedAt: Instant): MovementDto {
        val job = access.job(jobId, Permissions.OPS_MOVEMENT_WRITE)
        lockRecoveryOpen(jobId)
        val old = row(jobId, id)
        Versioning.checkCurrent(expected, old.version)
        if (old.status != "ACTIVE" || old.returnedAt != null || old.pendingAction != null)
            throw BusinessRuleException("INVALID_TRANSITION", "Movement has already returned, was voided or has a pending correction.")
        if (!returnedAt.isAfter(old.departedAt)) bad("returnedAt", "Return must be after departure.")
        val now = access.now()
        jdbc.sql("UPDATE craft_movement SET returned_at=:returned,updated_at=:now,updated_by=:actor,version=version+1 WHERE id=:id AND version=:version")
            .param("returned", Uuids.toTimestamp(returnedAt)).param("now", now).param("actor", access.actor().bytes())
            .param("id", id.bytes()).param("version", expected).update().also { if (it != 1) Versioning.checkCurrent(expected, row(jobId, id).version) }
        syncClock(jobId, id, returnedAt, now)
        history(id, old, "Return recorded")
        audit.record(AuditEntry(AuditAction.UPDATE, "CRAFT_MOVEMENT", id, job.branchId,
            mapOf("returnedAt" to returnedAt)))
        return row(jobId, id)
    }

    @Transactional
    fun correct(jobId: UUID, id: UUID, expected: Long, input: MovementInput): MovementDto {
        val job = access.job(jobId, Permissions.OPS_MOVEMENT_WRITE)
        lockRecoveryOpen(jobId)
        val old = row(jobId, id)
        Versioning.checkCurrent(expected, old.version)
        if (old.status != "ACTIVE" || old.pendingAction != null)
            throw BusinessRuleException("INVALID_TRANSITION", "Only active movements without a pending change can be corrected.")
        validate(input)
        val why = reason(input.reason)
        input.craftId?.let { vendor(job, it, input.departedAt) }
        assets(jobId, input.allocationIds, input.departedAt)
        jdbc.sql("""UPDATE craft_movement SET pending_action='CORRECT',pending_json=:payload,pending_reason=:reason,
            pending_by=:actor,approved_by=NULL,updated_at=:now,updated_by=:actor,version=version+1
            WHERE id=:id AND version=:version""")
            .param("payload", json.writeValueAsString(input)).param("reason", why)
            .param("actor", access.actor().bytes()).param("now", access.now())
            .param("id", id.bytes()).param("version", expected)
            .update().also { if (it != 1) Versioning.checkCurrent(expected, row(jobId, id).version) }
        audit.record(AuditEntry(AuditAction.UPDATE, "CRAFT_MOVEMENT", id, job.branchId,
            mapOf("action" to "REQUEST_CORRECTION", "reason" to why)))
        return row(jobId, id)
    }

    @Transactional
    fun void(jobId: UUID, id: UUID, expected: Long, why: String): MovementDto {
        val job = access.job(jobId, Permissions.OPS_MOVEMENT_WRITE)
        lockRecoveryOpen(jobId)
        val old = row(jobId, id)
        Versioning.checkCurrent(expected, old.version)
        if (old.status != "ACTIVE" || old.pendingAction != null)
            throw BusinessRuleException("INVALID_TRANSITION", "Movement was voided or has a pending change.")
        val clean = reason(why)
        jdbc.sql("""UPDATE craft_movement SET pending_action='VOID',pending_reason=:reason,pending_by=:actor,
            approved_by=NULL,updated_at=:now,updated_by=:actor,version=version+1 WHERE id=:id AND version=:version""")
            .param("reason", clean).param("now", access.now()).param("actor", access.actor().bytes()).param("id", id.bytes())
            .param("version", expected).update().also { if (it != 1) Versioning.checkCurrent(expected, row(jobId, id).version) }
        audit.record(AuditEntry(AuditAction.UPDATE, "CRAFT_MOVEMENT", id, job.branchId,
            mapOf("action" to "REQUEST_VOID", "reason" to clean)))
        return row(jobId, id)
    }

    @Transactional
    fun decide(jobId: UUID, id: UUID, expected: Long, input: MovementDecisionInput): MovementDto {
        val job = access.job(jobId, Permissions.OPS_MOVEMENT_CHANGE_APPROVE)
        lockRecoveryOpen(jobId)
        jdbc.sql("SELECT id FROM craft_movement WHERE id=:id AND job_id=:job FOR UPDATE")
            .param("id", id.bytes()).param("job", jobId.bytes())
            .query { rs, _ -> rs.getBytes(1) }.optional().orElseThrow { NotFoundException("Movement") }
        val old = row(jobId, id)
        Versioning.checkCurrent(expected, old.version)
        val action = old.pendingAction ?: throw BusinessRuleException("INVALID_TRANSITION", "No movement change is awaiting approval.")
        if (old.pendingBy == access.actor()) throw BusinessRuleException("SELF_APPROVAL_FORBIDDEN", "A second person must decide the change.")
        val why = reason(input.reason)
        if (input.decision !in setOf("APPROVE", "RETURN")) bad("decision", "Choose APPROVE or RETURN.")
        val now = access.now()
        if (input.decision == "APPROVE" && action == "CORRECT") {
            val pending = json.readValue(old.pendingJson!!, MovementInput::class.java)
            validate(pending)
            val vendor = pending.craftId?.let { vendor(job, it, pending.departedAt) }
            val newAssets = assets(jobId, pending.allocationIds, pending.departedAt)
            jdbc.sql("""UPDATE craft_movement SET craft_id=:craft,vendor_id=:vendor,departed_at=:departed,returned_at=:returned,
                service_notes=:service,standby_notes=:standby,delay_notes=:delay,damage_notes=:damage WHERE id=:id""")
                .params(mapOf("craft" to pending.craftId?.bytes(), "vendor" to vendor?.bytes(),
                    "departed" to Uuids.toTimestamp(pending.departedAt), "returned" to pending.returnedAt?.let(Uuids::toTimestamp),
                    "service" to note(pending.serviceNotes, "serviceNotes"), "standby" to note(pending.standbyNotes, "standbyNotes"),
                    "delay" to note(pending.delayNotes, "delayNotes"), "damage" to note(pending.damageNotes, "damageNotes"),
                    "id" to id.bytes())).update()
            jdbc.sql("UPDATE movement_asset SET status='REMOVED' WHERE movement_id=:id").param("id", id.bytes()).update()
            putAssets(id, newAssets)
            syncClock(jobId, id, pending.returnedAt, now)
        }
        if (input.decision == "APPROVE" && action == "VOID") {
            jdbc.sql("UPDATE craft_movement SET status='VOIDED' WHERE id=:id").param("id", id.bytes()).update()
        }
        jdbc.sql("""UPDATE craft_movement SET pending_action=NULL,pending_json=NULL,pending_reason=NULL,
            approved_by=:approver,updated_at=:now,updated_by=:approver,version=version+1 WHERE id=:id""")
            .param("approver", access.actor().bytes()).param("now", now).param("id", id.bytes()).update()
        history(id, old, "${input.decision} $action: $why")
        audit.record(AuditEntry(AuditAction.UPDATE, "CRAFT_MOVEMENT", id, job.branchId,
            mapOf("decision" to input.decision, "action" to action, "reason" to why)))
        return row(jobId, id)
    }

    @Transactional(readOnly = true)
    fun history(jobId: UUID, id: UUID): List<MovementChangeDto> {
        access.job(jobId, Permissions.OPS_MOVEMENT_READ)
        row(jobId, id)
        return jdbc.sql("SELECT before_json,reason,changed_at,changed_by FROM movement_change WHERE movement_id=:id ORDER BY changed_at,id")
            .param("id", id.bytes()).query { rs, _ -> MovementChangeDto(rs.getString(1), rs.getString(2),
                rs.instant("changed_at")!!, rs.getBytes(4).uuid()!!) }.list()
    }

    @Transactional
    fun upload(jobId: UUID, id: UUID, upload: MultipartFile): MovementFileDto {
        val job = access.job(jobId, Permissions.OPS_MOVEMENT_WRITE)
        lockRecoveryOpen(jobId)
        row(jobId, id)
        val file = files.store(upload, FileAccess(Permissions.OPS_MOVEMENT_READ, job.branchId))
        jdbc.sql("INSERT INTO movement_file (movement_id,file_id) VALUES (:movement,:file)")
            .param("movement", id.bytes()).param("file", file.id.bytes()).update()
        audit.record(AuditEntry(AuditAction.CREATE, "MOVEMENT_FILE", file.id, job.branchId,
            mapOf("movementId" to id)))
        return MovementFileDto(file.id, file.originalName, file.scanState.name)
    }

    private fun lockRecoveryOpen(jobId: UUID) {
        jdbc.sql("INSERT IGNORE INTO job_recovery (job_id,status,updated_at,updated_by) VALUES (:job,'OPEN',:now,:actor)")
            .param("job", jobId.bytes()).param("now", access.now()).param("actor", access.actor().bytes()).update()
        val status = jdbc.sql("SELECT status FROM job_recovery WHERE job_id=:job FOR UPDATE")
            .param("job", jobId.bytes()).query(String::class.java).single()
        if (status != "OPEN") throw BusinessRuleException("INVALID_TRANSITION", "Recovery is closed.")
    }

    internal fun row(jobId: UUID, id: UUID): MovementDto {
        val base = jdbc.sql("""SELECT m.*,c.identifier craft_identifier,v.name vendor_name FROM craft_movement m
            LEFT JOIN resource_craft c ON c.id=m.craft_id LEFT JOIN resource_vendor v ON v.id=m.vendor_id
            WHERE m.job_id=:job AND m.id=:id""").param("job", jobId.bytes()).param("id", id.bytes())
            .query { rs, _ -> MovementDto(id, jobId, rs.getBytes("craft_id").uuid(), rs.getString("craft_identifier"),
                rs.getBytes("vendor_id").uuid(), rs.getString("vendor_name"), rs.instant("departed_at")!!,
                rs.instant("returned_at"), rs.getString("time_zone"), emptyList(), rs.getString("service_notes"),
                rs.getString("standby_notes"), rs.getString("delay_notes"), rs.getString("damage_notes"),
                rs.getString("status"), emptyList(), rs.getString("pending_action"), rs.getString("pending_json"), rs.getString("pending_reason"),
                rs.getBytes("pending_by").uuid(), rs.getLong("version")) }.optional().orElseThrow { NotFoundException("Movement") }
        val assets = jdbc.sql("SELECT allocation_id,asset_id,identifier,category_code FROM movement_asset WHERE movement_id=:id AND status='ACTIVE'")
            .param("id", id.bytes()).query { rs, _ -> MovementAssetDto(rs.getBytes(1).uuid()!!, rs.getBytes(2).uuid()!!,
                rs.getString(3), rs.getString(4)) }.list()
        val evidence = jdbc.sql("""SELECT f.id,f.original_name,f.scan_state FROM movement_file mf
            JOIN stored_file f ON f.id=mf.file_id WHERE mf.movement_id=:id""")
            .param("id", id.bytes()).query { rs, _ -> MovementFileDto(rs.getBytes(1).uuid()!!, rs.getString(2), rs.getString(3)) }.list()
        return base.copy(assets = assets, files = evidence)
    }

    private fun validate(input: MovementInput) {
        if (input.returnedAt != null && !input.returnedAt.isAfter(input.departedAt)) bad("returnedAt", "Return must be after departure.")
        if (input.allocationIds.size != input.allocationIds.distinct().size) bad("allocationIds", "An asset may be listed only once per trip.")
        note(input.serviceNotes, "serviceNotes"); note(input.standbyNotes, "standbyNotes")
        note(input.delayNotes, "delayNotes"); note(input.damageNotes, "damageNotes")
    }

    private fun vendor(job: JobTechnicalRef, craftId: UUID, departedAt: Instant): UUID = jdbc.sql("""SELECT c.vendor_id FROM resource_craft c
        JOIN resource_vendor v ON v.id=c.vendor_id WHERE c.id=:craft AND c.active=1 AND v.active=1
        AND EXISTS (SELECT 1 FROM job_resource_allocation a WHERE a.job_id=:job AND a.craft_id=c.id
        AND a.status='ACTIVE' AND a.start_at<=:at AND a.end_at>:at)""")
        .param("craft", craftId.bytes())
        .param("job", job.id.bytes()).param("at", Uuids.toTimestamp(departedAt))
        .query { rs, _ -> rs.getBytes(1).uuid()!! }.optional().orElseThrow { bad("craftId", "Choose a craft allocated to this job at departure.") }

    private fun assets(jobId: UUID, ids: List<UUID>, departedAt: Instant): List<MovementAssetDto> = ids.map { id ->
        jdbc.sql("""SELECT a.asset_id,e.asset_identifier,m.category_code FROM job_resource_allocation a
            JOIN equipment_asset e ON e.id=a.asset_id JOIN equipment_model m ON m.id=e.model_id
            WHERE a.id=:id AND a.job_id=:job AND a.status='ACTIVE' AND a.start_at<=:at AND a.end_at>:at
            AND m.category_code IN ('PRIMARY_FENDER','SECONDARY_FENDER','CARGO_HOSE','VAPOUR_HOSE')""")
            .param("id", id.bytes()).param("job", jobId.bytes()).param("at", Uuids.toTimestamp(departedAt))
            .query { rs, _ -> MovementAssetDto(id, rs.getBytes(1).uuid()!!, rs.getString(2), rs.getString(3)) }
            .optional().orElseThrow { bad("allocationIds", "Choose an active fender or hose allocation covering departure.") }
    }

    private fun putAssets(id: UUID, assets: List<MovementAssetDto>) = assets.forEach { a ->
        jdbc.sql("""INSERT INTO movement_asset (movement_id,allocation_id,asset_id,identifier,category_code,status)
            VALUES (:m,:a,:asset,:identifier,:category,'ACTIVE') ON DUPLICATE KEY UPDATE status='ACTIVE'""")
            .param("m", id.bytes()).param("a", a.allocationId.bytes()).param("asset", a.assetId.bytes())
            .param("identifier", a.identifier).param("category", a.category).update()
    }

    private fun syncClock(jobId: UUID, movementId: UUID, returnedAt: Instant?, now: LocalDateTime) {
        val basis = jdbc.sql("SELECT base_hours FROM tariff_snapshot WHERE job_id=:job")
            .param("job", jobId.bytes()).query(Int::class.java).optional().orElse(48)
        val stop = if (returnedAt != null) movementId.bytes() else null
        jdbc.sql("""INSERT INTO job_charge_clock (job_id,start_movement_id,stop_movement_id,basis_hours,created_at,created_by,updated_at,updated_by,version)
            VALUES (:job,:start,:stop,:basis,:now,:actor,:now,:actor,0)
            ON DUPLICATE KEY UPDATE start_movement_id=:start,stop_movement_id=:stop,pending_start_id=NULL,pending_stop_id=NULL,pending_reason=NULL,updated_at=:now,updated_by=:actor""")
            .param("job", jobId.bytes()).param("start", movementId.bytes()).param("stop", stop)
            .param("basis", basis).param("now", now).param("actor", access.actor().bytes()).update()
    }

    private fun history(id: UUID, before: MovementDto, why: String) {
        jdbc.sql("INSERT INTO movement_change (id,movement_id,before_json,reason,changed_at,changed_by) VALUES (:id,:m,:before,:reason,:at,:by)")
            .param("id", UuidV7.generate().bytes()).param("m", id.bytes())
            .param("before", json.writeValueAsString(before)).param("reason", why)
            .param("at", access.now()).param("by", access.actor().bytes()).update()
    }
}
