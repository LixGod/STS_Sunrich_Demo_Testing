package com.ship2ship.core.jobs

import com.ship2ship.core.security.TariffRef
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class NewJobVessel(val role: String, val vesselId: UUID, val etaUtc: Instant?, val etaTimezone: String?)

data class NewJobCargo(val lineNo: Int, val cargoGradeId: UUID, val quantity: BigDecimal, val unitId: UUID)

/** Everything a job starts with, copied from the accepted enquiry and quotation (FR-J03). */
data class NewJob(
    val branchId: UUID,
    val branchCode: String?,
    val year: Int,
    val enquiryId: UUID,
    val enquiryReference: String,
    val quotationVersionId: UUID,
    val quotationReference: String,
    val clientId: UUID,
    val clientContactId: UUID?,
    val operationType: String?,
    val currency: String,
    val quotedAmount: BigDecimal,
    val vessels: List<NewJobVessel>,
    val cargo: List<NewJobCargo>,
    /** The quoted tariff, frozen on the job (AR-02). */
    val tariff: TariffRef,
    val jobNumber: String? = null,
    val acceptedOn: LocalDate = LocalDate.of(year, 1, 1),
)

data class JobRef(val id: UUID, val jobNumber: String)

data class CommercialJobUpdate(val clientId: UUID, val clientContactId: UUID?, val operationType: String?,
    val quotedAmount: BigDecimal, val vessels: List<NewJobVessel>, val cargo: List<NewJobCargo>, val tariff: TariffRef?)

/**
 * Creates STS jobs; implemented by the jobs feature so commercial code never touches job tables. Must be called inside
 * the caller's transaction, so acceptance and job creation commit or fail together.
 */
interface JobRegistry {
    fun create(job: NewJob): JobRef

    fun syncCommercial(jobId: UUID, update: CommercialJobUpdate)

    fun byEnquiries(enquiryIds: Collection<UUID>): Map<UUID, JobRef>
}

data class JobTechnicalRef(
    val id: UUID,
    val jobNumber: String,
    val branchId: UUID,
    val vessels: List<JobTechnicalVesselRef>,
    val operationType: String? = null,
    val cargo: List<NewJobCargo> = emptyList(),
)

data class JobTechnicalVesselRef(
    val id: UUID, val role: String, val vesselId: UUID, val etaUtc: Instant? = null, val etaTimezone: String? = null,
)

/** Job facts exposed to technical code without importing the jobs feature. */
interface JobLookup {
    fun technical(id: UUID): JobTechnicalRef?
    /** Lock the job before a mutation, serialising it with closure or cancellation. */
    fun requireWritable(id: UUID)
}

/** Finance's closure check; the workflow checks job scope before calling this trusted internal read. */
fun interface JobFinancialClosure {
    fun reconciled(jobId: UUID): Boolean
}
