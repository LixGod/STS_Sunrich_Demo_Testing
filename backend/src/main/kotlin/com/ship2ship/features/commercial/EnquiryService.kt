package com.ship2ship.features.commercial

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditDiff
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.persistence.IdempotencyService
import com.ship2ship.core.persistence.NumberContext
import com.ship2ship.core.persistence.NumberSeriesService
import com.ship2ship.core.persistence.StatusHistory
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.AccessScope
import com.ship2ship.core.security.BranchRef
import com.ship2ship.core.config.CompanyProperties
import com.ship2ship.core.security.OrganisationLookup
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.security.ReferenceLookup
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.BusinessRuleException
import com.ship2ship.core.web.FieldError
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.PageResponse
import com.ship2ship.core.web.Versioning
import com.ship2ship.core.web.Xlsx
import com.ship2ship.features.jobs.JobEntity
import com.ship2ship.features.masters.OperationTypeRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.persistence.criteria.Predicate
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

data class EnquiryFilters(
    val q: String? = null,
    val status: EnquiryStatus? = null,
    val clientId: UUID? = null,
    val branchId: UUID? = null,
    val receivedFrom: LocalDate? = null,
    val receivedTo: LocalDate? = null,
    val timeZone: String? = null,
)

/**
 * Enquiry register (FR-J01): scoped to a branch, numbered per year, multi-grade cargo,
 * mother/daughter vessels with tentative ETAs, duplicate warning and an audited status history.
 */
