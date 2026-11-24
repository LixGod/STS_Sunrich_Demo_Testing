package com.ship2ship.features.commercial

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.persistence.StatusHistory
import com.ship2ship.core.web.NotFoundException
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Status changes of an enquiry caused by its estimate and quotation (e.g. submitting a quotation moves the enquiry to
 * QUOTATION_APPROVAL). Recorded in the history and audit trail like manual changes, with the triggering action as reason.
 */
@Component
class EnquiryWorkflow(
    private val enquiries: EnquiryRepository,
    private val history: StatusHistory,
    private val audit: AuditService,
    private val entityManager: EntityManager,
) {

    /** The enquiry, locked for the rest of the transaction so concurrent quotation actions are serialised. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lock(enquiryId: UUID): EnquiryEntity {
        val enquiry = enquiries.findById(enquiryId).orElseThrow { NotFoundException("Enquiry") }
        entityManager.lock(enquiry, LockModeType.PESSIMISTIC_WRITE)
        return enquiry
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun move(enquiry: EnquiryEntity, to: EnquiryStatus, reason: String, actorUserId: UUID) {
        if (enquiry.status == to) return
        EnquiryLifecycle.WORKFLOW.check(enquiry.status, to, reason)
        val from = enquiry.status
        enquiry.status = to
        enquiries.saveAndFlush(enquiry)
        history.record(ENTITY, enquiry.id, from.name, to.name, reason, actorUserId)
        audit.record(AuditEntry(AuditAction.STATUS_CHANGE, ENTITY, enquiry.id, enquiry.branchId,
            mapOf("from" to from.name, "to" to to.name, "reason" to reason)))
    }

    private companion object {
        const val ENTITY = "ENQUIRY"
    }
}
