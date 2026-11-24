package com.ship2ship.features.finance

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.movements.ChargeClockLookup
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.web.BusinessRuleException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Clock
import java.util.HexFormat
import java.util.UUID

data class ExtraHourInvoiceInput(val costHeadCode:String)

@Service
class ExtraHourInvoices(private val jdbc:JdbcClient,private val access:FinanceAccess,private val clocks:ChargeClockLookup,
    private val invoices:InvoiceService,private val json:JsonMapper,private val audit:AuditService,private val clock:Clock) {
    @Transactional
    fun create(jobId:UUID,input:ExtraHourInvoiceInput):InvoiceDto {
        val job=access.job(jobId,Permissions.FIN_INVOICE_WRITE)
        val actual=clocks.forJob(jobId)
        if(!actual.final || actual.tariffTermsStatus!="CONFIRMED" || actual.additionalCharge==null)
            throw BusinessRuleException("TARIFF_TERMS_PENDING","Finalize the clock and confirm the accepted tariff terms first.")
        val charged=charged(jobId)
        val sources=jdbc.sql("""SELECT i.id,i.status,i.version FROM invoice i WHERE i.id IN (SELECT invoice_id FROM job_extra_invoice WHERE job_id=:job)
            OR i.credited_invoice_id IN (SELECT invoice_id FROM job_extra_invoice WHERE job_id=:job) ORDER BY i.id""")
            .param("job",jobId.bytes()).query { rs,_->"${rs.getBytes(1).uuid()}:${rs.getString(2)}:${rs.getLong(3)}" }.list()
        val snapshot=json.writeValueAsString(mapOf("clock" to actual,"billingSources" to sources))
        val fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(snapshot.toByteArray(Charsets.UTF_8)))
        val existing=jdbc.sql("SELECT invoice_id FROM job_extra_invoice WHERE job_id=:job AND fingerprint=:fingerprint")
            .param("job",jobId.bytes()).param("fingerprint",fingerprint).query { rs,_->rs.getBytes(1).uuid()!! }.optional().orElse(null)
        if(existing!=null) return invoices.get(existing)
        val pending=jdbc.sql("SELECT e.invoice_id FROM job_extra_invoice e JOIN invoice i ON i.id=e.invoice_id WHERE e.job_id=:job AND i.status<>'ISSUED' ORDER BY e.created_at LIMIT 1")
            .param("job",jobId.bytes()).query { rs,_->rs.getBytes(1).uuid()!! }.optional().orElse(null)
        if(pending!=null) return invoices.get(pending)
        val quoted = jdbc.sql("""SELECT COALESCE(SUM(l.amount),0) FROM quotation_line l
            JOIN sts_job j ON j.quotation_version_id=l.quotation_version_id WHERE j.id=:job AND l.extra_hours=TRUE
            AND NOT EXISTS (SELECT 1 FROM invoice i WHERE i.job_id=j.id AND i.doc_type='INVOICE' AND i.status='ISSUED'
                AND i.id NOT IN (SELECT invoice_id FROM job_extra_invoice WHERE job_id=j.id))""")
            .param("job",jobId.bytes()).query(BigDecimal::class.java).single()
        val delta=actual.additionalCharge-charged-quoted
        if(delta.signum()<=0) throw BusinessRuleException("EXTRA_CHARGE_RECONCILED",if(delta.signum()==0)
            "The confirmed extra charge is already covered by the quotation or issued invoices." else "Reduce the quoted extra hours or issue a credit note to reconcile the reduced charge.")
        val draft=invoices.create(jobId,NewInvoiceRequest(false))
        val updated=invoices.update(draft.id,draft.version,InvoiceInput(
            invoiceDate=draft.invoiceDate,dueDate=draft.dueDate,billingName=draft.billingName,billingAddress=draft.billingAddress,
            paymentTerms=draft.paymentTerms,notes="Confirmed additional-hours charge. ${actual.billableExtraUnits} billable hours; already quoted or invoiced net ${charged+quoted}.",
            lines=listOf(InvoiceLineInput(source="MANUAL",costHeadCode=input.costHeadCode,description="Additional-hours charge from the finalized job clock",quantity=BigDecimal.ONE,unitPrice=delta))))
        jdbc.sql("INSERT INTO job_extra_invoice(job_id,invoice_id,fingerprint,clock_snapshot,created_at,created_by) VALUES(:job,:invoice,:fingerprint,:snapshot,:now,:actor)")
            .param("job",jobId.bytes()).param("invoice",draft.id.bytes()).param("fingerprint",fingerprint).param("snapshot",snapshot)
            .param("now",Uuids.toTimestamp(clock.instant())).param("actor",access.actor().bytes()).update()
        audit.record(AuditEntry(AuditAction.CREATE,"EXTRA_HOUR_INVOICE",draft.id,job.branchId,
            mapOf("jobId" to jobId,"delta" to delta,"clock" to actual,"previousNet" to charged,"quotedNet" to quoted)))
        return updated
    }

    internal fun charged(jobId:UUID):BigDecimal = jdbc.sql("""SELECT
        COALESCE((SELECT SUM(i.net_amount) FROM job_extra_invoice e JOIN invoice i ON i.id=e.invoice_id WHERE e.job_id=:job AND i.status='ISSUED'),0)
        -COALESCE((SELECT SUM(c.net_amount) FROM job_extra_invoice e JOIN invoice c ON c.credited_invoice_id=e.invoice_id WHERE e.job_id=:job AND c.status='ISSUED'),0)
        +COALESCE((SELECT SUM(l.amount) FROM invoice i JOIN invoice_line l ON l.invoice_id=i.id
            WHERE i.job_id=:job AND i.doc_type='INVOICE' AND i.status='ISSUED' AND l.extra_hours=TRUE
            AND i.id NOT IN (SELECT invoice_id FROM job_extra_invoice WHERE job_id=:job)),0)
        -COALESCE((SELECT SUM(l.amount) FROM invoice c JOIN invoice_line l ON l.invoice_id=c.id
            JOIN invoice i ON i.id=c.credited_invoice_id WHERE i.job_id=:job AND i.doc_type='INVOICE'
            AND c.status='ISSUED' AND l.extra_hours=TRUE),0)""")
        .param("job",jobId.bytes()).query(BigDecimal::class.java).single()
}

@RestController
class ExtraHourInvoiceController(private val service:ExtraHourInvoices) {
    @PostMapping("/api/v1/jobs/{jobId}/invoices/extra-hours")
    fun create(@PathVariable jobId:UUID,@RequestBody input:ExtraHourInvoiceInput)=service.create(jobId,input)
}
