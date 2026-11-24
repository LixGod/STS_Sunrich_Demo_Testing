package com.ship2ship.features.technical

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.files.FileService
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.BusinessRuleException
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.Versioning
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

data class VoyageFieldDto(val code: String,val label: String,val unit: String,val requiredForReview: Boolean)
data class VoyageValueInput(val fieldCode: String,val value: String,val sourceDocumentId: UUID?,val sourcePage: Int?)
data class VoyageUpdate(val notes: String?,val values: List<VoyageValueInput>)
data class VoyageVerify(val confirmedFieldCodes: List<String>)
data class VoyageReturn(val reason: String)
data class VoyageValueDto(val fieldCode: String,val value: String,val sourceDocumentId: UUID?,
    val sourcePage: Int?,val verified: Boolean)
data class VoyageDto(val id: UUID,val jobVesselId: UUID,val versionNo: Int,val status: String,val notes: String?,
    val values: List<VoyageValueDto>,val submittedBy: UUID?,val submittedAt: Instant?,
    val verifiedBy: UUID?,val verifiedAt: Instant?,val returnedReason: String?,val version: Long)
data class VoyageOverviewDto(val jobVesselId: UUID,val versions: List<VoyageDto>)
data class CompatibilityWarningDto(val code: String,val vesselRole: String?,val message: String)
data class CompatibilityReviewDto(val jobId: UUID,val warnings: List<CompatibilityWarningDto>,
    val fixedParticularsVersionIds: Map<String,UUID?>,val voyageVersionIds: Map<String,UUID?>)
data class CompatibilityRuleDto(val code: String,val enabled: Boolean,val thresholdPercent: BigDecimal?,
    val comparisonMethod: String?,val version: Long)
data class CompatibilityRuleInput(val enabled: Boolean,val thresholdPercent: BigDecimal?,val comparisonMethod: String?)

