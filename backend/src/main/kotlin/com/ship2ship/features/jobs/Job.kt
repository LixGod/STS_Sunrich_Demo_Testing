package com.ship2ship.features.jobs

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.jobs.JobRef
import com.ship2ship.core.jobs.CommercialJobUpdate
import com.ship2ship.core.jobs.JobRegistry
import com.ship2ship.core.jobs.JobLookup
import com.ship2ship.core.jobs.JobTechnicalRef
import com.ship2ship.core.jobs.JobTechnicalVesselRef
import com.ship2ship.core.jobs.NewJob
import com.ship2ship.core.jobs.NewJobCargo
import com.ship2ship.core.persistence.BaseEntity
import com.ship2ship.core.persistence.NumberContext
import com.ship2ship.core.persistence.NumberSeriesService
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.AccessScope
import com.ship2ship.core.security.OrganisationLookup
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.security.ReferenceLookup
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.FieldError
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.PageResponse
import com.ship2ship.core.web.Paging
import com.ship2ship.core.web.Versioning
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import jakarta.persistence.criteria.Predicate
import org.hibernate.annotations.BatchSize
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import com.ship2ship.core.web.BusinessRuleException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Job lifecycle from SRS §6; all changes go through JobWorkflowService. */
enum class JobStatus {
    OPEN, DOCUMENTS_PENDING, PLANNING, APPROVED_FOR_READINESS, MOBILISING, IN_PROGRESS,
    DEMOBILISING, REPORT_PENDING, FINANCIAL_CLOSING, CLOSED, ON_HOLD, SUSPENDED, CANCELLED,
}

@Entity
@Table(name = "sts_job")
class JobEntity(
    @Column(nullable = false, updatable = false) var jobNumber: String,
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)") var branchId: UUID,
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)") var enquiryId: UUID,
    @Column(nullable = false, updatable = false) var enquiryReference: String,
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)") var quotationVersionId: UUID,
    @Column(nullable = false, updatable = false) var quotationReference: String,
    @Column(nullable = false, columnDefinition = "BINARY(16)") var clientId: UUID,
    @Column(columnDefinition = "BINARY(16)") var clientContactId: UUID?,
    var operationType: String?,
    @Column(nullable = false, updatable = false, columnDefinition = "CHAR(3)") var currency: String,
    @Column(nullable = false, precision = 19, scale = 4) var quotedAmount: BigDecimal,
) : BaseEntity() {

    @Column(nullable = false)
    var status: String = JobStatus.OPEN.name

    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true)
    @JoinColumn(name = "job_id", nullable = false, updatable = false)
    @BatchSize(size = 100)
    var vessels: MutableList<JobVesselEntity> = mutableListOf()

    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true)
    @JoinColumn(name = "job_id", nullable = false, updatable = false)
    @OrderBy("lineNo")
    @BatchSize(size = 100)
    var cargo: MutableList<JobCargoEntity> = mutableListOf()
}

@Entity
@Table(name = "job_vessel")
class JobVesselEntity(
    @Column(nullable = false, updatable = false) var role: String,
    @Column(nullable = false, columnDefinition = "BINARY(16)") var vesselId: UUID,
    var etaUtc: Instant?,
    var etaTimezone: String?,
) {
    @Id
    @Column(columnDefinition = "BINARY(16)")
    var id: UUID = UuidV7.generate()
}

@Entity
@Table(name = "job_cargo")
class JobCargoEntity(
    @Column(nullable = false) var lineNo: Int,
    @Column(nullable = false, columnDefinition = "BINARY(16)") var cargoGradeId: UUID,
    @Column(nullable = false, precision = 18, scale = 3) var quantity: BigDecimal,
    @Column(nullable = false, columnDefinition = "BINARY(16)") var unitId: UUID,
) {
    @Id
    @Column(columnDefinition = "BINARY(16)")
    var id: UUID = UuidV7.generate()
}

