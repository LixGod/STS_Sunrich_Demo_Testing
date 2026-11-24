package com.ship2ship.features.masters

import com.ship2ship.core.security.DocumentTypeRef
import com.ship2ship.core.security.ClientRef
import com.ship2ship.core.security.CodeRef
import com.ship2ship.core.security.ContactRef
import com.ship2ship.core.security.CostHeadRef
import com.ship2ship.core.security.CurrencyRef
import com.ship2ship.core.security.ReferenceLookup
import com.ship2ship.core.security.TariffRef
import com.ship2ship.core.security.VesselRef
import jakarta.persistence.criteria.CommonAbstractCriteria
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Expression
import jakarta.persistence.criteria.Predicate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Gives other features read-only access to commercial reference data without exposing masters' entities. */
@Component
class MasterReferenceLookup(
    private val clients: ClientRepository,
    private val contacts: ClientContactRepository,
    private val vessels: VesselRepository,
    private val cargoGrades: CargoGradeRepository,
    private val units: UnitOfMeasureRepository,
    private val costHeads: CostHeadRepository,
    private val currencies: CurrencyRepository,
    private val tariffs: TariffRepository,
    private val documentTypes: DocumentTypeRepository,
) : ReferenceLookup {

    @Transactional(readOnly = true)
    override fun clients(ids: Collection<UUID>): Map<UUID, ClientRef> =
        byIds(ids) { clients.findAllById(it).associate { c -> c.id to ClientRef(c.id, c.code, c.legalName, c.active, c.billingAddress, c.defaultCurrency) } }

    @Transactional(readOnly = true)
    override fun contacts(ids: Collection<UUID>): Map<UUID, ContactRef> =
        byIds(ids) { contacts.findAllById(it).associate { c -> c.id to ContactRef(c.id, c.clientId, c.fullName, c.email, c.active) } }

    @Transactional(readOnly = true)
    override fun vessels(ids: Collection<UUID>): Map<UUID, VesselRef> =
        byIds(ids) { vessels.findAllById(it).associate { v -> v.id to VesselRef(v.id, v.imo, v.name, v.active) } }

    @Transactional(readOnly = true)
    override fun cargoGrades(ids: Collection<UUID>): Map<UUID, CodeRef> =
        byIds(ids) { cargoGrades.findAllById(it).associate { g -> g.id to CodeRef(g.id, g.code, g.name, g.active) } }

    @Transactional(readOnly = true)
    override fun units(ids: Collection<UUID>): Map<UUID, CodeRef> =
        byIds(ids) { units.findAllById(it).associate { u -> u.id to CodeRef(u.id, u.code, u.name, u.active) } }

    @Transactional(readOnly = true)
    override fun costHeads(ids: Collection<UUID>): Map<UUID, CostHeadRef> =
        byIds(ids) { costHeads.findAllById(it).associate { h -> h.id to CostHeadRef(h.id, h.code, h.name, h.kind.name, h.active) } }

    @Transactional(readOnly = true)
    override fun currency(code: String): CurrencyRef? =
        currencies.findByCode(code)?.let { CurrencyRef(it.code, it.name, it.minorUnits, it.active) }

    @Transactional(readOnly = true)
    override fun tariffs(ids: Collection<UUID>): Map<UUID, TariffRef> =
        byIds(ids) {
            tariffs.findAllById(it).associate { t ->
                t.id to TariffRef(
                    t.id, t.code, t.name, t.currency, t.baseHours, t.baseCharge, t.extraHourRate,
                    t.billingIncrementMinutes, t.graceMinutes, t.roundingRule?.name, t.effectiveFrom, t.effectiveTo, t.active,
                )
            }
        }

    @Transactional(readOnly = true)
    override fun documentTypes(ids: Collection<UUID>): Map<UUID, DocumentTypeRef> =
        byIds(ids) { documentTypes.findAllById(it).associate { d -> d.id to DocumentTypeRef(d.id, d.code, d.name, d.appliesTo.name, d.active) } }

    override fun clientMatches(clientId: Expression<UUID>, query: CommonAbstractCriteria, cb: CriteriaBuilder, text: String): Predicate {
        val like = "%${text.trim().lowercase()}%"
        val sub = query.subquery(UUID::class.java)
        val c = sub.from(ClientEntity::class.java)
        sub.select(c.get("id")).where(cb.or(cb.like(cb.lower(c.get("code")), like), cb.like(cb.lower(c.get("legalName")), like)))
        return clientId.`in`(sub)
    }

    override fun vesselMatches(vesselId: Expression<UUID>, query: CommonAbstractCriteria, cb: CriteriaBuilder, text: String): Predicate {
        val like = "%${text.trim().lowercase()}%"
        val sub = query.subquery(UUID::class.java)
        val v = sub.from(VesselEntity::class.java)
        sub.select(v.get("id")).where(cb.or(cb.like(v.get("imo"), "%${text.trim()}%"), cb.like(cb.lower(v.get("name")), like),
            cb.like(cb.lower(v.get("formerNames")), like)))
        return vesselId.`in`(sub)
    }

    private fun <T> byIds(ids: Collection<UUID>, load: (Set<UUID>) -> Map<UUID, T>): Map<UUID, T> =
        if (ids.isEmpty()) emptyMap() else load(ids.toSet())
}
