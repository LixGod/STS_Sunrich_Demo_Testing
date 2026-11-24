package com.ship2ship.features.finance

import com.ship2ship.features.technical.JobTestSupport
import com.ship2ship.support.StatementCounter
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.util.HexFormat
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 5a: invoices from the accepted quotation, second-person approval, numbers assigned at
 * issue, immutable PDFs, credit notes within what is open, receipts with allocation and reversal, and receivables ageing.
 */
class FinanceApiIT : JobTestSupport() {

    private val today = LocalDate.now()
    private fun invoice(id: String) = "/api/v1/finance/invoices/$id"
    private fun codes(node: JsonNode, field: String) = (0 until node[field].size()).map { node[field][it]["code"].asString() }
    private fun money(expected: String, node: JsonNode) =
        assertEquals(0, BigDecimal(expected).compareTo(node.decimalValue()), "expected $expected but was $node")

    private fun line(qty: String, price: String, source: String = "MANUAL", head: String = "STS-SERVICE", description: String? = null) =
        mapOf("source" to source, "costHeadCode" to head, "quantity" to qty, "unitPrice" to price, "description" to description)

    private fun header(lines: List<Map<String, Any?>>, date: LocalDate = today, due: LocalDate? = today.plusDays(30)) = mapOf(
        "invoiceDate" to date.toString(), "dueDate" to due?.toString(), "billingName" to "DEMO Charterers Ltd (Synthetic)",
        "billingAddress" to "Synthetic address", "paymentTerms" to "Synthetic: 30 days", "lines" to lines,
    )

    /** Operations prepares and issues; Finance approves the invoice. */
    private fun issue(draft: JsonNode, content: Map<String, Any?>): JsonNode {
        val finance = login("ops.manager")
        val url = invoice(draft["id"].asString())
        val saved = ok(call(finance, "PUT", url, content, draft["version"].asLong()))
        val submitted = ok(call(finance, "POST", "$url/submit", version = saved["version"].asLong()))
        val approved = ok(call(login("finance"), "POST", "$url/decision", mapOf("decision" to "APPROVE"), submitted["version"].asLong()))
        return ok(call(finance, "POST", "$url/issue", version = approved["version"].asLong()))
    }

    private fun issuedInvoice(price: String, date: LocalDate = today, due: LocalDate? = today.plusDays(30)): JsonNode {
        val jobId = newJob()["id"].asString()
        val draft = ok(call(login("ops.manager"), "POST", "/api/v1/jobs/$jobId/invoices", mapOf("fromQuotation" to false)))
        return issue(draft, header(listOf(line("1", price)), date, due))
    }

    private fun seq(number: String) = number.substringAfterLast('/').toInt()

