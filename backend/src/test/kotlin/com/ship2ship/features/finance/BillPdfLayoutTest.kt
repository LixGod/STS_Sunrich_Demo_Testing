package com.ship2ship.features.finance

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.math.BigDecimal
import kotlin.test.assertContains
import kotlin.test.assertEquals

class BillPdfLayoutTest {
    @Test
    fun `INR uses Indian grouping and USD retains standard grouping`() {
        val inr = BigDecimal("47908").multiply(BigDecimal("95.27"))
        assertEquals("45,64,195.16", formatBillMoney(inr, true))
        assertEquals("4,564,195.16", formatBillMoney(inr))
        assertEquals("—", formatBillMoney(null, true))
    }

    @Test
    fun `invoice bill shows the reference summary and approved conversion`() {
        val bill = BillSummary("FINAL INVOICE", "SHIP2SHIP PRIVATE LIMITED", "STS/2026/001", "Atlantic Global Shipping",
            "Surat, Gujarat", "Vadinar", "Gas Al Kuwait II / C Sansovino", "19 November 2026",
            listOf(BillRow(1, "STS operation cost (up to 48 hrs)", "45,64,195.00", "47,908.00", "47,908.00"),
                BillRow(2, "STS operation cost (above 48 hrs)", "9,10,591.00", "9,558.00", "9,558.00")),
            "54,74,786.00", "57,466.00", "57,466.00", "95.27", "USD")
        val pdf = InvoiceOutput().pdf(InvoiceDocument("Invoice", "INV/2026/001", bill.company, null, bill.client,
            bill.clientAddress, listOf("Job" to bill.jobNumber), emptyList(), "USD 57,466.00", "USD 0.00", "USD 57,466.00",
            null, null, false, bill))
        val output = Path.of("build/tmp/pdfs/bill-sample.pdf")
        Files.createDirectories(output.parent)
        Files.write(output, pdf)
        Loader.loadPDF(pdf).use { document ->
            assertEquals(1, document.numberOfPages)
            val text = PDFTextStripper().getText(document)
            assertContains(text, "SHIP2SHIP PRIVATE LIMITED")
            assertContains(text, "ANNEX NO.")
            assertContains(text, "45,64,195.00")
            assertContains(text, "AMOUNT IN USD @ 95.27")
            assertContains(text, "THANK YOU FOR YOUR BUSINESS")
        }
    }
}
