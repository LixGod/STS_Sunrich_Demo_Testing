package com.ship2ship.features.technical

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.files.FileAccess
import com.ship2ship.core.files.FileService
import com.ship2ship.core.files.ScanState
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.security.ReferenceLookup
import com.ship2ship.core.security.UserDirectory
import com.ship2ship.core.vessels.VesselMasterUpdates
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.FieldError
import com.ship2ship.core.web.NotFoundException
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Isolation
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** A document attached to a record (vessels in 3a). Each upload of the same type is a new version; nothing is overwritten. */
@Entity
@Table(name = "document_version")
class DocumentVersionEntity(
    @Column(nullable = false, updatable = false) var ownerType: String,
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)") var ownerId: UUID,
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)") var documentTypeId: UUID,
    @Column(nullable = false, updatable = false) var versionNo: Int,
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)") var fileId: UUID,
    @Column(updatable = false) var title: String?,
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)") var uploadedBy: UUID,
    @Column(nullable = false, updatable = false) var uploadedAt: Instant,
) {
    @Id
    @Column(columnDefinition = "BINARY(16)")
    var id: UUID = UuidV7.generate()

    @Column(nullable = false)
    var issued: Boolean = false
}

interface DocumentVersionRepository : JpaRepository<DocumentVersionEntity, UUID> {
    fun findByOwnerTypeAndOwnerIdOrderByUploadedAtDesc(ownerType: String, ownerId: UUID): List<DocumentVersionEntity>

    @Query("select coalesce(max(d.versionNo), 0) from DocumentVersionEntity d where d.ownerType = :ownerType and d.ownerId = :ownerId and d.documentTypeId = :typeId")
    fun maxVersion(ownerType: String, ownerId: UUID, typeId: UUID): Int
}

data class FileInfoDto(val id: UUID, val name: String, val contentType: String, val sizeBytes: Long, val sha256: String, val scanState: ScanState)

data class VesselDocumentDto(
    val id: UUID,
    val documentTypeId: UUID,
    val documentTypeCode: String?,
    val documentTypeName: String?,
    val versionNo: Int,
    /** True for the newest version of its type. */
    val latest: Boolean,
    val title: String?,
    val file: FileInfoDto?,
    val uploadedByName: String?,
    val uploadedAt: Instant,
)

/** Vessel documents (FR-J04, FR-DOC-01): the source documents particulars are read from (Q88, GA plan, ...). */
@Service
class VesselDocumentService(
    private val documents: DocumentVersionRepository,
    private val files: FileService,
    private val vessels: VesselMasterUpdates,
    private val references: ReferenceLookup,
    private val users: UserDirectory,
    private val accessPolicy: AccessPolicy,
    private val audit: AuditService,
    private val clock: Clock,
    private val jdbc: JdbcClient,
) {

    @Transactional(readOnly = true)
    fun list(vesselId: UUID): List<VesselDocumentDto> {
        accessPolicy.requireAny(Permissions.DOCUMENT_READ)
        if (!vessels.exists(vesselId)) throw NotFoundException("Vessel")
        return toDtos(documents.findByOwnerTypeAndOwnerIdOrderByUploadedAtDesc(OWNER, vesselId))
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun upload(vesselId: UUID, documentTypeId: UUID, title: String?, file: MultipartFile): VesselDocumentDto {
        accessPolicy.requireAny(Permissions.DOCUMENT_WRITE)
        jdbc.sql("SELECT id FROM vessel WHERE id=:id FOR UPDATE").param("id", Uuids.toBytes(vesselId))
            .query { rs, _ -> rs.getBytes(1) }.optional().orElseThrow { NotFoundException("Vessel") }
        val type = references.documentTypes(listOf(documentTypeId))[documentTypeId]
        if (type == null || !type.active || type.appliesTo != OWNER) {
            throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.",
                listOf(FieldError("documentTypeId", "unknown", "Choose an active vessel document type.")))
        }
        if (type.code == "GA-PLAN") accessPolicy.requireAny(Permissions.MASTER_COMMERCIAL_WRITE)
        val cleanTitle = title?.trim()?.take(200)?.ifBlank { null }
        // Vessel records are group-wide, so is reading their documents.
        val stored = files.store(file, FileAccess(Permissions.DOCUMENT_READ))
        val document = documents.saveAndFlush(DocumentVersionEntity(
            OWNER, vesselId, documentTypeId, documents.maxVersion(OWNER, vesselId, documentTypeId) + 1, stored.id, cleanTitle,
            accessPolicy.currentUser().id, clock.instant().truncatedTo(ChronoUnit.MICROS),
        ))
        audit.record(AuditEntry(AuditAction.CREATE, ENTITY, document.id, details = mapOf(
            "vesselId" to vesselId, "documentType" to type.code, "versionNo" to document.versionNo, "fileId" to stored.id,
            "sha256" to stored.sha256, "title" to cleanTitle,
        )))
        return toDtos(listOf(document), latestIds = setOf(document.id)).single()
    }

    /** Documents of the vessel by id, to check that a value's source belongs to the same vessel. */
    @Transactional(readOnly = true)
    fun ofVessel(vesselId: UUID): Map<UUID, DocumentVersionEntity> =
        documents.findByOwnerTypeAndOwnerIdOrderByUploadedAtDesc(OWNER, vesselId).associateBy { it.id }

    private fun toDtos(list: List<DocumentVersionEntity>, latestIds: Set<UUID>? = null): List<VesselDocumentDto> {
        val types = references.documentTypes(list.map { it.documentTypeId })
        val fileRefs = files.refs(list.map { it.fileId })
        val names = users.names(list.map { it.uploadedBy })
        val latest = latestIds ?: list.groupBy { it.documentTypeId }.values.map { group -> group.maxBy { it.versionNo }.id }.toSet()
        return list.map { d ->
            val f = fileRefs[d.fileId]
            VesselDocumentDto(
                d.id, d.documentTypeId, types[d.documentTypeId]?.code, types[d.documentTypeId]?.name, d.versionNo, d.id in latest, d.title,
                f?.let { FileInfoDto(it.id, it.originalName, it.contentType, it.sizeBytes, it.sha256, it.scanState) },
                names[d.uploadedBy], d.uploadedAt,
            )
        }
    }

    private companion object {
        const val OWNER = "VESSEL"
        const val ENTITY = "DOCUMENT"
    }
}

@RestController
@RequestMapping("/api/v1/technical/vessels/{vesselId}/documents")
class VesselDocumentController(private val service: VesselDocumentService) {

    @GetMapping
    fun list(@PathVariable vesselId: UUID): List<VesselDocumentDto> = service.list(vesselId)

    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @PathVariable vesselId: UUID,
        @RequestParam documentTypeId: UUID,
        @RequestParam(required = false) title: String?,
        @RequestPart("file") file: MultipartFile,
    ): ResponseEntity<VesselDocumentDto> = ResponseEntity.status(201).body(service.upload(vesselId, documentTypeId, title, file))
}
