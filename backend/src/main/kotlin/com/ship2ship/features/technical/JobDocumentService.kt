package com.ship2ship.features.technical

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.files.FileAccess
import com.ship2ship.core.files.FileService
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.security.ReferenceLookup
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.BusinessRuleException
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.Versioning
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class RequestTemplateItemInput(val code: String, val label: String, val kind: String,
    val appliesTo: String, val documentTypeId: UUID?, val required: Boolean = true, val phase: String = "PRE_ARRIVAL")
data class RequestTemplateInput(val name: String, val emailSubject: String, val emailBody: String,
    val items: List<RequestTemplateItemInput>)
data class RequestTemplatePublish(val ownerConfirmation: String)
data class RequestTemplateItemDto(val code: String, val label: String, val kind: String,
    val appliesTo: String, val documentTypeId: UUID?, val required: Boolean, val phase: String = "PRE_ARRIVAL")
data class RequestTemplateDto(val id: UUID, val versionNo: Int, val status: String, val name: String,
    val emailSubject: String, val emailBody: String, val items: List<RequestTemplateItemDto>, val version: Long)
data class RequestItemDto(val id: UUID, val jobVesselId: UUID?, val code: String, val label: String,
    val kind: String, val documentTypeId: UUID?, val required: Boolean, val status: String,
    val responseText: String?, val reviewComment: String?, val documentVersionIds: List<UUID>,
    val events: List<RequestEventDto>, val version: Long, val phase: String = "PRE_ARRIVAL")
data class RequestEventDto(val from: String?,val to: String,val reason: String?,val actorId: UUID,val occurredAt: Instant)
data class JobRequestDto(val id: UUID?, val jobId: UUID, val status: String, val templateVersion: Int?,
    val items: List<RequestItemDto>, val version: Long?)
data class EmailPreviewDto(val subject: String, val body: String, val unresolved: List<String>, val templateVersion: Int)
data class RequestItemChange(val status: String, val responseText: String? = null, val reason: String? = null)
data class RequestItemAdd(val code: String,val label: String,val kind: String,val documentTypeId: UUID?,
    val jobVesselId: UUID?,val required: Boolean,val reason: String, val phase: String = "PRE_ARRIVAL")
data class JobDocumentDto(val id: UUID, val jobVesselId: UUID?, val documentTypeId: UUID,
    val versionNo: Int, val fileId: UUID, val fileName: String, val contentType: String,
    val scanState: String, val uploadedAt: Instant)

