package com.ship2ship.features.technical

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.security.UserDirectory
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.BusinessRuleException
import com.ship2ship.core.web.FieldError
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.Versioning
import org.springframework.http.HttpHeaders
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
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

data class AnnexSectionInput(val code: String = "", val title: String = "", val body: String = "", val appliesWhen: String = "ALWAYS")

data class AnnexTemplateInput(val name: String = "", val sections: List<AnnexSectionInput> = emptyList())

data class AnnexPublishRequest(val ownerConfirmation: String = "")

data class AnnexTemplateDto(
    val id: UUID, val versionNo: Int, val status: String, val name: String, val sections: List<AnnexSectionDto>,
    val ownerConfirmation: String?, val publishedByName: String?, val publishedAt: Instant?, val updatedByName: String?,
    val updatedAt: Instant?, val version: Long,
)

/**
 * JPO annex wording as versioned, approved template text (D13). Only one version is PUBLISHED at a time; each JPO
 * revision freezes the version it was submitted with. Publication needs the owner's confirmation and a second person
 * (not the last editor), and every section must have wording.
 */
@Service
class AnnexTemplateService(
    private val jdbc: JdbcClient,
    private val policy: AccessPolicy,
    private val users: UserDirectory,
    private val audit: AuditService,
    private val clock: Clock,
) {
    private fun now() = Uuids.toTimestamp(clock.instant().truncatedTo(ChronoUnit.MICROS))

    @Transactional(readOnly = true)
    fun list(): List<AnnexTemplateDto> {
        policy.requireAny(Permissions.MASTER_TECHNICAL_READ)
        return jdbc.sql("SELECT id FROM annex_template ORDER BY version_no DESC").query { rs, _ -> rs.getBytes(1).uuid()!! }.list().map(::load)
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): AnnexTemplateDto {
        policy.requireAny(Permissions.MASTER_TECHNICAL_READ)
        return load(id)
    }

    /** A new draft version, copied from the newest version. */
    @Transactional
    fun createDraft(): AnnexTemplateDto {
        policy.requireAny(Permissions.MASTER_TECHNICAL_WRITE)
        if (jdbc.sql("SELECT COUNT(*) FROM annex_template WHERE status='DRAFT'").query(Long::class.java).single() > 0) {
            throw BusinessRuleException("ANNEX_DRAFT_OPEN", "Finish or publish the open annex draft first.")
        }
        val latest = jdbc.sql("SELECT id FROM annex_template ORDER BY version_no DESC LIMIT 1")
            .query { rs, _ -> rs.getBytes(1).uuid()!! }.optional().orElse(null)?.let(::load)
        val id = UuidV7.generate()
        val actor = policy.currentUser().id
        jdbc.sql("""INSERT INTO annex_template (id,version_no,status,name,created_at,created_by,updated_at,updated_by,version)
            VALUES (:id,(SELECT n FROM (SELECT COALESCE(MAX(version_no),0)+1 n FROM annex_template) t),'DRAFT',:name,:time,:actor,:time,:actor,0)""")
            .param("id", id.bytes()).param("name", latest?.name ?: "JPO annex").param("time", now()).param("actor", actor.bytes()).update()
        latest?.sections?.forEachIndexed { i, s -> insertSection(id, i, AnnexSectionInput(s.code, s.title, s.body, s.appliesWhen)) }
        audit.record(AuditEntry(AuditAction.CREATE, ENTITY, id, details = mapOf("copiedFrom" to latest?.versionNo)))
        return load(id)
    }

    @Transactional
    fun update(id: UUID, expectedVersion: Long, input: AnnexTemplateInput): AnnexTemplateDto {
        policy.requireAny(Permissions.MASTER_TECHNICAL_WRITE)
        val current = load(id)
        Versioning.checkCurrent(expectedVersion, current.version)
        if (current.status != "DRAFT") throw BusinessRuleException("INVALID_TRANSITION", "Only a draft annex can be edited; create a new version.")
        validate(input)
        jdbc.sql("DELETE FROM annex_template_section WHERE template_id=:id").param("id", id.bytes()).update()
        input.sections.forEachIndexed { i, section -> insertSection(id, i, section) }
        jdbc.sql("UPDATE annex_template SET name=:name,updated_at=:time,updated_by=:actor,version=version+1 WHERE id=:id")
            .param("name", input.name.trim()).param("time", now()).param("actor", policy.currentUser().id.bytes()).param("id", id.bytes()).update()
        audit.record(AuditEntry(AuditAction.UPDATE, ENTITY, id, details = mapOf(
            "sections" to input.sections.map { "${it.code}:${it.appliesWhen}:${it.body.length} chars" })))
        return load(id)
    }

    @Transactional
    fun publish(id: UUID, expectedVersion: Long, request: AnnexPublishRequest): AnnexTemplateDto {
        policy.requireAny(Permissions.MASTER_TECHNICAL_WRITE)
        val current = load(id)
        Versioning.checkCurrent(expectedVersion, current.version)
        if (current.status != "DRAFT") throw BusinessRuleException("INVALID_TRANSITION", "Only a draft annex can be published.")
        val actor = policy.currentUser().id
        val empty = current.sections.filter { it.body.isBlank() }
        if (current.sections.isEmpty() || empty.isNotEmpty()) {
            throw BadRequestException("ANNEX_WORDING_MISSING", "Enter the approved wording of every section before publishing.",
                empty.map { FieldError("sections.${it.code}", "required", null) })
        }
        val lastEditor = jdbc.sql("SELECT updated_by FROM annex_template WHERE id=:id").param("id", id.bytes())
            .query { rs, _ -> rs.getBytes(1).uuid() }.list().firstOrNull()
        if (lastEditor == actor) {
            throw BusinessRuleException("SECOND_PERSON_REQUIRED", "The annex must be published by someone other than its last editor.")
        }
        if (request.ownerConfirmation.isBlank()) {
            throw BadRequestException("REASON_REQUIRED", "Record who confirmed the wording for Ship2Ship.", listOf(FieldError("ownerConfirmation", "required", null)))
        }
        jdbc.sql("UPDATE annex_template SET status='RETIRED',version=version+1 WHERE status='PUBLISHED'").update()
        jdbc.sql("""UPDATE annex_template SET status='PUBLISHED',owner_confirmation=:confirmation,published_by=:actor,
            published_at=:time,version=version+1 WHERE id=:id""")
            .param("confirmation", request.ownerConfirmation.trim().take(500)).param("actor", actor.bytes()).param("time", now())
            .param("id", id.bytes()).update()
        audit.record(AuditEntry(AuditAction.STATUS_CHANGE, ENTITY, id, details = mapOf("to" to "PUBLISHED",
            "versionNo" to current.versionNo, "ownerConfirmation" to request.ownerConfirmation.trim())))
        return load(id)
    }

    /** The published annex, for a JPO being submitted; null while none is published. */
    @Transactional(readOnly = true)
    fun published(): AnnexTemplateDto? =
        jdbc.sql("SELECT id FROM annex_template WHERE status='PUBLISHED' ORDER BY version_no DESC LIMIT 1")
            .query { rs, _ -> rs.getBytes(1).uuid()!! }.optional().orElse(null)?.let(::load)

    /** Wording already entered for the JPO; no separate publication step is needed. */
    @Transactional(readOnly = true)
    fun currentForJpo(): AnnexTemplateDto? =
        jdbc.sql("SELECT id FROM annex_template ORDER BY version_no DESC LIMIT 1")
            .query { rs, _ -> rs.getBytes(1).uuid()!! }.optional().orElse(null)?.let(::load)

    /** Any version by id, for a JPO revision that froze it (no permission check: callers check JPO access). */
    @Transactional(readOnly = true)
    fun version(id: UUID): AnnexTemplateDto = load(id)

    private fun validate(input: AnnexTemplateInput) {
        val errors = mutableListOf<FieldError>()
        if (input.name.isBlank() || input.name.length > 150) errors += FieldError("name", "required", "Name the annex (max 150 characters).")
        if (input.sections.isEmpty() || input.sections.size > 50) errors += FieldError("sections", "size", "Between 1 and 50 sections.")
        val codes = input.sections.map { it.code.trim().uppercase() }
        if (codes.toSet().size != codes.size) errors += FieldError("sections", "duplicate", "Section codes must be unique.")
        input.sections.forEachIndexed { i, s ->
            if (!Regex("^[A-Za-z0-9][A-Za-z0-9-]{0,39}$").matches(s.code.trim())) errors += FieldError("sections[$i].code", "pattern", null)
            if (s.title.isBlank() || s.title.length > 200) errors += FieldError("sections[$i].title", "required", null)
            if (s.body.length > 100_000) errors += FieldError("sections[$i].body", "size", null)
            if (s.appliesWhen !in CONDITIONS) errors += FieldError("sections[$i].appliesWhen", "allowed", null)
        }
        if (errors.isNotEmpty()) throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", errors)
    }

    private fun insertSection(templateId: UUID, index: Int, s: AnnexSectionInput) {
        jdbc.sql("""INSERT INTO annex_template_section (id,template_id,sort_order,code,title,body,applies_when)
            VALUES (:id,:template,:sort,:code,:title,:body,:when)""")
            .param("id", UuidV7.generate().bytes()).param("template", templateId.bytes()).param("sort", (index + 1) * 10)
            .param("code", s.code.trim().uppercase()).param("title", s.title.trim()).param("body", s.body.trim())
            .param("when", s.appliesWhen).update()
    }

    private fun load(id: UUID): AnnexTemplateDto {
        data class Head(val versionNo: Int, val status: String, val name: String, val confirmation: String?, val publishedBy: UUID?,
            val publishedAt: Instant?, val updatedBy: UUID?, val updatedAt: Instant?, val version: Long)
        val head = jdbc.sql("""SELECT version_no,status,name,owner_confirmation,published_by,published_at,updated_by,updated_at,version
            FROM annex_template WHERE id=:id""").param("id", id.bytes()).query { rs, _ ->
            Head(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBytes(5).uuid(),
                rs.getObject(6, LocalDateTime::class.java)?.let(Uuids::fromTimestamp), rs.getBytes(7).uuid(),
                rs.getObject(8, LocalDateTime::class.java)?.let(Uuids::fromTimestamp), rs.getLong(9))
        }.optional().orElse(null) ?: throw NotFoundException("Annex template")
        val sections = jdbc.sql("SELECT code,title,body,applies_when FROM annex_template_section WHERE template_id=:id ORDER BY sort_order")
            .param("id", id.bytes()).query { rs, _ -> AnnexSectionDto(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) }.list()
        val names = users.names(listOfNotNull(head.publishedBy, head.updatedBy))
        return AnnexTemplateDto(id, head.versionNo, head.status, head.name, sections, head.confirmation,
            head.publishedBy?.let(names::get), head.publishedAt, head.updatedBy?.let(names::get), head.updatedAt, head.version)
    }

    companion object {
        private const val ENTITY = "ANNEX_TEMPLATE"
        val CONDITIONS = setOf("ALWAYS", "REVERSE_LIGHTERING", "SIMILAR_LOA")
    }
}

@RestController
@RequestMapping("/api/v1/technical/annex-templates")
class AnnexTemplateController(private val service: AnnexTemplateService) {

    @GetMapping
    fun list(): List<AnnexTemplateDto> = service.list()

    @PostMapping
    fun create(): ResponseEntity<AnnexTemplateDto> = service.createDraft().let { ResponseEntity.status(201).eTag(Versioning.etag(it.version)).body(it) }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): ResponseEntity<AnnexTemplateDto> = service.get(id).let { Versioning.ok(it, it.version) }

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?, @RequestBody input: AnnexTemplateInput,
    ): ResponseEntity<AnnexTemplateDto> = service.update(id, Versioning.requireIfMatch(ifMatch), input).let { Versioning.ok(it, it.version) }

    @PostMapping("/{id}/publish")
    fun publish(
        @PathVariable id: UUID, @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?, @RequestBody request: AnnexPublishRequest,
    ): ResponseEntity<AnnexTemplateDto> = service.publish(id, Versioning.requireIfMatch(ifMatch), request).let { Versioning.ok(it, it.version) }
}
