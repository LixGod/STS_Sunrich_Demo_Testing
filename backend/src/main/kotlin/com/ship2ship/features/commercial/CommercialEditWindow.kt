package com.ship2ship.features.commercial

import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.web.BusinessRuleException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class CommercialEditWindow(private val jdbc: JdbcClient) {
    fun canEdit(enquiryId: UUID): Boolean = issuedFinalInvoiceCount(enquiryId) == 0L

    /** Lock the job so a concurrent final-invoice issue cannot cross this edit. */
    fun requireOpen(enquiryId: UUID) {
        jdbc.sql("SELECT id FROM sts_job WHERE enquiry_id=:id FOR UPDATE").param("id", Uuids.toBytes(enquiryId))
            .query { rs, _ -> rs.getBytes(1) }.optional()
        if (!canEdit(enquiryId)) throw BusinessRuleException("FINAL_INVOICE_ISSUED", "Commercial details cannot change after the final invoice is issued.")
    }

    private fun issuedFinalInvoiceCount(enquiryId: UUID): Long = jdbc.sql("""SELECT COUNT(*) FROM invoice i
        JOIN sts_job j ON j.id=i.job_id WHERE j.enquiry_id=:id AND i.doc_type='INVOICE' AND i.status='ISSUED'
        AND i.id NOT IN (SELECT invoice_id FROM job_extra_invoice WHERE job_id=j.id)""")
        .param("id", Uuids.toBytes(enquiryId)).query(Long::class.java).single()
}
