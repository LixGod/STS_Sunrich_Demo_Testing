package com.ship2ship.features.commercial

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditDiff
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.jobs.JobRegistry
import com.ship2ship.core.jobs.CommercialJobUpdate
import com.ship2ship.core.jobs.FinalInvoiceDrafts
import com.ship2ship.core.jobs.NewJob
import com.ship2ship.core.jobs.NewJobCargo
import com.ship2ship.core.jobs.NewJobVessel
import com.ship2ship.core.persistence.IdempotencyService
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.core.persistence.NumberContext
import com.ship2ship.core.config.CompanyProperties
import com.ship2ship.core.persistence.NumberSeriesService
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.OrganisationLookup
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.security.ReferenceLookup
import com.ship2ship.core.security.TariffRef
import com.ship2ship.core.security.UserDirectory
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.BusinessRuleException
import com.ship2ship.core.web.ConflictException
import com.ship2ship.core.web.FieldError
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.Versioning
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.data.repository.findByIdOrNull
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

/**
 * Quotations (FR-J03): versions prepared from an estimate, approved by someone other than the submitter (FR-UM-05),
 * issued with a stored PDF, and accepted by the owner, which creates exactly one job (UAT-03, AR-02).
 * Commercial corrections replace the current PDF and archive its previous bytes until final invoice issue.
 */
