package com.ship2ship.features.jobs

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.AccessScope
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.BusinessRuleException
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.PageResponse
import com.ship2ship.core.web.Paging
import com.ship2ship.core.web.Versioning
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.data.domain.Sort
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

data class AlertRule(val code:String,val label:String,val permission:String,val route:String,val leadMinutes:Int?,val escalationMinutes:Int?,val enabled:Boolean,val version:Long)
data class AlertRuleInput(val leadMinutes:Int?,val escalationMinutes:Int?,val enabled:Boolean,val reason:String)
data class AlertAction(val action:String,val reason:String?=null)
data class JobAlert(val id:UUID,val jobId:UUID,val jobNumber:String,val code:String,val label:String,val route:String,
    val dueAt:Instant?,val createdAt:Instant,val conditionActive:Boolean,val state:String,val escalatedAt:Instant?,
    val acknowledgedBy:UUID?,val acknowledgedAt:Instant?,val resolvedBy:UUID?,val resolvedAt:Instant?,val resolutionReason:String?,val version:Long)

@Service
class AlertService(private val jdbc:JdbcClient,private val access:AccessPolicy,private val audit:AuditService,private val clock:Clock) {
    @Transactional(readOnly=true)
    fun rules():List<AlertRule> {
        access.requireGlobal(Permissions.MASTER_ORG_READ)
        return allRules()
    }
    @Transactional
    fun configure(code:String,expected:Long,input:AlertRuleInput):AlertRule {
        access.requireGlobal(Permissions.MASTER_ORG_WRITE)
        jdbc.sql("SELECT code FROM alert_rule WHERE code=:code FOR UPDATE").param("code",code).query(String::class.java).optional()
            .orElseThrow { NotFoundException("Alert rule") }
        val before=allRules().first { it.code==code }; Versioning.checkCurrent(expected,before.version)
        if(input.reason.isBlank() || input.reason.length>500 || input.leadMinutes?.let { it !in 0..525600 }==true || input.escalationMinutes?.let { it !in 1..525600 }==true)
            throw BadRequestException("VALIDATION_FAILED","Enter a change reason and valid minute-based lead and escalation times (at most one year).")
        jdbc.sql("UPDATE alert_rule SET lead_minutes=:lead,escalation_minutes=:escalate,enabled=:enabled,updated_at=:now,updated_by=:actor,version=version+1 WHERE code=:code")
            .param("lead",input.leadMinutes).param("escalate",input.escalationMinutes).param("enabled",input.enabled).param("now",stamp(clock.instant()))
            .param("actor",bytes(access.currentUser().id)).param("code",code).update()
        audit.record(AuditEntry(AuditAction.UPDATE,"ALERT_RULE",details=mapOf("code" to code,"before" to before,"after" to input)))
        return allRules().first { it.code==code }
    }

