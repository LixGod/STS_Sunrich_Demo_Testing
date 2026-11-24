package com.ship2ship.features.masters

import com.ship2ship.core.security.BranchRef
import com.ship2ship.core.security.OrganisationLookup
import org.springframework.data.repository.findByIdOrNull
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.FieldError
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.DateTimeException
import java.time.ZoneId
import java.util.Currency
import java.util.UUID

/** Field checks that need more than Bean Validation (IANA zones, ISO 4217 currencies). */
internal object OrganisationValidation {

    fun check(vararg errors: FieldError?) {
        val found = errors.filterNotNull()
        if (found.isNotEmpty()) throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", found)
    }

    fun timezone(field: String, value: String): FieldError? = try {
        // Region IDs only (e.g. "Asia/Kolkata"); fixed offsets would ignore daylight-saving rules.
        if (ZoneId.of(value).id.contains('/')) null else FieldError(field, "timezone", "Use an IANA region such as Asia/Kolkata.")
    } catch (ex: DateTimeException) {
        FieldError(field, "timezone", "Unknown time zone.")
    }

    fun currency(field: String, value: String): FieldError? = try {
        Currency.getInstance(value)
        null
    } catch (ex: IllegalArgumentException) {
        FieldError(field, "currency", "Unknown ISO 4217 currency code.")
    }

    fun reasonForDeactivation(active: Boolean, reason: String?): FieldError? = null
}

@Component
class OrganisationLookupImpl(private val branches: BranchRepository) : OrganisationLookup {

    @Transactional(readOnly = true)
    override fun branch(branchId: UUID): BranchRef? = branches.findByIdOrNull(branchId)?.toRef()

    @Transactional(readOnly = true)
    override fun branches(ids: Collection<UUID>): Map<UUID, BranchRef> =
        if (ids.isEmpty()) emptyMap() else branches.findAllById(ids.toSet()).associate { it.id to it.toRef() }

    private fun BranchEntity.toRef() = BranchRef(id, code, timezone, active)
}
