package com.ship2ship.core.security

import jakarta.persistence.criteria.CommonAbstractCriteria
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Expression
import jakarta.persistence.criteria.Predicate
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class ClientRef(
    val id: UUID, val code: String, val legalName: String, val active: Boolean,
    val billingAddress: String? = null, val defaultCurrency: String? = null,
)

data class ContactRef(val id: UUID, val clientId: UUID, val fullName: String, val email: String?, val active: Boolean)

data class VesselRef(val id: UUID, val imo: String, val name: String, val active: Boolean)

data class CodeRef(val id: UUID, val code: String, val name: String, val active: Boolean)

/** A cost head with its kind: EXPENSE, REVENUE or BOTH. */
data class CostHeadRef(val id: UUID, val code: String, val name: String, val kind: String, val active: Boolean)

data class DocumentTypeRef(val id: UUID, val code: String, val name: String, val appliesTo: String, val active: Boolean)

data class CurrencyRef(val code: String, val name: String, val minorUnits: Int, val active: Boolean)

/** A tariff version's charging terms (D05). Null increment, grace or rounding = still pending confirmation. */
data class TariffRef(
    val id: UUID, val code: String, val name: String, val currency: String,
    val baseHours: Int, val baseCharge: BigDecimal, val extraHourRate: BigDecimal,
    val billingIncrementMinutes: Int?, val graceMinutes: Int?, val roundingRule: String?,
    val effectiveFrom: LocalDate, val effectiveTo: LocalDate?, val active: Boolean,
) {
    fun effectiveOn(date: LocalDate): Boolean = !date.isBefore(effectiveFrom) && (effectiveTo == null || !date.isAfter(effectiveTo))
}

/**
 * Commercial reference data (clients, vessels, cargo grades and units) for features other than masters.
 * Batch methods keep list screens to a fixed number of queries per page.
 */
interface ReferenceLookup {
    fun clients(ids: Collection<UUID>): Map<UUID, ClientRef>

    fun contacts(ids: Collection<UUID>): Map<UUID, ContactRef>

    fun vessels(ids: Collection<UUID>): Map<UUID, VesselRef>

    fun cargoGrades(ids: Collection<UUID>): Map<UUID, CodeRef>

    fun units(ids: Collection<UUID>): Map<UUID, CodeRef>

    fun costHeads(ids: Collection<UUID>): Map<UUID, CostHeadRef>

    fun currency(code: String): CurrencyRef?

    fun tariffs(ids: Collection<UUID>): Map<UUID, TariffRef>

    fun documentTypes(ids: Collection<UUID>): Map<UUID, DocumentTypeRef>

    /**
     * A criteria predicate for free-text search in other features: [clientId] is a client whose code or name contains
     * the text (case-insensitive). It is a subquery, so every match counts, however many clients match.
     */
    fun clientMatches(clientId: Expression<UUID>, query: CommonAbstractCriteria, cb: CriteriaBuilder, text: String): Predicate

    /** As [clientMatches], for a vessel whose name, former name or IMO contains the text. */
    fun vesselMatches(vesselId: Expression<UUID>, query: CommonAbstractCriteria, cb: CriteriaBuilder, text: String): Predicate
}

/** An active equipment model offered on estimate lines, with its category cost head, optional rate and free units now. */
data class EquipmentOptionRef(
    val modelId: UUID, val name: String, val categoryLabel: String, val costHeadId: UUID?,
    val standardRate: BigDecimal?, val standardRateCurrency: String?, val available: Int,
    val standardRateUsd: BigDecimal? = null,
)

/** Equipment stock for features other than resources; callers check their own permission. */
interface EquipmentLookup {
    fun equipmentOptions(): List<EquipmentOptionRef>
}