@Service
class JobVoyageService(private val jdbc: JdbcClient,private val access: JobTechnicalAccess,
    private val policy: AccessPolicy,private val audit: AuditService,private val clock: Clock,
    private val progress: JobRequestProgress,private val files: FileService) {
    private fun now() = Uuids.toTimestamp(clock.instant())
    private fun actor() = policy.currentUser().id

    @Transactional
    fun populateFromDocument(jobId: UUID, jobVesselId: UUID, preferredDocumentId: UUID? = null): VoyageDto {
        val job = access.job(jobId, Permissions.TECH_JOB_WRITE)
        val vessel = access.vessel(job, jobVesselId)
        val doc = findVesselDocument(jobVesselId, vessel.vesselId, preferredDocumentId)
            ?: throw BadRequestException("SOURCE_REQUIRED", "Upload a document for this vessel first.")
        val extracted = extractPdfValues(doc.fileId, doc.contentType)
        val docLoa = extracted["LOA"]?.first
        val docBeam = extracted["BEAM"]?.first
        val docDwt = extracted["DWT"]?.first
        if (docLoa != null || docBeam != null || docDwt != null) {
            jdbc.sql("UPDATE vessel SET loa_m=COALESCE(loa_m,:loa), beam_m=COALESCE(beam_m,:beam), dwt_t=COALESCE(dwt_t,:dwt) WHERE id=:v")
                .param("loa", docLoa).param("beam", docBeam).param("dwt", docDwt).param("v", vessel.vesselId.bytes()).update()
        }

        val existing = if (preferredDocumentId == null)
            overview(jobId, jobVesselId).versions.firstOrNull()?.values?.associateBy { it.fieldCode }.orEmpty()
        else emptyMap()
        val values = fields(jobId).mapNotNull { field ->
            val pdfHit = extracted[field.code]
            val prev = existing[field.code]
            val number = pdfHit?.first ?: prev?.value?.toBigDecimalOrNull()
            if (number != null) {
                val page = pdfHit?.second ?: prev?.sourcePage ?: 1
                VoyageValueInput(field.code, number.stripTrailingZeros().toPlainString(), doc.id, page)
            } else null
        }
        return saveDraftVoyage(job.branchId, jobId, jobVesselId, "Taken from ${doc.fileName}", values)
    }

    @Transactional
    fun saveFromDocument(jobId: UUID, jobVesselId: UUID, input: VoyageUpdate): VoyageDto {
        val job = access.job(jobId, Permissions.TECH_JOB_WRITE)
        val vessel = access.vessel(job, jobVesselId)
        val fallbackDoc = findVesselDocument(jobVesselId, vessel.vesselId, null)?.id
        val catalogue = fields(jobId).associateBy { it.code }
        val clean = input.values.filter { it.value.isNotBlank() && it.fieldCode in catalogue }.map { v ->
            val docId = v.sourceDocumentId ?: fallbackDoc
                ?: throw BadRequestException("SOURCE_REQUIRED", "Upload a document for this vessel first.")
            requireDocument(jobVesselId, docId)
            val num = v.value.trim().toBigDecimalOrNull()?.takeIf { it.signum() >= 0 && it.scale() <= 3 && it.precision() - it.scale() <= 9 }
                ?: throw BadRequestException("VALIDATION_FAILED", "Voyage values must be nonnegative numbers with at most 3 decimals.")
            VoyageValueInput(v.fieldCode, num.stripTrailingZeros().toPlainString(), docId, (v.sourcePage ?: 1).coerceAtLeast(1))
        }
        if (clean.isEmpty()) throw BusinessRuleException("VOYAGE_EMPTY", "Enter at least one voyage value.")
        return saveDraftVoyage(job.branchId, jobId, jobVesselId, input.notes, clean)
    }

    @Transactional(readOnly=true)
    fun fields(jobId: UUID): List<VoyageFieldDto> {
        access.job(jobId)
        return jdbc.sql("SELECT code,label,unit,required_for_review FROM voyage_field WHERE active=TRUE ORDER BY sort_order")
            .query { rs,_ -> VoyageFieldDto(rs.getString(1),rs.getString(2),rs.getString(3),rs.getBoolean(4)) }.list()
    }

    @Transactional(readOnly=true)
    fun overview(jobId: UUID,jobVesselId: UUID): VoyageOverviewDto {
        val job = access.job(jobId)
        access.vessel(job,jobVesselId)
        val ids = jdbc.sql("SELECT id FROM job_voyage_version WHERE job_vessel_id=:id ORDER BY version_no DESC")
            .param("id",jobVesselId.bytes()).query { rs,_ -> rs.getBytes(1).uuid()!! }.list()
        return VoyageOverviewDto(jobVesselId,ids.map { load(it) })
    }

    @Transactional
    fun create(jobId: UUID,jobVesselId: UUID): VoyageDto {
        val job = access.job(jobId,Permissions.TECH_JOB_WRITE)
        access.vessel(job,jobVesselId)
        val existing = overview(jobId,jobVesselId).versions
        if (existing.any { it.status in setOf("DRAFT","SUBMITTED") })
            throw BusinessRuleException("VOYAGE_VERSION_OPEN","Finish the open voyage version first.")
        val id = UuidV7.generate()
        jdbc.sql("""INSERT INTO job_voyage_version (id,job_vessel_id,version_no,status,created_at,created_by,
            updated_at,updated_by,version) VALUES (:id,:vessel,:number,'DRAFT',:time,:actor,:time,:actor,0)""")
            .param("id",id.bytes()).param("vessel",jobVesselId.bytes())
            .param("number",(existing.maxOfOrNull { it.versionNo } ?: 0)+1)
            .param("time",now()).param("actor",actor().bytes()).update()
        audit.record(AuditEntry(AuditAction.CREATE,"JOB_VOYAGE",id,job.branchId))
        return load(id)
    }

    @Transactional(readOnly=true)
    fun get(jobId: UUID,id: UUID): VoyageDto {
        val job = access.job(jobId)
        val found = load(id)
        access.vessel(job,found.jobVesselId)
        return found
    }

    @Transactional
    fun update(jobId: UUID,id: UUID,expectedVersion: Long,input: VoyageUpdate): VoyageDto {
        val job = access.job(jobId,Permissions.TECH_JOB_WRITE)
        val current = get(jobId,id)
        Versioning.checkCurrent(expectedVersion,current.version)
        if (current.status != "DRAFT") throw BusinessRuleException("INVALID_TRANSITION","Only a draft can be edited.")
        val catalogue = fields(jobId).associateBy { it.code }
        if (input.values.map { it.fieldCode }.toSet().size != input.values.size || input.values.any { it.fieldCode !in catalogue })
            throw BadRequestException("VALIDATION_FAILED","Unknown or repeated voyage field.")
        input.values.forEach { value ->
            val number = value.value.trim().toBigDecimalOrNull()
            if (number == null || number.signum() < 0 || number.scale() > 3 || number.precision()-number.scale() > 9)
                throw BadRequestException("VALIDATION_FAILED","Voyage values must be nonnegative numbers with at most 3 decimals.")
            if (value.sourcePage != null && value.sourcePage < 1)
                throw BadRequestException("VALIDATION_FAILED","Source page must be positive.")
            if (value.sourceDocumentId != null) requireDocument(current.jobVesselId,value.sourceDocumentId)
        }
        jdbc.sql("DELETE FROM job_voyage_value WHERE version_id=:id").param("id",id.bytes()).update()
        input.values.forEach { value ->
            val number = value.value.trim().toBigDecimal()
            jdbc.sql("""INSERT INTO job_voyage_value (id,version_id,field_code,value_text,value_number,
                source_document_id,source_page,verified)
                VALUES (:id,:version,:field,:text,:number,:document,:page,FALSE)""")
                .param("id",UuidV7.generate().bytes()).param("version",id.bytes()).param("field",value.fieldCode)
                .param("text",number.stripTrailingZeros().toPlainString()).param("number",number)
                .param("document",value.sourceDocumentId?.bytes()).param("page",value.sourcePage).update()
        }
        jdbc.sql("UPDATE job_voyage_version SET notes=:notes,updated_at=:time,updated_by=:actor,version=version+1 WHERE id=:id")
            .param("notes",input.notes?.trim()?.take(1000)).param("time",now())
            .param("actor",actor().bytes()).param("id",id.bytes()).update()
        audit.record(AuditEntry(AuditAction.UPDATE,"JOB_VOYAGE",id,job.branchId,
            mapOf("valueCount" to input.values.size)))
        return load(id)
    }

    @Transactional
    fun submit(jobId: UUID,id: UUID,expectedVersion: Long): VoyageDto {
        val job = access.job(jobId,Permissions.TECH_JOB_WRITE)
        val current = get(jobId,id)
        Versioning.checkCurrent(expectedVersion,current.version)
        if (current.status != "DRAFT") throw BusinessRuleException("INVALID_TRANSITION","Only a draft can be submitted.")
        if (current.values.isEmpty()) throw BusinessRuleException("VOYAGE_EMPTY","Enter at least one voyage value.")
        if (current.values.any { it.sourceDocumentId == null || it.sourcePage == null })
            throw BadRequestException("SOURCE_REQUIRED","Every voyage value needs a document and page.")
        jdbc.sql("""UPDATE job_voyage_version SET status='SUBMITTED',submitted_by=:actor,submitted_at=:time,
            returned_reason=NULL,updated_at=:time,updated_by=:actor,version=version+1 WHERE id=:id""")
            .param("actor",actor().bytes()).param("time",now()).param("id",id.bytes()).update()
        audit.record(AuditEntry(AuditAction.STATUS_CHANGE,"JOB_VOYAGE",id,job.branchId,
            mapOf("to" to "SUBMITTED")))
        progress.markReceived(jobId,current.jobVesselId,"VOYAGE_DATA",null,"Voyage version $id submitted with sourced values")
        return load(id)
    }

    @Transactional
    fun verify(jobId: UUID,id: UUID,expectedVersion: Long,input: VoyageVerify): VoyageDto {
        val job = access.job(jobId,Permissions.TECH_JOB_REVIEW)
        val current = get(jobId,id)
        Versioning.checkCurrent(expectedVersion,current.version)
        if (current.status != "SUBMITTED") throw BusinessRuleException("INVALID_TRANSITION","Only submitted values can be verified.")
        if (current.submittedBy == actor())
            throw BusinessRuleException("SELF_VERIFICATION_FORBIDDEN","A different person must verify these values.")
        if (current.values.map { it.fieldCode }.toSet() != input.confirmedFieldCodes.toSet())
            throw BadRequestException("VALIDATION_FAILED","Confirm every value against its source.")
        jdbc.sql("UPDATE job_voyage_version SET status='SUPERSEDED',updated_at=:time,version=version+1 WHERE job_vessel_id=:vessel AND status='VERIFIED'")
            .param("time",now()).param("vessel",current.jobVesselId.bytes()).update()
        jdbc.sql("UPDATE job_voyage_value SET verified=TRUE WHERE version_id=:id").param("id",id.bytes()).update()
        jdbc.sql("""UPDATE job_voyage_version SET status='VERIFIED',verified_by=:actor,verified_at=:time,
            updated_at=:time,updated_by=:actor,version=version+1 WHERE id=:id""")
            .param("actor",actor().bytes()).param("time",now()).param("id",id.bytes()).update()
        audit.record(AuditEntry(AuditAction.STATUS_CHANGE,"JOB_VOYAGE",id,job.branchId,
            mapOf("to" to "VERIFIED")))
        return load(id)
    }

    @Transactional
    fun returnForCorrection(jobId: UUID,id: UUID,expectedVersion: Long,input: VoyageReturn): VoyageDto {
        val job = access.job(jobId,Permissions.TECH_JOB_REVIEW)
        val current = get(jobId,id)
        Versioning.checkCurrent(expectedVersion,current.version)
        if (current.status != "SUBMITTED") throw BusinessRuleException("INVALID_TRANSITION","Only submitted values can be returned.")
        if (input.reason.isBlank()) throw BadRequestException("REASON_REQUIRED","A return reason is required.")
        jdbc.sql("""UPDATE job_voyage_version SET status='DRAFT',returned_reason=:reason,
            updated_at=:time,updated_by=:actor,version=version+1 WHERE id=:id""")
            .param("reason",input.reason.trim().take(500)).param("time",now()).param("actor",actor().bytes())
            .param("id",id.bytes()).update()
        audit.record(AuditEntry(AuditAction.STATUS_CHANGE,"JOB_VOYAGE",id,job.branchId,
            mapOf("to" to "DRAFT","reason" to input.reason)))
        return load(id)
    }

    @Transactional(readOnly=true)
    fun review(jobId: UUID): CompatibilityReviewDto {
        val job = access.job(jobId)
        val warnings = mutableListOf<CompatibilityWarningDto>()
        val fixed = mutableMapOf<String,UUID?>()
        val voyage = mutableMapOf<String,UUID?>()
        job.vessels.forEach { vessel ->
            val fixedId = jdbc.sql("""SELECT id FROM vessel_particulars_version WHERE vessel_id=:vessel
                AND status='VERIFIED' ORDER BY version_no DESC LIMIT 1""")
                .param("vessel",vessel.vesselId.bytes()).query { rs,_ -> rs.getBytes(1).uuid()!! }.optional().orElse(null)
            fixed[vessel.role] = fixedId
            if (fixedId == null) warnings += CompatibilityWarningDto("MISSING_VERIFIED_PARTICULARS",vessel.role,
                "No verified fixed particulars are available.")
            val voyageId = jdbc.sql("""SELECT id FROM job_voyage_version WHERE job_vessel_id=:vessel
                AND status='VERIFIED' ORDER BY version_no DESC LIMIT 1""")
                .param("vessel",vessel.id.bytes()).query { rs,_ -> rs.getBytes(1).uuid()!! }.optional().orElse(null)
            voyage[vessel.role] = voyageId
            if (voyageId == null) warnings += CompatibilityWarningDto("MISSING_VERIFIED_VOYAGE",vessel.role,
                "No verified voyage-condition values are available.")
            if (voyageId != null) {
                val present = jdbc.sql("SELECT field_code FROM job_voyage_value WHERE version_id=:id AND verified=TRUE")
                    .param("id",voyageId.bytes()).query(String::class.java).list().toSet()
                fields(jobId).filter { it.requiredForReview && it.code !in present }.forEach { field ->
                    warnings += CompatibilityWarningDto("MISSING_VOYAGE_FIELD",vessel.role,"Missing verified ${field.label}.")
                }
            }
        }
        val rule = ruleRow("SIMILAR_LOA")
        if (rule.enabled && rule.thresholdPercent != null && rule.comparisonMethod != null && fixed.values.none { it == null }) {
            val lengths = fixed.values.map { id ->
                jdbc.sql("SELECT value_number FROM vessel_particular_value WHERE version_id=:id AND field_code='LOA'")
                    .param("id",id!!.bytes()).query(BigDecimal::class.java).optional().orElse(null)
            }
            if (lengths.all { it != null }) {
                val a = lengths[0]!!; val b = lengths[1]!!
                val denominator = when (rule.comparisonMethod) { "LONGER" -> a.max(b); "SHORTER" -> a.min(b); else -> null }
                if (denominator != null && denominator.signum() > 0) {
                    val difference = a.subtract(b).abs().multiply(BigDecimal(100)).divide(denominator,6,java.math.RoundingMode.HALF_UP)
                    if (difference < rule.thresholdPercent) warnings += CompatibilityWarningDto("SIMILAR_LOA",null,
                        "LOA difference is ${difference.stripTrailingZeros().toPlainString()}%, below the configured ${rule.thresholdPercent}% threshold.")
                }
            }
        }
        return CompatibilityReviewDto(jobId,warnings,fixed,voyage)
    }

    @Transactional(readOnly=true)
    fun rule(code: String): CompatibilityRuleDto {
        policy.requireAny(Permissions.MASTER_TECHNICAL_READ)
        return ruleRow(code)
    }

    private fun ruleRow(code: String): CompatibilityRuleDto {
        return jdbc.sql("SELECT enabled,threshold_percent,comparison_method,version FROM compatibility_rule WHERE code=:code")
            .param("code",code).query { rs,_ -> CompatibilityRuleDto(code,rs.getBoolean(1),rs.getBigDecimal(2),
                rs.getString(3),rs.getLong(4)) }.optional().orElse(null) ?: throw NotFoundException("Compatibility rule")
    }

    @Transactional
    fun updateRule(code: String,expectedVersion: Long,input: CompatibilityRuleInput): CompatibilityRuleDto {
        policy.requireAny(Permissions.MASTER_TECHNICAL_WRITE)
        val current = rule(code)
        Versioning.checkCurrent(expectedVersion,current.version)
        if (input.enabled && (input.thresholdPercent == null || input.thresholdPercent <= BigDecimal.ZERO ||
                input.comparisonMethod !in setOf("LONGER","SHORTER")))
            throw BadRequestException("VALIDATION_FAILED","Set a positive threshold and a confirmed comparison method.")
        jdbc.sql("""UPDATE compatibility_rule SET enabled=:enabled,threshold_percent=:threshold,
            comparison_method=:method,updated_at=:time,updated_by=:actor,version=version+1 WHERE code=:code""")
            .param("enabled",input.enabled).param("threshold",input.thresholdPercent).param("method",input.comparisonMethod)
            .param("time",now()).param("actor",actor().bytes()).param("code",code).update()
        audit.record(AuditEntry(AuditAction.UPDATE,"COMPATIBILITY_RULE",details=mapOf("code" to code,"enabled" to input.enabled,
            "threshold" to input.thresholdPercent,"method" to input.comparisonMethod)))
        return rule(code)
    }

    private fun requireDocument(jobVesselId: UUID,documentId: UUID) {
        val found = jdbc.sql("""SELECT 1 FROM document_version d LEFT JOIN job_vessel jv ON jv.id=:owner
            WHERE d.id=:id AND ((d.owner_type='JOB_VESSEL' AND d.owner_id=:owner) OR (d.owner_type='VESSEL' AND d.owner_id=jv.vessel_id))""")
            .param("id",documentId.bytes()).param("owner",jobVesselId.bytes()).query(Int::class.java).optional().orElse(null)
        if (found == null) throw BadRequestException("WRONG_JOB_VESSEL","Source document must belong to this job vessel.")
    }

    private data class VesselDocRef(val id: UUID, val fileId: UUID, val contentType: String, val fileName: String, val typeCode: String)

    private fun findVesselDocument(jobVesselId: UUID, vesselId: UUID, preferredId: UUID?): VesselDocRef? {
        val docs = jdbc.sql("""SELECT d.id, d.file_id, f.content_type, f.original_name, t.code
            FROM document_version d JOIN stored_file f ON f.id=d.file_id JOIN document_type t ON t.id=d.document_type_id
            WHERE (d.owner_type='JOB_VESSEL' AND d.owner_id=:jv) OR (d.owner_type='VESSEL' AND d.owner_id=:v AND t.code='GA-PLAN')
            ORDER BY d.uploaded_at DESC""")
            .param("jv", jobVesselId.bytes()).param("v", vesselId.bytes())
            .query { rs, _ -> VesselDocRef(rs.getBytes(1).uuid()!!, rs.getBytes(2).uuid()!!, rs.getString(3), rs.getString(4), rs.getString(5)) }
            .list()
        if (preferredId != null) docs.firstOrNull { it.id == preferredId }?.let { return it }
        return docs.firstOrNull { it.typeCode in setOf("Q88", "PARTICULARS") } ?: docs.firstOrNull()
    }

    private fun extractPdfValues(fileId: UUID, contentType: String): Map<String, Pair<BigDecimal, Int>> {
        if (contentType != "application/pdf") return emptyMap()
        val bytes = runCatching { files.readForRendering(fileId)?.second }.getOrNull() ?: return emptyMap()
        val found = mutableMapOf<String, Pair<BigDecimal, Int>>()
        val patterns = mapOf(
            "ARRIVAL_DRAFT_FWD" to Regex("""(?:arrival\s+draft\s+(?:forward|fwd)|arr_draft_fwd)[^\d]{0,30}(\d+(?:\.\d{1,3})?)""", RegexOption.IGNORE_CASE),
            "ARRIVAL_DRAFT_AFT" to Regex("""(?:arrival\s+draft\s+aft|arr_draft_aft)[^\d]{0,30}(\d+(?:\.\d{1,3})?)""", RegexOption.IGNORE_CASE),
            "DEPARTURE_DRAFT_FWD" to Regex("""(?:departure\s+draft\s+(?:forward|fwd)|dep_draft_fwd)[^\d]{0,30}(\d+(?:\.\d{1,3})?)""", RegexOption.IGNORE_CASE),
            "DEPARTURE_DRAFT_AFT" to Regex("""(?:departure\s+draft\s+aft|dep_draft_aft)[^\d]{0,30}(\d+(?:\.\d{1,3})?)""", RegexOption.IGNORE_CASE),
            "ARRIVAL_FREEBOARD_FWD" to Regex("""(?:arrival\s+freeboard\s+(?:forward|fwd))[^\d]{0,30}(\d+(?:\.\d{1,3})?)""", RegexOption.IGNORE_CASE),
            "ARRIVAL_FREEBOARD_AFT" to Regex("""(?:arrival\s+freeboard\s+aft)[^\d]{0,30}(\d+(?:\.\d{1,3})?)""", RegexOption.IGNORE_CASE),
            "DEPARTURE_FREEBOARD_FWD" to Regex("""(?:departure\s+freeboard\s+(?:forward|fwd))[^\d]{0,30}(\d+(?:\.\d{1,3})?)""", RegexOption.IGNORE_CASE),
            "DEPARTURE_FREEBOARD_AFT" to Regex("""(?:departure\s+freeboard\s+aft)[^\d]{0,30}(\d+(?:\.\d{1,3})?)""", RegexOption.IGNORE_CASE),
            "ARRIVAL_DISPLACEMENT" to Regex("""(?:arrival\s+displacement)[^\d]{0,30}(\d+(?:\.\d{1,3})?)""", RegexOption.IGNORE_CASE),
            "DEPARTURE_DISPLACEMENT" to Regex("""(?:departure\s+displacement)[^\d]{0,30}(\d+(?:\.\d{1,3})?)""", RegexOption.IGNORE_CASE),
            "ARRIVAL_PARALLEL_BODY_LENGTH" to Regex("""(?:arrival\s+parallel\s+body(?:\s+length)?)[^\d]{0,30}(\d+(?:\.\d{1,3})?)""", RegexOption.IGNORE_CASE),
            "DEPARTURE_PARALLEL_BODY_LENGTH" to Regex("""(?:departure\s+parallel\s+body(?:\s+length)?)[^\d]{0,30}(\d+(?:\.\d{1,3})?)""", RegexOption.IGNORE_CASE),
            "ARRIVAL_MANIFOLD_HEIGHT" to Regex("""(?:arrival\s+manifold\s+height)[^\d]{0,30}(\d+(?:\.\d{1,3})?)""", RegexOption.IGNORE_CASE),
            "DEPARTURE_MANIFOLD_HEIGHT" to Regex("""(?:departure\s+manifold\s+height)[^\d]{0,30}(\d+(?:\.\d{1,3})?)""", RegexOption.IGNORE_CASE),
            "SUMMER_DRAFT" to Regex("""(?:summer\s+dr(?:aft|aught)|laden\s+draft)[^\d]{0,30}(\d+(?:\.\d{1,3})?)""", RegexOption.IGNORE_CASE),
            "BALLAST_DRAFT" to Regex("""(?:ballast\s+dr(?:aft|aught))[^\d]{0,30}(\d+(?:\.\d{1,3})?)""", RegexOption.IGNORE_CASE),
            "FREEBOARD" to Regex("""(?:summer\s+freeboard|normal\s+ballast\s+freeboard|freeboard)[^\d]{0,30}(\d+(?:\.\d{1,3})?)""", RegexOption.IGNORE_CASE),
            "PARALLEL_BODY" to Regex("""(?:parallel\s+body(?:\s+length)?)[^\d]{0,30}(\d+(?:\.\d{1,3})?)""", RegexOption.IGNORE_CASE),
            "MANIFOLD_HEIGHT" to Regex("""(?:manifold\s+height|height\s+of\s+manifold)[^\d]{0,30}(\d+(?:\.\d{1,3})?)""", RegexOption.IGNORE_CASE),
            "LOA" to Regex("""(?:length\s+overall|l\.?o\.?a\.?)[^\d]{0,30}(\d{2,3}(?:\.\d{1,3})?)""", RegexOption.IGNORE_CASE),
            "BEAM" to Regex("""(?:extreme\s+breadth|breadth|beam)[^\d]{0,30}(\d{2}(?:\.\d{1,3})?)""", RegexOption.IGNORE_CASE),
            "DWT" to Regex("""(?:summer\s+deadweight|deadweight|d\.?w\.?t\.?)[^\d]{0,30}(\d{3,7}(?:\.\d{1,3})?)""", RegexOption.IGNORE_CASE),
        )
        runCatching {
            Loader.loadPDF(bytes).use { pdf ->
                val stripper = PDFTextStripper()
                for (page in 1..pdf.numberOfPages) {
                    stripper.startPage = page
                    stripper.endPage = page
                    val text = stripper.getText(pdf)
                    patterns.forEach { (key, regex) ->
                        if (key !in found) {
                            regex.find(text)?.groupValues?.getOrNull(1)?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 && it.scale() <= 3 }?.let { num ->
                                found[key] = num to page
                            }
                        }
                    }
                }
            }
        }
        return found
    }

    private fun saveDraftVoyage(branchId: UUID, jobId: UUID, jobVesselId: UUID, notes: String?, values: List<VoyageValueInput>): VoyageDto {
        val existing = overview(jobId, jobVesselId).versions
        val versionId = existing.firstOrNull { it.status == "DRAFT" }?.id
            ?: existing.firstOrNull()?.id
            ?: UuidV7.generate().also { newId ->
                jdbc.sql("""INSERT INTO job_voyage_version (id,job_vessel_id,version_no,status,notes,
                    created_at,created_by,updated_at,updated_by,version)
                    VALUES (:id,:vessel,1,'DRAFT',:notes,:time,:actor,:time,:actor,0)""")
                    .param("id", newId.bytes()).param("vessel", jobVesselId.bytes()).param("notes", notes?.take(1000))
                    .param("actor", actor().bytes()).param("time", now()).update()
            }
        if (existing.isNotEmpty()) {
            jdbc.sql("""UPDATE job_voyage_version SET status='DRAFT',notes=:notes,submitted_by=NULL,submitted_at=NULL,
                verified_by=NULL,verified_at=NULL,returned_reason=NULL,updated_at=:time,updated_by=:actor,version=version+1 WHERE id=:id""")
                .param("notes", notes?.take(1000)).param("actor", actor().bytes()).param("time", now()).param("id", versionId.bytes()).update()
        }
        jdbc.sql("DELETE FROM job_voyage_value WHERE version_id=:id").param("id", versionId.bytes()).update()
        values.forEach { v ->
            val number = v.value.trim().toBigDecimal()
            jdbc.sql("""INSERT INTO job_voyage_value (id,version_id,field_code,value_text,value_number,source_document_id,source_page,verified)
                VALUES (:id,:version,:field,:text,:number,:document,:page,FALSE)""")
                .param("id", UuidV7.generate().bytes()).param("version", versionId.bytes()).param("field", v.fieldCode)
                .param("text", number.stripTrailingZeros().toPlainString()).param("number", number)
                .param("document", v.sourceDocumentId?.bytes()).param("page", v.sourcePage ?: 1).update()
        }
        if (values.isNotEmpty()) {
            progress.markReceived(jobId, jobVesselId, "VOYAGE_DATA", null, "Voyage values extracted from document")
        }
        audit.record(AuditEntry(AuditAction.UPDATE, "JOB_VOYAGE", versionId, branchId, mapOf("valueCount" to values.size, "status" to "DRAFT")))
        return load(versionId)
    }

    private fun load(id: UUID): VoyageDto {
        val head = jdbc.sql("""SELECT job_vessel_id,version_no,status,notes,submitted_by,submitted_at,
            verified_by,verified_at,returned_reason,version FROM job_voyage_version WHERE id=:id""")
            .param("id",id.bytes()).query { rs,_ -> VoyageHead(rs.getBytes(1).uuid()!!,rs.getInt(2),rs.getString(3),
                rs.getString(4),rs.getBytes(5).uuid(),rs.getObject(6,LocalDateTime::class.java)?.let(Uuids::fromTimestamp),
                rs.getBytes(7).uuid(),rs.getObject(8,LocalDateTime::class.java)?.let(Uuids::fromTimestamp),
                rs.getString(9),rs.getLong(10)) }.optional().orElse(null) ?: throw NotFoundException("Voyage version")
        val values = jdbc.sql("""SELECT field_code,value_text,source_document_id,source_page,verified
            FROM job_voyage_value WHERE version_id=:id ORDER BY field_code""").param("id",id.bytes())
            .query { rs,_ -> VoyageValueDto(rs.getString(1),rs.getString(2),rs.getBytes(3).uuid(),
                rs.getInt(4).takeUnless { rs.wasNull() },rs.getBoolean(5)) }.list()
        return VoyageDto(id,head.jobVesselId,head.versionNo,head.status,head.notes,values,head.submittedBy,
            head.submittedAt,head.verifiedBy,head.verifiedAt,head.returnedReason,head.version)
    }
    private data class VoyageHead(val jobVesselId: UUID,val versionNo: Int,val status: String,val notes: String?,
        val submittedBy: UUID?,val submittedAt: Instant?,val verifiedBy: UUID?,val verifiedAt: Instant?,
        val returnedReason: String?,val version: Long)
}