@Service
class QuotationService(
    private val quotations: QuotationRepository,
    private val versions: QuotationVersionRepository,
    private val acceptances: OwnerAcceptanceRepository,
    private val advancePayments: QuotationAdvancePaymentRepository,
    private val estimates: EstimateRepository,
    private val enquiries: EnquiryRepository,
    private val workflow: EnquiryWorkflow,
    private val references: ReferenceLookup,
    private val organisation: OrganisationLookup,
    private val users: UserDirectory,
    private val jobs: JobRegistry,
    private val finalInvoiceDrafts: FinalInvoiceDrafts,
    private val editWindow: CommercialEditWindow,
    private val entityManager: EntityManager,
    private val numbers: NumberSeriesService,
    private val idempotency: IdempotencyService,
    private val pdf: QuotationPdfRenderer,
    private val accessPolicy: AccessPolicy,
    private val audit: AuditService,
    private val jdbc: JdbcClient,
    private val clock: Clock,
    private val company: CompanyProperties,
) {

    data class PdfRevisionDto(val id: UUID, val sha256: String, val replacedAt: Instant)

    @Transactional(readOnly = true)
    fun pdfRevisions(versionId: UUID): List<PdfRevisionDto> {
        val (_, _, enquiry) = context(versionId)
        accessPolicy.requireBranch(Permissions.QUOTATION_READ, enquiry.branchId, ENTITY, versionId)
        return jdbc.sql("SELECT BIN_TO_UUID(id),pdf_sha256,replaced_at FROM quotation_pdf_revision WHERE quotation_version_id=:id ORDER BY replaced_at DESC")
            .param("id", Uuids.toBytes(versionId)).query { rs, _ ->
                PdfRevisionDto(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getTimestamp(3).toInstant())
            }.list()
    }

    @Transactional(readOnly = true)
    fun pdfRevision(versionId: UUID, revisionId: UUID): ByteArray {
        val (_, _, enquiry) = context(versionId)
        accessPolicy.requireBranch(Permissions.QUOTATION_READ, enquiry.branchId, ENTITY, versionId)
        return jdbc.sql("SELECT pdf FROM quotation_pdf_revision WHERE id=:rev AND quotation_version_id=:version")
            .param("rev", Uuids.toBytes(revisionId)).param("version", Uuids.toBytes(versionId))
            .query { rs, _ -> rs.getBytes(1) }.optional().orElseThrow { NotFoundException("Quotation PDF revision") }
    }

    // ---------- reads ----------

    @Transactional(readOnly = true)
    fun overview(enquiryId: UUID): QuotationOverviewDto {
        val enquiry = enquiry(enquiryId)
        accessPolicy.requireBranch(Permissions.QUOTATION_READ, enquiry.branchId, ENQUIRY, enquiryId)
        return overviewOf(enquiry)
    }

    @Transactional(readOnly = true)
    fun pdf(versionId: UUID): Pair<String, ByteArray> {
        val (version, quotation, enquiry) = context(versionId)
        accessPolicy.requireBranch(Permissions.QUOTATION_READ, enquiry.branchId, ENTITY, versionId)
        val stored = if (version.status in FROZEN) storedPdf(versionId) else null
        val bytes = stored ?: pdf.render(document(version, quotation, enquiry, draft = false))
        val name = "${quotation.reference.replace('/', '-')}-v${version.versionNo}.pdf"
        return name to bytes
    }

    /** Approval step is disabled; keep endpoint returning an empty list for backward compatibility. */
    @Transactional(readOnly = true)
    fun pendingApproval(): List<PendingApprovalDto> = emptyList()

    // ---------- preparing ----------

    @Transactional
    fun createVersion(enquiryId: UUID, request: NewQuotationVersionRequest): QuotationOverviewDto {
        val probe = enquiry(enquiryId)
        accessPolicy.requireBranch(Permissions.QUOTATION_WRITE, probe.branchId, ENQUIRY, enquiryId)
        val actor = accessPolicy.currentUser().id
        val enquiry = workflow.lock(enquiryId)
        val estimate = estimates.findByIdOrNull(request.estimateId)?.takeIf { it.enquiryId == enquiryId }
            ?: throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.",
                listOf(FieldError("estimateId", "unknown", "Choose an estimate of this enquiry.")))
        val revenue = estimate.lines.filter { it.kind == LineKind.REVENUE }.sortedBy { it.lineNo }
        if (revenue.isEmpty()) {
            throw BusinessRuleException("ESTIMATE_HAS_NO_REVENUE", "The estimate has no revenue lines to quote.")
        }
        val tariffId = request.tariffId ?: estimate.tariffId
        validateTerms(enquiry, estimate.currency, request.validUntil, tariffId, requireTariff = false)
        if (revenue.any { it.extraHours } && estimate.tariffId != null && tariffId != estimate.tariffId)
            throw BusinessRuleException("TARIFF_MISMATCH", "The quotation must use the estimate tariff for extra hours.")

        val quotation = quotations.findByEnquiryId(enquiryId) ?: newQuotation(enquiry)
        val existing = versions.findByQuotationIdOrderByVersionNoDesc(quotation.id)
        if (existing.any { it.status in OPEN }) {
            throw ConflictException("QUOTATION_VERSION_OPEN", "Finish or reject the open quotation version first.")
        }
        val heads = references.costHeads(revenue.map { it.costHeadId })
        val version = QuotationVersionEntity(quotation.id, (existing.maxOfOrNull { it.versionNo } ?: 0) + 1, estimate.id,
            estimate.currency, estimate.totalRevenue, request.validUntil)
        version.paymentTerms = request.paymentTerms.clean()
        version.advanceReceived = request.advanceReceived
        version.advanceGiven = request.advanceGiven
        version.termsText = request.termsText.clean()
        version.scopeOfServices = request.scopeOfServices.clean()
        version.tariffId = tariffId
        if (estimate.currency == "USD") {
            val userRate = request.inrFxRate ?: estimate.inrFxRate
            if (userRate != null) {
                version.inrFxRate = userRate
            }
        }
        revenue.forEachIndexed { i, line ->
            val head = heads[line.costHeadId]
            version.lines += QuotationLineEntity(i + 1, head?.code ?: "?", head?.name ?: "?", line.description, line.quantity,
                line.unitPrice, line.amount, line.extraHours)
        }
        versions.saveAndFlush(version)
        if (!estimate.locked) {
            estimate.locked = true
            estimates.saveAndFlush(estimate)
        }
        audit.record(AuditEntry(AuditAction.CREATE, ENTITY, version.id, enquiry.branchId,
            mapOf("quotation" to quotation.reference, "versionNo" to version.versionNo, "estimateId" to estimate.id) + version.toAuditMap()))
        return overviewOf(enquiry)
    }

    @Transactional
    fun updateVersion(versionId: UUID, expectedVersion: Long, request: QuotationVersionRequest): QuotationOverviewDto {
        val (version, _, enquiry) = lockedContext(versionId, Permissions.QUOTATION_WRITE, expectedVersion, allowAccepted = true)
        validateTerms(enquiry, version.currency, request.validUntil, request.tariffId, requireTariff = false,
            requireFutureValidity = version.status !in FROZEN)
        if (version.lines.any { it.extraHours } && version.tariffId != null && request.tariffId != version.tariffId)
            throw BusinessRuleException("TARIFF_MISMATCH", "Update the estimate extra-hour rate before changing the quotation tariff.")
        val before = version.toAuditMap()
        val tariffChanged = version.tariffId != request.tariffId
        version.validUntil = request.validUntil
        version.paymentTerms = request.paymentTerms.clean()
        version.advanceReceived = request.advanceReceived
        version.advanceGiven = request.advanceGiven
        version.termsText = request.termsText.clean()
        version.scopeOfServices = request.scopeOfServices.clean()
        version.tariffId = request.tariffId
        if (version.currency == "USD") {
            if (request.inrFxRate != version.inrFxRate) version.inrFxRateDate = null
            version.inrFxRate = request.inrFxRate
        } else {
            version.inrFxRate = null
            version.inrFxRateDate = null
        }
        versions.saveAndFlush(version)
        if (version.status in FROZEN) {
            refreshPdf(version, enquiry)
            if (version.status == QuotationStatus.ACCEPTED) syncAcceptedJob(version, enquiry, tariffChanged)
        }
        audit.record(AuditEntry(AuditAction.UPDATE, ENTITY, versionId, enquiry.branchId,
            AuditDiff.changes(before, version.toAuditMap())))
        return overviewOf(enquiry)
    }

    @Transactional
    fun recordAdvance(versionId: UUID, expectedVersion: Long, request: RecordAdvanceRequest): QuotationOverviewDto {
        val (version, _, enquiry) = lockedContext(versionId, Permissions.QUOTATION_WRITE, expectedVersion, allowAccepted = true)
        requireStatus(version, QuotationStatus.ACCEPTED)
        editWindow.requireOpen(enquiry.id)
        val existing = advancePayments.findByQuotationVersionIdOrderByRecordedAtAsc(version.id)
        val minor = references.currency(version.currency)?.minorUnits ?: 2
        val before = advanceSummary(version, existing, minor)
        if (before.remaining.signum() <= 0) {
            throw BusinessRuleException("ADVANCE_ALREADY_PAID", "100% of the final amount has already been paid.")
        }
        val amount = request.amount.setScale(minor, RoundingMode.HALF_UP)
        if (amount.signum() <= 0) {
            throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.",
                listOf(FieldError("amount", "min", "Enter an advance amount greater than zero.")))
        }
        if (amount > before.remaining) {
            throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.",
                listOf(FieldError("amount", "max", "Advance cannot exceed the remaining balance of ${version.currency} ${before.remaining.toPlainString()}.")))
        }
        val actor = accessPolicy.currentUser().id
        val payment = advancePayments.saveAndFlush(QuotationAdvancePaymentEntity(version.id, amount, request.note.clean(), actor, now()))
        val after = advanceSummary(version, existing + payment, minor)
        entityManager.lock(version, LockModeType.PESSIMISTIC_FORCE_INCREMENT)
        versions.saveAndFlush(version)
        refreshPdf(version, enquiry)
        audit.record(AuditEntry(AuditAction.UPDATE, ENTITY, versionId, enquiry.branchId, mapOf(
            "action" to "RECORD_ADVANCE",
            "amount" to amount.toPlainString(),
            "note" to payment.note,
            "finalAmount" to after.finalAmount.toPlainString(),
            "advancePaid" to after.paid.toPlainString(),
            "advanceRemaining" to after.remaining.toPlainString(),
            "advanceStatus" to after.status.name,
        )))
        return overviewOf(enquiry)
    }

    /** Refresh the active draft/issued/accepted quotation after Commercial changes its source estimate. */
    @Transactional
    fun syncFromEstimate(estimate: EstimateEntity, enquiry: EnquiryEntity): Boolean {
        val quotation = quotations.findByEnquiryId(enquiry.id) ?: return false
        val heads = references.costHeads(estimate.lines.filter { it.kind == LineKind.REVENUE }.map { it.costHeadId })
        var refreshedFrozen = false
        versions.findByQuotationIdOrderByVersionNoDesc(quotation.id)
            .filter { it.estimateId == estimate.id && it.status in setOf(QuotationStatus.DRAFT, QuotationStatus.PENDING_APPROVAL, QuotationStatus.APPROVED, QuotationStatus.ISSUED, QuotationStatus.ACCEPTED) }
            .forEach { version ->
                val tariffChanged = estimate.tariffId != null && estimate.tariffId != version.tariffId
                if (tariffChanged) version.tariffId = estimate.tariffId
                if (estimate.currency == "USD" && estimate.inrFxRate != null) {
                    if (estimate.inrFxRate != version.inrFxRate) version.inrFxRateDate = null
                    version.inrFxRate = estimate.inrFxRate
                }
                version.lines.clear()
                versions.saveAndFlush(version)
                estimate.lines.filter { it.kind == LineKind.REVENUE }.sortedBy { it.lineNo }.forEachIndexed { i, line ->
                    val head = heads[line.costHeadId]
                    version.lines += QuotationLineEntity(i + 1, head?.code ?: "?", head?.name ?: "?", line.description,
                        line.quantity, line.unitPrice, line.amount, line.extraHours)
                }
                version.totalAmount = estimate.totalRevenue
                versions.saveAndFlush(version)
                if (version.status in FROZEN) {
                    refreshPdf(version, enquiry)
                    refreshedFrozen = true
                }
                if (version.status == QuotationStatus.ACCEPTED) syncAcceptedJob(version, enquiry, tariffChanged)
            }
        return refreshedFrozen
    }

    @Transactional
    fun syncFromEnquiry(enquiry: EnquiryEntity) {
        val quotation = quotations.findByEnquiryId(enquiry.id) ?: return
        versions.findByQuotationIdOrderByVersionNoDesc(quotation.id)
            .filter { it.status in setOf(QuotationStatus.ISSUED, QuotationStatus.ACCEPTED) }
            .forEach { version ->
                refreshPdf(version, enquiry)
                if (version.status == QuotationStatus.ACCEPTED) syncAcceptedJob(version, enquiry)
            }
    }

    fun acceptedEstimateId(enquiryId: UUID): UUID? = quotations.findByEnquiryId(enquiryId)?.let { quotation ->
        versions.findByQuotationIdOrderByVersionNoDesc(quotation.id).firstOrNull { it.status == QuotationStatus.ACCEPTED }?.estimateId
    }

    private fun syncAcceptedJob(version: QuotationVersionEntity, enquiry: EnquiryEntity, refreshTariff: Boolean = false) {
        val job = jobs.byEnquiries(listOf(enquiry.id))[enquiry.id] ?: return
        val tariff = if (refreshTariff) version.tariffId?.let { references.tariffs(listOf(it))[it] }
            ?: throw BusinessRuleException("TARIFF_REQUIRED", "Select a tariff before updating the accepted job.") else null
        val client = enquiry.clientId ?: throw BusinessRuleException("CLIENT_REQUIRED", "Select a client on the enquiry.")
        jobs.syncCommercial(job.id, CommercialJobUpdate(client, enquiry.clientContactId, enquiry.operationType,
            version.totalAmount, enquiry.vessels.sortedBy { it.role }.map { NewJobVessel(it.role.name, it.vesselId, it.etaUtc, it.etaTimezone) },
            enquiry.cargo.sortedBy { it.lineNo }.map { NewJobCargo(it.lineNo, it.cargoGradeId, it.quantity, it.unitId) }, tariff))
        finalInvoiceDrafts.refreshFromQuotation(job.id)
    }

    @Transactional
    fun submit(versionId: UUID, expectedVersion: Long): QuotationOverviewDto {
        val (version, _, enquiry) = lockedContext(versionId, Permissions.QUOTATION_WRITE, expectedVersion)
        requireStatus(version, QuotationStatus.DRAFT)
        if (enquiry.clientId == null) {
            throw BusinessRuleException("CLIENT_REQUIRED", "Select a client on the enquiry before submitting a quotation.")
        }
        validateTerms(enquiry, version.currency, version.validUntil, version.tariffId, requireTariff = true)
        val actor = accessPolicy.currentUser().id
        version.status = QuotationStatus.PENDING_APPROVAL
        version.submittedBy = actor
        version.submittedAt = now()
        versions.saveAndFlush(version)
        workflow.move(enquiry, EnquiryStatus.QUOTATION_APPROVAL, "Quotation v${version.versionNo} submitted for approval", actor)
        status(version, enquiry, QuotationStatus.DRAFT, null)
        return overviewOf(enquiry)
    }

    // ---------- approval ----------

    @Transactional
    fun decide(versionId: UUID, expectedVersion: Long, request: DecisionRequest): QuotationOverviewDto {
        val (version, quotation, enquiry) = lockedContext(versionId, Permissions.QUOTATION_APPROVE, expectedVersion)
        requireStatus(version, QuotationStatus.PENDING_APPROVAL)
        val actor = accessPolicy.currentUser().id
        if (version.submittedBy == actor) {
            throw BusinessRuleException("SELF_APPROVAL_FORBIDDEN", "A quotation cannot be approved or rejected by the person who submitted it.")
        }
        val reason = request.reason.clean()
        if (!request.approve && reason == null) {
            throw BadRequestException("REASON_REQUIRED", "A reason is required for this change.", listOf(FieldError("reason", "required", null)))
        }
        version.status = if (request.approve) QuotationStatus.APPROVED else QuotationStatus.REJECTED
        version.decidedBy = actor
        version.decidedAt = now()
        version.decisionReason = reason
        versions.saveAndFlush(version)
        if (!request.approve) {
            // Back to costing, unless an earlier version is still issued to the client.
            val stillQuoted = versions.findByQuotationIdOrderByVersionNoDesc(quotation.id).any { it.status == QuotationStatus.ISSUED }
            workflow.move(enquiry, if (stillQuoted) EnquiryStatus.QUOTED else EnquiryStatus.COSTING,
                "Quotation v${version.versionNo} rejected", actor)
        }
        status(version, enquiry, QuotationStatus.PENDING_APPROVAL, reason)
        return overviewOf(enquiry)
    }

    // ---------- issue ----------

    @Transactional
    fun issue(versionId: UUID, expectedVersion: Long, request: IssueRequest): QuotationOverviewDto {
        val (version, quotation, enquiry) = lockedContext(versionId, Permissions.QUOTATION_WRITE, expectedVersion)
        if (version.status !in setOf(QuotationStatus.DRAFT, QuotationStatus.PENDING_APPROVAL, QuotationStatus.APPROVED)) {
            throw BusinessRuleException("INVALID_STATE", "Expected a draft or approved quotation version.")
        }
        if (enquiry.clientId == null) {
            throw BusinessRuleException("CLIENT_REQUIRED", "Select a client on the enquiry before issuing a quotation.")
        }
        validateTerms(enquiry, version.currency, version.validUntil, version.tariffId, requireTariff = true)
        val previousStatus = version.status
        val actor = accessPolicy.currentUser().id
        version.status = QuotationStatus.ISSUED
        version.issuedBy = actor
        version.issuedAt = now()
        version.issuedTo = request.issuedTo.trim()
        version.issueChannel = request.channel
        version.issueNote = request.note.clean()
        val bytes = pdf.render(document(version, quotation, enquiry, draft = false))
        version.pdfSha256 = QuotationPdfRenderer.sha256(bytes)
        versions.saveAndFlush(version)
        jdbc.sql("UPDATE quotation_version SET pdf = :pdf WHERE id = :id").param("pdf", bytes).param("id", Uuids.toBytes(versionId)).update()

        versions.findByQuotationIdOrderByVersionNoDesc(quotation.id)
            .filter { it.id != versionId && it.status == QuotationStatus.ISSUED }
            .forEach { older ->
                older.status = QuotationStatus.SUPERSEDED
                versions.saveAndFlush(older)
                status(older, enquiry, QuotationStatus.ISSUED, "Superseded by version ${version.versionNo}")
            }
        workflow.move(enquiry, EnquiryStatus.QUOTED, "Quotation v${version.versionNo} issued", actor)
        status(version, enquiry, previousStatus, null, mapOf("issuedTo" to version.issuedTo, "channel" to request.channel.name,
            "pdfSha256" to version.pdfSha256))
        return overviewOf(enquiry)
    }

    // ---------- owner response ----------

    @Transactional
    fun rejectByOwner(versionId: UUID, expectedVersion: Long): QuotationOverviewDto {
        val (version, _, enquiry) = lockedContext(versionId, Permissions.QUOTATION_WRITE, expectedVersion)
        requireStatus(version, QuotationStatus.ISSUED)
        if (enquiry.status != EnquiryStatus.QUOTED) {
            throw BusinessRuleException("ENQUIRY_LOCKED", "The enquiry is no longer awaiting the owner's response.")
        }
        version.status = QuotationStatus.OWNER_REJECTED
        versions.saveAndFlush(version)
        status(version, enquiry, QuotationStatus.ISSUED, "Owner rejected quotation")
        return overviewOf(enquiry)
    }

    // ---------- owner acceptance → job ----------

    /** Records the owner's acceptance and creates the job. A retry with the same `Idempotency-Key` returns the same result. */
    @Transactional
    fun accept(versionId: UUID, request: AcceptanceRequest, idempotencyKey: String?): QuotationOverviewDto {
        val (_, _, probe) = context(versionId)
        accessPolicy.requireBranch(Permissions.QUOTATION_WRITE, probe.branchId, ENTITY, versionId)
        val actor = accessPolicy.currentUser().id
        idempotency.existing(actor, idempotencyKey, OPERATION_ACCEPT)?.let { return overviewOf(enquiry(probe.id)) }

        val enquiry = workflow.lock(probe.id)
        val version = versions.findByIdOrNull(versionId) ?: throw NotFoundException("Quotation version")
        val quotation = quotations.findByIdOrNull(version.quotationId) ?: throw NotFoundException("Quotation")
        if (version.status == QuotationStatus.ACCEPTED) {
            throw ConflictException("ALREADY_ACCEPTED", "This quotation has already been accepted.")
        }
        requireOpen(enquiry)
        requireStatus(version, QuotationStatus.ISSUED)
        val timeError = when {
            request.acceptedAt.isAfter(now().plus(5, ChronoUnit.MINUTES)) ->
                FieldError("acceptedAt", "future", "The acceptance time cannot be in the future.")
            version.issuedAt?.let { request.acceptedAt.isBefore(it.truncatedTo(ChronoUnit.MINUTES)) } == true ->
                FieldError("acceptedAt", "beforeIssue", "The acceptance time is before the quotation was issued.")
            else -> null
        }
        if (timeError != null) throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", listOf(timeError))
        val tariff = version.tariffId?.let { references.tariffs(listOf(it))[it] }
            ?: throw BusinessRuleException("TARIFF_REQUIRED", "The quotation has no tariff to freeze on the job.")
        val clientId = enquiry.clientId
            ?: throw BusinessRuleException("CLIENT_REQUIRED", "Select a client on the enquiry before creating a job.")
        val zone = zoneOrUtc(company.timezone)
        val acceptedOn = request.acceptedAt.atZone(zone).toLocalDate()

        val job = jobs.create(NewJob(
            branchId = enquiry.branchId,
            branchCode = organisation.branch(enquiry.branchId)?.code,
            year = acceptedOn.year,
            enquiryId = enquiry.id,
            enquiryReference = enquiry.reference,
            quotationVersionId = version.id,
            quotationReference = "${quotation.reference} v${version.versionNo}",
            clientId = clientId,
            clientContactId = enquiry.clientContactId,
            operationType = enquiry.operationType,
            currency = version.currency,
            quotedAmount = version.totalAmount,
            vessels = enquiry.vessels.sortedBy { it.role }.map { NewJobVessel(it.role.name, it.vesselId, it.etaUtc, it.etaTimezone) },
            cargo = enquiry.cargo.sortedBy { it.lineNo }.map { NewJobCargo(it.lineNo, it.cargoGradeId, it.quantity, it.unitId) },
            tariff = tariff,
            jobNumber = request.jobNumber.clean(),
            acceptedOn = acceptedOn,
        ))

        val acceptance = OwnerAcceptanceEntity(
            version.id, job.id, request.acceptedAt.truncatedTo(ChronoUnit.MICROS), request.acceptedByName.trim(),
            request.workOrderReference.clean(), request.paymentTerms.clean(), request.specialConditions.clean(),
            afterValidity = acceptedOn.isAfter(version.validUntil), recordedBy = actor, recordedAt = now(),
        )
        acceptances.saveAndFlush(acceptance)
        version.status = QuotationStatus.ACCEPTED
        versions.saveAndFlush(version)
        workflow.move(enquiry, EnquiryStatus.ACCEPTED, "Owner accepted quotation v${version.versionNo}; job ${job.jobNumber}", actor)
        status(version, enquiry, QuotationStatus.ISSUED, null, mapOf("jobId" to job.id, "jobNumber" to job.jobNumber,
            "acceptedByName" to acceptance.acceptedByName, "afterValidity" to acceptance.afterValidity))
        idempotency.remember(actor, idempotencyKey, OPERATION_ACCEPT, acceptance.id)
        return overviewOf(enquiry)
    }

    // ---------- helpers ----------

    private fun newQuotation(enquiry: EnquiryEntity): QuotationEntity {
        val branch = organisation.branch(enquiry.branchId)
        val year = now().atZone(zoneOrUtc(company.timezone)).year
        val reference = numbers.next(SERIES, NumberContext(branch?.code, year))
        return quotations.saveAndFlush(QuotationEntity(enquiry.id, reference, enquiry.branchId))
    }

    private fun validateTerms(
        enquiry: EnquiryEntity, currency: String, validUntil: LocalDate, tariffId: UUID?,
        requireTariff: Boolean, requireFutureValidity: Boolean = true,
    ) {
        val today = LocalDate.now(clock.withZone(zoneOrUtc(company.timezone)))
        val tariff = tariffId?.let { references.tariffs(listOf(it))[it] }
        val errors = listOfNotNull(
            FieldError("validUntil", "past", "The validity date is in the past.").takeIf { requireFutureValidity && validUntil.isBefore(today) },
            FieldError("tariffId", "required", "Choose the tariff the price is based on.").takeIf { requireTariff && tariffId == null },
            tariffId?.let {
                when {
                    tariff == null || !tariff.active -> FieldError("tariffId", "unknown", "Unknown or inactive tariff.")
                    tariff.currency != currency -> FieldError("tariffId", "currency", "The tariff currency differs from the quotation currency.")
                    requireTariff && !tariff.effectiveOn(today) -> FieldError("tariffId", "period", "The tariff is not in effect today.")
                    else -> null
                }
            },
        )
        if (errors.isNotEmpty()) throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", errors)
    }

    /** Lost, cancelled or accepted enquiries take no further quotation actions. */
    private fun requireOpen(enquiry: EnquiryEntity) {
        if (enquiry.status !in EnquiryLifecycle.OPEN) {
            throw BusinessRuleException("ENQUIRY_LOCKED", "An enquiry in status ${enquiry.status} takes no further quotation actions.")
        }
    }

    private fun requireStatus(version: QuotationVersionEntity, expected: QuotationStatus) {
        if (version.status != expected) {
            throw BusinessRuleException("INVALID_TRANSITION", "A quotation version in status ${version.status} cannot do this.")
        }
    }

    private fun status(
        version: QuotationVersionEntity, enquiry: EnquiryEntity, from: QuotationStatus, reason: String?, extra: Map<String, Any?> = emptyMap(),
    ) = audit.record(AuditEntry(AuditAction.STATUS_CHANGE, ENTITY, version.id, enquiry.branchId,
        mapOf("from" to from.name, "to" to version.status.name, "versionNo" to version.versionNo, "reason" to reason) + extra))

    private data class Context(val version: QuotationVersionEntity, val quotation: QuotationEntity, val enquiry: EnquiryEntity)

    private fun context(versionId: UUID): Context {
        val version = versions.findByIdOrNull(versionId) ?: throw NotFoundException("Quotation version")
        val quotation = quotations.findByIdOrNull(version.quotationId) ?: throw NotFoundException("Quotation")
        return Context(version, quotation, enquiry(quotation.enquiryId))
    }

    /** Checks permission and scope, then locks the enquiry and checks the version the user edited. */
    private fun lockedContext(versionId: UUID, permission: String, expectedVersion: Long, allowAccepted: Boolean = false): Context {
        val (_, quotation, probe) = context(versionId)
        accessPolicy.requireBranch(permission, probe.branchId, ENTITY, versionId)
        val enquiry = workflow.lock(probe.id)
        if (!allowAccepted) requireOpen(enquiry)
        val version = versions.findByIdOrNull(versionId) ?: throw NotFoundException("Quotation version")
        Versioning.checkCurrent(expectedVersion, version.version)
        return Context(version, quotation, enquiry)
    }

    private data class AdvanceSummary(
        val finalAmount: BigDecimal,
        val paid: BigDecimal,
        val remaining: BigDecimal,
        val status: AdvancePaymentStatus,
    )

    private fun advanceSummary(version: QuotationVersionEntity, payments: List<QuotationAdvancePaymentEntity>, minor: Int): AdvanceSummary {
        val finalAmount = version.totalAmount.multiply(BigDecimal("1.18")).setScale(minor, RoundingMode.HALF_UP)
        val paid = payments.fold(BigDecimal.ZERO) { acc, p -> acc + p.amount }.setScale(minor, RoundingMode.HALF_UP)
        val remaining = finalAmount.subtract(paid).max(BigDecimal.ZERO).setScale(minor, RoundingMode.HALF_UP)
        val status = when {
            paid.signum() <= 0 -> AdvancePaymentStatus.UNPAID
            paid >= finalAmount -> AdvancePaymentStatus.PAID
            else -> AdvancePaymentStatus.PARTIAL
        }
        return AdvanceSummary(finalAmount, paid, remaining, status)
    }

    private fun overviewOf(enquiry: EnquiryEntity): QuotationOverviewDto {
        val quotation = quotations.findByEnquiryId(enquiry.id)
        val list = quotation?.let { versions.findByQuotationIdOrderByVersionNoDesc(it.id) }.orEmpty()
        val estimateNos = estimates.findAllById(list.map { it.estimateId }.toSet()).associate { it.id to it.versionNo }
        val tariffs = references.tariffs(list.mapNotNull { it.tariffId })
        val acceptanceByVersion = acceptances.findByQuotationVersionIdIn(list.map { it.id }).associateBy { it.quotationVersionId }
        val paymentsByVersion = if (list.isEmpty()) emptyMap() else advancePayments.findByQuotationVersionIdInOrderByRecordedAtAsc(list.map { it.id })
            .groupBy { it.quotationVersionId }
        val names = users.names(list.flatMap { listOfNotNull(it.submittedBy, it.decidedBy, it.issuedBy) } +
            acceptanceByVersion.values.map { it.recordedBy } +
            paymentsByVersion.values.flatten().map { it.recordedBy })
        val job = jobs.byEnquiries(listOf(enquiry.id))[enquiry.id]
        val user = accessPolicy.currentUser()
        val canWrite = user.scopeFor(Permissions.QUOTATION_WRITE).coversBranch(enquiry.branchId)
        val canEditOpenWindow = canWrite && editWindow.canEdit(enquiry.id)
        val latestIssued = list.firstOrNull { it.status == QuotationStatus.ISSUED }?.id

        fun actions(v: QuotationVersionEntity, summary: AdvanceSummary): List<QuotationAction> = buildList {
            if (canWrite) add(QuotationAction.EDIT)
            when (v.status) {
                QuotationStatus.DRAFT, QuotationStatus.PENDING_APPROVAL, QuotationStatus.APPROVED -> if (canWrite) add(QuotationAction.ISSUE)
                QuotationStatus.ISSUED -> if (canWrite && v.id == latestIssued && enquiry.status == EnquiryStatus.QUOTED) {
                    addAll(listOf(QuotationAction.ACCEPT, QuotationAction.OWNER_REJECT))
                }
                QuotationStatus.ACCEPTED -> if (canEditOpenWindow && summary.remaining.signum() > 0) {
                    add(QuotationAction.RECORD_ADVANCE)
                }
                else -> Unit
            }
        }

        return QuotationOverviewDto(
            enquiryId = enquiry.id,
            enquiryReference = enquiry.reference,
            enquiryStatus = enquiry.status,
            quotationId = quotation?.id,
            reference = quotation?.reference,
            versions = list.map { v ->
                val acceptance = acceptanceByVersion[v.id]
                val payments = paymentsByVersion[v.id].orEmpty()
                val minor = references.currency(v.currency)?.minorUnits ?: 2
                val summary = advanceSummary(v, payments, minor)
                QuotationVersionDto(
                    id = v.id, versionNo = v.versionNo, status = v.status, estimateId = v.estimateId, estimateVersionNo = estimateNos[v.estimateId],
                    currency = v.currency, inrFxRate = v.inrFxRate, totalAmount = v.totalAmount,
                    finalAmount = summary.finalAmount, advancePaid = summary.paid, advanceRemaining = summary.remaining,
                    advanceStatus = summary.status,
                    advancePayments = payments.map { AdvancePaymentDto(it.id, it.amount, it.note, names[it.recordedBy], it.recordedAt) },
                    validUntil = v.validUntil, paymentTerms = v.paymentTerms,
                    advanceReceived = v.advanceReceived, advanceGiven = v.advanceGiven,
                    termsText = v.termsText, scopeOfServices = v.scopeOfServices, tariff = v.tariffId?.let { tariffs[it] }?.toDto(),
                    lines = v.lines.sortedBy { it.lineNo }.map {
                        QuotationLineDto(it.lineNo, it.costHeadCode, it.costHeadName, it.description, it.quantity, it.unitPrice, it.amount, it.extraHours)
                    },
                    submittedByName = v.submittedBy?.let(names::get), submittedAt = v.submittedAt,
                    decidedByName = v.decidedBy?.let(names::get), decidedAt = v.decidedAt, decisionReason = v.decisionReason,
                    issuedByName = v.issuedBy?.let(names::get), issuedAt = v.issuedAt, issuedTo = v.issuedTo, issueChannel = v.issueChannel,
                    issueNote = v.issueNote, pdfSha256 = v.pdfSha256,
                    acceptance = acceptance?.let {
                        AcceptanceDto(it.acceptedAt, it.acceptedByName, it.workOrderReference, it.paymentTerms, it.specialConditions,
                            it.afterValidity, names[it.recordedBy], it.recordedAt, it.jobId, job?.takeIf { j -> j.id == it.jobId }?.jobNumber)
                    },
                    allowedActions = actions(v, summary),
                    updatedAt = v.updatedAt, version = v.version,
                )
            },
            canCreateVersion = canWrite && list.none { it.status in OPEN },
            job = job?.let { JobLinkDto(it.id, it.jobNumber) },
        )
    }

    private fun document(version: QuotationVersionEntity, quotation: QuotationEntity, enquiry: EnquiryEntity, draft: Boolean): QuotationDocument {
        val zone = zoneOrUtc(company.timezone)
        val client = enquiry.clientId?.let { references.clients(listOf(it))[it] }
        val contact = enquiry.clientContactId?.let { references.contacts(listOf(it))[it] }
        val vessels = references.vessels(enquiry.vessels.map { it.vesselId })
        val grades = references.cargoGrades(enquiry.cargo.map { it.cargoGradeId })
        val units = references.units(enquiry.cargo.map { it.unitId })
        val primaryMinor = references.currency(version.currency)?.minorUnits ?: 2
        val tariff = version.tariffId?.let { references.tariffs(listOf(it))[it] }
        val approver = version.decidedBy?.let { users.names(listOf(it))[it] }
        val dateOf = { instant: Instant -> DATE.format(instant.atZone(zone)) }
        val issueDate = (version.issuedAt ?: now()).atZone(zone).toLocalDate()
        val workingDays = generateSequence(issueDate.plusDays(1)) { it.plusDays(1) }
            .takeWhile { !it.isAfter(version.validUntil) }
            .count { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }

        val billableLines = version.lines.filter { it.quantity.signum() > 0 }.sortedBy { it.lineNo }
        val deferredExtraLine = version.lines.firstOrNull { !it.extraHours && it.costHeadCode == "ADD-TARIFF" && it.quantity.signum() == 0 }
        val hasPrebilledExtraHours = billableLines.any { it.extraHours }
        val baseHours = enquiry.expectedDurationHours?.let(QuotationPdfRenderer::quantity) ?: tariff?.baseHours?.toString()
        val recordedPayments = advancePayments.findByQuotationVersionIdOrderByRecordedAtAsc(version.id)
        val advanceSummary = advanceSummary(version, recordedPayments, primaryMinor)
        val hasRecordedAdvance = advanceSummary.paid.signum() > 0

        val effectiveInrRate = if (version.currency == "USD") version.inrFxRate else null
        val effectiveInrRateDate = if (version.currency == "USD") version.inrFxRateDate else null
        val inrMinor = if (effectiveInrRate != null) references.currency("INR")?.minorUnits ?: 2 else 2

        val primaryRate = when {
            deferredExtraLine != null && deferredExtraLine.unitPrice.signum() > 0 ->
                QuotationPdfRenderer.amount(deferredExtraLine.unitPrice, primaryMinor)
            tariff != null -> QuotationPdfRenderer.amount(tariff.extraHourRate, primaryMinor)
            else -> null
        }
        val inrDeferredRate = if (effectiveInrRate != null) when {
            deferredExtraLine != null && deferredExtraLine.unitPrice.signum() > 0 ->
                QuotationPdfRenderer.amount(deferredExtraLine.unitPrice.multiply(effectiveInrRate), inrMinor)
            tariff != null -> QuotationPdfRenderer.amount(tariff.extraHourRate.multiply(effectiveInrRate), inrMinor)
            else -> null
        } else null
        val extraHoursNotice = if (!hasPrebilledExtraHours && (deferredExtraLine != null || tariff != null || baseHours != null)) {
            QuotationDocument.ExtraHoursNotice(baseHours, primaryRate, inrDeferredRate)
        } else null

        val inrSummary = effectiveInrRate?.let { rate ->
            val inrTotal = version.totalAmount.multiply(rate)
            QuotationDocument.InrSummary(
                total = QuotationPdfRenderer.amount(inrTotal, inrMinor),
                gst = QuotationPdfRenderer.amount(inrTotal.multiply(BigDecimal("0.18")), inrMinor),
                grandTotal = QuotationPdfRenderer.amount(inrTotal.multiply(BigDecimal("1.18")), inrMinor),
                amountInWords = QuotationPdfRenderer.amountInWords(inrTotal.multiply(BigDecimal("1.18")), "INR"),
                advanceReceived = version.advanceReceived?.let { QuotationPdfRenderer.amount(it.multiply(rate), inrMinor) },
                advancePaid = if (hasRecordedAdvance) QuotationPdfRenderer.amount(advanceSummary.paid.multiply(rate), inrMinor) else null,
                balancePayable = if (hasRecordedAdvance) QuotationPdfRenderer.amount(advanceSummary.remaining.multiply(rate), inrMinor) else null,
            )
        }

        return QuotationDocument(
            entityName = company.name,
            entityAddress = company.billingAddress?.ifBlank { null },
            taxRegistrationNo = company.taxRegistrationNo?.ifBlank { null },
            reference = quotation.reference,
            versionNo = version.versionNo,
            date = dateOf(version.issuedAt ?: now()),
            validUntil = DATE.format(version.validUntil),
            validFor = "$workingDays working days",
            enquiryReference = enquiry.reference,
            clientName = client?.legalName ?: "",
            clientAddress = client?.billingAddress,
            contactName = contact?.fullName,
            vessels = enquiry.vessels.sortedBy { it.role }.mapNotNull { v ->
                vessels[v.vesselId]?.let { "${if (v.role == VesselRole.MOTHER) "Mother" else "Daughter"}: ${it.name} (IMO ${it.imo})" }
            },
            operationType = enquiry.cargoTypes.sorted().joinToString(", ").ifBlank { null },
            cargo = enquiry.cargo.sortedBy { it.lineNo }.map {
                "${grades[it.cargoGradeId]?.name ?: ""}: ${QuotationPdfRenderer.quantity(it.quantity)} ${units[it.unitId]?.code ?: ""}"
            },
            currency = version.currency,
            lines = billableLines.mapIndexed { index, it ->
                QuotationDocument.Line(
                    no = index + 1,
                    description = listOfNotNull(it.costHeadName, it.description).joinToString(" - "),
                    quantity = QuotationPdfRenderer.quantity(it.quantity),
                    unitPrice = QuotationPdfRenderer.amount(it.unitPrice, primaryMinor),
                    amount = QuotationPdfRenderer.amount(it.amount, primaryMinor),
                    totalWithGst = QuotationPdfRenderer.amount(it.amount.multiply(BigDecimal("1.18")), primaryMinor),
                    inrUnitPrice = effectiveInrRate?.let { r -> QuotationPdfRenderer.amount(it.unitPrice.multiply(r), inrMinor) },
                    inrAmount = effectiveInrRate?.let { r -> QuotationPdfRenderer.amount(it.amount.multiply(r), inrMinor) },
                    inrTotalWithGst = effectiveInrRate?.let { r -> QuotationPdfRenderer.amount(it.amount.multiply(r).multiply(BigDecimal("1.18")), inrMinor) },
                )
            },
            total = QuotationPdfRenderer.amount(version.totalAmount, primaryMinor),
            gst = QuotationPdfRenderer.amount(version.totalAmount.multiply(BigDecimal("0.18")), primaryMinor),
            grandTotal = QuotationPdfRenderer.amount(version.totalAmount.multiply(BigDecimal("1.18")), primaryMinor),
            amountInWords = QuotationPdfRenderer.amountInWords(version.totalAmount.multiply(BigDecimal("1.18")), version.currency),
            fxNote = effectiveInrRate?.let { rate ->
                "Converted from USD at 1 USD = ${rate.stripTrailingZeros().toPlainString()} INR" +
                    (effectiveInrRateDate?.let { " (FX master $it)" } ?: "") + "."
            },
            inr = inrSummary,
            tariff = tariff?.let {
                QuotationDocument.Tariff(it.name, it.baseHours, QuotationPdfRenderer.amount(it.baseCharge, primaryMinor),
                    QuotationPdfRenderer.amount(it.extraHourRate, primaryMinor), pending = it.toDto().termsPending)
            },
            hasPrebilledExtraHours = hasPrebilledExtraHours,
            extraHoursNotice = extraHoursNotice,
            paymentTerms = version.paymentTerms,
            advanceReceived = version.advanceReceived?.let { QuotationPdfRenderer.amount(it, primaryMinor) },
            advanceGiven = version.advanceGiven?.let { QuotationPdfRenderer.amount(it, primaryMinor) },
            advancePaid = if (hasRecordedAdvance) QuotationPdfRenderer.amount(advanceSummary.paid, primaryMinor) else null,
            balancePayable = if (hasRecordedAdvance) QuotationPdfRenderer.amount(advanceSummary.remaining, primaryMinor) else null,
            termsText = version.termsText,
            scopeOfServices = resolveScopeOfServices(version),
            approvedBy = approver,
            approvedAt = version.decidedAt?.let(dateOf),
            draft = draft,
        )
    }

    private fun resolveScopeOfServices(version: QuotationVersionEntity): List<QuotationDocument.ScopeItem> {
        val manual = version.scopeOfServices?.lineSequence()?.map(String::trim)?.filter(String::isNotEmpty)
            ?.mapIndexed { index, line ->
                val parts = line.split('|', limit = 2)
                QuotationDocument.ScopeItem(index + 1, parts[0].trim(), parts.getOrNull(1)?.trim())
            }?.toList()
        if (!manual.isNullOrEmpty()) return manual

        val costLines = estimates.findByIdOrNull(version.estimateId)?.lines
            ?.filter { it.kind == LineKind.COST }
            ?.sortedBy { it.lineNo }
            .orEmpty()
        val heads = references.costHeads(costLines.map { it.costHeadId })
        val fenderLines = costLines.filter { heads[it.costHeadId]?.code == "FENDERS" }
        val fendersItem = if (fenderLines.isEmpty()) {
            QuotationDocument.ScopeItem(3, "Yokohama Fenders", "Primary and secondary pneumatic fenders for the operation.")
        } else {
            val totalFenders = QuotationPdfRenderer.quantity(fenderLines.sumOf { it.quantity })
            val details = fenderLines.joinToString("\n") { line ->
                val label = line.description?.trim()?.ifBlank { null } ?: heads[line.costHeadId]?.name ?: "Yokohama fender"
                "${QuotationPdfRenderer.quantity(line.quantity)} \u00D7 $label"
            }
            QuotationDocument.ScopeItem(3, "Yokohama Fenders \u2013 $totalFenders in total", details)
        }

        return listOf(
            QuotationDocument.ScopeItem(1, "Tug Assistance", "Tugs for mobilisation and demobilisation of fenders, personnel and hoses."),
            QuotationDocument.ScopeItem(2, "Fender & Hose Management", "Mobilisation and demobilisation of fenders and hoses."),
            fendersItem,
            QuotationDocument.ScopeItem(4, "STS Hoses", "Cargo transfer hoses for the operation."),
            QuotationDocument.ScopeItem(5, "Mooring Ropes", "80 m assorted ropes, 64 mm diameter."),
            QuotationDocument.ScopeItem(6, "Personnel", "2 Loading / Mooring Masters overseeing loading, mooring and STS operations."),
            QuotationDocument.ScopeItem(7, "Labour & Transportation", "Labour for setup and maintenance; transport of personnel and equipment."),
            QuotationDocument.ScopeItem(8, "Miscellaneous", "Other incidental expenses related to the operation."),
        )
    }

    private fun storedPdf(versionId: UUID): ByteArray? =
        jdbc.sql("SELECT pdf FROM quotation_version WHERE id = :id").param("id", Uuids.toBytes(versionId))
            .query { rs, _ -> rs.getBytes(1) }.optional().orElse(null)

    private fun refreshPdf(version: QuotationVersionEntity, enquiry: EnquiryEntity) {
        val quotation = quotations.findByIdOrNull(version.quotationId) ?: throw NotFoundException("Quotation")
        val old = jdbc.sql("SELECT pdf FROM quotation_version WHERE id=:id")
            .param("id", Uuids.toBytes(version.id)).query { rs, _ -> rs.getBytes(1) }.optional().orElse(null)
        old?.let { bytes ->
            jdbc.sql("""INSERT INTO quotation_pdf_revision(id,quotation_version_id,pdf,pdf_sha256,replaced_at,replaced_by)
                VALUES (:id,:version,:pdf,:sha,:at,:actor)""")
                .param("id", Uuids.toBytes(UuidV7.generate())).param("version", Uuids.toBytes(version.id))
                .param("pdf", bytes)
                .param("sha", version.pdfSha256 ?: QuotationPdfRenderer.sha256(bytes))
                .param("at", now()).param("actor", Uuids.toBytes(accessPolicy.currentUser().id)).update()
        }
        val bytes = pdf.render(document(version, quotation, enquiry, draft = false))
        version.pdfSha256 = QuotationPdfRenderer.sha256(bytes)
        versions.saveAndFlush(version)
        jdbc.sql("UPDATE quotation_version SET pdf=:pdf WHERE id=:id")
            .param("pdf", bytes).param("id", Uuids.toBytes(version.id)).update()
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private fun enquiry(id: UUID) = enquiries.findByIdOrNull(id) ?: throw NotFoundException("Enquiry")

    private companion object {
        const val ENTITY = "QUOTATION_VERSION"
        const val ENQUIRY = "ENQUIRY"
        const val SERIES = "QUOTATION"
        const val OPERATION_ACCEPT = "QUOTATION.ACCEPT"
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
        val OPEN = setOf(QuotationStatus.DRAFT, QuotationStatus.PENDING_APPROVAL, QuotationStatus.APPROVED)
        val FROZEN = setOf(QuotationStatus.ISSUED, QuotationStatus.SUPERSEDED, QuotationStatus.ACCEPTED, QuotationStatus.OWNER_REJECTED)

        fun String?.clean(): String? = this?.trim()?.ifBlank { null }

        fun zoneOrUtc(zone: String?): ZoneId = try {
            zone?.let(ZoneId::of) ?: ZoneOffset.UTC
        } catch (ex: DateTimeException) {
            ZoneOffset.UTC
        }

        fun TariffRef.toDto() = TariffTermsDto(
            id, code, name, currency, baseHours, baseCharge, extraHourRate, billingIncrementMinutes, graceMinutes, roundingRule,
            effectiveFrom, effectiveTo,
            termsPending = roundingRule == null || graceMinutes == null || (roundingRule == "ROUND_UP_TO_INCREMENT" && billingIncrementMinutes == null),
        )
    }
}