    @Test
    fun `an invoice is drafted from the quotation, approved by a second person and numbered at issue`() {
        val jobId = newJob()["id"].asString()
        val finance = login("ops.manager")
        val overview = ok(call(finance, "GET", "/api/v1/jobs/$jobId/invoices"))
        call(login("finance"), "POST", "/api/v1/jobs/$jobId/invoices").andExpect(status().isForbidden)
        assertTrue(overview["hasAcceptedQuotation"].asBoolean())
        assertTrue(overview["canCreate"].asBoolean())
        assertEquals("USD", overview["currency"].asString())

        // The draft carries the accepted quotation's line, the client's billing details, and no number.
        val draft = ok(call(finance, "POST", "/api/v1/jobs/$jobId/invoices"))
        assertEquals("DRAFT", draft["status"].asString())
        assertTrue(draft["number"].isNull)
        assertEquals("QUOTATION", draft["lines"][0]["source"].asString())
        assertEquals("STS-SERVICE", draft["lines"][0]["costHeadCode"].asString())
        money("10000", draft["lines"][0]["amount"])
        assertEquals("DEMO Charterers Ltd (Synthetic)", draft["billingName"].asString())
        assertTrue("dueDate" in codes(draft, "blockers"))
        val url = invoice(draft["id"].asString())

        // Line amounts are rounded half up to cents.
        call(finance, "PUT", url, header(listOf(line("0", "10"))), draft["version"].asLong())
            .andExpect(jsonPath("$.errors[0].field").value("lines[0].quantity"))
        val saved = ok(call(finance, "PUT", url, header(listOf(line("1", "10000", "QUOTATION"),
            line("2.5", "250.333", description = "Extra hours (manual until 5c)"))), draft["version"].asLong()))
        money("625.83", saved["lines"][1]["amount"])
        money("0", saved["lines"][1]["taxAmount"])
        money("10625.83", saved["netAmount"])
        money("0", saved["taxAmount"])
        money("10625.83", saved["grossAmount"])
        assertEquals(0, saved["blockers"].size())

        // The Operations submitter cannot approve their own invoice; Finance can.
        val submitted = ok(call(finance, "POST", "$url/submit", version = saved["version"].asLong()))
        assertTrue("APPROVE" !in (0 until submitted["allowedActions"].size()).map { submitted["allowedActions"][it].asString() })
        call(finance, "POST", "$url/decision", mapOf("decision" to "APPROVE"), submitted["version"].asLong())
            .andExpect(status().isForbidden)
        call(login("commercial"), "POST", "$url/decision", mapOf("decision" to "APPROVE"), submitted["version"].asLong()).andExpect(status().isForbidden)
        call(login("approver"), "POST", "$url/decision", mapOf("decision" to "APPROVE"), submitted["version"].asLong()).andExpect(status().isForbidden)
        val approved = ok(call(login("finance"), "POST", "$url/decision", mapOf("decision" to "APPROVE"), submitted["version"].asLong()))
        assertTrue(approved["number"].isNull)

        // Issue assigns the number; the issued invoice and its PDF never change.
        val issued = ok(call(finance, "POST", "$url/issue", version = approved["version"].asLong()))
        val number = issued["number"].asString()
        assertTrue(Regex("INV/${today.year}/\\d{5}").matches(number), number)
        call(finance, "PUT", url, header(listOf(line("1", "1"))), issued["version"].asLong()).andExpect(jsonPath("$.code").value("INVALID_TRANSITION"))
        val sha = issued["pdfSha256"].asString()
        repeat(2) {
            val pdf = mvc.perform(get("$url/pdf").session(finance)).andExpect(status().isOk).andReturn().response.contentAsByteArray
            assertEquals("%PDF", String(pdf.copyOf(4)))
            assertEquals(sha, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf)))
        }
        money("10625.83", issued["outstandingAmount"])

        // The next issued invoice takes the next number in the series.
        val next = issuedInvoice("500")
        assertEquals(seq(number) + 1, seq(next["number"].asString()))
        val listed = ok(call(finance, "GET", "/api/v1/finance/invoices?jobId=$jobId"))
        assertEquals(1, listed["items"].size())
        assertEquals(number, listed["items"][0]["number"].asString())
    }

    @Test
    fun `credit notes stay within what is open, and receipts are allocated within limits and reversed with a reason`() {
        val finance = login("finance")
        val operations = login("ops.manager")
        val issued = issuedInvoice("10000")
        val url = invoice(issued["id"].asString())

        val credit = ok(call(operations, "POST", "$url/credit-notes"))
        assertEquals("CREDIT_NOTE", credit["docType"].asString())
        assertEquals(issued["number"].asString(), credit["creditedInvoiceNumber"].asString())
        val creditIssued = issue(credit, header(listOf(line("1", "1500", description = "Synthetic correction")), due = null))
        assertTrue(creditIssued["number"].asString().startsWith("CN/${today.year}/"))
        money("8500", ok(call(finance, "GET", url))["outstandingAmount"])

        // A second credit note larger than what is open cannot be submitted.
        val tooBig = ok(call(operations, "POST", "$url/credit-notes"))
        val tooBigUrl = invoice(tooBig["id"].asString())
        val saved = ok(call(operations, "PUT", tooBigUrl, header(listOf(line("1", "9000")), due = null), tooBig["version"].asLong()))
        assertTrue("credit" in codes(saved, "blockers"))
        call(operations, "POST", "$tooBigUrl/submit", version = saved["version"].asLong()).andExpect(jsonPath("$.code").value("INVOICE_INCOMPLETE"))

        // A receipt allocated to the invoice, the rest on account; limits on the invoice and on the receipt.
        val parties = ok(call(finance, "GET", "/api/v1/finance/receipts/parties"))
        assertTrue((0 until parties.size()).any { parties[it]["clientId"].asString() == CLIENT_CHARTERER && parties[it]["currency"].asString() == "USD" })
        val open = ok(call(finance, "GET", "/api/v1/finance/receipts/open-invoices?clientId=$CLIENT_CHARTERER&currency=USD"))
        assertTrue((0 until open.size()).any { open[it]["id"].asString() == issued["id"].asString() })
        val receiptInput = mapOf("clientId" to CLIENT_CHARTERER, "receivedOn" to today.toString(), "amount" to "9000",
            "currency" to "USD", "method" to "BANK_TRANSFER", "reference" to "SYNTH-REF-1")
        call(finance, "POST", "/api/v1/finance/receipts", receiptInput + mapOf("allocations" to listOf(mapOf("invoiceId" to issued["id"].asString(), "amount" to "8600"))))
            .andExpect(jsonPath("$.code").value("ALLOCATION_EXCEEDS_OUTSTANDING"))
        call(finance, "POST", "/api/v1/finance/receipts", receiptInput + mapOf("amount" to "100",
            "allocations" to listOf(mapOf("invoiceId" to issued["id"].asString(), "amount" to "200"))))
            .andExpect(jsonPath("$.code").value("ALLOCATION_EXCEEDS_RECEIPT"))
        call(finance, "POST", "/api/v1/finance/receipts", receiptInput + mapOf("currency" to "INR",
            "allocations" to listOf(mapOf("invoiceId" to issued["id"].asString(), "amount" to "100"))))
            .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"))
        call(finance, "POST", "/api/v1/finance/receipts", receiptInput + mapOf("allocations" to listOf(mapOf("invoiceId" to creditIssued["id"].asString(), "amount" to "1"))))
            .andExpect(jsonPath("$.code").value("ALLOCATION_NOT_ALLOWED"))
        val receipt = ok(call(finance, "POST", "/api/v1/finance/receipts",
            receiptInput + mapOf("allocations" to listOf(mapOf("invoiceId" to issued["id"].asString(), "amount" to "6000")))))
        money("6000", receipt["allocatedAmount"])
        money("3000", receipt["onAccountAmount"])
        val receiptUrl = "/api/v1/finance/receipts/${receipt["id"].asString()}"
        call(finance, "POST", "$receiptUrl/allocations", mapOf("allocations" to listOf(mapOf("invoiceId" to issued["id"].asString(), "amount" to "2600"))),
            receipt["version"].asLong()).andExpect(jsonPath("$.code").value("ALLOCATION_EXCEEDS_OUTSTANDING"))
        val more = ok(call(finance, "POST", "$receiptUrl/allocations", mapOf("allocations" to listOf(mapOf("invoiceId" to issued["id"].asString(), "amount" to "2500"))),
            receipt["version"].asLong()))
        money("500", more["onAccountAmount"])
        val paid = ok(call(finance, "GET", url))
        money("0", paid["outstandingAmount"])
        assertTrue("CREDIT" !in (0 until paid["allowedActions"].size()).map { paid["allowedActions"][it].asString() })
        call(operations, "POST", "$url/credit-notes").andExpect(jsonPath("$.code").value("CREDIT_EXCEEDS_OUTSTANDING"))

        // Reversal needs a reason and reopens the invoice.
        call(finance, "POST", "$receiptUrl/reverse", mapOf("reason" to " "), more["version"].asLong()).andExpect(jsonPath("$.code").value("REASON_REQUIRED"))
        val reversed = ok(call(finance, "POST", "$receiptUrl/reverse", mapOf("reason" to "Synthetic: posted to the wrong client"), more["version"].asLong()))
        assertEquals("REVERSED", reversed["status"].asString())
        assertTrue(reversed["allocations"][0]["reversed"].asBoolean())
        money("8500", ok(call(finance, "GET", url))["outstandingAmount"])
        call(finance, "POST", "$receiptUrl/allocations", mapOf("allocations" to listOf(mapOf("invoiceId" to issued["id"].asString(), "amount" to "1"))),
            reversed["version"].asLong()).andExpect(jsonPath("$.code").value("INVALID_TRANSITION"))
    }

    @Test
    fun `receivables age from the due date as at a chosen day and export to Excel with an audit entry`() {
        val finance = login("finance")
        val issued = issuedInvoice("4000", today.minusDays(100), today.minusDays(45))
        val id = issued["id"].asString()
        ok(call(finance, "POST", "/api/v1/finance/receipts", mapOf("clientId" to CLIENT_CHARTERER,
            "receivedOn" to today.toString(), "amount" to "1000", "currency" to "USD", "allocations" to listOf(mapOf("invoiceId" to id, "amount" to "1000")))))

        fun row(asOf: LocalDate): JsonNode? {
            val data = ok(call(finance, "GET", "/api/v1/finance/receivables?asOf=$asOf&clientId=$CLIENT_CHARTERER&currency=USD"))
            return (0 until data["rows"].size()).map { data["rows"][it] }.firstOrNull { it["invoiceId"].asString() == id }
        }
        val now = row(today)!!
        assertEquals(45, now["overdueDays"].asInt())
        assertEquals("D31_60", now["bucket"].asString())
        money("3000", now["outstandingAmount"])
        val detail = ok(call(finance, "GET", "/api/v1/finance/receivables?asOf=$today&clientId=$CLIENT_CHARTERER&currency=USD"))
        val firstPage = ok(call(finance, "GET", "/api/v1/finance/receivables/page?asOf=$today&clientId=$CLIENT_CHARTERER&currency=USD&page=0&size=1"))
        assertEquals(1, firstPage["rows"]["items"].size())
        assertEquals(detail["rows"].size().toLong(), firstPage["rows"]["totalElements"].asLong())
        assertEquals(detail["rows"][0]["invoiceId"], firstPage["rows"]["items"][0]["invoiceId"])
        assertEquals(detail["totals"], firstPage["totals"])
        val emptyPage = ok(call(finance, "GET", "/api/v1/finance/receivables/page?asOf=$today&clientId=$CLIENT_CHARTERER&currency=USD&page=999&size=1"))
        assertEquals(0, emptyPage["rows"]["items"].size())
        assertEquals(detail["rows"].size().toLong(), emptyPage["rows"]["totalElements"].asLong())
        val summary = ok(call(finance, "GET", "/api/v1/finance/receivables/summary?asOf=$today&clientId=$CLIENT_CHARTERER&currency=USD"))
        val detailedTotal = (0 until detail["totals"].size()).fold(BigDecimal.ZERO) { sum, i -> sum + detail["totals"][i]["total"].decimalValue() }
        money(detailedTotal.toPlainString(), summary["totals"][0]["total"])
        for (bucket in listOf("NOT_DUE", "D1_30", "D31_60", "D61_90", "D90_PLUS")) {
            val expected = (0 until detail["totals"].size()).fold(BigDecimal.ZERO) { sum, i ->
                sum + detail["totals"][i]["buckets"][bucket].decimalValue()
            }
            money(expected.toPlainString(), summary["totals"][0]["buckets"][bucket])
        }
        assertEquals(null, summary.get("rows"))
        val parties = ok(call(finance, "GET", "/api/v1/finance/receipts/parties"))
        val party = (0 until parties.size()).map { parties[it] }.first {
            it["clientId"].asString() == CLIENT_CHARTERER && it["currency"].asString() == "USD"
        }
        val open = ok(call(finance, "GET", "/api/v1/finance/receipts/open-invoices?clientId=$CLIENT_CHARTERER&currency=USD"))
        val openTotal = (0 until open.size()).fold(BigDecimal.ZERO) { sum, i -> sum + open[i]["outstandingAmount"].decimalValue() }
        assertEquals(0, openTotal.compareTo(party["outstandingAmount"].decimalValue()))
        // The backdated invoice did not exist in the ledger until it was issued today.
        assertEquals(null, row(today.minusDays(50)))
        assertEquals("D90_PLUS", row(today.plusDays(50))!!["bucket"].asString())
        assertEquals(null, row(today.minusDays(101)))

        val before = auditCount("EXPORT", "RECEIVABLES")
        val bytes = mvc.perform(get("/api/v1/finance/receivables/xlsx?asOf=$today").session(finance)).andExpect(status().isOk)
            .andReturn().response.contentAsByteArray
        XSSFWorkbook(bytes.inputStream()).use { book ->
            val sheet = book.getSheetAt(0)
            assertEquals("Receivables", sheet.sheetName)
            assertEquals("Receivables as at $today", sheet.getRow(0).getCell(0).stringCellValue)
            assertTrue(book.getFontAt(sheet.getRow(0).getCell(0).cellStyle.fontIndex).bold)
            val header = sheet.getRow(2)
            assertEquals("Invoice", header.getCell(0).stringCellValue)
            assertEquals("Bucket", header.getCell(11).stringCellValue)
            assertEquals(IndexedColors.GREY_25_PERCENT.index, header.getCell(0).cellStyle.fillForegroundColor)
            assertEquals(FillPatternType.SOLID_FOREGROUND, header.getCell(0).cellStyle.fillPattern)
            assertEquals(CellType.NUMERIC, sheet.getRow(3).getCell(6).cellType)
            assertEquals(listOf(26 * 256, 14 * 256), listOf(sheet.getColumnWidth(0), sheet.getColumnWidth(3)))
            // The client and currency totals table follows the invoice rows after one blank row.
            val totals = (3..sheet.lastRowNum).first { sheet.getRow(it)?.getCell(0)?.stringCellValue == "Client" }
            assertEquals("Total", sheet.getRow(totals).getCell(7).stringCellValue)
        }
        assertEquals(before + 1, auditCount("EXPORT", "RECEIVABLES"))
    }

    @Test
    fun `receivables retain an allocation that was reversed after the requested day`() {
        val finance = login("finance")
        val yesterday = today.minusDays(1)
        val issued = issuedInvoice("4000", yesterday)
        val invoiceId = issued["id"].asString()
        jdbc.sql("UPDATE invoice SET issued_at=:at WHERE id=UUID_TO_BIN(:id)")
            .param("at", yesterday.atTime(12, 0)).param("id", invoiceId).update()
        val receipt = ok(call(finance, "POST", "/api/v1/finance/receipts", mapOf("clientId" to CLIENT_CHARTERER, "receivedOn" to yesterday.toString(), "amount" to "1000", "currency" to "USD",
            "allocations" to listOf(mapOf("invoiceId" to invoiceId, "amount" to "1000")))))
        val receiptId = receipt["id"].asString()
        jdbc.sql("UPDATE receipt SET recorded_at=:at WHERE id=UUID_TO_BIN(:id)")
            .param("at", yesterday.atTime(12, 0)).param("id", receiptId).update()
        jdbc.sql("UPDATE receipt_allocation SET allocated_at=:at WHERE receipt_id=UUID_TO_BIN(:id)")
            .param("at", yesterday.atTime(12, 0)).param("id", receiptId).update()
        ok(call(finance, "POST", "/api/v1/finance/receipts/$receiptId/reverse", mapOf("reason" to "Wrong receipt"),
            receipt["version"].asLong()))

        val past = ok(call(finance, "GET", "/api/v1/finance/receivables?asOf=$yesterday"))
        val row = (0 until past["rows"].size()).map { past["rows"][it] }.first { it["invoiceId"].asString() == invoiceId }
        money("3000", row["outstandingAmount"])
    }

    /**
     * Issue benchmark, run by hand (README, "Measuring"): an invoice issue takes the yearly number-series row lock and then
     * renders the PDF in the same transaction. Compares the render alone, one issue, and 5 issues started together.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "SHIP2SHIP_BENCH", matches = "true")
    fun `invoice issue benchmark`() {
        val operations = login("ops.manager")
        val finance = login("finance")
        fun approved(): JsonNode {
            val jobId = newJob()["id"].asString()
            val draft = ok(call(operations, "POST", "/api/v1/jobs/$jobId/invoices", mapOf("fromQuotation" to false)))
            val url = invoice(draft["id"].asString())
            val saved = ok(call(operations, "PUT", url, header((1..20).map { line("1", "${100 + it}", description = "Synthetic line $it") }), draft["version"].asLong()))
            val submitted = ok(call(operations, "POST", "$url/submit", version = saved["version"].asLong()))
            return ok(call(finance, "POST", "$url/decision", mapOf("decision" to "APPROVE"), submitted["version"].asLong()))
        }
        fun ms(block: () -> Unit) = System.nanoTime().let { started -> block(); (System.nanoTime() - started) / 1_000_000 }
        fun issueNow(row: JsonNode) = ok(call(operations, "POST", "${invoice(row["id"].asString())}/issue", version = row["version"].asLong()))
        val rows = (1..9).map { approved() }
        val render = (1..3).map { ms { mvc.perform(get("${invoice(rows[0]["id"].asString())}/pdf").session(operations)).andExpect(status().isOk) } }.sorted()[1]
        issueNow(rows[0]) // warm-up
        val single = listOf(rows[1], rows[2], rows[3]).map { ms { issueNow(it) } }.sorted()[1]
        val pool = java.util.concurrent.Executors.newFixedThreadPool(5)
        val each = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        val together = ms { rows.drop(4).map { row -> pool.submit { each += ms { issueNow(row) } } }.forEach { it.get() } }
        pool.shutdown()
        println("ISSUE draft PDF render %d ms; one issue %d ms; 5 at once: %d ms in all, each %s ms".format(render, single, together, each.sorted()))
    }

    @Test
    fun `finance lists read a full page in a few statements and match the single-record reads`() {
        val finance = login("finance")
        val operations = login("ops.manager")
        val issued = issuedInvoice("1000")
        val jobId = issued["jobId"].asString()
        repeat(24) { ok(call(operations, "POST", "/api/v1/jobs/$jobId/invoices", mapOf("fromQuotation" to false))) }
        repeat(25) {
            ok(call(finance, "POST", "/api/v1/finance/receipts", mapOf("clientId" to CLIENT_CHARTERER, "receivedOn" to today.toString(),
                "amount" to "20", "currency" to "USD", "allocations" to listOf(mapOf("invoiceId" to issued["id"].asString(), "amount" to "10")))))
        }
        fun items(node: JsonNode) = (0 until node.size()).map { node[it] }

        val (invoices, invoiceStatements) = StatementCounter.measure { ok(call(finance, "GET", "/api/v1/finance/invoices?jobId=$jobId&size=25")) }
        val (forJob, forJobStatements) = StatementCounter.measure { ok(call(operations, "GET", "/api/v1/jobs/$jobId/invoices")) }
        val (receipts, receiptStatements) = StatementCounter.measure {
            ok(call(finance, "GET", "/api/v1/finance/receipts?clientId=$CLIENT_CHARTERER&size=25"))
        }
        val (open, openStatements) = StatementCounter.measure {
            ok(call(finance, "GET", "/api/v1/finance/receipts/open-invoices?clientId=$CLIENT_CHARTERER&currency=USD"))
        }
        val (receivables, receivableStatements) = StatementCounter.measure { ok(call(finance, "GET", "/api/v1/finance/receivables")) }
        println("STATEMENTS invoices=$invoiceStatements/${invoices["items"].size()} jobInvoices=$forJobStatements/${forJob["invoices"].size()} " +
            "receipts=$receiptStatements/${receipts["items"].size()} openInvoices=$openStatements/${open.size()} " +
            "receivables=$receivableStatements/${receivables["rows"].size()}")

        // A fixed number per request, not per row (before batching: 183, 187, 231, 101 and 15 for these pages).
        for ((name, count) in listOf("invoices" to invoiceStatements, "jobInvoices" to forJobStatements, "receipts" to receiptStatements,
            "openInvoices" to openStatements, "receivables" to receivableStatements)) assertTrue(count <= 15, "$name used $count statements")
        assertEquals(25, invoices["items"].size())
        assertEquals(25, receipts["items"].size())
        // A list row is exactly what the single-record read reports.
        for (row in items(invoices["items"])) {
            val detail = ok(call(finance, "GET", invoice(row["id"].asString())))
            for (field in listOf("docType", "number", "status", "jobId", "jobNumber", "clientName", "currency", "invoiceDate", "dueDate",
                "grossAmount", "outstandingAmount", "minorUnits")) assertEquals(detail[field], row[field], field)
        }
        assertEquals(items(invoices["items"]).toSet(), items(forJob["invoices"]).toSet())
        for (row in items(receipts["items"])) assertEquals(ok(call(finance, "GET", "/api/v1/finance/receipts/${row["id"].asString()}")), row)
        val issuedRow = items(invoices["items"]).first { it["id"] == issued["id"] }
        money("750", issuedRow["outstandingAmount"])
        assertEquals(issuedRow, items(open).first { it["id"] == issued["id"] })
    }

    @Test
    fun `finance records are scoped by permission and legal entity`() {
        val issued = issuedInvoice("100")
        val url = invoice(issued["id"].asString())
        val operations: MockHttpSession = login("ops.mumbai")
        ok(call(operations, "GET", url))
        ok(call(operations, "GET", "/api/v1/finance/invoices"))
        ok(call(operations, "GET", "/api/v1/finance/receivables"))
        val commercial = login("commercial")
        ok(call(commercial, "GET", url))
        call(commercial, "POST", "$url/credit-notes").andExpect(status().isForbidden)
        call(commercial, "POST", "/api/v1/finance/receipts", mapOf("clientId" to CLIENT_CHARTERER,
            "receivedOn" to today.toString(), "amount" to "1", "currency" to "USD")).andExpect(status().isForbidden)
        call(login("finance"), "GET", invoice(UUID.randomUUID().toString())).andExpect(status().isNotFound)
        call(login("finance"), "POST", "/api/v1/finance/receipts", mapOf("clientId" to CLIENT_CHARTERER,
            "receivedOn" to today.plusDays(1).toString(), "amount" to "1.234", "currency" to "USD"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.errors[0].field").value("receivedOn"))
    }
}