    @Transactional(readOnly=true)
    fun list(state:String?,jobId:UUID?,page:Int,size:Int):PageResponse<JobAlert> {
        access.scope(Permissions.JOB_READ)
        if(state!=null && state !in setOf("OPEN","ACKNOWLEDGED","RESOLVED")) throw BadRequestException("VALIDATION_FAILED","Choose a valid inbox state.")
        val params=mutableMapOf<String,Any>()
        val visible=allRules().mapIndexed { index,rule->
            "(a.rule_code=:rule$index AND ${scope(access.currentUser().scopeFor(rule.permission),params,"r$index")})".also { params["rule$index"]=rule.code }
        }.joinToString(" OR ")
        val where=mutableListOf(scope(access.currentUser().scopeFor(Permissions.JOB_READ),params,"jobs"),
            "(($visible) OR (a.escalated_at IS NOT NULL AND ${scope(access.currentUser().scopeFor("ALERT.ESCALATION.READ"),params,"esc")}))")
        if(state==null) where+="a.state<>'RESOLVED'" else { where+="a.state=:state";params["state"]=state }
        if(jobId!=null) { where+="a.job_id=:job";params["job"]=bytes(jobId) }
        val from="FROM job_alert a JOIN sts_job j ON j.id=a.job_id JOIN alert_rule r ON r.code=a.rule_code WHERE ${where.joinToString(" AND ")}"
        val total=jdbc.sql("SELECT COUNT(*) $from").params(params).query(Long::class.java).single()
        val rows=jdbc.sql("SELECT a.*,j.job_number,r.label,r.route $from ORDER BY a.escalated_at IS NOT NULL DESC,a.due_at IS NULL,a.due_at,a.created_at,a.id LIMIT :size OFFSET :offset")
            .params(params).param("size",size).param("offset",page.toLong()*size).query { rs,_->
                fun at(column:String)=rs.getObject(column,LocalDateTime::class.java)?.let(Uuids::fromTimestamp)
                JobAlert(Uuids.fromBytes(rs.getBytes("id"))!!,Uuids.fromBytes(rs.getBytes("job_id"))!!,rs.getString("job_number"),rs.getString("rule_code"),
                    rs.getString("label"),rs.getString("route"),at("due_at"),at("created_at")!!,rs.getBoolean("condition_active"),rs.getString("state"),at("escalated_at"),
                    Uuids.fromBytes(rs.getBytes("acknowledged_by")),at("acknowledged_at"),Uuids.fromBytes(rs.getBytes("resolved_by")),at("resolved_at"),rs.getString("resolution_reason"),rs.getLong("version"))
            }.list()
        return PageResponse(rows,page,size,total)
    }

    @Transactional
    fun action(id:UUID,expected:Long,input:AlertAction) {
        if((input.reason?.length?:0)>500) throw BadRequestException("VALIDATION_FAILED","Use at most 500 characters for the reason.")
        val row=jdbc.sql("SELECT job_id,rule_code,version,state,condition_active,escalated_at FROM job_alert WHERE id=:id FOR UPDATE").param("id",bytes(id))
            .query { rs,_-> arrayOf<Any?>(Uuids.fromBytes(rs.getBytes(1))!!,rs.getString(2),rs.getLong(3),rs.getString(4),rs.getBoolean(5),rs.getObject(6)) }
            .optional().orElseThrow { NotFoundException("Alert") }
        val jobId=row[0] as UUID
        val rule=allRules().first { it.code==row[1] }
        val branch=jdbc.sql("SELECT branch_id FROM sts_job WHERE id=:id").param("id",bytes(jobId))
            .query { rs,_->Uuids.fromBytes(rs.getBytes(1))!! }.single()
        access.requireBranch(Permissions.JOB_READ,branch,"JOB",jobId)
        val permission=if(row[5]!=null && access.currentUser().scopeFor("ALERT.ESCALATION.READ").coversBranch(branch)) "ALERT.ESCALATION.READ" else rule.permission
        access.requireBranch(permission,branch,"JOB",jobId)
        Versioning.checkCurrent(expected,row[2] as Long)
        val state=when(input.action) {
            "ACKNOWLEDGE" -> { if(row[3]!="OPEN") throw BusinessRuleException("INVALID_TRANSITION","Only open alerts can be acknowledged.");"ACKNOWLEDGED" }
            "RESOLVE" -> {
                if(row[3]=="RESOLVED") throw BusinessRuleException("INVALID_TRANSITION","This alert was already resolved.")
                if(row[4]==true && rule.code !in setOf("JOB_CHANGE","ETA_CHANGE")) throw BusinessRuleException("ALERT_STILL_ACTIVE","Complete the source task first; acknowledgement does not resolve it.")
                if(input.reason.isNullOrBlank() || input.reason.length>500) throw BadRequestException("REASON_REQUIRED","Record how this alert was resolved (at most 500 characters).")
                "RESOLVED"
            }
            else -> throw BadRequestException("VALIDATION_FAILED","Choose ACKNOWLEDGE or RESOLVE.")
        }
        val column=if(state=="ACKNOWLEDGED") "acknowledged" else "resolved"
        jdbc.sql("UPDATE job_alert SET state=:state,${column}_by=:actor,${column}_at=:now,resolution_reason=:why,version=version+1 WHERE id=:id")
            .param("state",state).param("actor",bytes(access.currentUser().id)).param("now",stamp(clock.instant())).param("why",input.reason?.trim()).param("id",bytes(id)).update()
        audit.record(AuditEntry(AuditAction.STATUS_CHANGE,"JOB_ALERT",id,branch,mapOf("jobId" to jobId,"from" to row[3],"to" to state,"reason" to input.reason)))
    }

