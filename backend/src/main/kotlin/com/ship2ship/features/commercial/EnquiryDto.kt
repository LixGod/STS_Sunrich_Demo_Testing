package com.ship2ship.features.commercial

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

data class EnquiryVesselRequest(
    @field:NotNull val vesselId: UUID,
    /** Tentative ETA as wall-clock time in [etaTimezone] (defaults to the branch's zone). */
    val etaLocal: LocalDateTime? = null,
    @field:Size(max = 64) val etaTimezone: String? = null,
)

data class EnquiryCargoRequest(
    @field:NotNull val cargoGradeId: UUID,
    @field:NotNull @field:DecimalMin(value = "0", inclusive = false) @field:Digits(integer = 15, fraction = 3) val quantity: BigDecimal,
    @field:NotNull val unitId: UUID,
)

data class EnquirySpecialRequirementRequest(
    @field:NotNull @field:Size(min = 1, max = 300) val description: String,
    val paid: Boolean = false,
    @field:DecimalMin(value = "0", inclusive = false) @field:Digits(integer = 15, fraction = 4) val salePriceUsd: BigDecimal? = null,
)

data class EnquirySpecialRequirementDto(val lineNo: Int, val description: String, val paid: Boolean, val salePriceUsd: BigDecimal?)

data class EnquiryRequest(
    @field:NotNull val branchId: UUID,
    val receivedAt: Instant? = null,
    val clientId: UUID? = null,
    val clientContactId: UUID? = null,
    @field:Size(max = 20) val operationType: String? = null,
    @field:Size(max = 20) val cargoTypes: List<String>? = null,
    @field:DecimalMin(value = "0", inclusive = false) @field:Digits(integer = 5, fraction = 1) val expectedDurationHours: BigDecimal? = null,
    @field:Size(max = 4000) val scopeOfServices: String? = null,
    @field:Size(max = 4000) val specialRequirements: String? = null,
    @field:Size(max = 255) val sourceReference: String? = null,
    @field:Valid val motherVessel: EnquiryVesselRequest? = null,
    @field:Valid val daughterVessel: EnquiryVesselRequest? = null,
    @field:Valid @field:Size(max = 10, message = "Add at most 10 cargo grades.") val cargo: List<EnquiryCargoRequest> = emptyList(),
    @field:Valid @field:Size(max = 20) val specialItems: List<EnquirySpecialRequirementRequest> = emptyList(),
)

data class TransitionRequest(
    @field:NotNull val to: EnquiryStatus,
    @field:Size(max = 500) val reason: String? = null,
)

data class NamedRef(val id: UUID, val code: String?, val name: String)

data class EnquiryVesselDto(
    val role: VesselRole,
    val vesselId: UUID,
    val imo: String?,
    val name: String?,
    val etaUtc: Instant?,
    val etaTimezone: String?,
    val etaLocal: LocalDateTime?,
)

data class EnquiryCargoDto(
    val lineNo: Int,
    val cargoGradeId: UUID,
    val cargoGradeCode: String?,
    val cargoGradeName: String?,
    val quantity: BigDecimal,
    val unitId: UUID,
    val unitCode: String?,
)

data class TransitionDto(val fromStatus: String?, val toStatus: String, val reason: String?, val occurredAt: Instant)

data class EnquiryDetailDto(
    val id: UUID,
    val reference: String,
    val branchId: UUID,
    val branchCode: String?,
    val status: EnquiryStatus,
    val allowedTransitions: List<EnquiryStatus>,
    val editable: Boolean,
    val receivedAt: Instant?,
    val client: NamedRef?,
    val contact: NamedRef?,
    val operationType: String?,
    val cargoTypes: List<String>,
    val expectedDurationHours: BigDecimal?,
    val scopeOfServices: String?,
    val specialRequirements: String?,
    val sourceReference: String?,
    val motherVessel: EnquiryVesselDto?,
    val daughterVessel: EnquiryVesselDto?,
    val cargo: List<EnquiryCargoDto>,
    val specialItems: List<EnquirySpecialRequirementDto>,
    val history: List<TransitionDto>,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val version: Long?,
)

data class EnquirySummaryDto(
    val id: UUID,
    val reference: String,
    val status: EnquiryStatus,
    val receivedAt: Instant?,
    val branchCode: String?,
    val clientName: String?,
    val motherVesselName: String?,
    val daughterVesselName: String?,
    val cargoGrades: List<String>,
    val jobNumber: String? = null,
    val clientStatus: String? = null,
    val estimatedCost: BigDecimal? = null,
    val estimatedCurrency: String? = null,
    val jobCurrency: String? = null,
    val fdaStatus: String? = null,
    val expenses: BigDecimal? = null,
    val grossProfit: BigDecimal? = null,
    val grossProfitPercent: BigDecimal? = null,
    val balanceReceivable: BigDecimal? = null,
)