/** The accepted tariff terms, revised only through an audited Commercial edit before final invoice issue. */
@Entity
@Table(name = "tariff_snapshot")
class TariffSnapshotEntity(
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)") var jobId: UUID,
    @Column(nullable = false, columnDefinition = "BINARY(16)") var tariffId: UUID,
    @Column(nullable = false) var tariffCode: String,
    @Column(nullable = false) var tariffName: String,
    @Column(nullable = false, columnDefinition = "CHAR(3)") var currency: String,
    @Column(nullable = false) var baseHours: Int,
    @Column(nullable = false, precision = 19, scale = 4) var baseCharge: BigDecimal,
    @Column(nullable = false, precision = 19, scale = 4) var extraHourRate: BigDecimal,
    var billingIncrementMinutes: Int?,
    var graceMinutes: Int?,
    var roundingRule: String?,
    @Column(nullable = false) var effectiveFrom: LocalDate,
    var effectiveTo: LocalDate?,
    @Column(nullable = false, updatable = false) var capturedAt: Instant,
) {
    @Id
    @Column(columnDefinition = "BINARY(16)")
    var id: UUID = UuidV7.generate()
}

interface JobRepository : JpaRepository<JobEntity, UUID>, JpaSpecificationExecutor<JobEntity> {
    fun findByEnquiryIdIn(enquiryIds: Collection<UUID>): List<JobEntity>
    fun existsByJobNumberIgnoreCase(jobNumber: String): Boolean
}

interface TariffSnapshotRepository : JpaRepository<TariffSnapshotEntity, UUID> {
    fun findByJobId(jobId: UUID): TariffSnapshotEntity?
}