    /**
     * Trusted background read model; each refresh is atomic and never auto-resolves a user's action. READ COMMITTED lets
     * it read the source rows without share-locking them (needs row-based binary logging, the MySQL 8.4 default). Each
     * rule's source is read once into a temporary table; existing alerts are refreshed with plain UPDATEs, which take no
     * lock on the job row, and only new sources are inserted (the foreign-key check share-locks each new alert's job).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun refresh() {
        val now=stamp(clock.instant())
        jdbc.sql("CREATE TEMPORARY TABLE IF NOT EXISTS alert_source (job BINARY(16) NOT NULL,source VARCHAR(100) NOT NULL,due DATETIME(6) NULL,KEY(job,source))").update()
        try { for(rule in allRules()) {
            val source=sources[rule.code] ?: continue
            if(rule.enabled) {
                jdbc.sql("DELETE FROM alert_source").update()
                jdbc.sql("""INSERT INTO alert_source SELECT s.job,s.source,s.due FROM ($source) s JOIN sts_job j ON j.id=s.job
                    WHERE (j.status NOT IN ('CLOSED','CANCELLED') OR :code='RECEIVABLE')""")
                    .param("code",rule.code).param("lead",rule.leadMinutes).update()
                // Reactivated or re-dated alerts get a new version; the condition is in WHERE, which always sees the old values.
                jdbc.sql("""UPDATE job_alert a JOIN alert_source s ON a.job_id=s.job AND a.rule_code=:code AND a.source_key=s.source
                    SET a.version=a.version+1,a.condition_active=TRUE,a.due_at=s.due,a.seen_at=:now
                    WHERE a.condition_active=FALSE OR NOT(a.due_at <=> s.due)""").param("code",rule.code).param("now",now).update()
                jdbc.sql("""UPDATE job_alert a JOIN alert_source s ON a.job_id=s.job AND a.rule_code=:code AND a.source_key=s.source
                    SET a.seen_at=:now WHERE a.seen_at<>:now""").param("code",rule.code).param("now",now).update()
                jdbc.sql("""INSERT INTO job_alert(id,job_id,rule_code,source_key,due_at,created_at,seen_at)
                    SELECT UUID_TO_BIN(UUID()),s.job,:code,s.source,s.due,:now,:now FROM alert_source s
                    WHERE NOT EXISTS (SELECT 1 FROM job_alert a WHERE a.job_id=s.job AND a.rule_code=:code AND a.source_key=s.source)
                    ON DUPLICATE KEY UPDATE job_alert.version=job_alert.version+IF(job_alert.condition_active=FALSE OR NOT(job_alert.due_at <=> s.due),1,0),
                    job_alert.condition_active=TRUE,job_alert.due_at=s.due,job_alert.seen_at=:now""")
                    .param("code",rule.code).param("now",now).update()
            }
            jdbc.sql("UPDATE job_alert SET condition_active=FALSE,version=version+1 WHERE rule_code=:code AND condition_active=TRUE AND seen_at<:now")
                .param("code",rule.code).param("now",now).update()
            if(rule.escalationMinutes!=null) jdbc.sql("""UPDATE job_alert SET escalated_at=:now,version=version+1
                WHERE rule_code=:code AND state<>'RESOLVED' AND condition_active=TRUE AND escalated_at IS NULL
                AND TIMESTAMPDIFF(MINUTE,COALESCE(due_at,created_at),:now)>=:minutes""")
                .param("code",rule.code).param("now",stamp(clock.instant())).param("minutes",rule.escalationMinutes).update()
            if(rule.code in setOf("CLOCK_DUE","OVERRUN")) jdbc.sql("""UPDATE job_alert SET state='OPEN',escalated_at=COALESCE(escalated_at,:now),version=version+1
                WHERE rule_code=:code AND condition_active=TRUE AND due_at IS NOT NULL AND due_at<=:now AND (state<>'OPEN' OR escalated_at IS NULL)""")
                .param("code",rule.code).param("now",now).update()
        } } finally { jdbc.sql("DROP TEMPORARY TABLE IF EXISTS alert_source").update() }
    }
    private fun allRules():List<AlertRule> = jdbc.sql("SELECT * FROM alert_rule ORDER BY code").query { rs,_->
        AlertRule(rs.getString("code"),rs.getString("label"),rs.getString("permission_code"),rs.getString("route"),rs.getObject("lead_minutes",Integer::class.java)?.toInt(),
            rs.getObject("escalation_minutes",Integer::class.java)?.toInt(),rs.getBoolean("enabled"),rs.getLong("version")) }.list()
    private fun scope(scope:AccessScope,params:MutableMap<String,Any>,prefix:String):String =
        scope.sqlCondition("j.branch_id",params,prefix) ?: "1=1"
    private fun bytes(id:UUID)=Uuids.toBytes(id)
    private fun stamp(at:Instant)=Uuids.toTimestamp(at)
    internal val sources=mapOf(
        "DOCUMENTS" to "SELECT r.job_id job,HEX(i.id) source,NULL due FROM job_request_item i JOIN job_document_request r ON r.id=i.request_id WHERE i.required=TRUE AND i.status<>'REVIEWED'",
        "JPO_APPROVAL" to "SELECT j.job_id job,HEX(r.id) source,NULL due FROM jpo_revision r JOIN jpo j ON j.id=r.jpo_id WHERE r.status='SUBMITTED'",
        "MOORING_APPROVAL" to "SELECT p.job_id job,HEX(r.id) source,NULL due FROM mooring_plan_revision r JOIN mooring_plan p ON p.id=r.plan_id WHERE r.status='SUBMITTED'",
        "WEATHER_APPROVAL" to "SELECT job_id job,HEX(id) source,NULL due FROM job_limit_set WHERE status='SUBMITTED'",
        "WEATHER_BREACH" to """SELECT r.job_id job,CONCAT(HEX(r.id),':',l.parameter_code) source,r.valid_to due FROM job_weather_report r
            JOIN job_weather_value v ON v.report_id=r.id JOIN job_limit_set s ON s.job_id=r.job_id AND s.status='APPROVED'
            JOIN job_limit l ON l.set_id=s.id AND l.parameter_code=v.parameter_code WHERE r.status='ACCEPTED'
            AND (r.valid_to IS NULL OR r.valid_to>=UTC_TIMESTAMP(6)) AND ((l.comparison='ABOVE' AND v.value>l.threshold) OR (l.comparison='BELOW' AND v.value<l.threshold))""",
        "ETA_CHANGE" to "SELECT v.job_id job,HEX(e.id) source,e.eta_utc due FROM eta_revision e JOIN job_vessel v ON v.id=e.job_vessel_id",
        "ALLOCATION" to "SELECT job_id job,HEX(id) source,start_at due FROM job_resource_allocation WHERE status='PENDING_EXCEPTION'",
        "CERTIFICATE" to """SELECT a.job_id job,CONCAT(HEX(c.id),':',c.version) source,CAST(c.expiry_date AS DATETIME) due FROM job_resource_allocation a
            JOIN resource_certificate c ON (c.asset_id=a.asset_id OR c.personnel_id=a.personnel_id OR c.craft_id=a.craft_id)
            WHERE a.status='ACTIVE' AND c.expiry_date IS NOT NULL AND c.expiry_date<=DATE_ADD(UTC_DATE(),INTERVAL COALESCE(:lead,0) MINUTE)""",
        "CLOCK_DUE" to """SELECT c.job_id job,CONCAT(HEX(c.start_movement_id),':',c.version) source,DATE_ADD(m.departed_at,INTERVAL c.basis_hours HOUR) due
            FROM job_charge_clock c JOIN craft_movement m ON m.id=c.start_movement_id WHERE c.stop_movement_id IS NULL AND m.returned_at IS NULL AND m.status='ACTIVE'
            AND UTC_TIMESTAMP(6)>=DATE_SUB(DATE_ADD(m.departed_at,INTERVAL c.basis_hours HOUR),INTERVAL COALESCE(:lead,0) MINUTE)""",
        "OVERRUN" to "SELECT job_id job,HEX(id) source,created_at due FROM additional_hours_notice WHERE status='DRAFT' OR delivered_at IS NULL",
        "INCIDENT" to "SELECT job_id job,HEX(id) source,occurred_at due FROM job_incident WHERE status<>'CLOSED'",
        "DELAY" to "SELECT job_id job,HEX(id) source,started_at due FROM job_delay WHERE status='ACTIVE' AND ended_at IS NULL",
        "RECOVERY" to "SELECT job_id job,HEX(id) source,updated_at due FROM recovery_asset WHERE status IN ('DAMAGED','SHORT') AND resolved_at IS NULL",
        "REPORT_APPROVAL" to "SELECT f.job_id job,HEX(r.id) source,NULL due FROM final_report_revision r JOIN final_report f ON f.id=r.report_id WHERE r.status='SUBMITTED'",
        "COST_APPROVAL" to "SELECT job_id job,HEX(id) source,due_date due FROM job_cost_document WHERE status='SUBMITTED' AND kind='BILL'",
        "PO_APPROVAL" to "SELECT job_id job,HEX(id) source,due_date due FROM job_cost_document WHERE status='SUBMITTED' AND kind='PO'",
        "INVOICE_APPROVAL" to "SELECT job_id job,HEX(id) source,due_date due FROM invoice WHERE status='SUBMITTED'",
        "FDA_APPROVAL" to "SELECT job_id job,HEX(id) source,NULL due FROM fda_revision WHERE status='SUBMITTED'",
        "RECEIVABLE" to """SELECT i.job_id job,HEX(i.id) source,CAST(i.due_date AS DATETIME) due FROM invoice i WHERE i.doc_type='INVOICE' AND i.status='ISSUED' AND i.due_date<UTC_DATE()
            AND i.gross_amount>(SELECT COALESCE(SUM(c.gross_amount),0) FROM invoice c WHERE c.credited_invoice_id=i.id AND c.status='ISSUED')
            +(SELECT COALESCE(SUM(a.amount),0) FROM receipt_allocation a JOIN receipt r ON r.id=a.receipt_id WHERE a.invoice_id=i.id AND a.reversed_at IS NULL AND r.status='ACTIVE')""",
        "JOB_CHANGE" to "SELECT entity_id job,HEX(id) source,occurred_at due FROM workflow_transition WHERE entity_type='JOB'",
    )
}

@Component
class AlertMonitor(private val alerts:AlertService,@Value("\${ship2ship.alerts.enabled:true}") private val enabled:Boolean) {
    @Scheduled(fixedDelayString="\${ship2ship.alerts.refresh-ms:60000}",initialDelayString="\${ship2ship.alerts.refresh-ms:60000}")
    fun refresh() { if(enabled) alerts.refresh() }
}

@RestController
@RequestMapping("/api/v1/alerts")
class AlertController(private val service:AlertService,private val paging:Paging) {
    @GetMapping fun list(@RequestParam state:String?,@RequestParam jobId:UUID?,@RequestParam page:Int?,@RequestParam size:Int?):PageResponse<JobAlert> {
        val request=paging.pageable(page,size,null,emptyMap(),Sort.unsorted())
        return service.list(state,jobId,request.pageNumber,request.pageSize)
    }
    @PostMapping("/{id}/actions") fun action(@PathVariable id:UUID,@RequestHeader(HttpHeaders.IF_MATCH,required=false) version:String?,@RequestBody input:AlertAction)=service.action(id,Versioning.requireIfMatch(version),input)
    @GetMapping("/rules") fun rules()=service.rules()
    @PutMapping("/rules/{code}") fun configure(@PathVariable code:String,@RequestHeader(HttpHeaders.IF_MATCH,required=false) version:String?,@RequestBody input:AlertRuleInput)=service.configure(code,Versioning.requireIfMatch(version),input)
}
