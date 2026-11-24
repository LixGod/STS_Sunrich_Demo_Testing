package com.ship2ship.features.resources

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.jobs.JobTechnicalRef
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.core.resources.AllocatedResourceSnapshot
import com.ship2ship.core.resources.ResourceAllocationLookup
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.BusinessRuleException
import com.ship2ship.core.web.ConflictException
import com.ship2ship.core.web.FieldError
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.Versioning
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

@Service
class AllocationService(
    private val jdbc: JdbcClient, private val access: ResourceAccess, private val masters: ResourceMasterService,
    private val json: JsonMapper, private val audit: AuditService,
) : ResourceAllocationLookup {
    private data class Resource(val kind:String,val id:UUID,val identifier:String,
                                val category:String?,val active:Boolean)
    private data class Row(val id:UUID,val jobId:UUID,val kind:String,val resourceId:UUID,
                           val startAt:Instant,val endAt:Instant,val zone:String,val status:String,
                           val collisionIds:List<UUID>,val messages:List<String>,val exceptionReason:String?,
                           val requestedBy:UUID?,val approvedBy:UUID?,val decisionReason:String?,val version:Long)

    @Transactional(readOnly=true)
    fun context(jobId:UUID):ResourceJobContextDto {
        val job=access.job(jobId,Permissions.OPS_ALLOCATION_READ)
        return ResourceJobContextDto(job.id,job.jobNumber,zone(job).id)
    }

    @Transactional(readOnly=true)
    fun choices(jobId:UUID,q:String?):ResourceChoicesDto {
        val job=access.job(jobId,Permissions.OPS_ALLOCATION_READ)
        val term="%${q?.trim()?.take(80).orEmpty()}%"
        val assets=jdbc.sql("""SELECT a.id,a.asset_identifier,m.name,m.category_code FROM equipment_asset a
            JOIN equipment_model m ON m.id=a.model_id WHERE (a.branch_id IS NULL OR a.branch_id=:branch) AND a.status='AVAILABLE' AND m.active=1
            AND (a.asset_identifier LIKE :q OR m.name LIKE :q) ORDER BY a.asset_identifier LIMIT 100""")
            .params(mapOf("branch" to job.branchId.bytes(),"q" to term))
            .query { rs,_ -> ResourceChoiceDto(rs.getBytes(1).uuid()!!,rs.getString(2),rs.getString(3),rs.getString(4)) }.list()
        val people=jdbc.sql("""SELECT id,personnel_identifier,full_name FROM personnel
            WHERE active=1 AND (personnel_identifier LIKE :q OR full_name LIKE :q)
            ORDER BY personnel_identifier LIMIT 100""")
            .param("q",term)
            .query { rs,_ -> ResourceChoiceDto(rs.getBytes(1).uuid()!!,rs.getString(2),rs.getString(3),null) }.list()
        return ResourceChoicesDto(assets,people,emptyList(),masters.policyForAllocation())
    }

    @Transactional(readOnly=true)
    fun list(jobId:UUID,page:Int=0,size:Int=25):ResourcePage<AllocationDto> {
        access.job(jobId,Permissions.OPS_ALLOCATION_READ)
        if(page<0) invalid("page","Page must be non-negative.")
        val limit=size.coerceIn(1,100)
        val total=jdbc.sql("SELECT COUNT(*) FROM job_resource_allocation WHERE job_id=:job AND craft_id IS NULL")
            .param("job",jobId.bytes()).query(Long::class.java).single()
        val ids=jdbc.sql("""SELECT id FROM job_resource_allocation WHERE job_id=:job AND craft_id IS NULL
            ORDER BY start_at DESC,id DESC LIMIT :limit OFFSET :offset""")
            .param("job",jobId.bytes()).param("limit",limit).param("offset",page*limit)
            .query { rs,_ -> rs.getBytes(1).uuid()!! }.list()
        return ResourcePage(ids.map { dto(row(jobId,it)) },page,limit,total)
    }

    @Transactional(readOnly=true)
    fun get(jobId:UUID,id:UUID):AllocationDto {
        access.job(jobId,Permissions.OPS_ALLOCATION_READ)
        return dto(row(jobId,id))
    }

    @Transactional
    fun create(jobId:UUID,input:AllocationInput):AllocationDto {
        val job=access.job(jobId,Permissions.OPS_ALLOCATION_WRITE)
        if(input.kind.equals("CRAFT",ignoreCase=true)) invalid("kind","Craft allocation is no longer used.")
        val zone=zone(job)
        interval(input.startAt,input.endAt)
        val resource=lockedResource(input.kind,input.resourceId,job.branchId)
        val conflicts=conflicts(resource,input.startAt,input.endAt)
        val reason=optional(input.exceptionReason,500)
        if(conflicts.isNotEmpty() && reason==null) collision(conflicts)
        if(conflicts.isEmpty() && reason!=null) invalid("exceptionReason","An exception is only needed for an actual collision.")
        val messages=eligibility(resource,input.endAt,zone)
        val id=UuidV7.generate(); val status=if(conflicts.isEmpty()) "ACTIVE" else "PENDING_EXCEPTION"
        jdbc.sql("""INSERT INTO job_resource_allocation
            (id,job_id,asset_id,personnel_id,craft_id,start_at,end_at,time_zone,status,
             exception_reason,requested_by,collision_json,eligibility_json,created_at,created_by,updated_at,updated_by,version)
            VALUES (:id,:job,:asset,:person,:craft,:start,:end,:zone,:status,:reason,:requester,
             :collisions,:messages,:t,:actor,:t,:actor,0)""")
            .params(mapOf("id" to id.bytes(),"job" to jobId.bytes(),
                "asset" to if(resource.kind=="ASSET") resource.id.bytes() else null,
                "person" to if(resource.kind=="PERSON") resource.id.bytes() else null,
                "craft" to if(resource.kind=="CRAFT") resource.id.bytes() else null,
                "start" to Uuids.toTimestamp(input.startAt),"end" to Uuids.toTimestamp(input.endAt),
                "zone" to zone.id,"status" to status,"reason" to reason,
                "requester" to if(conflicts.isNotEmpty()) access.actor().bytes() else null,
                "collisions" to json.writeValueAsString(conflicts),"messages" to json.writeValueAsString(messages),
                "t" to access.now(),"actor" to access.actor().bytes())).update()
        event(id,"CREATE",mapOf("status" to status,"conflicts" to conflicts,"warnings" to messages))
        audit.record(AuditEntry(AuditAction.CREATE,"JOB_RESOURCE_ALLOCATION",id,job.branchId,
            mapOf("jobId" to jobId,"kind" to resource.kind,"resourceId" to resource.id,"status" to status)))
        return dto(row(jobId,id))
    }

    @Transactional
    fun amend(jobId:UUID,id:UUID,expected:Long,input:AllocationInput):AllocationDto {
        val job=access.job(jobId,Permissions.OPS_ALLOCATION_WRITE)
        val before=row(jobId,id)
        Versioning.checkCurrent(expected,before.version)
        if(before.status !in setOf("ACTIVE","REJECTED")) throw BusinessRuleException("INVALID_TRANSITION","Only active or returned allocations may be amended.")
        if(before.kind!=input.kind.uppercase() || before.resourceId!=input.resourceId) invalid("resourceId","Resource identity cannot change.")
        interval(input.startAt,input.endAt)
        val zone=zone(job)
        val resource=lockedResource(before.kind,before.resourceId,job.branchId)
        val currentStatus=verifyLocked(jobId,id,expected)
        if(currentStatus !in setOf("ACTIVE","REJECTED")) throw BusinessRuleException("INVALID_TRANSITION","Only active or returned allocations may be amended.")
        val conflicts=conflicts(resource,input.startAt,input.endAt,id)
        val reason=optional(input.exceptionReason,500)
        if(conflicts.isNotEmpty() && reason==null) collision(conflicts)
        if(conflicts.isEmpty() && reason!=null) invalid("exceptionReason","An exception is only needed for an actual collision.")
        val messages=eligibility(resource,input.endAt,zone)
        val status=if(conflicts.isEmpty()) "ACTIVE" else "PENDING_EXCEPTION"
        jdbc.sql("""UPDATE job_resource_allocation SET start_at=:start,end_at=:end,time_zone=:zone,status=:status,
            exception_reason=:reason,requested_by=:requester,approved_by=NULL,decided_at=NULL,decision_reason=NULL,
            collision_json=:collisions,eligibility_json=:messages,updated_at=:t,updated_by=:actor,version=version+1 WHERE id=:id""")
            .params(mapOf("start" to Uuids.toTimestamp(input.startAt),"end" to Uuids.toTimestamp(input.endAt),
                "zone" to zone.id,"status" to status,"reason" to reason,
                "requester" to if(conflicts.isNotEmpty()) access.actor().bytes() else null,
                "collisions" to json.writeValueAsString(conflicts),"messages" to json.writeValueAsString(messages),
                "t" to access.now(),"actor" to access.actor().bytes(),"id" to id.bytes())).update()
        event(id,"AMEND",mapOf("priorStart" to before.startAt,"priorEnd" to before.endAt,"status" to status))
        audit.record(AuditEntry(AuditAction.UPDATE,"JOB_RESOURCE_ALLOCATION",id,job.branchId,
            mapOf("status" to status)))
        return dto(row(jobId,id))
    }

    @Transactional
    fun decide(jobId:UUID,id:UUID,expected:Long,input:AllocationDecisionInput):AllocationDto {
        val job=access.job(jobId,Permissions.OPS_ALLOCATION_APPROVE)
        val before=row(jobId,id); Versioning.checkCurrent(expected,before.version)
        if(before.status!="PENDING_EXCEPTION") throw BusinessRuleException("INVALID_TRANSITION","Only a pending exception can be decided.")
        if(before.requestedBy==access.actor()) throw BusinessRuleException("SELF_APPROVAL_FORBIDDEN","A second person must decide the exception.")
        val reason=text(input.reason.orEmpty(),"reason",500)
        val resource=lockedResource(before.kind,before.resourceId,job.branchId)
        if(verifyLocked(jobId,id,expected)!="PENDING_EXCEPTION")
            throw BusinessRuleException("INVALID_TRANSITION","Only a pending exception can be decided.")
        val status=when(input.decision) {
            "APPROVE"->{
                val conflicts=conflicts(resource,before.startAt,before.endAt,id)
                if(conflicts.isEmpty()) throw BusinessRuleException("ALLOCATION_COLLISION_GONE","The collision is gone; amend this request normally.")
                eligibility(resource,before.endAt,ZoneId.of(before.zone))
                jdbc.sql("UPDATE job_resource_allocation SET collision_json=:c WHERE id=:id")
                    .param("c",json.writeValueAsString(conflicts)).param("id",id.bytes()).update()
                "ACTIVE"
            }
            "RETURN"->"REJECTED"
            else->invalid("decision","Choose APPROVE or RETURN.")
        }
        jdbc.sql("""UPDATE job_resource_allocation SET status=:status,approved_by=:approver,decided_at=:t,
            decision_reason=:reason,updated_at=:t,updated_by=:approver,version=version+1 WHERE id=:id""")
            .params(mapOf("status" to status,"approver" to access.actor().bytes(),"t" to access.now(),
                "reason" to reason,"id" to id.bytes())).update()
        event(id,input.decision,mapOf("reason" to reason,"status" to status))
        audit.record(AuditEntry(AuditAction.UPDATE,"JOB_RESOURCE_ALLOCATION",id,job.branchId,
            mapOf("decision" to input.decision,"reason" to reason)))
        return dto(row(jobId,id))
    }

    @Transactional
    fun cancel(jobId:UUID,id:UUID,expected:Long,reason:String):AllocationDto {
        val job=access.job(jobId,Permissions.OPS_ALLOCATION_WRITE)
        val before=row(jobId,id); Versioning.checkCurrent(expected,before.version)
        if(before.status=="CANCELLED") throw BusinessRuleException("INVALID_TRANSITION","Allocation is already cancelled.")
        val clean=text(reason,"reason",500)
        lockedResource(before.kind,before.resourceId,job.branchId)
        if(verifyLocked(jobId,id,expected)=="CANCELLED")
            throw BusinessRuleException("INVALID_TRANSITION","Allocation is already cancelled.")
        jdbc.sql("""UPDATE job_resource_allocation SET status='CANCELLED',decision_reason=:reason,
            updated_at=:t,updated_by=:actor,version=version+1 WHERE id=:id""")
            .params(mapOf("reason" to clean,"t" to access.now(),"actor" to access.actor().bytes(),"id" to id.bytes())).update()
        event(id,"CANCEL",mapOf("reason" to clean,"priorStatus" to before.status))
        audit.record(AuditEntry(AuditAction.UPDATE,"JOB_RESOURCE_ALLOCATION",id,job.branchId,
            mapOf("action" to "CANCEL","reason" to clean)))
        return dto(row(jobId,id))
    }

    @Transactional(readOnly=true)
    override fun activeForJob(jobId:UUID):List<AllocatedResourceSnapshot> =
        jdbc.sql("""SELECT a.id,a.asset_id,a.personnel_id,a.craft_id,a.start_at,a.end_at,a.approved_by,
            e.asset_identifier,m.category_code,e.dimensions_text,p.personnel_identifier,c.identifier
            ,a.version,a.eligibility_json,a.collision_json
            FROM job_resource_allocation a
            LEFT JOIN equipment_asset e ON e.id=a.asset_id LEFT JOIN equipment_model m ON m.id=e.model_id
            LEFT JOIN personnel p ON p.id=a.personnel_id LEFT JOIN resource_craft c ON c.id=a.craft_id
            WHERE a.job_id=:job AND a.status='ACTIVE' ORDER BY a.start_at,a.id""")
            .param("job",jobId.bytes()).query { rs,_ ->
                val asset=rs.getBytes(2).uuid(); val person=rs.getBytes(3).uuid(); val craft=rs.getBytes(4).uuid()
                AllocatedResourceSnapshot(rs.getBytes(1).uuid()!!,asset?:person?:craft!!,
                    if(asset!=null) "ASSET" else if(person!=null) "PERSON" else "CRAFT",rs.getString(9),
                    rs.getString(8)?:rs.getString(11)?:rs.getString(12),rs.getString(10),
                    Uuids.fromTimestamp(rs.getObject(5,LocalDateTime::class.java)),
                    Uuids.fromTimestamp(rs.getObject(6,LocalDateTime::class.java)),rs.getBytes(7)!=null,
                    rs.getLong(13),json.readTree(rs.getString(14)?:"[]").let { tree -> (0 until tree.size()).map { tree[it].asString() } },
                    json.readTree(rs.getString(15)?:"[]").let { tree -> (0 until tree.size()).map { UUID.fromString(tree[it].asString()) } })
            }.list()

    private fun row(jobId:UUID,id:UUID):Row = jdbc.sql("""SELECT id,job_id,asset_id,personnel_id,craft_id,start_at,end_at,
        time_zone,status,collision_json,eligibility_json,exception_reason,requested_by,approved_by,decision_reason,version
        FROM job_resource_allocation WHERE id=:id AND job_id=:job""")
        .param("id",id.bytes()).param("job",jobId.bytes()).query { rs,_ -> fromRow(rs) }
        .optional().orElseThrow { NotFoundException("Allocation") }

    private fun verifyLocked(jobId:UUID,id:UUID,expected:Long):String {
        val current=jdbc.sql("SELECT status,version FROM job_resource_allocation WHERE id=:id AND job_id=:job FOR UPDATE")
            .param("id",id.bytes()).param("job",jobId.bytes()).query { rs,_ -> rs.getString(1) to rs.getLong(2) }
            .optional().orElseThrow { NotFoundException("Allocation") }
        Versioning.checkCurrent(expected,current.second)
        return current.first
    }

    private fun fromRow(rs:ResultSet):Row {
        val asset=rs.getBytes(3).uuid(); val person=rs.getBytes(4).uuid(); val craft=rs.getBytes(5).uuid()
        val collisionTree=json.readTree(rs.getString(10)?:"[]")
        val messageTree=json.readTree(rs.getString(11)?:"[]")
        val collisions=(0 until collisionTree.size()).map { UUID.fromString(collisionTree[it].asString()) }
        val messages=(0 until messageTree.size()).map { messageTree[it].asString() }
        return Row(rs.getBytes(1).uuid()!!,rs.getBytes(2).uuid()!!,
            if(asset!=null) "ASSET" else if(person!=null) "PERSON" else "CRAFT",asset?:person?:craft!!,
            Uuids.fromTimestamp(rs.getObject(6,LocalDateTime::class.java)),
            Uuids.fromTimestamp(rs.getObject(7,LocalDateTime::class.java)),rs.getString(8),rs.getString(9),
            collisions,messages,rs.getString(12),rs.getBytes(13).uuid(),rs.getBytes(14).uuid(),rs.getString(15),rs.getLong(16))
    }
    private fun dto(row:Row):AllocationDto {
        val r=resource(row.kind,row.resourceId)?:throw NotFoundException("Resource")
        return AllocationDto(row.id,row.jobId,row.kind,row.resourceId,r.identifier,r.category,row.startAt,row.endAt,
            row.zone,row.status,row.collisionIds,row.messages,row.exceptionReason,row.requestedBy,row.approvedBy,
            row.decisionReason,row.version)
    }

    private fun resource(kind:String,id:UUID):Resource? {
        val sql=when(kind.uppercase()) {
            "ASSET"->"""SELECT a.asset_identifier,m.category_code,a.status='AVAILABLE' AND m.active=1
                FROM equipment_asset a JOIN equipment_model m ON m.id=a.model_id WHERE a.id=:id"""
            "PERSON"->"SELECT personnel_identifier,NULL,active FROM personnel WHERE id=:id"
            "CRAFT"->"""SELECT c.identifier,c.craft_type,c.active=1 AND v.active=1
                FROM resource_craft c JOIN resource_vendor v ON v.id=c.vendor_id WHERE c.id=:id"""
            else->invalid("kind","Choose ASSET, PERSON or CRAFT.")
        }
        return jdbc.sql(sql).param("id",id.bytes()).query { rs,_ -> Resource(kind.uppercase(),id,
            rs.getString(1),rs.getString(2),rs.getBoolean(3)) }.optional().orElse(null)
    }
    private fun lockedResource(kind:String,id:UUID,branchId:UUID):Resource {
        val table=when(kind.uppercase()) {"ASSET"->"equipment_asset";"PERSON"->"personnel";"CRAFT"->"resource_craft";
            else->invalid("kind","Choose ASSET, PERSON or CRAFT.")}
        val branchCondition=if(kind.uppercase()=="ASSET") " AND (branch_id IS NULL OR branch_id=:branch)" else ""
        jdbc.sql("SELECT id FROM $table WHERE id=:id$branchCondition FOR UPDATE")
            .params(if(branchCondition.isEmpty()) mapOf("id" to id.bytes())
                else mapOf("id" to id.bytes(),"branch" to branchId.bytes()))
            .query { rs,_ -> rs.getBytes(1) }
            .optional().orElseThrow { NotFoundException("Resource") }
        return resource(kind,id)?.also { if(!it.active) throw BusinessRuleException("RESOURCE_INELIGIBLE","Resource is inactive or unavailable.") }
            ?:throw NotFoundException("Resource")
    }
    private fun conflicts(r:Resource,start:Instant,end:Instant,exclude:UUID?=null):List<UUID> {
        val col=when(r.kind) {"ASSET"->"asset_id";"PERSON"->"personnel_id";else->"craft_id"}
        return jdbc.sql("""SELECT id FROM job_resource_allocation WHERE $col=:resource AND status='ACTIVE'
            AND start_at<:end AND :start<end_at AND (:exclude IS NULL OR id<>:exclude) ORDER BY id FOR UPDATE""")
            .params(mapOf("resource" to r.id.bytes(),"start" to Uuids.toTimestamp(start),"end" to Uuids.toTimestamp(end),
                "exclude" to exclude?.bytes())).query { rs,_ -> rs.getBytes(1).uuid()!! }.list()
    }
    private fun collision(ids:List<UUID>):Nothing = throw ConflictException("ALLOCATION_COLLISION",
        "This resource is already allocated in an overlapping window.",
        listOf(FieldError("startAt","overlap",ids.joinToString())))

    private fun eligibility(r:Resource,end:Instant,zone:ZoneId):List<String> {
        val policy=masters.policyForAllocation()
        if(!policy.requirementsConfigured) throw BusinessRuleException("CERTIFICATE_POLICY_PENDING",
            "Certificate requirements must be configured before allocation.")
        val scopeCodes=if(r.kind=="PERSON") jdbc.sql("SELECT competency_code FROM personnel_competency WHERE personnel_id=:id")
            .param("id",r.id.bytes()).query(String::class.java).list() else listOfNotNull(r.category)
        val requirements=masters.requirementsForAllocation().filter { it.active && it.resourceKind==r.kind && it.scopeCode in scopeCodes }
        if(requirements.isEmpty()) throw BusinessRuleException("CERTIFICATE_POLICY_PENDING",
            "Certificate requirements for this resource category or competency are not configured.")
        val certs=masters.certificatesForAllocation(r.kind,r.id)
        val endDate=end.atZone(zone).toLocalDate()
        val messages=mutableListOf<String>()
        requirements.forEach { requirement ->
            val valid=certs.any { it.typeCode==requirement.certificateTypeCode && it.issueDate<=endDate &&
                (it.expiryDate==null || it.expiryDate>=endDate) }
            if(!valid) messages += "Required ${requirement.certificateTypeCode} certificate is missing or expires before the allocation ends."
        }
        if(policy.mode=="BLOCK" && messages.isNotEmpty()) throw BusinessRuleException("CERTIFICATE_REQUIRED",messages.joinToString(" "))
        policy.warningDays?.let { horizon -> certs.filter { it.expiryDate!=null &&
            !it.expiryDate.isBefore(endDate) && !it.expiryDate.isAfter(endDate.plusDays(horizon.toLong())) }.forEach {
            messages += "${it.typeCode} certificate expires within $horizon days of allocation end."
        } }
        return messages.distinct()
    }
    private fun zone(job:JobTechnicalRef):ZoneId {
        val name=jdbc.sql("SELECT timezone FROM branch WHERE id=:id").param("id",job.branchId.bytes())
            .query(String::class.java).single()
        return ZoneId.of(name)
    }
    private fun interval(start:Instant,end:Instant) {
        if(!end.isAfter(start)) throw BadRequestException("VALIDATION_FAILED","End must be after start.",
            listOf(FieldError("endAt","range",null)))
    }
    private fun event(id:UUID,action:String,details:Map<String,Any?>) {
        jdbc.sql("""INSERT INTO allocation_event (id,allocation_id,action,details_json,occurred_at,actor_id)
            VALUES (:id,:allocation,:action,:details,:at,:actor)""")
            .params(mapOf("id" to UuidV7.generate().bytes(),"allocation" to id.bytes(),"action" to action,
                "details" to json.writeValueAsString(details),"at" to access.now(),"actor" to access.actor().bytes())).update()
    }
}
