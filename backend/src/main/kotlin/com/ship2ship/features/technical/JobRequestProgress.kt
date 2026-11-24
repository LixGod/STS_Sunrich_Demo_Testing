package com.ship2ship.features.technical

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.core.security.AccessPolicy
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

/** Records evidence from the checklist and voyage workflows in the job collection tracker. */
@Service
class JobRequestProgress(private val jdbc: JdbcClient, private val access: JobTechnicalAccess,
    private val policy: AccessPolicy, private val audit: AuditService, private val clock: Clock) {

    fun markReceived(jobId: UUID, jobVesselId: UUID?, kind: String, code: String?, evidence: String) {
        val job = access.job(jobId)
        val items = jdbc.sql("""SELECT i.id,i.status FROM job_request_item i
            JOIN job_document_request r ON r.id=i.request_id
            WHERE r.job_id=:job AND i.job_vessel_id <=> :vessel AND i.kind=:kind
              AND (:code IS NULL OR i.code=:code) AND i.status IN ('PENDING','RETURNED')""")
            .param("job",jobId.bytes()).param("vessel",jobVesselId?.bytes())
            .param("kind",kind).param("code",code)
            .query { rs,_ -> rs.getBytes(1).uuid()!! to rs.getString(2) }.list()
        val actor = policy.currentUser().id
        val time = Uuids.toTimestamp(clock.instant())
        items.forEach { (id,oldStatus) ->
            val changed = jdbc.sql("""UPDATE job_request_item SET status='RECEIVED',updated_at=:time,
                updated_by=:actor,version=version+1 WHERE id=:id AND status=:old""")
                .param("time",time).param("actor",actor.bytes()).param("id",id.bytes())
                .param("old",oldStatus).update()
            if (changed == 1) {
                jdbc.sql("""INSERT INTO job_request_event (id,item_id,old_status,new_status,reason,actor_id,occurred_at)
                    VALUES (:id,:item,:old,'RECEIVED',:reason,:actor,:time)""")
                    .param("id",UuidV7.generate().bytes()).param("item",id.bytes())
                    .param("old",oldStatus).param("reason",evidence.take(500))
                    .param("actor",actor.bytes()).param("time",time).update()
                audit.record(AuditEntry(AuditAction.STATUS_CHANGE,"JOB_REQUEST_ITEM",id,job.branchId,
                    mapOf("from" to oldStatus,"to" to "RECEIVED","evidence" to evidence)))
            }
        }
    }
}
