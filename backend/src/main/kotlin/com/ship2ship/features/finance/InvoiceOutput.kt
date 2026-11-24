package com.ship2ship.features.finance

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import org.springframework.stereotype.Component
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Everything printed on an invoice or credit note, as display text. Bank details and legal text wait for owner question 4. */
data class InvoiceDocument(
    val title: String,
    val number: String,
    val issuer: String,
    val issuerAddress: String?,
    val billingName: String,
    val billingAddress: String?,
    val facts: List<Pair<String, String>>,
    val lines: List<List<String>>,
    val net: String,
    val tax: String,
    val gross: String,
    val paymentTerms: String?,
    val notes: String?,
    val draft: Boolean,
    val bill: BillSummary,
) {
    companion object {
        private const val DASH = "—"

        internal fun build(
            r: InvoiceService.Row, issuer: String, issuerAddress: String?, jobNumber: String, workOrder: String?, creditedNumber: String?,
            minorUnits: Int, draft: Boolean, bill: BillSummary,
        ): InvoiceDocument {
            val day = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
            fun money(v: BigDecimal) = "${r.currency} ${v.setScale(minorUnits, RoundingMode.HALF_UP).toPlainString()}"
            val credit = r.docType == "CREDIT_NOTE"
            return InvoiceDocument(
                title = if (credit) "Credit note" else "Invoice",
                number = r.number ?: "Draft — number assigned at issue",
                issuer = issuer, issuerAddress = issuerAddress, billingName = r.billingName, billingAddress = r.billingAddress,
                facts = listOfNotNull(
                    "Date" to day.format(r.invoiceDate),
                    r.dueDate?.let { "Due date" to day.format(it) },
                    "Job" to jobNumber,
                    workOrder?.let { "Work order" to it },
                    creditedNumber?.let { "Credits invoice" to it },
                    "Currency" to r.currency,
                ),
                lines = r.lines.map { l ->
                    listOf(listOfNotNull(l.costHeadName, l.description).joinToString(" — "), l.quantity.toPlainString(), l.unitPrice.toPlainString(),
                        l.amount.toPlainString(), l.taxCode?.let { "$it (${l.taxRate?.toPlainString()}%)" } ?: DASH, l.taxAmount.toPlainString())
                },
                net = money(r.net), tax = money(r.tax), gross = money(r.gross),
                paymentTerms = r.paymentTerms, notes = r.notes, draft = draft, bill = bill,
            )
        }
    }
}

/** Renders invoices and credit notes on the server (openhtmltopdf + Thymeleaf XHTML), as for the other documents. */
@Component
class InvoiceOutput {
    private val engine = TemplateEngine().apply {
        setTemplateResolver(ClassLoaderTemplateResolver().apply {
            prefix = "templates/pdf/"
            suffix = ".xhtml"
            templateMode = TemplateMode.XML
            characterEncoding = "UTF-8"
            isCacheable = true
        })
    }

    fun pdf(document: InvoiceDocument): ByteArray = render("invoice", document)

    /** Any finance document template in `templates/pdf/`, given its display data as `d`. */
    fun render(template: String, document: Any): ByteArray {
        val html = engine.process(template, Context(Locale.ENGLISH, mapOf("d" to document)))
        val out = ByteArrayOutputStream()
        PdfRendererBuilder().useFastMode().withHtmlContent(html, null).toStream(out).run()
        return out.toByteArray()
    }
}
