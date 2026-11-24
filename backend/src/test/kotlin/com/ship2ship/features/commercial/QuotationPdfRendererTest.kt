package com.ship2ship.features.commercial

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuotationPdfRendererTest {
    @Test
    fun `spells the payable amount including minor units`() {
        assertEquals("US Dollars Seventy-Six Thousand Seven Hundred Only",
            QuotationPdfRenderer.amountInWords(java.math.BigDecimal("76700.00"), "USD"))
        assertEquals("Indian Rupees One Hundred One and Twenty-Five Paise Only",
            QuotationPdfRenderer.amountInWords(java.math.BigDecimal("101.25"), "INR"))
        assertEquals("EUR Two Hundred Only",
            QuotationPdfRenderer.amountInWords(java.math.BigDecimal("200.00"), "EUR"))
    }

    @Test
    fun `renders inline USD and INR columns FX rate and planned advances in one PDF`() {
        val document = QuotationDocument(
            entityName = "Ship2Ship", entityAddress = null, taxRegistrationNo = null, reference = "Q-1", versionNo = 1,
            date = "13 Nov 2026", validUntil = "20 Nov 2026", validFor = "5 working days", enquiryReference = "E-1",
            clientName = "Demo Client", clientAddress = "Mumbai", contactName = "Jane Doe",
            vessels = listOf("Mother: Vessel A", "Daughter: Vessel B"),
            operationType = "CHEMICAL, LPG", cargo = emptyList(), currency = "USD",
            lines = listOf(QuotationDocument.Line(1, "STS service", "1", "100.00", "100.00", "118.00",
                inrUnitPrice = "8,325.00", inrAmount = "8,325.00", inrTotalWithGst = "9,823.50")),
            total = "100.00", gst = "18.00", grandTotal = "118.00",
            amountInWords = "US Dollars One Hundred Eighteen Only",
            fxNote = "Converted from USD at 1 USD = 83.25 INR (FX master 2026-11-13).",
            inr = QuotationDocument.InrSummary(
                total = "8,325.00", gst = "1,498.50", grandTotal = "9,823.50",
                amountInWords = "Indian Rupees Nine Thousand Eight Hundred Twenty-Three and Fifty Paise Only",
                advanceReceived = "1,000.00",
            ),
            tariff = QuotationDocument.Tariff("STS", 48, "100.00", "450.00", false),
            extraHoursNotice = QuotationDocument.ExtraHoursNotice("48", "450.00", "37,462.50"),
            paymentTerms = null, advanceReceived = "12.00", advanceGiven = "500.00",
            termsText = null, scopeOfServices = (1..8).map {
                QuotationDocument.ScopeItem(it, "Service $it", if (it == 3) "4 \u00D7 primary\n2 \u00D7 baby" else "Mobilisation and support")
            },
            approvedBy = null, approvedAt = null, draft = false,
        )
        val bytes = QuotationPdfRenderer().render(document)
        assertEquals(3, QuotationPdfRenderer().pageCount(bytes))
        val preview = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(QuotationPdfRenderer().previewPage(bytes, 1)))
        assertEquals(793, preview.width)
        Loader.loadPDF(bytes).use { pdf ->
            val text = PDFTextStripper().getText(pdf)
            assertTrue(!text.contains("DRAFT"))
            assertContains(text, "USD / INR")
            assertContains(text, "Amount (USD)")
            assertContains(text, "Amount (INR)")
            assertContains(text, "Total (USD)")
            assertContains(text, "Total (INR)")
            assertContains(text, "USD 118.00 / INR 9,823.50")
            assertContains(text, "FX master 2026-11-13")
            assertContains(text, "Cargo types")
            assertContains(text, "CHEMICAL, LPG")
            assertContains(text, "Planned client advance")
            assertContains(text, "Planned supplier advance")
            assertContains(text, "Service 8")
            assertContains(text, "STS cost inclusions")
            assertContains(text, "Additional hours beyond 48 hrs")
            assertContains(text, "BENEFICIARY BANK")
        }
    }
}
