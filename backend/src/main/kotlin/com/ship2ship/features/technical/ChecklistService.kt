package com.ship2ship.features.technical

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.BusinessRuleException
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.Versioning
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

data class ChecklistItemInput(val code: String,val label: String,val answerType: String,val required: Boolean = true)
data class ChecklistTemplateInput(val code: String,val name: String,val appliesTo: String,val items: List<ChecklistItemInput>)
data class ChecklistItemDto(val code: String,val label: String,val answerType: String,val required: Boolean)
data class ChecklistTemplateDto(val id: UUID,val code: String,val name: String,val versionNo: Int,
    val status: String,val appliesTo: String,val items: List<ChecklistItemDto>,val version: Long)
data class ChecklistResponseInput(val templateId: UUID,val jobVesselId: UUID?)
data class ChecklistAnswerInput(val itemCode: String,val answer: String?)
data class ChecklistResponseUpdate(val answers: List<ChecklistAnswerInput>,val signedDocumentId: UUID?,val status: String)
data class ChecklistResponseDto(val id: UUID,val jobId: UUID,val jobVesselId: UUID?,val template: ChecklistTemplateDto,
    val status: String,val answers: Map<String,String?>,val signedDocumentId: UUID?,val version: Long)

@Service
class ChecklistService(private val jdbc: JdbcClient,private val access: JobTechnicalAccess,
    private val policy: AccessPolicy,private val audit: AuditService,private val clock: Clock,
    private val progress: JobRequestProgress) {
    private fun now() = Uuids.toTimestamp(clock.instant())
    private fun actor() = policy.currentUser().id

    @Transactional(readOnly=true)
    fun templates(): List<ChecklistTemplateDto> {
        policy.requireAny(Permissions.MASTER_TECHNICAL_READ)
        return jdbc.sql("SELECT id FROM checklist_template ORDER BY code,version_no DESC")
            .query { rs,_ -> template(rs.getBytes(1).uuid()!!) }.list()
    }

    @Transactional
    fun createTemplate(input: ChecklistTemplateInput): ChecklistTemplateDto {
        policy.requireAny(Permissions.MASTER_TECHNICAL_WRITE)
        if (input.code.isBlank() || input.name.isBlank() || input.appliesTo !in setOf("JOB","EACH_VESSEL") ||
            input.items.map { it.code }.toSet().size != input.items.size)
            throw BadRequestException("VALIDATION_FAILED","Invalid checklist template.")
        val id = UuidV7.generate()
        val versionNo = jdbc.sql("SELECT COALESCE(MAX(version_no),0)+1 FROM checklist_template WHERE code=:code")
            .param("code",input.code.trim().uppercase()).query(Int::class.java).single()
        jdbc.sql("""INSERT INTO checklist_template (id,code,version_no,status,name,applies_to,created_at,created_by,
            updated_at,updated_by,version) VALUES (:id,:code,:number,'DRAFT',:name,:applies,:time,:actor,:time,:actor,0)""")
            .param("id",id.bytes()).param("code",input.code.trim().uppercase()).param("number",versionNo)
            .param("name",input.name.trim()).param("applies",input.appliesTo).param("time",now())
            .param("actor",actor().bytes()).update()
        input.items.forEachIndexed { index,item ->
            if (item.code.isBlank() || item.label.isBlank() || item.answerType !in setOf("TEXT","YES_NO","NUMBER","DATE"))
                throw BadRequestException("VALIDATION_FAILED","Invalid checklist item.")
            jdbc.sql("""INSERT INTO checklist_template_item (id,template_id,sort_order,code,label,answer_type,required)
                VALUES (:id,:template,:sort,:code,:label,:type,:required)""")
                .param("id",UuidV7.generate().bytes()).param("template",id.bytes()).param("sort",index)
                .param("code",item.code.trim().uppercase()).param("label",item.label.trim())
                .param("type",item.answerType).param("required",item.required).update()
        }
        audit.record(AuditEntry(AuditAction.CREATE,"CHECKLIST_TEMPLATE",id,details=mapOf("code" to input.code)))
        return template(id)
    }

    @Transactional
    fun reviseTemplate(id: UUID,expectedVersion: Long,input: ChecklistTemplateInput): ChecklistTemplateDto {
        policy.requireAny(Permissions.MASTER_TECHNICAL_WRITE)
        val current = template(id)
        Versioning.checkCurrent(expectedVersion,current.version)
        if (input.code.trim().uppercase() != current.code)
            throw BadRequestException("CODE_IMMUTABLE","Checklist code cannot change.")
        if (current.status == "PUBLISHED") return createTemplate(input)
        if (input.name.isBlank() || input.appliesTo !in setOf("JOB","EACH_VESSEL") ||
            input.items.map { it.code }.toSet().size != input.items.size)
            throw BadRequestException("VALIDATION_FAILED","Invalid checklist template.")
        jdbc.sql("DELETE FROM checklist_template_item WHERE template_id=:id").param("id",id.bytes()).update()
        jdbc.sql("""UPDATE checklist_template SET name=:name,applies_to=:applies,updated_at=:time,
            updated_by=:actor,version=version+1 WHERE id=:id""")
            .param("name",input.name.trim()).param("applies",input.appliesTo)
            .param("time",now()).param("actor",actor().bytes()).param("id",id.bytes()).update()
        input.items.forEachIndexed { index,item ->
            if (item.code.isBlank() || item.label.isBlank() || item.answerType !in setOf("TEXT","YES_NO","NUMBER","DATE"))
                throw BadRequestException("VALIDATION_FAILED","Invalid checklist item.")
            jdbc.sql("""INSERT INTO checklist_template_item (id,template_id,sort_order,code,label,answer_type,required)
                VALUES (:id,:template,:sort,:code,:label,:type,:required)""")
                .param("id",UuidV7.generate().bytes()).param("template",id.bytes()).param("sort",index)
                .param("code",item.code.trim().uppercase()).param("label",item.label.trim())
                .param("type",item.answerType).param("required",item.required).update()
        }
        audit.record(AuditEntry(AuditAction.UPDATE,"CHECKLIST_TEMPLATE",id,details=mapOf("itemCount" to input.items.size)))
        return template(id)
    }

    @Transactional
    fun publish(id: UUID,expectedVersion: Long): ChecklistTemplateDto {
        policy.requireAny(Permissions.MASTER_TECHNICAL_WRITE)
        val current = template(id)
        Versioning.checkCurrent(expectedVersion,current.version)
        if (current.status != "DRAFT" || current.items.isEmpty())
            throw BusinessRuleException("TEMPLATE_PENDING","A checklist needs configured items before publishing.")
        jdbc.sql("UPDATE checklist_template SET status='PUBLISHED',version=version+1,updated_at=:time,updated_by=:actor WHERE id=:id")
            .param("time",now()).param("actor",actor().bytes()).param("id",id.bytes()).update()
        audit.record(AuditEntry(AuditAction.STATUS_CHANGE,"CHECKLIST_TEMPLATE",id,details=mapOf("to" to "PUBLISHED")))
        return template(id)
    }

    @Transactional(readOnly=true)
    fun forJob(jobId: UUID): List<ChecklistResponseDto> {
        access.job(jobId)
        return jdbc.sql("SELECT id FROM job_checklist_response WHERE job_id=:job ORDER BY created_at")
            .param("job",jobId.bytes()).query { rs,_ -> response(rs.getBytes(1).uuid()!!) }.list()
    }

    @Transactional
    fun create(jobId: UUID,input: ChecklistResponseInput): ChecklistResponseDto {
        val job = access.job(jobId,Permissions.TECH_JOB_WRITE)
        val template = template(input.templateId)
        if (template.status != "PUBLISHED") throw BusinessRuleException("TEMPLATE_PENDING","Checklist template is not published.")
        if (template.appliesTo == "EACH_VESSEL") {
            access.vessel(job,input.jobVesselId ?: throw BadRequestException("WRONG_JOB_VESSEL","Choose a job vessel."))
        } else if (input.jobVesselId != null) throw BadRequestException("VALIDATION_FAILED","This checklist belongs to the whole job.")
        // Serialize creation for this job so a retried request returns the same response.
        jdbc.sql("SELECT id FROM sts_job WHERE id=:job FOR UPDATE").param("job",jobId.bytes())
            .query { rs,_ -> rs.getBytes(1) }.single()
        val existing = jdbc.sql("""SELECT id FROM job_checklist_response
            WHERE job_id=:job AND template_id=:template AND job_vessel_id <=> :vessel""")
            .param("job",jobId.bytes()).param("template",input.templateId.bytes())
            .param("vessel",input.jobVesselId?.bytes())
            .query { rs,_ -> rs.getBytes(1).uuid()!! }.optional().orElse(null)
        if (existing != null) return response(existing)
        val id = UuidV7.generate()
        jdbc.sql("""INSERT INTO job_checklist_response (id,job_id,job_vessel_id,template_id,status,
            created_at,created_by,updated_at,updated_by,version)
            VALUES (:id,:job,:vessel,:template,'DRAFT',:time,:actor,:time,:actor,0)""")
            .param("id",id.bytes()).param("job",jobId.bytes()).param("vessel",input.jobVesselId?.bytes())
            .param("template",input.templateId.bytes()).param("time",now()).param("actor",actor().bytes()).update()
        audit.record(AuditEntry(AuditAction.CREATE,"JOB_CHECKLIST",id,job.branchId))
        return response(id)
    }

    @Transactional(readOnly=true)
    fun get(jobId: UUID,id: UUID): ChecklistResponseDto {
        access.job(jobId)
        val found = response(id)
        if (found.jobId != jobId) throw NotFoundException("Checklist response")
        return found
    }

    @Transactional
    fun update(jobId: UUID,id: UUID,expectedVersion: Long,input: ChecklistResponseUpdate): ChecklistResponseDto {
        val job = access.job(jobId,Permissions.TECH_JOB_WRITE)
        val current = get(jobId,id)
        Versioning.checkCurrent(expectedVersion,current.version)
        if (input.status !in setOf("DRAFT","RECORDED")) throw BadRequestException("VALIDATION_FAILED","Invalid checklist status.")
        val fields = current.template.items.associateBy { it.code }
        if (input.answers.map { it.itemCode }.toSet().size != input.answers.size || input.answers.any { it.itemCode !in fields })
            throw BadRequestException("VALIDATION_FAILED","Unknown or repeated checklist item.")
        input.answers.forEach { answer ->
            val value = answer.answer?.trim()
            when (fields[answer.itemCode]!!.answerType) {
                "YES_NO" -> if (value != null && value !in setOf("YES","NO")) throw BadRequestException("VALIDATION_FAILED","Use YES or NO.")
                "NUMBER" -> if (value != null && value.toBigDecimalOrNull() == null) throw BadRequestException("VALIDATION_FAILED","Enter a number.")
                "DATE" -> if (value != null && runCatching { java.time.LocalDate.parse(value) }.isFailure)
                    throw BadRequestException("VALIDATION_FAILED","Enter an ISO date.")
            }
        }
        if (input.status == "RECORDED" && fields.values.any { it.required && input.answers.none { a -> a.itemCode == it.code && !a.answer.isNullOrBlank() } })
            throw BusinessRuleException("CHECKLIST_INCOMPLETE","Answer every required item before recording.")
        if (input.signedDocumentId != null) {
            val owner = jdbc.sql("SELECT owner_type,owner_id FROM document_version WHERE id=:id")
                .param("id",input.signedDocumentId.bytes()).query { rs,_ -> rs.getString(1) to rs.getBytes(2).uuid() }
                .optional().orElse(null) ?: throw BadRequestException("VALIDATION_FAILED","Unknown signed document.")
            val valid = (owner.first == "JOB" && owner.second == jobId) ||
                (owner.first == "JOB_VESSEL" && owner.second == current.jobVesselId)
            if (!valid) throw BadRequestException("WRONG_JOB_VESSEL","Signed document belongs to another job or vessel.")
        }
        jdbc.sql("DELETE FROM job_checklist_answer WHERE response_id=:id").param("id",id.bytes()).update()
        input.answers.forEach { answer ->
            jdbc.sql("INSERT INTO job_checklist_answer (id,response_id,item_code,answer_text) VALUES (:id,:response,:code,:answer)")
                .param("id",UuidV7.generate().bytes()).param("response",id.bytes()).param("code",answer.itemCode)
                .param("answer",answer.answer?.trim()?.take(1000)).update()
        }
        jdbc.sql("""UPDATE job_checklist_response SET status=:status,signed_document_id=:document,
            updated_at=:time,updated_by=:actor,version=version+1 WHERE id=:id""")
            .param("status",input.status).param("document",input.signedDocumentId?.bytes())
            .param("time",now()).param("actor",actor().bytes()).param("id",id.bytes()).update()
        audit.record(AuditEntry(AuditAction.UPDATE,"JOB_CHECKLIST",id,job.branchId,
            mapOf("status" to input.status,"answerCount" to input.answers.size,"signedDocumentId" to input.signedDocumentId)))
        if (input.status == "RECORDED" && input.signedDocumentId != null)
            progress.markReceived(jobId,current.jobVesselId,"CHECKLIST",current.template.code,"Checklist response $id with signed document ${input.signedDocumentId}")
        return response(id)
    }

    private fun template(id: UUID): ChecklistTemplateDto {
        val head = jdbc.sql("SELECT code,name,version_no,status,applies_to,version FROM checklist_template WHERE id=:id")
            .param("id",id.bytes()).query { rs,_ -> ChecklistTemplateDto(id,rs.getString(1),rs.getString(2),
                rs.getInt(3),rs.getString(4),rs.getString(5),emptyList(),rs.getLong(6)) }
            .optional().orElse(null) ?: throw NotFoundException("Checklist template")
        val items = jdbc.sql("""SELECT code,label,answer_type,required FROM checklist_template_item
            WHERE template_id=:id ORDER BY sort_order""").param("id",id.bytes())
            .query { rs,_ -> ChecklistItemDto(rs.getString(1),rs.getString(2),rs.getString(3),rs.getBoolean(4)) }.list()
        return head.copy(items=items)
    }

    private data class ResponseRow(val jobId: UUID,val jobVesselId: UUID?,val templateId: UUID,
        val status: String,val signedDocumentId: UUID?,val version: Long)

    private fun response(id: UUID): ChecklistResponseDto {
        val row = jdbc.sql("""SELECT job_id,job_vessel_id,template_id,status,signed_document_id,version
            FROM job_checklist_response WHERE id=:id""").param("id",id.bytes()).query { rs,_ ->
                ResponseRow(rs.getBytes(1).uuid()!!,rs.getBytes(2).uuid(),rs.getBytes(3).uuid()!!,
                    rs.getString(4),rs.getBytes(5).uuid(),rs.getLong(6))
            }.optional().orElse(null) ?: throw NotFoundException("Checklist response")
        val answers = jdbc.sql("SELECT item_code,answer_text FROM job_checklist_answer WHERE response_id=:id")
            .param("id",id.bytes()).query { rs,_ -> rs.getString(1) to rs.getString(2) }.list().toMap()
        return ChecklistResponseDto(id,row.jobId,row.jobVesselId,template(row.templateId),
            row.status,answers,row.signedDocumentId,row.version)
    }
}