@Service
class EnquiryService(
    private val enquiries: EnquiryRepository,
    private val organisation: OrganisationLookup,
    private val references: ReferenceLookup,
    private val numbers: NumberSeriesService,
    private val idempotency: IdempotencyService,
    private val history: StatusHistory,
    private val accessPolicy: AccessPolicy,
    private val audit: AuditService,
    private val entityManager: EntityManager,
    private val company: CompanyProperties,
    private val jdbc: JdbcClient,
    private val estimateService: EstimateService,
    private val operationTypes: OperationTypeRepository,
    private val editWindow: CommercialEditWindow,
    private val quotationService: QuotationService,
) {

    @Transactional(readOnly = true)
    fun list(filters: EnquiryFilters, pageable: Pageable): PageResponse<EnquirySummaryDto> {
        val scope = accessPolicy.scope(Permissions.ENQUIRY_READ)
        val text = filters.q?.trim()?.takeIf { it.isNotEmpty() }
        val zone = try { ZoneId.of(filters.timeZone ?: "UTC") }
        catch (_: DateTimeException) { throw BadRequestException("INVALID_TIME_ZONE", "Invalid time zone.") }

        val spec = Specification<EnquiryEntity> { root, query, cb ->
            val predicates = mutableListOf<Predicate>(scopePredicate(scope, cb, root))
            filters.status?.let { predicates += cb.equal(root.get<EnquiryStatus>("status"), it) }
            filters.clientId?.let { predicates += cb.equal(root.get<UUID>("clientId"), it) }
            filters.branchId?.let { predicates += cb.equal(root.get<UUID>("branchId"), it) }
            val dateExpr = cb.coalesce(root.get<Instant>("receivedAt"), root.get<Instant>("createdAt"))
            filters.receivedFrom?.let { predicates += cb.greaterThanOrEqualTo(dateExpr, it.atStartOfDay(zone).toInstant()) }
            filters.receivedTo?.let { predicates += cb.lessThan(dateExpr, it.plusDays(1).atStartOfDay(zone).toInstant()) }
            if (text != null) {
                val sub = query!!.subquery(UUID::class.java)
                val v = sub.from(EnquiryVesselEntity::class.java)
                sub.select(v.get("enquiryId")).where(references.vesselMatches(v.get("vesselId"), sub, cb, text))
                val jobs = query.subquery(UUID::class.java)
                val job = jobs.from(JobEntity::class.java)
                jobs.select(job.get("enquiryId")).where(cb.like(cb.lower(job.get("jobNumber")), "%${text.lowercase()}%"))
                predicates += cb.or(cb.like(cb.lower(root.get("reference")), "%${text.lowercase()}%"),
                    cb.and(cb.isNotNull(root.get<UUID>("clientId")), references.clientMatches(root.get("clientId"), query, cb, text)),
                    root.get<UUID>("id").`in`(sub),
                    root.get<UUID>("id").`in`(jobs))
            }
            cb.and(*predicates.toTypedArray())
        }
        val page = enquiries.findAll(spec, pageable)
        return PageResponse(summaries(page.content), page.number, page.size, page.totalElements)
    }

    @Transactional
    fun exportXlsx(filters: EnquiryFilters, sort: Sort): Pair<String, ByteArray> {
        val zone = try { ZoneId.of(filters.timeZone ?: "UTC") }
        catch (_: DateTimeException) { throw BadRequestException("INVALID_TIME_ZONE", "Invalid time zone.") }
        var count = 0
        val rows = sequence {
            var page = 0
            do {
                val batch = list(filters, PageRequest.of(page++, 250, sort)).items
                for (row in batch) {
                    count++
                    yield(listOf(
                        count,
                        row.reference,
                        row.receivedAt?.atZone(zone)?.format(EXPORT_TIME),
                        row.clientName,
                        listOfNotNull(row.motherVesselName, row.daughterVesselName).joinToString(" / ").ifEmpty { null },
                        row.cargoGrades.joinToString(", ").ifEmpty { null },
                        row.branchCode,
                        row.jobNumber,
                        row.clientStatus,
                        row.estimatedCurrency,
                        row.estimatedCost,
                        row.fdaStatus,
                        row.jobCurrency,
                        row.expenses,
                        row.grossProfit,
                        row.grossProfitPercent,
                        row.balanceReceivable,
                        row.status.name,
                    ))
                }
            } while (batch.size == 250)
        }
        val bytes = Xlsx.workbook(
            "Enquiries",
            "Enquiries Overview",
            listOf(
                "#", "Reference", "Received (${zone.id})", "Client", "Vessels", "Cargo", "Branch",
                "Job number", "Quotation status", "PDA currency", "PDA estimate", "FDA status",
                "Job currency", "Actual expenses", "Gross profit", "Gross profit %", "Balance receivable", "Status",
            ),
            rows.asIterable(),
        )
        audit.record(AuditEntry(AuditAction.EXPORT, ENTITY, null, filters.branchId, mapOf(
            "format" to "xlsx", "rows" to count, "q" to filters.q, "status" to filters.status?.name,
            "clientId" to filters.clientId, "branchId" to filters.branchId,
            "receivedFrom" to filters.receivedFrom?.toString(), "receivedTo" to filters.receivedTo?.toString(),
        )))
        return "enquiries-overview.xlsx" to bytes
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): EnquiryDetailDto {
        val enquiry = load(id)
        accessPolicy.requireBranch(Permissions.ENQUIRY_READ, enquiry.branchId, ENTITY, id)
        return detail(enquiry)
    }

    @Transactional
    fun create(request: EnquiryRequest, idempotencyKey: String?): EnquiryDetailDto {
        accessPolicy.requireBranch(Permissions.ENQUIRY_WRITE, request.branchId, ENTITY)
        val userId = accessPolicy.currentUser().id
        idempotency.existing(userId, idempotencyKey, OPERATION_CREATE)?.let { return detail(load(it)) }

        val branch = validateScope(request)
        val resolved = validate(request, branch)
        val date = (request.receivedAt ?: Instant.now()).atZone(zoneOrUtc(company.timezone)).toLocalDate()
        val reference = numbers.next(SERIES, NumberContext(branch.code, date.year, date))

        val enquiry = EnquiryEntity(reference, request.branchId, request.receivedAt, request.clientId)
        apply(enquiry, request, resolved)
        enquiries.saveAndFlush(enquiry)
        history.record(ENTITY, enquiry.id, null, enquiry.status.name, null, userId)
        audit.record(AuditEntry(AuditAction.CREATE, ENTITY, enquiry.id, enquiry.branchId,
            mapOf("reference" to reference) + enquiry.toAuditMap()))
        idempotency.remember(userId, idempotencyKey, OPERATION_CREATE, enquiry.id)
        return detail(enquiry)
    }

    @Transactional
    fun update(id: UUID, expectedVersion: Long, request: EnquiryRequest): EnquiryDetailDto {
        val enquiry = load(id)
        accessPolicy.requireBranch(Permissions.ENQUIRY_WRITE, enquiry.branchId, ENTITY, id)
        Versioning.checkCurrent(expectedVersion, enquiry.version)
        if (enquiry.status !in EnquiryLifecycle.EDITABLE) {
            throw BusinessRuleException("ENQUIRY_LOCKED", "An enquiry in status ${enquiry.status} can no longer be edited.")
        }
        editWindow.requireOpen(id)
        if (request.branchId != enquiry.branchId) {
            throw BadRequestException("SCOPE_IMMUTABLE", "The branch of an enquiry cannot be changed.",
                listOf(FieldError("branchId", "immutable", null)))
        }
        val branch = validateScope(request)
        val resolved = validate(request, branch)
        val before = enquiry.toAuditMap()
        enquiry.receivedAt = request.receivedAt
        enquiry.clientId = request.clientId
        apply(enquiry, request, resolved)
        // The version covers the whole enquiry, including vessel and cargo rows that change without touching the parent row.
        // Incremented now (not at commit) so the response carries the new version for the next If-Match.
        entityManager.lock(enquiry, LockModeType.PESSIMISTIC_FORCE_INCREMENT)
        enquiries.saveAndFlush(enquiry)
        if (!estimateService.syncSpecialItems(enquiry)) quotationService.syncFromEnquiry(enquiry)
        audit.record(AuditEntry(AuditAction.UPDATE, ENTITY, id, enquiry.branchId,
            AuditDiff.changes(before, enquiry.toAuditMap())))
        return detail(enquiry)
    }

    @Transactional
    fun transition(id: UUID, expectedVersion: Long, request: TransitionRequest): EnquiryDetailDto {
        val enquiry = load(id)
        accessPolicy.requireBranch(Permissions.ENQUIRY_WRITE, enquiry.branchId, ENTITY, id)
        Versioning.checkCurrent(expectedVersion, enquiry.version)
        EnquiryLifecycle.RULES.check(enquiry.status, request.to, request.reason)
        val from = enquiry.status
        enquiry.status = request.to
        enquiries.saveAndFlush(enquiry)
        history.record(ENTITY, id, from.name, request.to.name, request.reason, accessPolicy.currentUser().id)
        audit.record(AuditEntry(AuditAction.STATUS_CHANGE, ENTITY, id, enquiry.branchId,
            mapOf("from" to from.name, "to" to request.to.name, "reason" to request.reason)))
        return detail(enquiry)
    }

    /** Open enquiries for the same mother/daughter pair that the reader may see (warning only; never blocks). */
    @Transactional(readOnly = true)
    fun duplicates(motherVesselId: UUID, daughterVesselId: UUID, excludeId: UUID?): List<EnquirySummaryDto> {
        val scope = accessPolicy.scope(Permissions.ENQUIRY_READ)
        val found = enquiries.findOpenForVesselPair(motherVesselId, daughterVesselId, EnquiryLifecycle.OPEN, excludeId)
            .filter { scope.coversBranch(it.branchId) }
            .take(MAX_DUPLICATES)
        return summaries(found)
    }

    // ---------- validation ----------

    private data class Resolved(val zoneForEta: ZoneId)

    private fun validateScope(request: EnquiryRequest): BranchRef {
        val branch = organisation.branch(request.branchId)
        if (branch == null || !branch.active) throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.",
            listOf(FieldError("branchId", "unknown", "Unknown or inactive branch.")))
        return branch
    }

    private fun validate(request: EnquiryRequest, branch: BranchRef): Resolved {
        val errors = mutableListOf<FieldError>()
        request.clientId?.let { clientId ->
            val client = references.clients(listOf(clientId))[clientId]
            if (client?.active != true) errors += FieldError("clientId", "unknown", "Unknown or inactive client.")
        }
        request.clientContactId?.let { contactId ->
            val contact = references.contacts(listOf(contactId))[contactId]
            if (request.clientId == null || contact == null || !contact.active || contact.clientId != request.clientId) {
                errors += FieldError("clientContactId", "mismatch", "The contact must be active and belong to the client.")
            }
        }

        val zone = zoneOrUtc(branch.timezone)

        val vesselIds = listOfNotNull(request.motherVessel?.vesselId, request.daughterVessel?.vesselId)
        val vessels = references.vessels(vesselIds)
        listOf("motherVessel" to request.motherVessel, "daughterVessel" to request.daughterVessel).forEach { (field, vessel) ->
            if (vessel != null) {
                if (vessels[vessel.vesselId]?.active != true) errors += FieldError("$field.vesselId", "unknown", "Unknown or inactive vessel.")
                vessel.etaTimezone?.let { if (!isRegionZone(it)) errors += FieldError("$field.etaTimezone", "timezone", "Unknown time zone.") }
            }
        }
        if (request.motherVessel != null && request.motherVessel.vesselId == request.daughterVessel?.vesselId) {
            errors += FieldError("daughterVessel.vesselId", "same", "Mother and daughter must be different vessels.")
        }

        val grades = references.cargoGrades(request.cargo.map { it.cargoGradeId })
        val units = references.units(request.cargo.map { it.unitId })
        request.cargo.forEachIndexed { i, line ->
            if (grades[line.cargoGradeId]?.active != true) errors += FieldError("cargo[$i].cargoGradeId", "unknown", "Unknown or inactive cargo grade.")
            if (units[line.unitId]?.active != true) errors += FieldError("cargo[$i].unitId", "unknown", "Unknown or inactive unit.")
        }
        if (request.cargo.map { it.cargoGradeId }.toSet().size != request.cargo.size) {
            errors += FieldError("cargo", "duplicate", "Each cargo grade may appear only once.")
        }
        request.specialItems.forEachIndexed { i, item ->
            if (item.description.isBlank()) errors += FieldError("specialItems[$i].description", "required", "Enter a description.")
            if (item.paid && item.salePriceUsd == null) errors += FieldError("specialItems[$i].salePriceUsd", "required", "Enter the USD sale price.")
            if (!item.paid && item.salePriceUsd != null) errors += FieldError("specialItems[$i].salePriceUsd", "unexpected", "Only paid items have a sale price.")
        }
        val cargoTypes = request.cargoTypes ?: request.operationType?.let(::listOf).orEmpty()
        cargoTypes.forEachIndexed { index, code ->
            if (code.isBlank() || code.length > 20 || operationTypes.findByCode(code.trim().uppercase())?.active != true)
                errors += FieldError("cargoTypes[$index]", "unknown", "Choose an active cargo type.")
        }
        if (cargoTypes.map { it.trim().uppercase() }.toSet().size != cargoTypes.size)
            errors += FieldError("cargoTypes", "duplicate", "Choose each cargo type once.")
        if (errors.isNotEmpty()) throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", errors)
        return Resolved(zone)
    }

    private fun apply(enquiry: EnquiryEntity, request: EnquiryRequest, resolved: Resolved) {
        enquiry.clientContactId = request.clientContactId
        val cargoTypes = (request.cargoTypes ?: request.operationType?.let(::listOf).orEmpty()).map { it.trim().uppercase() }
        if (enquiry.cargoTypes != cargoTypes.toSet()) {
            enquiry.cargoTypes.clear()
            enquiry.cargoTypes.addAll(cargoTypes)
        }
        enquiry.operationType = when (cargoTypes.size) { 0 -> null; 1 -> cargoTypes.single(); else -> "OTHER" }
        enquiry.expectedDurationHours = request.expectedDurationHours
        enquiry.scopeOfServices = request.scopeOfServices?.trim()?.ifBlank { null }
        enquiry.specialRequirements = request.specialRequirements?.trim()?.ifBlank { null }
        enquiry.sourceReference = request.sourceReference?.trim()?.ifBlank { null }

        // Vessels: update the row for each role in place, add or remove as needed.
        mapOf(VesselRole.MOTHER to request.motherVessel, VesselRole.DAUGHTER to request.daughterVessel).forEach { (role, input) ->
            val existing = enquiry.vessel(role)
            if (input == null) {
                existing?.let { enquiry.vessels.remove(it) }
            } else {
                val zone = input.etaTimezone?.let(ZoneId::of) ?: resolved.zoneForEta
                val etaUtc = input.etaLocal?.atZone(zone)?.toInstant()
                val etaZone = input.etaLocal?.let { zone.id }
                if (existing == null) {
                    enquiry.vessels += EnquiryVesselEntity(role, input.vesselId, etaUtc, etaZone)
                } else {
                    existing.vesselId = input.vesselId
                    existing.etaUtc = etaUtc
                    existing.etaTimezone = etaZone
                }
            }
        }

        // Cargo lines: rows are matched by line number and updated in place (no delete-then-insert of the same key).
        request.cargo.forEachIndexed { i, input ->
            val lineNo = i + 1
            val existing = enquiry.cargo.firstOrNull { it.lineNo == lineNo }
            if (existing == null) {
                enquiry.cargo += EnquiryCargoEntity(lineNo, input.cargoGradeId, input.quantity, input.unitId)
            } else {
                existing.cargoGradeId = input.cargoGradeId
                existing.quantity = input.quantity
                existing.unitId = input.unitId
            }
        }
        enquiry.cargo.removeIf { it.lineNo > request.cargo.size }
        request.specialItems.forEachIndexed { index, item ->
            val lineNo = index + 1
            val existing = enquiry.specialItems.firstOrNull { it.lineNo == lineNo }
            if (existing == null) enquiry.specialItems += EnquirySpecialRequirementEntity(
                lineNo, item.description.trim(), item.paid, item.salePriceUsd)
            else {
                existing.description = item.description.trim()
                existing.paid = item.paid
                existing.salePriceUsd = item.salePriceUsd
            }
        }
        enquiry.specialItems.removeIf { it.lineNo > request.specialItems.size }
    }

    // ---------- mapping ----------

    private fun detail(enquiry: EnquiryEntity): EnquiryDetailDto {
        val branch = organisation.branch(enquiry.branchId)
        val client = enquiry.clientId?.let { references.clients(listOf(it))[it] }
        val contact = enquiry.clientContactId?.let { references.contacts(listOf(it))[it] }
        val vessels = references.vessels(enquiry.vessels.map { it.vesselId })
        val grades = references.cargoGrades(enquiry.cargo.map { it.cargoGradeId })
        val units = references.units(enquiry.cargo.map { it.unitId })

        fun vesselDto(role: VesselRole) = enquiry.vessel(role)?.let { v ->
            val ref = vessels[v.vesselId]
            EnquiryVesselDto(role, v.vesselId, ref?.imo, ref?.name, v.etaUtc, v.etaTimezone,
                v.etaUtc?.let { utc -> v.etaTimezone?.let { tz -> utc.atZone(ZoneId.of(tz)).toLocalDateTime() } })
        }

        return EnquiryDetailDto(
            id = enquiry.id,
            reference = enquiry.reference,
            branchId = enquiry.branchId,
            branchCode = branch?.code,
            status = enquiry.status,
            allowedTransitions = EnquiryLifecycle.RULES.next(enquiry.status).sorted(),
            editable = enquiry.status in EnquiryLifecycle.EDITABLE && editWindow.canEdit(enquiry.id),
            receivedAt = enquiry.receivedAt,
            client = client?.let { NamedRef(it.id, it.code, it.legalName) },
            contact = contact?.let { NamedRef(it.id, it.email, it.fullName) },
            operationType = enquiry.operationType,
            cargoTypes = enquiry.cargoTypes.sorted(),
            expectedDurationHours = enquiry.expectedDurationHours,
            scopeOfServices = enquiry.scopeOfServices,
            specialRequirements = enquiry.specialRequirements,
            sourceReference = enquiry.sourceReference,
            motherVessel = vesselDto(VesselRole.MOTHER),
            daughterVessel = vesselDto(VesselRole.DAUGHTER),
            cargo = enquiry.cargo.sortedBy { it.lineNo }.map {
                EnquiryCargoDto(it.lineNo, it.cargoGradeId, grades[it.cargoGradeId]?.code, grades[it.cargoGradeId]?.name,
                    it.quantity, it.unitId, units[it.unitId]?.code)
            },
            specialItems = enquiry.specialItems.sortedBy { it.lineNo }.map {
                EnquirySpecialRequirementDto(it.lineNo, it.description, it.paid, it.salePriceUsd)
            },
            history = history.history(ENTITY, enquiry.id).map { TransitionDto(it.fromStatus, it.toStatus, it.reason, it.occurredAt) },
            createdAt = enquiry.createdAt,
            updatedAt = enquiry.updatedAt,
            version = enquiry.version,
        )
    }

    /** Page mapping with one batched lookup per reference type. */
    private fun summaries(items: List<EnquiryEntity>): List<EnquirySummaryDto> {
        val branches = organisation.branches(items.map { it.branchId })
        val clients = references.clients(items.mapNotNull { it.clientId })
        val vessels = references.vessels(items.flatMap { e -> e.vessels.map { it.vesselId } })
        val grades = references.cargoGrades(items.flatMap { e -> e.cargo.map { it.cargoGradeId } })
        val ids = items.map { Uuids.toBytes(it.id) }
        val costs = if (ids.isEmpty()) emptyMap() else jdbc.sql("""SELECT e.enquiry_id,e.total_cost,e.currency FROM estimate e
            WHERE e.enquiry_id IN (:ids) AND e.version_no=(SELECT MAX(x.version_no) FROM estimate x WHERE x.enquiry_id=e.enquiry_id)""")
            .param("ids", ids).query { rs, _ -> Uuids.fromBytes(rs.getBytes(1))!! to (rs.getBigDecimal(2) to rs.getString(3)) }.list().toMap()
        val responses = if (ids.isEmpty()) emptyMap() else jdbc.sql("""SELECT q.enquiry_id,v.status FROM quotation q
            JOIN quotation_version v ON v.quotation_id=q.id WHERE q.enquiry_id IN (:ids)
            AND v.version_no=(SELECT MAX(x.version_no) FROM quotation_version x WHERE x.quotation_id=q.id)""")
            .param("ids", ids).query { rs, _ -> Uuids.fromBytes(rs.getBytes(1))!! to rs.getString(2) }.list().toMap()
        data class JobFigures(val number: String, val currency: String, val fda: String?, val expense: BigDecimal?, val profitAmount: BigDecimal?, val profit: BigDecimal?, val balance: BigDecimal?)
        val user = accessPolicy.currentUser()
        val estimateScope = user.scopeFor(Permissions.ESTIMATE_READ)
        val quotationScope = user.scopeFor(Permissions.QUOTATION_READ)
        val financeScope = user.scopeFor(Permissions.FIN_INVOICE_READ)
        val receiptScope = user.scopeFor(Permissions.FIN_RECEIPT_READ)
        val costScope = user.scopeFor("FIN.COST.READ")
        val profitScope = user.scopeFor("FIN.PROFIT.READ")
        val fdaScope = user.scopeFor("FIN.FDA.READ")
        val jobs = if (ids.isEmpty()) emptyMap() else jdbc.sql("""SELECT j.enquiry_id,j.branch_id,j.job_number,j.id,
            (SELECT status FROM fda_revision f WHERE f.job_id=j.id ORDER BY revision_no DESC LIMIT 1),
            (SELECT COALESCE(SUM(job_amount),0) FROM job_cost_document c WHERE c.job_id=j.id AND kind='BILL' AND status='APPROVED'),
            (SELECT COALESCE(SUM(CASE WHEN doc_type='INVOICE' THEN net_amount ELSE -net_amount END),0) FROM invoice i WHERE i.job_id=j.id AND status='ISSUED'),
            (SELECT COALESCE(SUM(CASE WHEN doc_type='INVOICE' THEN gross_amount ELSE -gross_amount END),0) FROM invoice i WHERE i.job_id=j.id AND status='ISSUED'),
            (SELECT COALESCE(SUM(a.amount),0) FROM receipt_allocation a JOIN invoice i ON i.id=a.invoice_id
              JOIN receipt r ON r.id=a.receipt_id WHERE i.job_id=j.id AND a.reversed_at IS NULL AND r.status='ACTIVE'),
            j.currency
            FROM sts_job j WHERE j.enquiry_id IN (:ids)""").param("ids", ids).query { rs, _ ->
            val branch = Uuids.fromBytes(rs.getBytes(2))!!
            val visible = financeScope.coversBranch(branch)
            val costVisible = costScope.coversBranch(branch)
            val income = rs.getBigDecimal(7)
            val expense = rs.getBigDecimal(6)
            val profitVisible = visible && costVisible && profitScope.coversBranch(branch)
            val profitAmount = if (profitVisible) income.subtract(expense) else null
            val margin = if (profitVisible && income.signum() > 0) income.subtract(expense).multiply(BigDecimal(100)).divide(income, 1, RoundingMode.HALF_UP) else null
            Uuids.fromBytes(rs.getBytes(1))!! to JobFigures(rs.getString(3), rs.getString(10), if (fdaScope.coversBranch(branch)) rs.getString(5) else null,
                if (costVisible) expense else null, profitAmount, margin,
                if (visible && receiptScope.coversBranch(branch)) rs.getBigDecimal(8).subtract(rs.getBigDecimal(9)) else null)
        }.list().toMap()
        return items.map { e ->
            val job = jobs[e.id]
            EnquirySummaryDto(
                id = e.id,
                reference = e.reference,
                status = e.status,
                receivedAt = e.receivedAt,
                branchCode = branches[e.branchId]?.code,
                clientName = e.clientId?.let { clients[it]?.legalName },
                motherVesselName = e.vessel(VesselRole.MOTHER)?.let { vessels[it.vesselId]?.name },
                daughterVesselName = e.vessel(VesselRole.DAUGHTER)?.let { vessels[it.vesselId]?.name },
                cargoGrades = e.cargo.sortedBy { it.lineNo }.mapNotNull { grades[it.cargoGradeId]?.code },
                jobNumber = job?.number,
                clientStatus = if (quotationScope.coversBranch(e.branchId)) responses[e.id] else null,
                estimatedCost = if (estimateScope.coversBranch(e.branchId)) costs[e.id]?.first else null,
                estimatedCurrency = if (estimateScope.coversBranch(e.branchId)) costs[e.id]?.second else null,
                jobCurrency = job?.currency,
                fdaStatus = job?.fda,
                expenses = job?.expense,
                grossProfit = job?.profitAmount,
                grossProfitPercent = job?.profit,
                balanceReceivable = job?.balance,
            )
        }
    }

    private fun scopePredicate(
        scope: AccessScope, cb: jakarta.persistence.criteria.CriteriaBuilder, root: jakarta.persistence.criteria.Root<EnquiryEntity>,
    ): Predicate {
        if (scope.global) return cb.conjunction()
        return if (scope.branchIds.isEmpty()) cb.disjunction() else root.get<UUID>("branchId").`in`(scope.branchIds)
    }

    private fun load(id: UUID) = enquiries.findByIdOrNull(id) ?: throw NotFoundException("Enquiry")

    private companion object {
        const val ENTITY = "ENQUIRY"
        const val SERIES = "ENQUIRY"
        const val OPERATION_CREATE = "ENQUIRY.CREATE"
        const val MAX_DUPLICATES = 10
        val EXPORT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        fun isRegionZone(zone: String): Boolean = try {
            ZoneId.of(zone).id.contains('/')
        } catch (ex: DateTimeException) {
            false
        }

        fun zoneOrUtc(zone: String): ZoneId = try {
            ZoneId.of(zone)
        } catch (ex: DateTimeException) {
            ZoneOffset.UTC
        }
    }
}