@Service
class JobLookupImpl(private val jobs: JobRepository, private val jdbc: JdbcClient) : JobLookup {
    @Transactional(readOnly = true)
    override fun technical(id: UUID): JobTechnicalRef? = jobs.findByIdOrNull(id)?.let { job ->
        JobTechnicalRef(job.id, job.jobNumber, job.branchId,
            job.vessels.map { JobTechnicalVesselRef(it.id, it.role, it.vesselId, it.etaUtc, it.etaTimezone) },
            job.operationType, job.cargo.sortedBy { it.lineNo }.map { NewJobCargo(it.lineNo, it.cargoGradeId, it.quantity, it.unitId) })
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun requireWritable(id: UUID) {
        val status = jdbc.sql("SELECT status FROM sts_job WHERE id=:id FOR UPDATE").param("id", Uuids.toBytes(id))
            .query(String::class.java).optional().orElseThrow { NotFoundException("Job") }
        if (status in setOf("CLOSED", "CANCELLED")) throw BusinessRuleException("JOB_LOCKED", "Reopen the job before changing its records.")
    }
}

// ---------- creation (called by commercial through core.jobs.JobRegistry) ----------

@Service
class JobRegistryImpl(
    private val jobs: JobRepository,
    private val snapshots: TariffSnapshotRepository,
    private val numbers: NumberSeriesService,
    private val audit: AuditService,
    private val clock: Clock,
    private val history: com.ship2ship.core.persistence.StatusHistory,
    private val access: AccessPolicy,
) : JobRegistry {

    @Transactional(propagation = Propagation.MANDATORY)
    override fun syncCommercial(jobId: UUID, update: CommercialJobUpdate) {
        val job = jobs.findByIdOrNull(jobId) ?: throw NotFoundException("Job")
        val before = mapOf("clientId" to job.clientId, "quotedAmount" to job.quotedAmount,
            "vessels" to job.vessels.map { it.role to it.vesselId }, "cargo" to job.cargo.map { it.cargoGradeId to it.quantity })
        job.clientId = update.clientId
        job.clientContactId = update.clientContactId
        job.operationType = update.operationType
        job.quotedAmount = update.quotedAmount
        job.vessels.removeIf { existing -> update.vessels.none { it.role == existing.role } }
        update.vessels.forEach { vessel ->
            val existing = job.vessels.firstOrNull { it.role == vessel.role }
            if (existing == null) job.vessels += JobVesselEntity(vessel.role, vessel.vesselId, vessel.etaUtc, vessel.etaTimezone)
            else { existing.vesselId = vessel.vesselId; existing.etaUtc = vessel.etaUtc; existing.etaTimezone = vessel.etaTimezone }
        }
        job.cargo.removeIf { existing -> update.cargo.none { it.lineNo == existing.lineNo } }
        update.cargo.forEach { cargo ->
            val existing = job.cargo.firstOrNull { it.lineNo == cargo.lineNo }
            if (existing == null) job.cargo += JobCargoEntity(cargo.lineNo, cargo.cargoGradeId, cargo.quantity, cargo.unitId)
            else { existing.cargoGradeId = cargo.cargoGradeId; existing.quantity = cargo.quantity; existing.unitId = cargo.unitId }
        }
        jobs.saveAndFlush(job)
        update.tariff?.let { tariff ->
            val snapshot = snapshots.findByJobId(jobId) ?: throw NotFoundException("Tariff snapshot")
            snapshot.tariffId = tariff.id
            snapshot.tariffCode = tariff.code
            snapshot.tariffName = tariff.name
            snapshot.currency = tariff.currency
            snapshot.baseHours = tariff.baseHours
            snapshot.baseCharge = tariff.baseCharge
            snapshot.extraHourRate = tariff.extraHourRate
            snapshot.billingIncrementMinutes = tariff.billingIncrementMinutes
            snapshot.graceMinutes = tariff.graceMinutes
            snapshot.roundingRule = tariff.roundingRule
            snapshot.effectiveFrom = tariff.effectiveFrom
            snapshot.effectiveTo = tariff.effectiveTo
            snapshots.saveAndFlush(snapshot)
        }
        audit.record(AuditEntry(AuditAction.UPDATE, ENTITY, jobId, job.branchId,
            mapOf("before" to before, "after" to mapOf("clientId" to job.clientId, "quotedAmount" to job.quotedAmount,
                "vessels" to job.vessels.map { it.role to it.vesselId }, "cargo" to job.cargo.map { it.cargoGradeId to it.quantity },
                "tariffId" to update.tariff?.id))))
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun create(job: NewJob): JobRef {
        val custom = job.jobNumber?.trim()?.ifBlank { null }
        if (custom != null && jobs.existsByJobNumberIgnoreCase(custom)) {
            throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.",
                listOf(FieldError("jobNumber", "duplicate", "This job number is already in use.")))
        }
        val number = custom ?: generateSequence { numbers.next(SERIES, NumberContext(job.branchCode, job.year, job.acceptedOn)) }
            .first { !jobs.existsByJobNumberIgnoreCase(it) }
        val entity = JobEntity(
            number, job.branchId, job.enquiryId, job.enquiryReference, job.quotationVersionId, job.quotationReference,
            job.clientId, job.clientContactId, job.operationType, job.currency, job.quotedAmount,
        )
        job.vessels.forEach { entity.vessels += JobVesselEntity(it.role, it.vesselId, it.etaUtc, it.etaTimezone) }
        job.cargo.forEach { entity.cargo += JobCargoEntity(it.lineNo, it.cargoGradeId, it.quantity, it.unitId) }
        jobs.saveAndFlush(entity)
        history.record("JOB", entity.id, null, "OPEN", "Owner acceptance", access.currentUser().id)
        val t = job.tariff
        snapshots.saveAndFlush(TariffSnapshotEntity(
            entity.id, t.id, t.code, t.name, t.currency, t.baseHours, t.baseCharge, t.extraHourRate, t.billingIncrementMinutes,
            t.graceMinutes, t.roundingRule, t.effectiveFrom, t.effectiveTo, clock.instant().truncatedTo(ChronoUnit.MICROS),
        ))
        audit.record(AuditEntry(AuditAction.CREATE, ENTITY, entity.id, job.branchId, mapOf(
            "jobNumber" to number, "enquiryId" to job.enquiryId, "quotationVersionId" to job.quotationVersionId,
            "tariff" to "${t.code} from ${t.effectiveFrom}", "baseHours" to t.baseHours, "baseCharge" to t.baseCharge.toPlainString(),
            "extraHourRate" to t.extraHourRate.toPlainString(), "currency" to job.currency, "quotedAmount" to job.quotedAmount.toPlainString(),
        )))
        return JobRef(entity.id, number)
    }

    @Transactional(readOnly = true)
    override fun byEnquiries(enquiryIds: Collection<UUID>): Map<UUID, JobRef> =
        if (enquiryIds.isEmpty()) emptyMap()
        else jobs.findByEnquiryIdIn(enquiryIds.toSet()).associate { it.enquiryId to JobRef(it.id, it.jobNumber) }

    private companion object {
        const val ENTITY = "JOB"
        const val SERIES = "JOB"
    }
}

// ---------- read API ----------

data class JobNamedRef(val id: UUID, val code: String?, val name: String)

data class JobVesselDto(
    val id: UUID, val role: String, val vesselId: UUID, val imo: String?, val name: String?, val etaUtc: Instant?, val etaTimezone: String?,
    val etaLocal: LocalDateTime?,
)

data class JobCargoDto(val lineNo: Int, val cargoGradeCode: String?, val cargoGradeName: String?, val quantity: BigDecimal, val unitCode: String?)

data class TariffSnapshotDto(
    val tariffCode: String, val tariffName: String, val currency: String, val baseHours: Int, val baseCharge: BigDecimal,
    val extraHourRate: BigDecimal, val billingIncrementMinutes: Int?, val graceMinutes: Int?, val roundingRule: String?,
    val effectiveFrom: LocalDate, val effectiveTo: LocalDate?, val capturedAt: Instant,
)

data class JobDetailDto(
    val id: UUID,
    val jobNumber: String,
    val status: String,
    val branchCode: String?,
    val enquiryId: UUID,
    val enquiryReference: String,
    val quotationReference: String,
    val client: JobNamedRef?,
    val contact: JobNamedRef?,
    val operationType: String?,
    val currency: String,
    val quotedAmount: BigDecimal,
    val vessels: List<JobVesselDto>,
    val cargo: List<JobCargoDto>,
    val tariff: TariffSnapshotDto?,
    val createdAt: Instant?,
    val version: Long?,
    val flow: JobFlowDto,
)

data class JobFlowDto(
    val documentTotal: Long,
    val documentReceived: Long,
    val preArrivalTotal: Long,
    val preArrivalReviewed: Long,
    val jpoStatus: String?,
    val jpoAcceptanceCount: Long,
    val mooringStatus: String?,
    val finalReportIssued: Boolean,
    val canOpenDocuments: Boolean,
    val canOpenJpo: Boolean,
    val canOpenMooring: Boolean,
    val canOpenWeather: Boolean,
)

data class JobSummaryDto(
    val id: UUID,
    val jobNumber: String,
    val status: String,
    val branchCode: String?,
    val enquiryReference: String,
    val clientName: String?,
    val motherVesselName: String?,
    val daughterVesselName: String?,
    val createdAt: Instant?,
)

data class JobFilters(
    val q: String? = null,
    val jobNumber: String? = null,
    val createdFrom: LocalDate? = null,
    val createdTo: LocalDate? = null,
    val client: String? = null,
    val vessel: String? = null,
    val enquiryReference: String? = null,
    val status: JobStatus? = null,
    val timeZone: String? = null,
)

@Service
class JobService(
    private val jobs: JobRepository,
    private val snapshots: TariffSnapshotRepository,
    private val organisation: OrganisationLookup,
    private val references: ReferenceLookup,
    private val accessPolicy: AccessPolicy,
    private val jdbc: JdbcClient,
) {

    @Transactional(readOnly = true)
    fun statusCounts(): Map<String, Long> {
        val params = mutableMapOf<String, Any>()
        val condition = accessPolicy.scope(Permissions.JOB_READ).sqlCondition("branch_id", params)
        val where = condition?.let { "WHERE $it" } ?: ""
        return jdbc.sql("SELECT status, COUNT(*) FROM sts_job $where GROUP BY status").params(params)
            .query { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().toMap()
    }

    @Transactional(readOnly = true)
    fun list(filters: JobFilters, pageable: Pageable): PageResponse<JobSummaryDto> {
        val scope = accessPolicy.scope(Permissions.JOB_READ)
        val text = filters.q?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val client = filters.client?.trim()?.takeIf { it.isNotEmpty() }
        val vessel = filters.vessel?.trim()?.takeIf { it.isNotEmpty() }
        val zone = try { ZoneId.of(filters.timeZone ?: "UTC") }
            catch (_: DateTimeException) { throw BadRequestException("INVALID_TIME_ZONE", "Invalid time zone.") }
        val spec = Specification<JobEntity> { root, query, cb ->
            val predicates = mutableListOf(scopePredicate(scope, cb, root))
            filters.status?.let { predicates += cb.equal(root.get<String>("status"), it.name) }
            filters.jobNumber?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let {
                predicates += cb.like(cb.lower(root.get("jobNumber")), "%$it%")
            }
            filters.enquiryReference?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let {
                predicates += cb.like(cb.lower(root.get("enquiryReference")), "%$it%")
            }
            filters.createdFrom?.let { predicates += cb.greaterThanOrEqualTo(root.get("createdAt"), it.atStartOfDay(zone).toInstant()) }
            filters.createdTo?.let { predicates += cb.lessThan(root.get("createdAt"), it.plusDays(1).atStartOfDay(zone).toInstant()) }
            client?.let { predicates += references.clientMatches(root.get("clientId"), query, cb, it) }
            vessel?.let {
                val sub = query.subquery(UUID::class.java)
                val job = sub.from(JobEntity::class.java)
                val jobVessel = job.join<JobEntity, JobVesselEntity>("vessels")
                sub.select(job.get("id")).where(references.vesselMatches(jobVessel.get("vesselId"), sub, cb, it))
                predicates += root.get<UUID>("id").`in`(sub)
            }
            text?.let {
                predicates += cb.or(
                    cb.like(cb.lower(root.get("jobNumber")), "%$it%"),
                    cb.like(cb.lower(root.get("enquiryReference")), "%$it%"),
                )
            }
            cb.and(*predicates.toTypedArray())
        }
        val page = jobs.findAll(spec, pageable)
        val branches = organisation.branches(page.content.map { it.branchId })
        val clients = references.clients(page.content.map { it.clientId })
        val vessels = references.vessels(page.content.flatMap { j -> j.vessels.map { it.vesselId } })
        return PageResponse.of(page) { j ->
            JobSummaryDto(
                j.id, j.jobNumber, j.status, branches[j.branchId]?.code, j.enquiryReference, clients[j.clientId]?.legalName,
                j.vessels.firstOrNull { it.role == "MOTHER" }?.let { vessels[it.vesselId]?.name },
                j.vessels.firstOrNull { it.role == "DAUGHTER" }?.let { vessels[it.vesselId]?.name },
                j.createdAt,
            )
        }
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): JobDetailDto {
        val job = jobs.findByIdOrNull(id) ?: throw NotFoundException("Job")
        accessPolicy.requireBranch(Permissions.JOB_READ, job.branchId, "JOB", id)
        val client = references.clients(listOf(job.clientId))[job.clientId]
        val contact = job.clientContactId?.let { references.contacts(listOf(it))[it] }
        val vessels = references.vessels(job.vessels.map { it.vesselId })
        val grades = references.cargoGrades(job.cargo.map { it.cargoGradeId })
        val units = references.units(job.cargo.map { it.unitId })
        val tariff = snapshots.findByJobId(id)
        return JobDetailDto(
            id = job.id,
            jobNumber = job.jobNumber,
            status = job.status,
            branchCode = organisation.branch(job.branchId)?.code,
            enquiryId = job.enquiryId,
            enquiryReference = job.enquiryReference,
            quotationReference = job.quotationReference,
            client = client?.let { JobNamedRef(it.id, it.code, it.legalName) },
            contact = contact?.let { JobNamedRef(it.id, it.email, it.fullName) },
            operationType = job.operationType,
            currency = job.currency,
            quotedAmount = job.quotedAmount,
            vessels = job.vessels.sortedBy { if (it.role == "MOTHER") 0 else 1 }.map { v ->
                val ref = vessels[v.vesselId]
                JobVesselDto(v.id, v.role, v.vesselId, ref?.imo, ref?.name, v.etaUtc, v.etaTimezone,
                    v.etaUtc?.let { utc -> v.etaTimezone?.let { tz -> utc.atZone(ZoneId.of(tz)).toLocalDateTime() } })
            },
            cargo = job.cargo.sortedBy { it.lineNo }.map {
                JobCargoDto(it.lineNo, grades[it.cargoGradeId]?.code, grades[it.cargoGradeId]?.name, it.quantity, units[it.unitId]?.code)
            },
            tariff = tariff?.let {
                TariffSnapshotDto(it.tariffCode, it.tariffName, it.currency, it.baseHours, it.baseCharge, it.extraHourRate,
                    it.billingIncrementMinutes, it.graceMinutes, it.roundingRule, it.effectiveFrom, it.effectiveTo, it.capturedAt)
            },
            createdAt = job.createdAt,
            version = job.version,
            flow = flow(id, job.branchId),
        )
    }

    private fun flow(id: UUID, branchId: UUID): JobFlowDto {
        val params = Uuids.toBytes(id)
        val documents = jdbc.sql("""SELECT COUNT(*),
            COALESCE(SUM(i.status IN ('RECEIVED','REVIEWED')),0),
            COALESCE(SUM(i.required=TRUE AND COALESCE(i.phase,'PRE_ARRIVAL')='PRE_ARRIVAL'),0),
            COALESCE(SUM(i.required=TRUE AND COALESCE(i.phase,'PRE_ARRIVAL')='PRE_ARRIVAL' AND i.status='REVIEWED'),0)
            FROM job_request_item i JOIN job_document_request r ON r.id=i.request_id
            WHERE r.job_id=:job AND i.status<>'REMOVED'""")
            .param("job", params).query { rs, _ -> (1..4).map(rs::getLong) }.single()
        val jpo = jdbc.sql("""SELECT r.status,(SELECT COUNT(*) FROM jpo_acceptance a WHERE a.revision_id=r.id)
            FROM jpo_revision r JOIN jpo j ON j.id=r.jpo_id WHERE j.job_id=:job
            ORDER BY r.revision_no DESC LIMIT 1""")
            .param("job", params).query { rs, _ -> rs.getString(1) to rs.getLong(2) }.optional().orElse(null)
        val mooring = jdbc.sql("""SELECT r.status FROM mooring_plan_revision r JOIN mooring_plan p ON p.id=r.plan_id
            WHERE p.job_id=:job ORDER BY r.revision_no DESC LIMIT 1""")
            .param("job", params).query(String::class.java).optional().orElse(null)
        val finalReportIssued = jdbc.sql("""SELECT COUNT(*) FROM final_report_revision r JOIN final_report f ON f.id=r.report_id
            WHERE f.job_id=:job AND r.status='ISSUED'""")
            .param("job", params).query(Long::class.java).single() > 0
        val user = accessPolicy.currentUser()
        return JobFlowDto(documents[0], documents[1], documents[2], documents[3], jpo?.first, jpo?.second ?: 0,
            mooring, finalReportIssued,
            user.scopeFor(Permissions.TECH_JOB_READ).coversBranch(branchId),
            user.scopeFor(Permissions.TECH_JPO_READ).coversBranch(branchId),
            user.scopeFor(Permissions.TECH_MOORING_READ).coversBranch(branchId),
            user.scopeFor(Permissions.TECH_WEATHER_READ).coversBranch(branchId))
    }

    private fun scopePredicate(
        scope: AccessScope, cb: jakarta.persistence.criteria.CriteriaBuilder, root: jakarta.persistence.criteria.Root<JobEntity>,
    ): Predicate {
        if (scope.global) return cb.conjunction()
        return if (scope.branchIds.isEmpty()) cb.disjunction() else root.get<UUID>("branchId").`in`(scope.branchIds)
    }
}

@RestController
@RequestMapping("/api/v1/jobs")
class JobController(private val service: JobService, private val paging: Paging) {

    @GetMapping("/status-counts")
    fun statusCounts(): Map<String, Long> = service.statusCounts()

    @GetMapping
    fun list(
        @RequestParam page: Int?, @RequestParam size: Int?, @RequestParam sort: String?,
        @RequestParam q: String?, @RequestParam jobNumber: String?,
        @RequestParam createdFrom: LocalDate?, @RequestParam createdTo: LocalDate?,
        @RequestParam client: String?, @RequestParam vessel: String?,
        @RequestParam enquiryReference: String?, @RequestParam status: JobStatus?, @RequestParam timeZone: String?,
    ): PageResponse<JobSummaryDto> =
        service.list(JobFilters(q, jobNumber, createdFrom, createdTo, client, vessel, enquiryReference, status, timeZone),
            paging.pageable(page, size, sort, SORTS, Sort.by(Sort.Direction.DESC, "createdAt")))

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): ResponseEntity<JobDetailDto> = service.get(id).let { Versioning.ok(it, it.version) }

    private companion object {
        val SORTS = mapOf("jobNumber" to "jobNumber", "createdAt" to "createdAt", "status" to "status")
    }
}
