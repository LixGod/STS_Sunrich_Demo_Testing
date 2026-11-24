package com.ship2ship.features.movements

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.files.FileAccess
import com.ship2ship.core.files.FileService
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.web.BusinessRuleException
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.Versioning
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Isolation
import org.springframework.web.multipart.MultipartFile
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

data class RecoveryAssetDto(val allocationId: UUID, val identifier: String, val category: String,
    val status: String, val notes: String?, val fileId: UUID?, val resolutionReason: String?,
    val resolvedBy: UUID?, val version: Long)
data class RecoveryOverview(val jobId: UUID, val disembarkedAt: Instant?, val status: String,
    val assets: List<RecoveryAssetDto>, val unresolved: List<UUID>, val version: Long)
data class RecoveryChangeDto(val allocationId: UUID?, val beforeJson: String, val reason: String,
    val changedAt: Instant, val changedBy: UUID)
data class RecoveryInput(val status: String, val notes: String? = null, val reason: String? = null)
data class DisembarkInput(val at: Instant, val reason: String? = null)
data class ResolutionInput(val reason: String)

@Service
class RecoveryService(private val jdbc: JdbcClient, private val access: MovementAccess,
    private val files: FileService, private val json: JsonMapper, private val audit: AuditService) {

    @Transactional(readOnly = true)
    fun overview(jobId: UUID): RecoveryOverview {
        access.job(jobId, Permissions.OPS_RECOVERY_READ)
        val state = jdbc.sql("SELECT disembarked_at,status,version FROM job_recovery WHERE job_id=:job")
            .param("job", jobId.bytes()).query { rs, _ -> Triple(rs.instant("disembarked_at"), rs.getString(2), rs.getLong(3)) }
            .optional().orElse(Triple(null, "OPEN", 0))
        val assets = jdbc.sql("""SELECT ma.allocation_id,ma.identifier,ma.category_code,ra.status,ra.notes,ra.file_id,
            ra.resolution_reason,ra.resolved_by,ra.version FROM movement_asset ma
            JOIN craft_movement m ON m.id=ma.movement_id
            LEFT JOIN recovery_asset ra ON ra.job_id=m.job_id AND ra.allocation_id=ma.allocation_id
            WHERE m.job_id=:job AND m.status='ACTIVE' AND ma.status='ACTIVE'
            GROUP BY ma.allocation_id,ma.identifier,ma.category_code,ra.status,ra.notes,ra.file_id,ra.resolution_reason,ra.resolved_by,ra.version
            ORDER BY ma.identifier""")
            .param("job", jobId.bytes()).query { rs, _ -> RecoveryAssetDto(rs.getBytes(1).uuid()!!, rs.getString(2),
                rs.getString(3), rs.getString(4) ?: "UNRECORDED", rs.getString(5), rs.getBytes(6).uuid(),
                rs.getString(7), rs.getBytes(8).uuid(), rs.getLong(9)) }.list()
        val unresolved = assets.filter { it.status == "UNRECORDED" ||
            (it.status in setOf("DAMAGED", "SHORT") && it.resolutionReason == null) }.map { it.allocationId }
        return RecoveryOverview(jobId, state.first, state.second, assets, unresolved, state.third)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun disembark(jobId: UUID, expected: Long?, input: DisembarkInput): RecoveryOverview {
        val job = access.job(jobId, Permissions.OPS_RECOVERY_WRITE)
        if (input.at.isAfter(access.instant().plusSeconds(300))) bad("at", "Disembarkation cannot be in the future.")
        ensure(jobId)
        lockOpen(jobId)
        val old = overview(jobId)
        Versioning.checkCurrent(expected ?: bad("version", "If-Match is required."), old.version)
        if (old.status != "OPEN") throw BusinessRuleException("INVALID_TRANSITION", "Recovery is closed.")
        if (old.disembarkedAt != null) reason(input.reason)
        jdbc.sql("""UPDATE job_recovery SET disembarked_at=:at,updated_at=:now,updated_by=:actor,version=version+1
            WHERE job_id=:job AND version=:version""")
            .param("at", Uuids.toTimestamp(input.at)).param("now", access.now()).param("actor", access.actor().bytes())
            .param("job", jobId.bytes()).param("version", old.version).update()
        change(jobId, null, json.writeValueAsString(old), input.reason ?: "Disembarkation recorded")
        audit.record(AuditEntry(AuditAction.UPDATE, "JOB_RECOVERY", jobId, job.branchId,
            mapOf("disembarkedAt" to input.at)))
        return overview(jobId)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun record(jobId: UUID, allocationId: UUID, expected: Long?, input: RecoveryInput): RecoveryOverview {
        val job = access.job(jobId, Permissions.OPS_RECOVERY_WRITE)
        ensure(jobId)
        lockOpen(jobId)
        val old = overview(jobId)
        if (old.status != "OPEN") throw BusinessRuleException("INVALID_TRANSITION", "Recovery is closed.")
        val asset = old.assets.firstOrNull { it.allocationId == allocationId } ?: throw NotFoundException("Dispatched asset")
        if (input.status !in setOf("RETURNED", "DAMAGED", "SHORT")) bad("status", "Choose returned, damaged or short.")
        if (asset.status != "UNRECORDED") {
            Versioning.checkCurrent(expected ?: bad("version", "If-Match is required."), asset.version)
            reason(input.reason)
        }
        val notes = note(input.notes, "notes")
        if (notes != null && notes.length > 1000) bad("notes", "Use at most 1000 characters.")
        if (input.status != "RETURNED" && notes == null) bad("notes", "Describe the damage or shortage.")
        if (asset.status == "UNRECORDED") {
            jdbc.sql("""INSERT INTO recovery_asset (id,job_id,allocation_id,status,notes,updated_at,updated_by)
                VALUES (:id,:job,:allocation,:status,:notes,:now,:actor)""")
                .param("id", UuidV7.generate().bytes()).param("job", jobId.bytes())
                .param("allocation", allocationId.bytes()).param("status", input.status).param("notes", notes)
                .param("now", access.now()).param("actor", access.actor().bytes()).update()
        } else {
            jdbc.sql("""UPDATE recovery_asset SET status=:status,notes=:notes,resolution_reason=NULL,resolved_by=NULL,
                resolved_at=NULL,updated_at=:now,updated_by=:actor,version=version+1
                WHERE job_id=:job AND allocation_id=:allocation AND version=:version""")
                .param("status", input.status).param("notes", notes).param("now", access.now())
                .param("actor", access.actor().bytes()).param("job", jobId.bytes())
                .param("allocation", allocationId.bytes()).param("version", asset.version)
                .update().also { if (it != 1) Versioning.checkCurrent(asset.version, overview(jobId).assets.first { a -> a.allocationId == allocationId }.version) }
        }
        change(jobId, allocationId, json.writeValueAsString(asset), input.reason ?: "Return recorded")
        audit.record(AuditEntry(AuditAction.UPDATE, "RECOVERY_ASSET", allocationId, job.branchId,
            mapOf("status" to input.status, "reason" to input.reason)))
        return overview(jobId)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun resolve(jobId: UUID, allocationId: UUID, expected: Long, input: ResolutionInput): RecoveryOverview {
        val job = access.job(jobId, Permissions.OPS_RECOVERY_APPROVE)
        lockOpen(jobId)
        val old = overview(jobId)
        if (old.status != "OPEN") throw BusinessRuleException("INVALID_TRANSITION", "Recovery is closed.")
        val asset = old.assets.firstOrNull { it.allocationId == allocationId } ?: throw NotFoundException("Dispatched asset")
        Versioning.checkCurrent(expected, asset.version)
        if (asset.status !in setOf("DAMAGED", "SHORT") || asset.resolutionReason != null)
            throw BusinessRuleException("INVALID_TRANSITION", "Only an unresolved damage or shortage can be resolved.")
        val why = reason(input.reason)
        jdbc.sql("""UPDATE recovery_asset SET resolution_reason=:reason,resolved_by=:actor,resolved_at=:now,
            updated_at=:now,updated_by=:actor,version=version+1 WHERE job_id=:job AND allocation_id=:allocation AND version=:version""")
            .param("reason", why).param("actor", access.actor().bytes()).param("now", access.now())
            .param("job", jobId.bytes()).param("allocation", allocationId.bytes()).param("version", expected)
            .update().also { if (it != 1) Versioning.checkCurrent(expected, overview(jobId).assets.first { a -> a.allocationId == allocationId }.version) }
        change(jobId, allocationId, json.writeValueAsString(asset), why)
        audit.record(AuditEntry(AuditAction.UPDATE, "RECOVERY_ASSET", allocationId, job.branchId,
            mapOf("action" to "RESOLVE", "reason" to why)))
        return overview(jobId)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun upload(jobId: UUID, allocationId: UUID, file: MultipartFile): RecoveryOverview {
        val job = access.job(jobId, Permissions.OPS_RECOVERY_WRITE)
        lockOpen(jobId)
        val recovery = overview(jobId)
        if (recovery.status != "OPEN") throw BusinessRuleException("INVALID_TRANSITION", "Recovery is closed.")
        val asset = recovery.assets.firstOrNull { it.allocationId == allocationId } ?: throw NotFoundException("Dispatched asset")
        if (asset.status == "UNRECORDED") bad("file", "Record the asset return before attaching evidence.")
        val stored = files.store(file, FileAccess(Permissions.OPS_RECOVERY_READ, job.branchId))
        jdbc.sql("""UPDATE recovery_asset SET file_id=:file,updated_at=:now,updated_by=:actor,version=version+1
            WHERE job_id=:job AND allocation_id=:allocation""")
            .param("file", stored.id.bytes()).param("now", access.now()).param("actor", access.actor().bytes())
            .param("job", jobId.bytes()).param("allocation", allocationId.bytes()).update()
        change(jobId, allocationId, json.writeValueAsString(asset), "Evidence attached")
        audit.record(AuditEntry(AuditAction.CREATE, "RECOVERY_FILE", stored.id, job.branchId,
            mapOf("allocationId" to allocationId)))
        return overview(jobId)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun close(jobId: UUID, expected: Long): RecoveryOverview {
        val job = access.job(jobId, Permissions.OPS_RECOVERY_APPROVE)
        ensure(jobId)
        jdbc.sql("SELECT job_id FROM job_recovery WHERE job_id=:job FOR UPDATE")
            .param("job", jobId.bytes()).query { rs, _ -> rs.getBytes(1) }.single()
        val old = overview(jobId)
        Versioning.checkCurrent(expected, old.version)
        if (old.status != "OPEN") throw BusinessRuleException("INVALID_TRANSITION", "Recovery is closed.")
        if (old.disembarkedAt == null || old.unresolved.isNotEmpty())
            throw BusinessRuleException("RECOVERY_DISCREPANCY", "Record disembarkation and resolve every dispatched asset before closing.")
        jdbc.sql("""UPDATE job_recovery SET status='CLOSED',closed_at=:now,closed_by=:actor,
            updated_at=:now,updated_by=:actor,version=version+1 WHERE job_id=:job""")
            .param("now", access.now()).param("actor", access.actor().bytes()).param("job", jobId.bytes()).update()
        change(jobId, null, json.writeValueAsString(old), "Recovery closed")
        audit.record(AuditEntry(AuditAction.STATUS_CHANGE, "JOB_RECOVERY", jobId, job.branchId,
            mapOf("status" to "CLOSED")))
        // Damaged and short units leave the usable stock; the master status brings them back once repaired or found.
        old.assets.filter { it.status == "DAMAGED" || it.status == "SHORT" }.forEach { recorded ->
            val assetStatus = if (recorded.status == "DAMAGED") "DAMAGED" else "LOST"
            val assetId = jdbc.sql("SELECT asset_id FROM job_resource_allocation WHERE id=:a")
                .param("a", recorded.allocationId.bytes()).query { rs, _ -> rs.getBytes(1).uuid() }.list().firstOrNull() ?: return@forEach
            jdbc.sql("""UPDATE equipment_asset SET status=:s,updated_at=:now,updated_by=:actor,version=version+1
                WHERE id=:id AND status<>'RETIRED'""")
                .param("s", assetStatus).param("now", access.now()).param("actor", access.actor().bytes())
                .param("id", assetId.bytes()).update()
            audit.record(AuditEntry(AuditAction.STATUS_CHANGE, "EQUIPMENT_ASSET", assetId, job.branchId,
                mapOf("status" to assetStatus, "jobNumber" to job.jobNumber, "reason" to recorded.resolutionReason)))
        }
        return overview(jobId)
    }

    @Transactional(readOnly = true)
    fun history(jobId: UUID): List<RecoveryChangeDto> {
        access.job(jobId, Permissions.OPS_RECOVERY_READ)
        return jdbc.sql("""SELECT allocation_id,before_json,reason,changed_at,changed_by FROM recovery_change
            WHERE job_id=:job ORDER BY changed_at,id""")
            .param("job", jobId.bytes()).query { rs, _ -> RecoveryChangeDto(rs.getBytes(1).uuid(),
                rs.getString(2), rs.getString(3), rs.instant("changed_at")!!, rs.getBytes(5).uuid()!!) }.list()
    }

    private fun ensure(jobId: UUID) {
        jdbc.sql("""INSERT IGNORE INTO job_recovery (job_id,status,updated_at,updated_by)
            VALUES (:job,'OPEN',:now,:actor)""")
            .param("job", jobId.bytes()).param("now", access.now()).param("actor", access.actor().bytes()).update()
    }

    private fun lockOpen(jobId: UUID) {
        val status = jdbc.sql("SELECT status FROM job_recovery WHERE job_id=:job FOR UPDATE")
            .param("job", jobId.bytes()).query(String::class.java).single()
        if (status != "OPEN") throw BusinessRuleException("INVALID_TRANSITION", "Recovery is closed.")
    }

    private fun change(jobId: UUID, allocationId: UUID?, before: String, why: String) {
        jdbc.sql("""INSERT INTO recovery_change (id,job_id,allocation_id,before_json,reason,changed_at,changed_by)
            VALUES (:id,:job,:allocation,:before,:reason,:now,:actor)""")
            .param("id", UuidV7.generate().bytes()).param("job", jobId.bytes()).param("allocation", allocationId?.bytes())
            .param("before", before).param("reason", why).param("now", access.now())
            .param("actor", access.actor().bytes()).update()
    }
}