@Service
class JobDocumentService(
    private val jdbc: JdbcClient,
    private val access: JobTechnicalAccess,
    private val policy: AccessPolicy,
    private val references: ReferenceLookup,
    private val files: FileService,
    private val audit: AuditService,
    private val clock: Clock,
) {
    private fun now() = Uuids.toTimestamp(clock.instant())
    private fun actor() = policy.currentUser().id

    @Transactional(readOnly = true)
    fun templates(): List<RequestTemplateDto> {
        policy.requireAny(Permissions.MASTER_TECHNICAL_READ)
        return jdbc.sql("SELECT id FROM job_request_template ORDER BY version_no DESC")
            .query { rs, _ -> template(rs.getBytes(1).uuid()!!) }.list()
    }

    @Transactional
    fun createTemplate(input: RequestTemplateInput): RequestTemplateDto {
        policy.requireAny(Permissions.MASTER_TECHNICAL_WRITE)
        validateTemplate(input)
        val id = UuidV7.generate()
        val versionNo = jdbc.sql("SELECT COALESCE(MAX(version_no),0)+1 FROM job_request_template")
            .query(Int::class.java).single()
        jdbc.sql("""INSERT INTO job_request_template
            (id,version_no,status,name,email_subject,email_body,created_at,created_by,updated_at,updated_by,version)
            VALUES (:id,:number,'DRAFT',:name,:subject,:body,:time,:actor,:time,:actor,0)""")
            .param("id",id.bytes()).param("number",versionNo).param("name",input.name.trim())
            .param("subject",input.emailSubject.trim()).param("body",input.emailBody.trim())
            .param("time",now()).param("actor",actor().bytes()).update()
        input.items.forEachIndexed { index, row ->
            jdbc.sql("""INSERT INTO job_request_template_item
                (id,template_id,sort_order,code,label,kind,applies_to,document_type_id,required,phase)
                VALUES (:id,:template,:sort,:code,:label,:kind,:applies,:type,:required,:phase)""")
                .param("id",UuidV7.generate().bytes()).param("template",id.bytes()).param("sort",index)
                .param("code",row.code.trim().uppercase()).param("label",row.label.trim())
                .param("kind",row.kind).param("applies",row.appliesTo)
                .param("type",row.documentTypeId?.bytes()).param("required",row.required)
                .param("phase",row.phase).update()
        }
        audit.record(AuditEntry(AuditAction.CREATE,"JOB_REQUEST_TEMPLATE",id,details=mapOf("versionNo" to versionNo)))
        return template(id)
    }

    @Transactional
    fun reviseTemplate(id: UUID,expectedVersion: Long,input: RequestTemplateInput): RequestTemplateDto {
        policy.requireAny(Permissions.MASTER_TECHNICAL_WRITE)
        val current = template(id)
        Versioning.checkCurrent(expectedVersion,current.version)
        validateTemplate(input)
        if (current.status == "PUBLISHED") return createTemplate(input)
        jdbc.sql("DELETE FROM job_request_template_item WHERE template_id=:id").param("id",id.bytes()).update()
        jdbc.sql("""UPDATE job_request_template SET name=:name,email_subject=:subject,email_body=:body,
            updated_at=:time,updated_by=:actor,version=version+1 WHERE id=:id""")
            .param("name",input.name.trim()).param("subject",input.emailSubject.trim())
            .param("body",input.emailBody.trim()).param("time",now()).param("actor",actor().bytes())
            .param("id",id.bytes()).update()
        input.items.forEachIndexed { index,row ->
            jdbc.sql("""INSERT INTO job_request_template_item
                (id,template_id,sort_order,code,label,kind,applies_to,document_type_id,required,phase)
                VALUES (:id,:template,:sort,:code,:label,:kind,:applies,:type,:required,:phase)""")
                .param("id",UuidV7.generate().bytes()).param("template",id.bytes()).param("sort",index)
                .param("code",row.code.trim().uppercase()).param("label",row.label.trim())
                .param("kind",row.kind).param("applies",row.appliesTo)
                .param("type",row.documentTypeId?.bytes()).param("required",row.required)
                .param("phase",row.phase).update()
        }
        audit.record(AuditEntry(AuditAction.UPDATE,"JOB_REQUEST_TEMPLATE",id,details=mapOf("itemCount" to input.items.size)))
        return template(id)
    }

    @Transactional
    fun publishTemplate(id: UUID, expectedVersion: Long, input: RequestTemplatePublish): RequestTemplateDto {
        policy.requireAny(Permissions.MASTER_TECHNICAL_WRITE)
        val current = template(id)
        Versioning.checkCurrent(expectedVersion,current.version)
        if (current.status != "DRAFT") throw BusinessRuleException("INVALID_TRANSITION","Only draft templates may be published.")
        if (current.items.isEmpty() || current.items.any { it.label.isBlank() })
            throw BusinessRuleException("TEMPLATE_PENDING","Complete the request items before publishing.")
        if (input.ownerConfirmation.isBlank())
            throw BadRequestException("REASON_REQUIRED","Record the owner confirmation before publishing.")
        if (current.items.any { it.label.contains("pending",ignoreCase=true) })
            throw BusinessRuleException("TEMPLATE_PENDING","Replace pending checklist names before publishing.")
        val checklistCodes = current.items.filter { it.kind == "CHECKLIST" }.map { it.code }
        if ((checklistCodes.isNotEmpty() && checklistCodes.size != 5) || current.items.count { it.kind != "CHECKLIST" } !in 9..10)
            throw BusinessRuleException("TEMPLATE_PENDING","Confirm nine or ten request categories; checklist requests must include all five signed checklists.")
        val publishedCodes = if (checklistCodes.isEmpty()) emptySet() else jdbc.sql("SELECT code FROM checklist_template WHERE status='PUBLISHED'")
            .query(String::class.java).list().toSet()
        if (checklistCodes.any { it !in publishedCodes })
            throw BusinessRuleException("TEMPLATE_PENDING","Publish all named checklist templates first.")
        jdbc.sql("UPDATE job_request_template SET status='PUBLISHED',version=version+1,updated_at=:time,updated_by=:actor WHERE id=:id")
            .param("time",now()).param("actor",actor().bytes()).param("id",id.bytes()).update()
        audit.record(AuditEntry(AuditAction.STATUS_CHANGE,"JOB_REQUEST_TEMPLATE",id,
            details=mapOf("to" to "PUBLISHED","ownerConfirmation" to input.ownerConfirmation.trim())))
        return template(id)
    }

    @Transactional(readOnly = true)
    fun request(jobId: UUID): JobRequestDto {
        access.job(jobId)
        val row = jdbc.sql("SELECT id,template_id,version FROM job_document_request WHERE job_id=:job")
            .param("job",jobId.bytes()).query { rs, _ -> Triple(rs.getBytes(1).uuid()!!,rs.getBytes(2).uuid()!!,rs.getLong(3)) }
            .optional().orElse(null) ?: return JobRequestDto(null,jobId,"PENDING_TEMPLATE",null,emptyList(),null)
        val templateVersion = jdbc.sql("SELECT version_no FROM job_request_template WHERE id=:id")
            .param("id",row.second.bytes()).query(Int::class.java).single()
        val items = jdbc.sql("""SELECT id,job_vessel_id,code,label,kind,document_type_id,required,status,
            response_text,review_comment,version,phase FROM job_request_item WHERE request_id=:id
            ORDER BY sort_order,job_vessel_id""").param("id",row.first.bytes()).query { rs, _ -> item(rs) }.list()
        return JobRequestDto(row.first,jobId,"ACTIVE",templateVersion,items,row.third)
    }

    @Transactional
    fun initialise(jobId: UUID): JobRequestDto {
        val job = access.job(jobId,Permissions.TECH_JOB_WRITE)
        jdbc.sql("SELECT id FROM sts_job WHERE id=:id FOR UPDATE").param("id",jobId.bytes())
            .query { rs,_ -> rs.getBytes(1) }.single()
        fun linkMatchingDocuments(current: JobRequestDto): JobRequestDto {
            var linked = false
            current.items.filter { (it.kind == "DOCUMENT" || it.kind == "CHECKLIST") && it.status == "PENDING"
                && it.documentVersionIds.isEmpty() }.forEach { item ->
                val vessel = item.jobVesselId?.let { vId -> job.vessels.firstOrNull { it.id == vId } }
                val targetDocTypeId = item.documentTypeId ?: jdbc.sql("SELECT id FROM document_type WHERE code=:code")
                    .param("code", item.code).query { rs, _ -> rs.getBytes(1).uuid() }.optional().orElse(null)
                if (targetDocTypeId != null) {
                    val docId = if (vessel != null) {
                        jdbc.sql("""SELECT id FROM document_version WHERE ((owner_type='JOB_VESSEL' AND owner_id=:jobVessel)
                            OR (owner_type='VESSEL' AND owner_id=:masterVessel)) AND document_type_id=:type
                            ORDER BY version_no DESC LIMIT 1""")
                            .param("jobVessel", vessel.id.bytes()).param("masterVessel", vessel.vesselId.bytes())
                            .param("type", targetDocTypeId.bytes()).query { rs,_ -> rs.getBytes(1).uuid() }.optional().orElse(null)
                    } else {
                        jdbc.sql("""SELECT id FROM document_version WHERE owner_type='JOB' AND owner_id=:job
                            AND document_type_id=:type ORDER BY version_no DESC LIMIT 1""")
                            .param("job", jobId.bytes()).param("type", targetDocTypeId.bytes())
                            .query { rs,_ -> rs.getBytes(1).uuid() }.optional().orElse(null)
                    }
                    if (docId != null) {
                        jdbc.sql("""INSERT INTO job_request_evidence (id,item_id,document_version_id,linked_at,linked_by)
                            SELECT :id,:item,:document,:time,:actor WHERE NOT EXISTS
                            (SELECT 1 FROM job_request_evidence WHERE item_id=:item AND document_version_id=:document)""")
                            .param("id", UuidV7.generate().bytes()).param("item", item.id.bytes())
                            .param("document", docId.bytes()).param("time", now()).param("actor", actor().bytes()).update()
                        jdbc.sql("UPDATE job_request_item SET status='RECEIVED',updated_at=:time,updated_by=:actor,version=version+1 WHERE id=:id")
                            .param("time", now()).param("actor", actor().bytes()).param("id", item.id.bytes()).update()
                        jdbc.sql("""INSERT INTO job_request_event (id,item_id,old_status,new_status,reason,actor_id,occurred_at)
                            VALUES (:id,:item,'PENDING','RECEIVED',NULL,:actor,:time)""")
                            .param("id", UuidV7.generate().bytes()).param("item", item.id.bytes())
                            .param("actor", actor().bytes()).param("time", now()).update()
                        audit.record(AuditEntry(AuditAction.STATUS_CHANGE,"JOB_REQUEST_ITEM",item.id,job.branchId,
                            mapOf("from" to "PENDING","to" to "RECEIVED","documentVersionId" to docId)))
                        linked = true
                    }
                }
            }
            return if (linked) request(jobId) else current
        }
        val existing = request(jobId)
        if (existing.id != null) return linkMatchingDocuments(existing)
        val template = jdbc.sql("SELECT id FROM job_request_template WHERE status='PUBLISHED' ORDER BY version_no DESC LIMIT 1")
            .query { rs, _ -> rs.getBytes(1).uuid()!! }.optional().orElse(null)
            ?: throw BusinessRuleException("TEMPLATE_PENDING","The document request template is pending owner confirmation.")
        val requestId = UuidV7.generate()
        jdbc.sql("""INSERT INTO job_document_request
            (id,job_id,template_id,created_at,created_by,updated_at,updated_by,version)
            VALUES (:id,:job,:template,:time,:actor,:time,:actor,0)""")
            .param("id",requestId.bytes()).param("job",jobId.bytes()).param("template",template.bytes())
            .param("time",now()).param("actor",actor().bytes()).update()
        val rows = jdbc.sql("""SELECT code,label,kind,applies_to,document_type_id,required,sort_order,phase
            FROM job_request_template_item WHERE template_id=:id ORDER BY sort_order""")
            .param("id",template.bytes()).query { rs, _ -> TemplateRow(rs.getString(1),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getBytes(5).uuid(),rs.getBoolean(6),rs.getInt(7),rs.getString(8)) }.list()
        rows.forEach { row ->
            val vesselIds = if (row.appliesTo == "EACH_VESSEL") job.vessels.map { it.id } else listOf(null)
            vesselIds.forEach { vesselId ->
                jdbc.sql("""INSERT INTO job_request_item (id,request_id,job_vessel_id,code,label,kind,document_type_id,
                    required,sort_order,status,phase,created_at,created_by,updated_at,updated_by,version)
                    VALUES (:id,:request,:vessel,:code,:label,:kind,:type,:required,:sort,'PENDING',:phase,:time,:actor,:time,:actor,0)""")
                    .param("id",UuidV7.generate().bytes()).param("request",requestId.bytes())
                    .param("vessel",vesselId?.bytes()).param("code",row.code).param("label",row.label)
                    .param("kind",row.kind).param("type",row.documentTypeId?.bytes())
                    .param("required",row.required).param("sort",row.sortOrder)
                    .param("phase",row.phase)
                    .param("time",now()).param("actor",actor().bytes()).update()
            }
        }
        audit.record(AuditEntry(AuditAction.CREATE,"JOB_DOCUMENT_REQUEST",requestId,job.branchId))
        return linkMatchingDocuments(request(jobId))
    }

    @Transactional
    fun changeItem(jobId: UUID, itemId: UUID, expectedVersion: Long, input: RequestItemChange): JobRequestDto {
        val job = access.job(jobId,Permissions.TECH_JOB_WRITE)
        val current = request(jobId)
        val item = current.items.firstOrNull { it.id == itemId } ?: throw NotFoundException("Request item")
        Versioning.checkCurrent(expectedVersion,item.version)
        if (input.status !in setOf("PENDING","RECEIVED","REVIEWED","RETURNED","REMOVED"))
            throw BadRequestException("VALIDATION_FAILED","Invalid collection status.")
        if (item.status == "REMOVED") throw BusinessRuleException("INVALID_TRANSITION","A removed item cannot be changed.")
        if (input.status == "REVIEWED")
            policy.requireBranch(Permissions.TECH_JOB_REVIEW,job.branchId,"JOB",jobId)
        if (input.status == "REVIEWED" && item.status != "RECEIVED")
            throw BusinessRuleException("INVALID_TRANSITION","Receive evidence before reviewing this item.")
        if (input.status == "RECEIVED" && item.kind in setOf("CONTACT","DECLARATION") && input.responseText.isNullOrBlank())
            throw BadRequestException("VALIDATION_FAILED","Enter the requested response before marking it received.")
        if (input.status == "RETURNED" && input.reason.isNullOrBlank())
            throw BadRequestException("REASON_REQUIRED","A reason is needed to return an item.")
        if (input.status == "REMOVED" && input.reason.isNullOrBlank())
            throw BadRequestException("REASON_REQUIRED","A reason is needed to remove an item.")
        jdbc.sql("""UPDATE job_request_item SET status=:status,response_text=:response,review_comment=:comment,
            updated_at=:time,updated_by=:actor,version=version+1 WHERE id=:id""")
            .param("status",input.status).param("response",input.responseText?.trim()?.take(1000))
            .param("comment",input.reason?.trim()?.take(500)).param("time",now())
            .param("actor",actor().bytes()).param("id",itemId.bytes()).update()
        jdbc.sql("""INSERT INTO job_request_event (id,item_id,old_status,new_status,reason,actor_id,occurred_at)
            VALUES (:id,:item,:old,:new,:reason,:actor,:time)""")
            .param("id",UuidV7.generate().bytes()).param("item",itemId.bytes()).param("old",item.status)
            .param("new",input.status).param("reason",input.reason?.trim()).param("actor",actor().bytes())
            .param("time",now()).update()
        audit.record(AuditEntry(AuditAction.STATUS_CHANGE,"JOB_REQUEST_ITEM",itemId,job.branchId,
            mapOf("from" to item.status,"to" to input.status,"reason" to input.reason)))
        return request(jobId)
    }

    @Transactional
    fun addItem(jobId: UUID,input: RequestItemAdd): JobRequestDto {
        val job = access.job(jobId,Permissions.TECH_JOB_WRITE)
        val current = request(jobId)
        val requestId = current.id ?: throw BusinessRuleException("TEMPLATE_PENDING","Create a request first.")
        if (input.code.isBlank() || input.label.isBlank() || input.reason.isBlank() ||
            input.kind !in setOf("DOCUMENT","DECLARATION","CONTACT","VOYAGE_DATA","CHECKLIST") ||
            (input.kind == "DOCUMENT" && input.documentTypeId == null))
            throw BadRequestException("VALIDATION_FAILED","Provide an item code, label, kind and reason.")
        input.jobVesselId?.let { access.vessel(job,it) }
        if (input.kind == "DOCUMENT") {
            val type = references.documentTypes(listOf(input.documentTypeId!!))[input.documentTypeId]
            val expectedOwner = if (input.jobVesselId == null) "JOB" else "VESSEL"
            if (type == null || !type.active || type.appliesTo != expectedOwner)
                throw BadRequestException("VALIDATION_FAILED","Document type does not match this item owner.")
        }
        if (current.items.any { it.code == input.code.trim().uppercase() && it.jobVesselId == input.jobVesselId && it.status != "REMOVED" })
            throw BadRequestException("VALIDATION_FAILED","This item already exists for the selected vessel.")
        val id = UuidV7.generate()
        val sort = current.items.size + 1
        jdbc.sql("""INSERT INTO job_request_item (id,request_id,job_vessel_id,code,label,kind,document_type_id,
            required,sort_order,status,phase,created_at,created_by,updated_at,updated_by,version)
            VALUES (:id,:request,:vessel,:code,:label,:kind,:type,:required,:sort,'PENDING',:phase,:time,:actor,:time,:actor,0)""")
            .param("id",id.bytes()).param("request",requestId.bytes()).param("vessel",input.jobVesselId?.bytes())
            .param("code",input.code.trim().uppercase()).param("label",input.label.trim().take(200))
            .param("kind",input.kind).param("type",input.documentTypeId?.bytes())
            .param("required",input.required).param("sort",sort).param("phase",input.phase).param("time",now())
            .param("actor",actor().bytes()).update()
        jdbc.sql("""INSERT INTO job_request_event (id,item_id,new_status,reason,actor_id,occurred_at)
            VALUES (:id,:item,'PENDING',:reason,:actor,:time)""")
            .param("id",UuidV7.generate().bytes()).param("item",id.bytes()).param("reason",input.reason.trim().take(500))
            .param("actor",actor().bytes()).param("time",now()).update()
        audit.record(AuditEntry(AuditAction.CREATE,"JOB_REQUEST_ITEM",id,job.branchId,
            mapOf("jobId" to jobId,"reason" to input.reason)))
        return request(jobId)
    }

    @Transactional
    fun upload(jobId: UUID, jobVesselId: UUID?, itemId: UUID, documentTypeId: UUID, title: String?, upload: MultipartFile): JobDocumentDto {
        val job = access.job(jobId,Permissions.TECH_JOB_WRITE)
        jobVesselId?.let { access.vessel(job,it) }
        policy.requireBranch(Permissions.DOCUMENT_WRITE,job.branchId,"JOB",jobId)
        val item = request(jobId).items.firstOrNull { it.id == itemId && it.jobVesselId == jobVesselId }
            ?: throw BadRequestException("WRONG_JOB_VESSEL","The request item does not belong to this job vessel.")
        if (item.status == "REMOVED") throw BusinessRuleException("INVALID_TRANSITION","Cannot upload to a removed item.")
        val resolvedDocTypeId = documentTypeId.takeIf { it != UUID(0, 0) } ?: item.documentTypeId
            ?: jdbc.sql("SELECT id FROM document_type WHERE code=:code").param("code", item.code).query { rs, _ -> rs.getBytes(1).uuid() }.optional().orElse(null)
            ?: jdbc.sql("SELECT id FROM document_type WHERE code='OTHER-VSL'").query { rs, _ -> rs.getBytes(1).uuid() }.single()
        if (item.documentTypeId != null && resolvedDocTypeId != item.documentTypeId)
            throw BadRequestException("VALIDATION_FAILED", "Choose the document type requested by this item.")
        val type = references.documentTypes(listOf(resolvedDocTypeId))[resolvedDocTypeId]
        val expectedOwner = if (jobVesselId == null) "JOB" else "VESSEL"
        if (type == null || !type.active || type.appliesTo != expectedOwner)
            throw BadRequestException("VALIDATION_FAILED","Choose a document type for the selected owner.")
        val ownerType = if (jobVesselId == null) "JOB" else "JOB_VESSEL"
        val ownerId = jobVesselId ?: jobId
        val lockTable = if (jobVesselId == null) "sts_job" else "job_vessel"
        jdbc.sql("SELECT id FROM $lockTable WHERE id=:id FOR UPDATE").param("id",ownerId.bytes())
            .query { rs,_ -> rs.getBytes(1) }.single()
        val file = files.store(upload,FileAccess(Permissions.DOCUMENT_READ,job.branchId))
        val versionNo = jdbc.sql("""SELECT COALESCE(MAX(version_no),0)+1 FROM document_version
            WHERE owner_type=:ownerType AND owner_id=:owner AND document_type_id=:type""")
            .param("ownerType",ownerType).param("owner",ownerId.bytes()).param("type",resolvedDocTypeId.bytes())
            .query(Int::class.java).single()
        val documentId = UuidV7.generate()
        jdbc.sql("""INSERT INTO document_version (id,owner_type,owner_id,document_type_id,version_no,file_id,title,issued,uploaded_by,uploaded_at)
            VALUES (:id,:ownerType,:owner,:type,:number,:file,:title,FALSE,:actor,:time)""")
            .param("id",documentId.bytes()).param("ownerType",ownerType).param("owner",ownerId.bytes()).param("type",resolvedDocTypeId.bytes())
            .param("number",versionNo).param("file",file.id.bytes()).param("title",title?.trim()?.take(200))
            .param("actor",actor().bytes()).param("time",now()).update()
        jdbc.sql("""INSERT INTO job_request_evidence (id,item_id,document_version_id,linked_at,linked_by)
            VALUES (:id,:item,:document,:time,:actor)""")
            .param("id",UuidV7.generate().bytes()).param("item",itemId.bytes()).param("document",documentId.bytes())
            .param("time",now()).param("actor",actor().bytes()).update()
        jdbc.sql("UPDATE job_request_item SET status='RECEIVED',updated_at=:time,updated_by=:actor,version=version+1 WHERE id=:id")
            .param("time",now()).param("actor",actor().bytes()).param("id",itemId.bytes()).update()
        jdbc.sql("""INSERT INTO job_request_event (id,item_id,old_status,new_status,reason,actor_id,occurred_at)
            VALUES (:id,:item,:old,'RECEIVED',NULL,:actor,:time)""")
            .param("id",UuidV7.generate().bytes()).param("item",itemId.bytes()).param("old",item.status)
            .param("actor",actor().bytes()).param("time",now()).update()
        audit.record(AuditEntry(AuditAction.CREATE,"JOB_DOCUMENT",documentId,job.branchId,
            mapOf("jobVesselId" to jobVesselId,"fileId" to file.id,"sha256" to file.sha256)))
        return JobDocumentDto(documentId,jobVesselId,resolvedDocTypeId,versionNo,file.id,file.originalName,
            file.contentType,file.scanState.name,clock.instant())
    }

    @Transactional(readOnly = true)
    fun documents(jobId: UUID): List<JobDocumentDto> {
        val job = access.job(jobId)
        policy.requireBranch(Permissions.DOCUMENT_READ,job.branchId,"JOB",jobId)
        val owners: List<Triple<String,UUID?,UUID>> = job.vessels.flatMap {
            listOf(Triple("JOB_VESSEL", it.id, it.id), Triple("VESSEL", it.id, it.vesselId))
        } + listOf(Triple("JOB", null, jobId))
        return owners.flatMap { (ownerType,vesselId,ownerId) ->
            jdbc.sql("""SELECT d.id,d.document_type_id,d.version_no,d.file_id,f.original_name,f.content_type,f.scan_state,d.uploaded_at
                FROM document_version d JOIN stored_file f ON f.id=d.file_id
                JOIN document_type t ON t.id=d.document_type_id
                WHERE d.owner_type=:ownerType AND d.owner_id=:owner
                AND (:ownerType <> 'VESSEL' OR t.code='GA-PLAN') ORDER BY d.uploaded_at DESC""")
                .param("ownerType",ownerType).param("owner",ownerId.bytes()).query { rs, _ ->
                    JobDocumentDto(rs.getBytes(1).uuid()!!,vesselId,rs.getBytes(2).uuid()!!,rs.getInt(3),
                        rs.getBytes(4).uuid()!!,rs.getString(5),rs.getString(6),rs.getString(7),
                        Uuids.fromTimestamp(rs.getObject(8,java.time.LocalDateTime::class.java)))
                }.list()
        }
    }

    @Transactional(readOnly = true)
    fun emailPreview(jobId: UUID): EmailPreviewDto {
        val job = access.job(jobId)
        val request = request(jobId)
        val templateId = request.id?.let { id -> jdbc.sql("SELECT template_id FROM job_document_request WHERE id=:id")
            .param("id",id.bytes()).query { rs, _ -> rs.getBytes(1).uuid()!! }.single() }
            ?: throw BusinessRuleException("TEMPLATE_PENDING","The document request template is pending.")
        val template = template(templateId)
        val vesselNames = references.vessels(job.vessels.map { it.vesselId })
        val replacements = mapOf(
            "JOB_NUMBER" to job.jobNumber,
            "MOTHER_VESSEL" to job.vessels.firstOrNull { it.role == "MOTHER" }?.let { vesselNames[it.vesselId]?.name },
            "DAUGHTER_VESSEL" to job.vessels.firstOrNull { it.role == "DAUGHTER" }?.let { vesselNames[it.vesselId]?.name },
            "REQUEST_ITEMS" to request.items.filter { it.phase == "PRE_ARRIVAL" }
                .distinctBy { it.code }
                .joinToString("\n") { "- ${it.label}" },
        )
        val unresolved = mutableSetOf<String>()
        fun render(source: String): String = Regex("\\{([A-Z_]+)}").replace(source) { match ->
            val key = match.groupValues[1]
            replacements[key]?.takeIf { it.isNotBlank() } ?: run { unresolved += key; "[${key.lowercase().replace('_',' ')} pending]" }
        }
        return EmailPreviewDto(render(template.emailSubject),render(template.emailBody),unresolved.sorted(),template.versionNo)
    }

    private data class TemplateRow(val code: String,val label: String,val kind: String,val appliesTo: String,
        val documentTypeId: UUID?,val required: Boolean,val sortOrder: Int,val phase: String)

    private fun template(id: UUID): RequestTemplateDto {
        val head = jdbc.sql("""SELECT id,version_no,status,name,email_subject,email_body,version
            FROM job_request_template WHERE id=:id""").param("id",id.bytes()).query { rs, _ ->
            RequestTemplateDto(rs.getBytes(1).uuid()!!,rs.getInt(2),rs.getString(3),rs.getString(4),
                rs.getString(5),rs.getString(6),emptyList(),rs.getLong(7))
        }.optional().orElse(null) ?: throw NotFoundException("Request template")
        val items = jdbc.sql("""SELECT code,label,kind,applies_to,document_type_id,required,phase
            FROM job_request_template_item WHERE template_id=:id ORDER BY sort_order""")
            .param("id",id.bytes()).query { rs, _ -> RequestTemplateItemDto(rs.getString(1),rs.getString(2),
                rs.getString(3),rs.getString(4),rs.getBytes(5).uuid(),rs.getBoolean(6),rs.getString(7)) }.list()
        return head.copy(items=items)
    }

    private fun item(rs: ResultSet): RequestItemDto {
        val id = rs.getBytes(1).uuid()!!
        val evidence = jdbc.sql("SELECT document_version_id FROM job_request_evidence WHERE item_id=:id ORDER BY linked_at")
            .param("id",id.bytes()).query { e, _ -> e.getBytes(1).uuid()!! }.list()
        val events = jdbc.sql("""SELECT old_status,new_status,reason,actor_id,occurred_at FROM job_request_event
            WHERE item_id=:id ORDER BY occurred_at""").param("id",id.bytes()).query { e,_ ->
                RequestEventDto(e.getString(1),e.getString(2),e.getString(3),e.getBytes(4).uuid()!!,
                    Uuids.fromTimestamp(e.getObject(5,java.time.LocalDateTime::class.java)))
            }.list()
        return RequestItemDto(id,rs.getBytes(2).uuid(),rs.getString(3),rs.getString(4),rs.getString(5),
            rs.getBytes(6).uuid(),rs.getBoolean(7),rs.getString(8),rs.getString(9),rs.getString(10),evidence,events,rs.getLong(11),rs.getString(12))
    }

    private fun validateTemplate(input: RequestTemplateInput) {
        if (input.name.isBlank() || input.emailSubject.isBlank() || input.emailBody.isBlank() || input.items.isEmpty())
            throw BadRequestException("VALIDATION_FAILED","Provide a name, email text and at least one request item.")
        if (input.items.map { it.code.trim().uppercase() }.toSet().size != input.items.size)
            throw BadRequestException("VALIDATION_FAILED","Request item codes must be unique.")
        input.items.forEach {
            if (it.label.isBlank() || it.kind !in setOf("DOCUMENT","DECLARATION","CONTACT","VOYAGE_DATA","CHECKLIST") ||
                it.appliesTo !in setOf("JOB","EACH_VESSEL") || (it.kind == "DOCUMENT" && it.documentTypeId == null) ||
                it.phase !in setOf("PRE_ARRIVAL","ON_ARRIVAL","POST_DEPARTURE"))
                throw BadRequestException("VALIDATION_FAILED","Invalid request item.")
            if (it.kind == "DOCUMENT") {
                val type = references.documentTypes(listOf(it.documentTypeId!!))[it.documentTypeId]
                val expectedOwner = if (it.appliesTo == "JOB") "JOB" else "VESSEL"
                if (type == null || !type.active || type.appliesTo != expectedOwner)
                    throw BadRequestException("VALIDATION_FAILED","Document type does not match request item ownership.")
            }
        }
    }

}
