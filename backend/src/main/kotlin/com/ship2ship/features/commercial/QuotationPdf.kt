package com.ship2ship.features.commercial

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import com.ship2ship.core.web.BadRequestException
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.springframework.stereotype.Component
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.HexFormat
import java.util.Locale
import java.util.Base64
import javax.imageio.ImageIO

/** Everything printed on a quotation, already resolved to display text. */
data class QuotationDocument(
    val entityName: String,
    val entityAddress: String?,
    val taxRegistrationNo: String?,
    val reference: String,
    val versionNo: Int,
    val date: String,
    val validUntil: String,
    val validFor: String,
    val enquiryReference: String,
    val clientName: String,
    val clientAddress: String?,
    val contactName: String?,
    val vessels: List<String>,
    val operationType: String?,
    val cargo: List<String>,
    val currency: String,
    val lines: List<Line>,
    val total: String,
    val gst: String,
    val grandTotal: String,
    val amountInWords: String,
    val fxNote: String?,
    val inr: InrSummary? = null,
    val tariff: Tariff?,
    val hasPrebilledExtraHours: Boolean = false,
    val extraHoursNotice: ExtraHoursNotice? = tariff?.let { ExtraHoursNotice(it.baseHours.toString(), it.extraHourRate) },
    val paymentTerms: String?,
    val advanceReceived: String?,
    val advanceGiven: String?,
    val advancePaid: String? = null,
    val balancePayable: String? = null,
    val termsText: String?,
    val scopeOfServices: List<ScopeItem>,
    val approvedBy: String?,
    val approvedAt: String?,
    /** Printed across every page until the version is issued. */
    val draft: Boolean,
) {
    data class Line(
        val no: Int,
        val description: String,
        val quantity: String,
        val unitPrice: String,
        val amount: String,
        val totalWithGst: String,
        val inrUnitPrice: String? = null,
        val inrAmount: String? = null,
        val inrTotalWithGst: String? = null,
    )

    data class InrSummary(
        val total: String,
        val gst: String,
        val grandTotal: String,
        val amountInWords: String,
        val advanceReceived: String? = null,
        val advancePaid: String? = null,
        val balancePayable: String? = null,
    )

    data class ScopeItem(val no: Int, val title: String, val detail: String?)

    data class Tariff(val name: String, val baseHours: Int, val baseCharge: String, val extraHourRate: String, val pending: Boolean)

    data class ExtraHoursNotice(val baseHours: String?, val rate: String?, val inrRate: String? = null)
}

/**
 * Renders the quotation PDF on the server (openhtmltopdf + a Thymeleaf XHTML template), so the issued document does not
 * depend on any browser. Uses the built-in PDF fonts; amounts are printed with the currency code, not a symbol.
 */
@Component
class QuotationPdfRenderer {

    private val logo = "data:image/png;base64," + Base64.getEncoder().encodeToString(
        requireNotNull(javaClass.classLoader.getResourceAsStream("templates/pdf/s2s-logo.png")) { "Quotation logo is missing" }.use { it.readBytes() }
    )

    private val engine = TemplateEngine().apply {
        setTemplateResolver(ClassLoaderTemplateResolver().apply {
            prefix = "templates/pdf/"
            suffix = ".xhtml"
            templateMode = TemplateMode.XML
            characterEncoding = "UTF-8"
            isCacheable = true
        })
    }

    fun render(document: QuotationDocument): ByteArray {
        val html = engine.process("quotation", Context(Locale.ENGLISH, mapOf("q" to document, "logo" to logo)))
        val out = ByteArrayOutputStream()
        PdfRendererBuilder().useFastMode().withHtmlContent(html, null).toStream(out).run()
        return out.toByteArray()
    }

    fun pageCount(bytes: ByteArray): Int = Loader.loadPDF(bytes).use { it.numberOfPages }

    fun previewPage(bytes: ByteArray, page: Int): ByteArray = Loader.loadPDF(bytes).use { document ->
        if (page !in 1..document.numberOfPages) throw BadRequestException("PAGE_OUT_OF_RANGE", "Choose a page in this quotation.")
        val image = PDFRenderer(document).renderImageWithDPI(page - 1, 96f)
        ByteArrayOutputStream().use { out -> ImageIO.write(image, "png", out); out.toByteArray() }
    }

    companion object {
        /** Grouped amount with the currency's minor units (display only; stored values keep 4 decimals). */
        fun amount(value: BigDecimal, minorUnits: Int): String {
            val pattern = if (minorUnits > 0) "#,##0." + "0".repeat(minorUnits) else "#,##0"
            return DecimalFormat(pattern, DecimalFormatSymbols(Locale.ENGLISH)).format(value.setScale(minorUnits, RoundingMode.HALF_UP))
        }

        fun quantity(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

        fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

        fun amountInWords(value: BigDecimal, currency: String): String {
            val rounded = value.setScale(2, RoundingMode.HALF_UP)
            val whole = rounded.toBigInteger()
            val cents = rounded.remainder(BigDecimal.ONE).movePointRight(2).abs().toInt()
            val (unit, fraction) = when (currency) {
                "INR" -> "Indian Rupees" to "Paise"
                "USD" -> "US Dollars" to "Cents"
                else -> currency to "Cents"
            }
            return "$unit ${words(whole.toLong())}${if (cents == 0) "" else " and ${words(cents.toLong())} $fraction"} Only"
        }

        private fun words(number: Long): String {
            if (number == 0L) return "Zero"
            val ones = listOf("", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
                "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen")
            val tens = listOf("", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety")
            fun underThousand(n: Int): String = buildList {
                if (n >= 100) add("${ones[n / 100]} Hundred")
                val rest = n % 100
                if (rest in 1..19) add(ones[rest])
                else if (rest >= 20) add(tens[rest / 10] + if (rest % 10 == 0) "" else "-${ones[rest % 10]}")
            }.joinToString(" ")
            var remaining = number
            val groups = listOf("", "Thousand", "Million", "Billion", "Trillion")
            val parts = mutableListOf<String>()
            for (group in groups) {
                val chunk = (remaining % 1000).toInt()
                if (chunk != 0) parts.add("${underThousand(chunk)} $group".trim())
                remaining /= 1000
                if (remaining == 0L) break
            }
            return parts.asReversed().joinToString(" ")
        }
    }
}
