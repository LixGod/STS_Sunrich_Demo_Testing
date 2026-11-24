package com.ship2ship.features.commercial

import com.ship2ship.support.IntegrationTest
import com.ship2ship.features.finance.ExtraHourInvoices
import org.springframework.beans.factory.annotation.Autowired
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * FR-J02/J03 end to end: estimate → quotation → approval by someone else → issue (frozen PDF) → owner acceptance →
 * exactly one job with the tariff frozen (UAT-03, AR-02, FR-UM-05).
 */
class QuotationFlowIT : IntegrationTest() {

    @Autowired private lateinit var extraHourInvoices: ExtraHourInvoices

    private val validUntil = LocalDate.now().plusDays(30).toString()

    private fun json(session: MockHttpSession, method: String, url: String, payload: Any? = null, version: Long? = null, key: String? = null): ResultActions {
        val builder = when (method) {
            "POST" -> post(url)
            "PUT" -> put(url)
            else -> get(url)
        }.session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
        version?.let { builder.header(HttpHeaders.IF_MATCH, "\"$it\"") }
        key?.let { builder.header("Idempotency-Key", it) }
        payload?.let { builder.content(body(it)) }
        return mvc.perform(builder)
    }

    /** JSON numbers lose trailing zeros when read back; money is compared by value. */
    private fun assertMoney(expected: String, node: JsonNode) =
        assertEquals(0, java.math.BigDecimal(expected).compareTo(node.decimalValue()), "expected $expected but was $node")

    private fun ok(actions: ResultActions): JsonNode = read(actions.andExpect(status().is2xxSuccessful).andReturn().response.contentAsString)

    private fun newEnquiry(session: MockHttpSession): String = ok(json(session, "POST", "/api/v1/enquiries", mapOf(
        "branchId" to BRANCH_MUM, "receivedAt" to "2026-10-16T05:00:00Z",
        "clientId" to CLIENT_CHARTERER, "clientContactId" to CONTACT_CHARTERER,
        "operationType" to "LPG",
        "motherVessel" to mapOf("vesselId" to VESSEL_MOTHER, "etaLocal" to "2026-10-20T08:00:00"),
        "daughterVessel" to mapOf("vesselId" to VESSEL_DAUGHTER),
        "cargo" to listOf(mapOf("cargoGradeId" to GRADE_PROPANE, "quantity" to "34000", "unitId" to UNIT_MT)),
    )))["id"].asString()

    private val lines = listOf(
        mapOf("kind" to "COST", "costHeadId" to COST_HEAD_FENDERS, "description" to "Fender hire", "quantity" to "2", "unitPrice" to "1250.125"),
        mapOf("kind" to "COST", "costHeadId" to COST_HEAD_TUGS, "quantity" to "1.5", "unitPrice" to "999.99"),
        mapOf("kind" to "REVENUE", "costHeadId" to COST_HEAD_STS_SERVICE, "description" to "STS service, 48 h base", "quantity" to "1", "unitPrice" to "10000"),
    )

    /** Enquiry with a saved estimate (2 cost lines, 1 revenue line); returns enquiry id and estimate id. */
    private fun costed(session: MockHttpSession): Pair<String, String> {
        val enquiryId = newEnquiry(session)
        val estimate = ok(json(session, "POST", "/api/v1/enquiries/$enquiryId/estimates", mapOf<String, Any>()))
        val saved = ok(json(session, "PUT", "/api/v1/estimates/${estimate["id"].asString()}",
            mapOf("currency" to "USD", "lines" to lines), estimate["version"].asLong()))
        return enquiryId to saved["id"].asString()
    }

    private fun quote(session: MockHttpSession, enquiryId: String, estimateId: String, tariffId: String? = TARIFF_DEMO): JsonNode =
        ok(json(session, "POST", "/api/v1/enquiries/$enquiryId/quotation/versions", mapOf(
            "estimateId" to estimateId, "validUntil" to validUntil, "paymentTerms" to "Synthetic: 30 days", "tariffId" to tariffId,
        )))["versions"][0]

    @Test
    fun `enquiry summaries respect estimate quotation and profit permissions`() {
        val commercial = login("commercial")
        val (enquiryId, estimateId) = costed(commercial)
        val draft = quote(commercial, enquiryId, estimateId)
        val versionId = draft["id"].asString()
        ok(json(commercial, "POST", "/api/v1/quotation-versions/$versionId/issue",
            mapOf("issuedTo" to "chartering@demo.invalid", "channel" to "EMAIL"), draft["version"].asLong()))
        ok(json(commercial, "POST", "/api/v1/quotation-versions/$versionId/acceptance",
            mapOf("acceptedAt" to java.time.Instant.now().toString(), "acceptedByName" to "Demo Charterer Representative")))

        val reference = ok(json(commercial, "GET", "/api/v1/enquiries/$enquiryId"))["reference"].asString()
        fun summary(session: MockHttpSession): JsonNode =
            ok(mvc.perform(get("/api/v1/enquiries").session(session).param("q", reference)))["items"][0]

        val operations = summary(login("ops.mumbai"))
        assertTrue(operations["estimatedCost"].isNull)
        assertTrue(operations["estimatedCurrency"].isNull)
        assertTrue(operations["clientStatus"].isNull)
        assertTrue(operations["grossProfit"].isNull)
        assertTrue(operations["grossProfitPercent"].isNull)

        val commercialSummary = summary(commercial)
        assertTrue(!commercialSummary["estimatedCost"].isNull)
        assertTrue(!commercialSummary["clientStatus"].isNull)
        assertTrue(commercialSummary["grossProfit"].isNull)
        assertTrue(!summary(login("finance"))["grossProfit"].isNull)
    }

    @Test
    fun `estimate totals and margin are exact and costing starts automatically`() {
        val session = login("commercial")
        val enquiryId = newEnquiry(session)
        val estimate = ok(json(session, "POST", "/api/v1/enquiries/$enquiryId/estimates", mapOf<String, Any>()))
        assertEquals(1, estimate["versionNo"].asInt())
        assertEquals("USD", estimate["currency"].asString())
        val equipment = ok(json(session, "GET", "/api/v1/estimates/${estimate["id"].asString()}/equipment-options"))
        val fender = equipment.first { it["name"].asString().startsWith("DEMO Pneumatic fender") }
        assertEquals(3, fender["available"].asInt())
        assertMoney("1250", fender["standardRate"])
        assertEquals("USD", fender["standardRateCurrency"].asString())
        val saved = ok(json(session, "PUT", "/api/v1/estimates/${estimate["id"].asString()}",
            mapOf("currency" to "USD", "lines" to lines), estimate["version"].asLong()))

        // 2 x 1250.125 = 2500.25; 1.5 x 999.99 = 1499.985; cost = 4000.235; margin = 5999.765 = 59.997...% → 60.00
        assertMoney("2500.2500", saved["lines"][0]["amount"])
        assertMoney("1499.9850", saved["lines"][1]["amount"])
        assertMoney("4000.2350", saved["totalCost"])
        assertMoney("10000.0000", saved["totalRevenue"])
        assertMoney("5999.7650", saved["margin"])
        assertMoney("60.00", saved["marginPercent"])
        json(session, "GET", "/api/v1/enquiries/$enquiryId").andExpect(jsonPath("$.status").value("COSTING"))

        // A revenue line may not use an expense-only cost head.
        json(session, "PUT", "/api/v1/estimates/${saved["id"].asString()}", mapOf("currency" to "USD", "lines" to listOf(
            mapOf("kind" to "REVENUE", "costHeadId" to COST_HEAD_FENDERS, "quantity" to "1", "unitPrice" to "1"),
        )), saved["version"].asLong()).andExpect(status().isBadRequest).andExpect(jsonPath("$.errors[0].field").value("lines[0].costHeadId"))
    }

    @Test
    fun `extra hours on a tariff revenue line use the tariff rate in the PDA and quotation`() {
        val commercial = login("commercial")
        val tariffId = ok(json(login("admin"), "POST", "/api/v1/masters/tariffs", mapOf(
            "code" to "EH-${UUID.randomUUID().toString().take(8)}", "name" to "Extra hours tariff",
            "currency" to "USD", "baseHours" to 48, "baseCharge" to "10000", "extraHourRate" to "250",
            "effectiveFrom" to "2026-01-01",
        )))["id"].asString()
        val enquiryId = newEnquiry(commercial)
        val estimate = ok(json(commercial, "POST", "/api/v1/enquiries/$enquiryId/estimates", mapOf<String, Any>()))
        val extra = mapOf("kind" to "REVENUE", "costHeadId" to "01920000-0000-7000-9000-000000000637",
            "description" to "Additional hours beyond 48 h", "quantity" to "3", "unitPrice" to "250", "extraHours" to true)
        val saved = ok(json(commercial, "PUT", "/api/v1/estimates/${estimate["id"].asString()}",
            mapOf("currency" to "USD", "tariffId" to tariffId, "lines" to listOf(lines[2], extra)), estimate["version"].asLong()))
        assertMoney("10750", saved["totalRevenue"])
        assertTrue(saved["lines"][1]["extraHours"].asBoolean())
        // Extra hours can be priced manually without selecting a tariff, while non-ADD-TARIFF extra-hours lines are rejected.
        val manual = ok(json(commercial, "PUT", "/api/v1/estimates/${saved["id"].asString()}",
            mapOf("currency" to "USD", "tariffId" to null, "lines" to listOf(lines[2], extra + ("unitPrice" to "325"))),
            saved["version"].asLong()))
        assertMoney("10975", manual["totalRevenue"])
        json(commercial, "PUT", "/api/v1/estimates/${manual["id"].asString()}",
            mapOf("currency" to "USD", "tariffId" to tariffId, "lines" to listOf(lines[2] + ("extraHours" to true))),
            manual["version"].asLong()).andExpect(status().isBadRequest)
        val restored = ok(json(commercial, "PUT", "/api/v1/estimates/${manual["id"].asString()}",
            mapOf("currency" to "USD", "tariffId" to tariffId, "lines" to listOf(lines[2], extra)),
            manual["version"].asLong()))
        val quotation = quote(commercial, enquiryId, restored["id"].asString(), tariffId)
        assertMoney("10750", quotation["totalAmount"])
        assertTrue(quotation["lines"][1]["extraHours"].asBoolean())
        val pdf = mvc.perform(get("/api/v1/quotation-versions/${quotation["id"].asString()}/pdf").session(commercial))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray
        org.apache.pdfbox.Loader.loadPDF(pdf).use { document ->
            val text = org.apache.pdfbox.text.PDFTextStripper().getText(document)
            assertTrue(!text.contains("Charged at actuals after completion."))
        }
        val issued = ok(json(commercial, "POST", "/api/v1/quotation-versions/${quotation["id"].asString()}/issue",
            mapOf("issuedTo" to "chartering@demo.invalid", "channel" to "EMAIL"), quotation["version"].asLong()))
        val accepted = ok(json(commercial, "POST", "/api/v1/quotation-versions/${quotation["id"].asString()}/acceptance",
            mapOf("acceptedAt" to java.time.Instant.now().toString(), "acceptedByName" to "Demo Charterer Representative")))
        val jobId = accepted["job"]["id"].asString()
        val invoice = ok(json(login("ops.manager"), "POST", "/api/v1/jobs/$jobId/invoices", mapOf("fromQuotation" to true)))
        assertTrue(invoice["lines"][1]["extraHours"].asBoolean())
        val finance = login("ops.manager")
        val savedInvoice = ok(json(finance, "PUT", "/api/v1/finance/invoices/${invoice["id"].asString()}", mapOf(
            "invoiceDate" to invoice["invoiceDate"].asString(), "billingName" to invoice["billingName"].asString(),
            "lines" to listOf(
                mapOf("source" to "QUOTATION", "costHeadCode" to "STS-SERVICE", "quantity" to "1", "unitPrice" to "10000"),
                mapOf("source" to "QUOTATION", "costHeadCode" to "ADD-TARIFF", "quantity" to "3", "unitPrice" to "250", "extraHours" to true)),
        ), invoice["version"].asLong()))
        assertTrue(savedInvoice["lines"][1]["extraHours"].asBoolean())
        // Unchecked ADD-TARIFF revenue line saves with quantity 0 + per-hour cost and prints the deferred extra-hours statement in the PDF.
        val enquiry2Id = ok(json(commercial, "POST", "/api/v1/enquiries", mapOf(
            "branchId" to BRANCH_MUM, "receivedAt" to "2026-10-16T05:00:00Z",
            "clientId" to CLIENT_CHARTERER, "clientContactId" to CONTACT_CHARTERER,
            "operationType" to "LPG", "expectedDurationHours" to "72",
            "motherVessel" to mapOf("vesselId" to VESSEL_MOTHER, "etaLocal" to "2026-10-20T08:00:00"),
            "daughterVessel" to mapOf("vesselId" to VESSEL_DAUGHTER),
            "cargo" to listOf(mapOf("cargoGradeId" to GRADE_PROPANE, "quantity" to "34000", "unitId" to UNIT_MT)),
        )))["id"].asString()
        val est2 = ok(json(commercial, "POST", "/api/v1/enquiries/$enquiry2Id/estimates", mapOf<String, Any>()))
        val deferred = mapOf("kind" to "REVENUE", "costHeadId" to "01920000-0000-7000-9000-000000000637",
            "quantity" to "0", "unitPrice" to "275", "extraHours" to false)
        val saved2 = ok(json(commercial, "PUT", "/api/v1/estimates/${est2["id"].asString()}",
            mapOf("currency" to "USD", "tariffId" to null, "lines" to listOf(lines[2], deferred)), est2["version"].asLong()))
        assertMoney("10000", saved2["totalRevenue"])
        assertEquals(2, saved2["lines"].size())
        assertMoney("0", saved2["lines"][1]["quantity"])
        assertMoney("275", saved2["lines"][1]["unitPrice"])
        val q2 = quote(commercial, enquiry2Id, saved2["id"].asString(), null)
        assertMoney("10000", q2["totalAmount"])
        val pdf2 = mvc.perform(get("/api/v1/quotation-versions/${q2["id"].asString()}/pdf").session(commercial))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray
        org.apache.pdfbox.Loader.loadPDF(pdf2).use { document ->
            val text = org.apache.pdfbox.text.PDFTextStripper().getText(document).replace(Regex("\\s+"), " ")
            assertTrue(text.contains("Additional hours beyond 72 hrs"), text)
            assertTrue(text.contains("USD 275.00 / hr if the operation exceeds 72 hrs"), text)
        }

        // Changing the tariff on an accepted estimate with extra hours updates the quotation, job snapshot, and unissued invoice draft.
        val tariff2Id = ok(json(login("admin"), "POST", "/api/v1/masters/tariffs", mapOf(
            "code" to "EH2-${UUID.randomUUID().toString().take(8)}", "name" to "Revised extra hours tariff",
            "currency" to "USD", "baseHours" to 72, "baseCharge" to "10000", "extraHourRate" to "300",
            "effectiveFrom" to "2026-01-01",
        )))["id"].asString()
        val estNow = ok(json(commercial, "GET", "/api/v1/estimates/${saved["id"].asString()}"))
        ok(json(commercial, "PUT", "/api/v1/estimates/${saved["id"].asString()}", mapOf(
            "currency" to "USD", "tariffId" to tariff2Id,
            "lines" to listOf(lines[2], extra + mapOf("description" to "Additional hours beyond 72 h", "unitPrice" to "300")),
        ), estNow["version"].asLong()))
        assertMoney("10900", ok(json(commercial, "GET", "/api/v1/enquiries/$enquiryId/quotation"))["versions"][0]["totalAmount"])
        assertEquals(72, ok(json(commercial, "GET", "/api/v1/jobs/$jobId"))["tariff"]["baseHours"].asInt())
        assertMoney("900", ok(json(finance, "GET", "/api/v1/finance/invoices/${invoice["id"].asString()}"))["lines"][1]["amount"])

        jdbc.sql("UPDATE invoice SET status='ISSUED' WHERE id=UUID_TO_BIN(:id)")
            .param("id", invoice["id"].asString()).update()
        assertEquals(0, java.math.BigDecimal("900").compareTo(extraHourInvoices.charged(UUID.fromString(jobId))))
    }

    @Test
    fun `estimate and quotation stay editable even after final invoice is issued while issued invoices stay untouched`() {
        val commercial = login("commercial")
        val (enquiryId, estimateId) = costed(commercial)
        val draft = quote(commercial, enquiryId, estimateId)
        val versionId = draft["id"].asString()
        val issued = ok(json(commercial, "POST", "/api/v1/quotation-versions/$versionId/issue",
            mapOf("issuedTo" to "chartering@demo.invalid", "channel" to "EMAIL"), draft["version"].asLong()))
        val accepted = ok(json(commercial, "POST", "/api/v1/quotation-versions/$versionId/acceptance",
            mapOf("acceptedAt" to java.time.Instant.now().toString(), "acceptedByName" to "Demo Charterer Representative")))
        val jobId = accepted["job"]["id"].asString()
        val invoice = ok(json(login("ops.manager"), "POST", "/api/v1/jobs/$jobId/invoices", mapOf("fromQuotation" to true)))
        assertEquals("DRAFT", invoice["status"].asString())
        assertTrue(ok(json(commercial, "GET", "/api/v1/enquiries/$enquiryId"))["editable"].asBoolean())
        assertTrue(ok(json(commercial, "GET", "/api/v1/estimates/$estimateId"))["editable"].asBoolean())
        assertTrue((0 until accepted["versions"][0]["allowedActions"].size())
            .any { accepted["versions"][0]["allowedActions"][it].asString() == "EDIT" })

        // An issued supplementary extra-hour invoice does not close the commercial edit window,
        // and commercial edits do not overwrite an unissued supplementary extra-hour invoice.
        val extraInvoice = ok(json(login("ops.manager"), "POST", "/api/v1/jobs/$jobId/invoices", mapOf("fromQuotation" to false)))
        val extraId = extraInvoice["id"].asString()
        jdbc.sql("""INSERT INTO job_extra_invoice(job_id,invoice_id,fingerprint,clock_snapshot,created_at,created_by)
            VALUES (UUID_TO_BIN(:job),UUID_TO_BIN(:inv),:fp,'{}',NOW(),UUID_TO_BIN(:actor))""")
            .param("job", jobId).param("inv", extraId).param("fp", "fp-${UUID.randomUUID()}")
            .param("actor", "01920000-0000-7000-9000-000000000001").update()
        val estBeforeExtra = ok(json(commercial, "GET", "/api/v1/estimates/$estimateId"))
        ok(json(commercial, "PUT", "/api/v1/estimates/$estimateId", mapOf("currency" to "USD", "tariffId" to TARIFF_DEMO, "lines" to lines),
            estBeforeExtra["version"].asLong()))
        assertEquals(0, ok(json(login("ops.manager"), "GET", "/api/v1/finance/invoices/$extraId"))["lines"].size())
        jdbc.sql("UPDATE invoice SET status='ISSUED' WHERE id=UUID_TO_BIN(:id)").param("id", extraId).update()
        assertTrue(ok(json(commercial, "GET", "/api/v1/enquiries/$enquiryId"))["editable"].asBoolean())
        assertTrue(ok(json(commercial, "GET", "/api/v1/estimates/$estimateId"))["editable"].asBoolean())

        jdbc.sql("UPDATE invoice SET status='ISSUED' WHERE id=UUID_TO_BIN(:id)")
            .param("id", invoice["id"].asString()).update()
        assertTrue(!ok(json(commercial, "GET", "/api/v1/enquiries/$enquiryId"))["editable"].asBoolean())
        assertTrue(ok(json(commercial, "GET", "/api/v1/estimates/$estimateId"))["editable"].asBoolean())
        val afterInvoice = ok(json(commercial, "GET", "/api/v1/enquiries/$enquiryId/quotation"))["versions"][0]
        assertTrue((0 until afterInvoice["allowedActions"].size()).any { afterInvoice["allowedActions"][it].asString() == "EDIT" })
        val enquiry = ok(json(commercial, "GET", "/api/v1/enquiries/$enquiryId"))
        json(commercial, "PUT", "/api/v1/enquiries/$enquiryId", mapOf("branchId" to BRANCH_MUM), enquiry["version"].asLong())
            .andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("FINAL_INVOICE_ISSUED"))
        val estimate = ok(json(commercial, "GET", "/api/v1/estimates/$estimateId"))
        ok(json(commercial, "PUT", "/api/v1/estimates/$estimateId", mapOf("currency" to "USD", "tariffId" to TARIFF_DEMO, "lines" to lines),
            estimate["version"].asLong()))
        val latestQuotation = ok(json(commercial, "GET", "/api/v1/enquiries/$enquiryId/quotation"))["versions"][0]
        ok(json(commercial, "PUT", "/api/v1/quotation-versions/$versionId",
            mapOf("validUntil" to validUntil, "tariffId" to TARIFF_DEMO, "paymentTerms" to "Post-invoice note"), latestQuotation["version"].asLong()))
    }

    @Test
    fun `accepted estimate and quotation edits refresh the job PDF and unissued invoice draft`() {
        val commercial = login("commercial")
        val (enquiryId, estimateId) = costed(commercial)
        val draft = quote(commercial, enquiryId, estimateId)
        val versionId = draft["id"].asString()
        ok(json(commercial, "POST", "/api/v1/quotation-versions/$versionId/issue",
            mapOf("issuedTo" to "chartering@demo.invalid", "channel" to "EMAIL"), draft["version"].asLong()))
        val accepted = ok(json(commercial, "POST", "/api/v1/quotation-versions/$versionId/acceptance",
            mapOf("acceptedAt" to java.time.Instant.now().toString(), "acceptedByName" to "Demo Charterer Representative")))
        val jobId = accepted["job"]["id"].asString()
        val invoice = ok(json(login("ops.manager"), "POST", "/api/v1/jobs/$jobId/invoices", mapOf("fromQuotation" to true)))
        val oldPdf = mvc.perform(get("/api/v1/quotation-versions/$versionId/pdf").session(commercial))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray
        val acceptedVersion = accepted["versions"][0]
        val edited = ok(json(commercial, "PUT", "/api/v1/quotation-versions/$versionId",
            mapOf("validUntil" to validUntil, "tariffId" to TARIFF_DEMO, "paymentTerms" to "Revised terms"),
            acceptedVersion["version"].asLong()))["versions"][0]
        assertEquals("ACCEPTED", edited["status"].asString())
        val newPdf = mvc.perform(get("/api/v1/quotation-versions/$versionId/pdf").session(commercial))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray
        assertTrue(!oldPdf.contentEquals(newPdf))
        val revisions = ok(json(commercial, "GET", "/api/v1/quotation-versions/$versionId/pdf-revisions"))
        val previous = mvc.perform(get("/api/v1/quotation-versions/$versionId/pdf-revisions/${revisions[0]["id"].asString()}")
            .session(commercial)).andExpect(status().isOk).andReturn().response.contentAsByteArray
        assertTrue(oldPdf.contentEquals(previous))
        org.apache.pdfbox.Loader.loadPDF(newPdf).use { document ->
            assertTrue(org.apache.pdfbox.text.PDFTextStripper().getText(document).contains("Revised terms"))
        }

        val invoiceId = invoice["id"].asString()
        val finance = login("ops.manager")
        val withManual = ok(json(finance, "PUT", "/api/v1/finance/invoices/$invoiceId", mapOf(
            "invoiceDate" to invoice["invoiceDate"].asString(), "billingName" to invoice["billingName"].asString(),
            "lines" to listOf(
                mapOf("source" to "QUOTATION", "costHeadCode" to "STS-SERVICE", "quantity" to "1", "unitPrice" to "10000"),
                mapOf("source" to "MANUAL", "costHeadCode" to "STS-SERVICE", "description" to "Finance adjustment",
                    "quantity" to "1", "unitPrice" to "25")),
        ), ok(json(finance, "GET", "/api/v1/finance/invoices/$invoiceId"))["version"].asLong()))
        assertMoney("25", withManual["lines"][1]["amount"])

        val estimate = ok(json(commercial, "GET", "/api/v1/estimates/$estimateId"))
        val changed = ok(json(commercial, "PUT", "/api/v1/estimates/$estimateId",
            mapOf("currency" to "USD", "tariffId" to TARIFF_DEMO, "lines" to lines.mapIndexed { i, line ->
                if (i == 2) line + ("unitPrice" to "12000") else line
            }), estimate["version"].asLong()))
        assertMoney("12000", changed["totalRevenue"])
        val current = ok(json(commercial, "GET", "/api/v1/enquiries/$enquiryId/quotation"))["versions"][0]
        assertMoney("12000", current["totalAmount"])
        assertMoney("12000", ok(json(commercial, "GET", "/api/v1/jobs/$jobId"))["quotedAmount"])
        assertMoney("12000", ok(json(login("ops.manager"), "GET", "/api/v1/finance/invoices/${invoice["id"].asString()}"))["lines"][0]["amount"])
        val refreshedInvoice = ok(json(finance, "GET", "/api/v1/finance/invoices/$invoiceId"))
        assertEquals("Finance adjustment", refreshedInvoice["lines"][1]["description"].asString())
        assertMoney("25", refreshedInvoice["lines"][1]["amount"])
    }

    @Test
    fun `editing an accepted enquiry refreshes the linked job cargo`() {
        val commercial = login("commercial")
        val (enquiryId, estimateId) = costed(commercial)
        val draft = quote(commercial, enquiryId, estimateId)
        val versionId = draft["id"].asString()
        ok(json(commercial, "POST", "/api/v1/quotation-versions/$versionId/issue",
            mapOf("issuedTo" to "chartering@demo.invalid", "channel" to "EMAIL"), draft["version"].asLong()))
        val accepted = ok(json(commercial, "POST", "/api/v1/quotation-versions/$versionId/acceptance",
            mapOf("acceptedAt" to java.time.Instant.now().toString(), "acceptedByName" to "Demo Charterer Representative")))
        val jobId = accepted["job"]["id"].asString()
        val invoice = ok(json(login("ops.manager"), "POST", "/api/v1/jobs/$jobId/invoices", mapOf("fromQuotation" to true)))
        val enquiry = ok(json(commercial, "GET", "/api/v1/enquiries/$enquiryId"))
        ok(json(commercial, "PUT", "/api/v1/enquiries/$enquiryId", mapOf(
            "branchId" to BRANCH_MUM, "receivedAt" to "2026-10-16T05:00:00Z", "clientId" to CLIENT_CHARTERER,
            "clientContactId" to CONTACT_CHARTERER, "operationType" to "LPG",
            "motherVessel" to mapOf("vesselId" to VESSEL_MOTHER, "etaLocal" to "2026-10-20T08:00:00"),
            "daughterVessel" to mapOf("vesselId" to VESSEL_DAUGHTER),
            "cargo" to listOf(mapOf("cargoGradeId" to GRADE_PROPANE, "quantity" to "35000", "unitId" to UNIT_MT)),
            "specialItems" to listOf(mapOf("description" to "Additional survey", "paid" to true, "salePriceUsd" to "125")),
        ), enquiry["version"].asLong()))
        assertMoney("35000", ok(json(commercial, "GET", "/api/v1/jobs/$jobId"))["cargo"][0]["quantity"])
        val quote = ok(json(commercial, "GET", "/api/v1/enquiries/$enquiryId/quotation"))["versions"][0]
        assertMoney("10125", quote["totalAmount"])
        val refreshedInvoice = ok(json(login("ops.manager"), "GET", "/api/v1/finance/invoices/${invoice["id"].asString()}"))
        assertMoney("125", refreshedInvoice["lines"][1]["amount"])
    }

    @Test
    fun `a quotation is approved by someone else, issued with a frozen PDF, and acceptance creates exactly one job`() {
        val commercial = login("commercial")
        val tariffId = ok(json(login("admin"), "POST", "/api/v1/masters/tariffs", mapOf(
            "code" to "SNAP-${UUID.randomUUID().toString().take(8)}", "name" to "Synthetic snapshot tariff",
            "currency" to "USD", "baseHours" to 48, "baseCharge" to "10000", "extraHourRate" to "250", "effectiveFrom" to "2026-01-01",
        )))["id"].asString()
        val (enquiryId, estimateId) = costed(commercial)
        val draft = quote(commercial, enquiryId, estimateId, tariffId)
        assertEquals("DRAFT", draft["status"].asString())
        assertMoney("10000.0000", draft["totalAmount"])
        assertEquals(1, draft["lines"].size()) // only revenue lines are quoted

        // An estimate used by a draft quotation remains editable and refreshes the draft quotation.
        val estimate = ok(json(commercial, "GET", "/api/v1/estimates/$estimateId"))
        assertTrue(estimate["locked"].asBoolean())
        assertTrue(estimate["editable"].asBoolean())
        val updatedDraftEstimate = ok(json(commercial, "PUT", "/api/v1/estimates/$estimateId", mapOf(
            "currency" to "USD", "tariffId" to tariffId, "lines" to lines.mapIndexed { i, line ->
                if (i == 2) line + ("unitPrice" to "10500") else line
            },
        ), estimate["version"].asLong()))
        assertMoney("10500", ok(json(commercial, "GET", "/api/v1/enquiries/$enquiryId/quotation"))["versions"][0]["totalAmount"])
        ok(json(commercial, "PUT", "/api/v1/estimates/$estimateId", mapOf(
            "currency" to "USD", "tariffId" to tariffId, "lines" to lines,
        ), updatedDraftEstimate["version"].asLong()))
        val draftAfterSync = ok(json(commercial, "GET", "/api/v1/enquiries/$enquiryId/quotation"))["versions"][0]
        assertMoney("10000.0000", draftAfterSync["totalAmount"])

        val versionId = draftAfterSync["id"].asString()
        val draftActions = (0 until draftAfterSync["allowedActions"].size()).map { draftAfterSync["allowedActions"][it].asString() }
        assertEquals(listOf("EDIT", "ISSUE"), draftActions)

        // Draft PDFs are served without a "-draft" suffix or DRAFT watermark.
        val draftPdf = mvc.perform(get("/api/v1/quotation-versions/$versionId/pdf").session(commercial)).andExpect(status().isOk).andReturn().response
        assertTrue(!draftPdf.getHeader(HttpHeaders.CONTENT_DISPOSITION)!!.contains("-draft"))
        org.apache.pdfbox.Loader.loadPDF(draftPdf.contentAsByteArray).use { pdf ->
            val text = org.apache.pdfbox.text.PDFTextStripper().getText(pdf)
            assertTrue(!Regex("""(?m)^\s*DRAFT\s*$""").containsMatchIn(text))
        }

        // Pending approval queue is retired when no approval step is required.
        json(login("approver"), "GET", "/api/v1/quotation-versions/pending-approval").andExpect(jsonPath("$.length()").value(0))

        var current = ok(json(commercial, "POST", "/api/v1/quotation-versions/$versionId/issue",
            mapOf("issuedTo" to "chartering@demo.invalid", "channel" to "EMAIL"), draftAfterSync["version"].asLong()))
        assertEquals("ISSUED", current["versions"][0]["status"].asString())
        assertEquals("QUOTED", current["enquiryStatus"].asString())
        val hash = current["versions"][0]["pdfSha256"].asString()
        assertEquals(64, hash.length)

        // The issued PDF is stored and served unchanged.
        val first = mvc.perform(get("/api/v1/quotation-versions/$versionId/pdf").session(commercial)).andExpect(status().isOk).andReturn().response
        assertEquals("application/pdf", first.contentType)
        assertTrue(String(first.contentAsByteArray.copyOf(5)) == "%PDF-")
        val second = mvc.perform(get("/api/v1/quotation-versions/$versionId/pdf").session(commercial)).andReturn().response
        assertTrue(first.contentAsByteArray.contentEquals(second.contentAsByteArray))
        assertEquals(hash, QuotationPdfRenderer.sha256(first.contentAsByteArray))

        // Owner acceptance: a retry with the same key and a second click both leave exactly one job.
        val key = UUID.randomUUID().toString()
        val acceptance = mapOf("acceptedAt" to java.time.Instant.now().toString(), "acceptedByName" to "Demo Charterer Representative",
            "workOrderReference" to "WO-SYNTHETIC-1")
        val accepted = ok(json(commercial, "POST", "/api/v1/quotation-versions/$versionId/acceptance", acceptance, key = key))
        val again = ok(json(commercial, "POST", "/api/v1/quotation-versions/$versionId/acceptance", acceptance, key = key))
        json(commercial, "POST", "/api/v1/quotation-versions/$versionId/acceptance", acceptance, key = UUID.randomUUID().toString())
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ALREADY_ACCEPTED"))

        assertEquals("ACCEPTED", accepted["enquiryStatus"].asString())
        assertEquals("ACCEPTED", accepted["versions"][0]["status"].asString())
        val jobId = accepted["job"]["id"].asString()
        assertEquals(jobId, again["job"]["id"].asString())
        assertTrue(Regex("""^JOB-STS/MUM/\d{8}/\d{3,}$""").matches(accepted["job"]["jobNumber"].asString()))
        val jobs = jdbc.sql("SELECT COUNT(*) FROM sts_job WHERE enquiry_id = UUID_TO_BIN(:id)").param("id", enquiryId).query(Long::class.java).single()
        assertEquals(1, jobs)

        // The job carries the enquiry's vessels and cargo and the quoted tariff.
        val job = ok(json(commercial, "GET", "/api/v1/jobs/$jobId"))
        assertEquals("OPEN", job["status"].asString())
        assertEquals(2, job["vessels"].size())
        assertEquals("MOTHER", job["vessels"][0]["role"].asString())
        assertEquals("PROPANE", job["cargo"][0]["cargoGradeCode"].asString())
        assertMoney("10000.0000", job["tariff"]["baseCharge"])
        assertEquals(48, job["tariff"]["baseHours"].asInt())
        // Unset values are sent as explicit nulls (the frontend types are "value | null"), not omitted.
        assertTrue(job["tariff"].has("graceMinutes") && job["tariff"]["graceMinutes"].isNull)

        // Phase 3b starts pending: the source count and signed checklist content are not assumed.
        jdbc.sql("UPDATE job_request_template SET status = 'DRAFT' WHERE status = 'PUBLISHED'").update()
        val technical = login("technical")
        val marine = login("marine")
        json(technical, "GET", "/api/v1/jobs/$jobId/technical/request")
            .andExpect(jsonPath("$.status").value("PENDING_TEMPLATE"))
        json(technical, "POST", "/api/v1/jobs/$jobId/technical/request")
            .andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("TEMPLATE_PENDING"))
        json(technical, "GET", "/api/v1/jobs/$jobId/technical/review")
            .andExpect(jsonPath("$.warnings.length()").value(4))

        // Synthetic checklist definitions make the candidate request publishable in this test.
        val adminForTechnical = login("admin")
        val checklistIds = (1..5).associateWith { n ->
            val created = ok(json(adminForTechnical, "POST", "/api/v1/technical/checklist-templates", mapOf(
                "code" to "CHECKLIST-$n", "name" to "DEMO signed checklist $n", "appliesTo" to "EACH_VESSEL",
                "items" to listOf(mapOf("code" to "DEMO-Q", "label" to "DEMO confirmation", "answerType" to "YES_NO", "required" to true)),
            )))
            ok(json(adminForTechnical, "POST", "/api/v1/technical/checklist-templates/${created["id"].asString()}/publish",
                version = created["version"].asLong()))["id"].asString()
        }
        val candidate = ok(json(adminForTechnical, "GET", "/api/v1/technical/request-templates"))[0]
        val items = (0 until candidate["items"].size()).map { i ->
            val row = candidate["items"][i]
            mapOf("code" to row["code"].asString(),
                "label" to row["label"].asString().replace(" — pending owner name and items", " DEMO"),
                "kind" to row["kind"].asString(), "appliesTo" to row["appliesTo"].asString(),
                "documentTypeId" to row["documentTypeId"].takeUnless { it.isNull }?.asString(),
                "required" to row["required"].asBoolean())
        }
        val revised = ok(json(adminForTechnical, "PUT", "/api/v1/technical/request-templates/${candidate["id"].asString()}",
            mapOf("name" to "DEMO owner-confirmed request", "emailSubject" to candidate["emailSubject"].asString(),
                "emailBody" to candidate["emailBody"].asString(), "items" to items), candidate["version"].asLong()))
        ok(json(adminForTechnical, "POST", "/api/v1/technical/request-templates/${revised["id"].asString()}/publish",
            mapOf("ownerConfirmation" to "DEMO test-only confirmation"), revised["version"].asLong()))
        val request = ok(json(technical, "POST", "/api/v1/jobs/$jobId/technical/request"))
        assertTrue(request["items"].size() >= 20) // Other tests may publish a different request template.
        assertEquals(request["id"].asString(), ok(json(technical, "POST", "/api/v1/jobs/$jobId/technical/request"))["id"].asString())
        val motherJobVessel = job["vessels"][0]["id"].asString()
        val q88 = (0 until request["items"].size()).map { request["items"][it] }
            .first { it["code"].asString() == "Q88" && it["jobVesselId"].asString() == motherJobVessel }
        val uploaded = read(mvc.perform(multipart("/api/v1/jobs/$jobId/technical/documents")
            .file(MockMultipartFile("file", "demo-q88.pdf", "application/pdf", "%PDF-1.4\nDEMO\n%%EOF\n".toByteArray()))
            .param("jobVesselId", motherJobVessel).param("itemId", q88["id"].asString())
            .param("documentTypeId", DOC_TYPE_Q88).session(technical).with(csrf()))
            .andExpect(status().isCreated).andReturn().response.contentAsString)
        assertEquals("NOT_SCANNED", uploaded["scanState"].asString())
        assertEquals("application/pdf", uploaded["contentType"].asString())
        val fileId = uploaded["fileId"].asString()
        json(login("finance"), "POST", "/api/v1/files/$fileId/download-token").andExpect(status().isForbidden)

        val checklist = ok(json(technical, "POST", "/api/v1/jobs/$jobId/technical/checklists",
            mapOf("templateId" to checklistIds.getValue(1), "jobVesselId" to motherJobVessel)))
        assertEquals(checklist["id"].asString(), ok(json(technical, "POST", "/api/v1/jobs/$jobId/technical/checklists",
            mapOf("templateId" to checklistIds.getValue(1), "jobVesselId" to motherJobVessel)))["id"].asString())
        val recorded = ok(json(technical, "PUT", "/api/v1/jobs/$jobId/technical/checklists/${checklist["id"].asString()}",
            mapOf("status" to "RECORDED", "answers" to listOf(mapOf("itemCode" to "DEMO-Q", "answer" to "YES")),
                "signedDocumentId" to uploaded["id"].asString()),
            checklist["version"].asLong()))
        assertEquals("RECORDED", recorded["status"].asString())
        val checklistItem = (0 until request["items"].size()).map { request["items"][it] }
            .firstOrNull { it["code"].asString() == "CHECKLIST-1" && it["jobVesselId"].asString() == motherJobVessel }
        if (checklistItem != null) {
            val afterChecklist = ok(json(technical, "GET", "/api/v1/jobs/$jobId/technical/request"))
            assertEquals("RECEIVED", (0 until afterChecklist["items"].size()).map { afterChecklist["items"][it] }
                .first { it["id"].asString() == checklistItem["id"].asString() }["status"].asString())
        }

        val voyage = ok(json(technical, "POST", "/api/v1/jobs/$jobId/technical/vessels/$motherJobVessel/voyage"))
        val savedVoyage = ok(json(technical, "PUT", "/api/v1/jobs/$jobId/technical/voyage/${voyage["id"].asString()}",
            mapOf("values" to listOf(mapOf("fieldCode" to "ARRIVAL_DRAFT_FWD", "value" to "7.250",
                "sourceDocumentId" to uploaded["id"].asString(), "sourcePage" to 2))), voyage["version"].asLong()))
        val submittedVoyage = ok(json(technical, "POST", "/api/v1/jobs/$jobId/technical/voyage/${voyage["id"].asString()}/submit",
            version = savedVoyage["version"].asLong()))
        val afterVoyage = ok(json(technical, "GET", "/api/v1/jobs/$jobId/technical/request"))
        assertEquals("RECEIVED", (0 until afterVoyage["items"].size()).map { afterVoyage["items"][it] }
            .first { it["code"].asString() == "VOYAGE" && it["jobVesselId"].asString() == motherJobVessel }["status"].asString())
        json(technical, "POST", "/api/v1/jobs/$jobId/technical/voyage/${voyage["id"].asString()}/verify",
            mapOf("confirmedFieldCodes" to listOf("ARRIVAL_DRAFT_FWD")), submittedVoyage["version"].asLong())
            .andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("SELF_VERIFICATION_FORBIDDEN"))
        val verifiedVoyage = ok(json(marine, "POST", "/api/v1/jobs/$jobId/technical/voyage/${voyage["id"].asString()}/verify",
            mapOf("confirmedFieldCodes" to listOf("ARRIVAL_DRAFT_FWD")), submittedVoyage["version"].asLong()))
        assertEquals("VERIFIED", verifiedVoyage["status"].asString())
        json(marine, "GET", "/api/v1/jobs/$jobId/technical/review")
            .andExpect(jsonPath("$.voyageVersionIds.MOTHER").value(voyage["id"].asString()))

        // AR-02: a later tariff change does not alter the accepted job.
        val admin = login("admin")
        val tariff = ok(json(admin, "GET", "/api/v1/masters/tariffs/$tariffId"))
        ok(json(admin, "PUT", "/api/v1/masters/tariffs/$tariffId", mapOf(
            "code" to tariff["code"].asString(), "name" to tariff["name"].asString(),
            "currency" to "USD", "baseHours" to 48, "baseCharge" to "12345", "extraHourRate" to "300", "effectiveFrom" to "2026-01-01",
        ), tariff["version"].asLong()))
        val after = ok(json(commercial, "GET", "/api/v1/jobs/$jobId"))
        assertMoney("10000.0000", after["tariff"]["baseCharge"])
        assertMoney("250.0000", after["tariff"]["extraHourRate"])
        // Restore the shared seed value for other tests.
        val changed = ok(json(admin, "GET", "/api/v1/masters/tariffs/$tariffId"))
        ok(json(admin, "PUT", "/api/v1/masters/tariffs/$tariffId", mapOf(
            "code" to tariff["code"].asString(), "name" to tariff["name"].asString(),
            "currency" to "USD", "baseHours" to 48, "baseCharge" to "10000", "extraHourRate" to "250", "effectiveFrom" to "2026-01-01",
        ), changed["version"].asLong()))
        jdbc.sql("UPDATE job_request_template SET status = 'DRAFT', name = 'Step 4 vessel document request' WHERE id = UUID_TO_BIN('01920000-0000-7000-8000-000000001001')").update()
    }

    @Test
    fun `commercial records owner rejection of an issued quotation without closing the enquiry`() {
        val commercial = login("commercial")
        val (enquiryId, estimateId) = costed(commercial)
        val draft = quote(commercial, enquiryId, estimateId)
        val versionId = draft["id"].asString()
        val current = ok(json(commercial, "POST", "/api/v1/quotation-versions/$versionId/issue",
            mapOf("issuedTo" to "chartering@demo.invalid", "channel" to "EMAIL"), draft["version"].asLong()))
        val version = current["versions"][0]
        assertTrue((0 until version["allowedActions"].size()).any { version["allowedActions"][it].asString() == "OWNER_REJECT" })
        json(login("approver"), "POST", "/api/v1/quotation-versions/$versionId/owner-rejection", version = version["version"].asLong())
            .andExpect(status().isForbidden)
        val rejected = ok(json(commercial, "POST", "/api/v1/quotation-versions/$versionId/owner-rejection", version = version["version"].asLong()))
        assertEquals("OWNER_REJECTED", rejected["versions"][0]["status"].asString())
        assertEquals("QUOTED", rejected["enquiryStatus"].asString())
        assertTrue(rejected["canCreateVersion"].asBoolean())
        val rejectedActions = (0 until rejected["versions"][0]["allowedActions"].size()).map { rejected["versions"][0]["allowedActions"][it].asString() }
        assertEquals(listOf("EDIT"), rejectedActions)
        assertTrue(rejected["job"].isNull)
        // The issued PDF stays frozen after the owner's rejection: same bytes, no draft watermark or suffix.
        val pdf = mvc.perform(get("/api/v1/quotation-versions/$versionId/pdf").session(commercial)).andExpect(status().isOk).andReturn().response
        assertEquals(version["pdfSha256"].asString(), QuotationPdfRenderer.sha256(pdf.contentAsByteArray))
        assertTrue(!pdf.getHeader(HttpHeaders.CONTENT_DISPOSITION)!!.contains("-draft"))
        json(commercial, "POST", "/api/v1/quotation-versions/$versionId/acceptance",
            mapOf("acceptedAt" to java.time.Instant.now().toString(), "acceptedByName" to "Owner"))
            .andExpect(status().isUnprocessableContent)
    }

    @Test
    fun `an issued quotation rejected by the owner can be revised into version 2`() {
        val admin = login("admin")
        val (enquiryId, estimateId) = costed(admin)
        val draft = quote(admin, enquiryId, estimateId)
        val versionId = draft["id"].asString()
        val issued = ok(json(admin, "POST", "/api/v1/quotation-versions/$versionId/issue",
            mapOf("issuedTo" to "chartering@demo.invalid", "channel" to "EMAIL"), draft["version"].asLong()))
        val rejected = ok(json(admin, "POST", "/api/v1/quotation-versions/$versionId/owner-rejection",
            version = issued["versions"][0]["version"].asLong()))
        assertEquals("OWNER_REJECTED", rejected["versions"][0]["status"].asString())
        assertEquals("QUOTED", rejected["enquiryStatus"].asString())
        assertTrue(ok(json(admin, "GET", "/api/v1/enquiries/$enquiryId/quotation"))["canCreateVersion"].asBoolean())

        // A revision starts from a new estimate version (the first one is locked) and gets version 2.
        val estimate2 = ok(json(admin, "POST", "/api/v1/enquiries/$enquiryId/estimates", mapOf<String, Any>()))
        assertEquals(2, estimate2["versionNo"].asInt())
        assertEquals(3, estimate2["lines"].size()) // copied from version 1
        val v2 = quote(admin, enquiryId, estimate2["id"].asString())
        assertEquals(2, v2["versionNo"].asInt())
        assertNotEquals(versionId, v2["id"].asString())
    }

    @Test
    fun `issuing needs a tariff in the quotation currency and an open version blocks a second one`() {
        val session = login("commercial")
        val (enquiryId, estimateId) = costed(session)
        val draft = ok(json(session, "POST", "/api/v1/enquiries/$enquiryId/quotation/versions",
            mapOf("estimateId" to estimateId, "validUntil" to validUntil)))["versions"][0]
        json(session, "POST", "/api/v1/quotation-versions/${draft["id"].asString()}/issue",
            mapOf("issuedTo" to "chartering@demo.invalid", "channel" to "EMAIL"), draft["version"].asLong())
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.errors[0].field").value("tariffId"))
        json(session, "POST", "/api/v1/enquiries/$enquiryId/quotation/versions",
            mapOf("estimateId" to estimateId, "validUntil" to validUntil))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("QUOTATION_VERSION_OPEN"))
        json(session, "PUT", "/api/v1/quotation-versions/${draft["id"].asString()}",
            mapOf("validUntil" to "2020-01-01", "tariffId" to TARIFF_DEMO), draft["version"].asLong())
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.errors[0].field").value("validUntil"))
    }

    @Test
    fun `a tariff's charging terms cannot change while an open quotation uses it, so the job freezes what was quoted`() {
        val finance = login("sts.admin")
        val tariff = ok(json(finance, "POST", "/api/v1/masters/tariffs", mapOf(
            "code" to "TQ-${UUID.randomUUID().toString().take(5).uppercase()}", "name" to "Synthetic quoted tariff",
            "currency" to "USD", "baseHours" to 48, "baseCharge" to "10000", "extraHourRate" to "250", "effectiveFrom" to "2026-01-01",
        )))
        val tariffUrl = "/api/v1/masters/tariffs/${tariff["id"].asString()}"
        val terms = mapOf("code" to tariff["code"].asString(), "name" to "Synthetic quoted tariff",
            "currency" to "USD", "baseHours" to 48, "baseCharge" to "10000", "extraHourRate" to "250", "effectiveFrom" to "2026-01-01")

        // Before any quotation uses it, the tariff can still be corrected.
        var version = ok(json(finance, "PUT", tariffUrl, terms + ("extraHourRate" to "260"), tariff["version"].asLong()))["version"].asLong()
        version = ok(json(finance, "PUT", tariffUrl, terms, version))["version"].asLong()

        val commercial = login("commercial")
        val (enquiryId, estimateId) = costed(commercial)
        val draft = ok(json(commercial, "POST", "/api/v1/enquiries/$enquiryId/quotation/versions", mapOf(
            "estimateId" to estimateId, "validUntil" to validUntil, "tariffId" to tariff["id"].asString(),
        )))["versions"][0]
        val versionId = draft["id"].asString()
        ok(json(commercial, "POST", "/api/v1/quotation-versions/$versionId/issue",
            mapOf("issuedTo" to "chartering@demo.invalid", "channel" to "EMAIL"), draft["version"].asLong()))

        // While issued: the rate, base and currency are locked; a pending term may still be filled in and the name may change.
        json(finance, "PUT", tariffUrl, terms + ("extraHourRate" to "300"), version)
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("TARIFF_IN_USE"))
            .andExpect(jsonPath("$.errors[0].field").value("extraHourRate"))
        json(finance, "PUT", tariffUrl, terms + ("baseHours" to 72), version).andExpect(jsonPath("$.code").value("TARIFF_IN_USE"))
        version = ok(json(finance, "PUT", tariffUrl, terms + mapOf("graceMinutes" to 30, "name" to "Renamed synthetic tariff"), version))["version"].asLong()
        json(finance, "PUT", tariffUrl, terms + mapOf("graceMinutes" to 60, "name" to "Renamed synthetic tariff"), version)
            .andExpect(jsonPath("$.code").value("TARIFF_IN_USE")).andExpect(jsonPath("$.errors[0].field").value("graceMinutes"))
        json(finance, "PUT", tariffUrl, terms + mapOf("graceMinutes" to 30, "extraHourRate" to "300"), version)
            .andExpect(jsonPath("$.code").value("TARIFF_IN_USE"))

        // Acceptance freezes the quoted terms on the job; afterwards the tariff is free to change for later quotations.
        val jobNum = "STS/MUM/26${UUID.randomUUID().toString().take(5).uppercase()}"
        val accepted = ok(json(commercial, "POST", "/api/v1/quotation-versions/$versionId/acceptance",
            mapOf("acceptedAt" to java.time.Instant.now().toString(), "acceptedByName" to "Demo Charterer Representative", "jobNumber" to jobNum)))
        val job = ok(json(commercial, "GET", "/api/v1/jobs/${accepted["job"]["id"].asString()}"))
        assertEquals(jobNum, job["jobNumber"].asString())
        assertMoney("250", job["tariff"]["extraHourRate"])
        assertEquals(30, job["tariff"]["graceMinutes"].asInt())
        ok(json(finance, "PUT", tariffUrl, terms + mapOf("graceMinutes" to 30, "extraHourRate" to "300"), version))
        assertMoney("250", ok(json(commercial, "GET", "/api/v1/jobs/${accepted["job"]["id"].asString()}"))["tariff"]["extraHourRate"])
        ok(json(commercial, "PUT", "/api/v1/quotation-versions/$versionId", mapOf(
            "validUntil" to validUntil, "tariffId" to tariff["id"].asString(), "paymentTerms" to "Revised terms",
        ), accepted["versions"][0]["version"].asLong()))
        assertMoney("250", ok(json(commercial, "GET", "/api/v1/jobs/${accepted["job"]["id"].asString()}"))["tariff"]["extraHourRate"])
    }

    @Test
    fun `estimate tariffId defaults onto new quotation version and issue requires client on enquiry`() {
        val commercial = login("commercial")
        val (enquiryId, estimateId) = costed(commercial)
        val existingEstimate = ok(json(commercial, "GET", "/api/v1/estimates/$estimateId"))
        ok(json(commercial, "PUT", "/api/v1/estimates/$estimateId", mapOf(
            "currency" to existingEstimate["currency"].asString(),
            "tariffId" to TARIFF_DEMO,
            "lines" to lines,
        ), existingEstimate["version"].asLong()))

        val draft = ok(json(commercial, "POST", "/api/v1/enquiries/$enquiryId/quotation/versions",
            mapOf("estimateId" to estimateId, "validUntil" to validUntil)))["versions"][0]
        assertEquals(TARIFF_DEMO, draft["tariff"]["id"].asString())

        // Without custom scopeOfServices text, the generated PDF lists the estimate's COST lines under Scope of Services Included.
        val pdfBytes = mvc.perform(get("/api/v1/quotation-versions/${draft["id"].asString()}/pdf").session(commercial))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray
        org.apache.pdfbox.Loader.loadPDF(pdfBytes).use { pdf ->
            val text = org.apache.pdfbox.text.PDFTextStripper().getText(pdf)
            assertTrue(text.contains("Scope of Services Included"))
            assertTrue(text.contains("STS cost inclusions"))
            assertTrue(text.contains("Tug Assistance"))
            assertTrue(text.contains("Yokohama Fenders"))
            assertTrue(text.contains("Fender hire"))
            assertTrue(text.contains("Miscellaneous"))
        }

        // Saving an INR exchange rate on the estimate carries into the quotation and renders both USD and INR sections in one Proforma PDF.
        val beforeFxEstimate = ok(json(commercial, "GET", "/api/v1/estimates/$estimateId"))
        ok(json(commercial, "PUT", "/api/v1/estimates/$estimateId", mapOf(
            "currency" to beforeFxEstimate["currency"].asString(),
            "tariffId" to TARIFF_DEMO,
            "inrFxRate" to 85,
            "lines" to lines,
        ), beforeFxEstimate["version"].asLong()))

        val dualPdfBytes = mvc.perform(get("/api/v1/quotation-versions/${draft["id"].asString()}/pdf").session(commercial))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray
        org.apache.pdfbox.Loader.loadPDF(dualPdfBytes).use { pdf ->
            val text = org.apache.pdfbox.text.PDFTextStripper().getText(pdf)
            assertTrue(text.contains("Amount (USD)"))
            assertTrue(text.contains("Amount (INR)"))
            assertTrue(text.contains("Total (USD)"))
            assertTrue(text.contains("Total (INR)"))
            assertTrue(text.contains("1 USD = 85 INR"))
        }

        // An enquiry created without a client can still be costed and drafted, but issuing requires clientId.
        val branchOnly = ok(json(commercial, "POST", "/api/v1/enquiries", mapOf("branchId" to BRANCH_MUM)))
        val branchEnquiryId = branchOnly["id"].asString()
        ok(json(commercial, "POST", "/api/v1/enquiries/$branchEnquiryId/transitions",
            mapOf("to" to "COSTING"), branchOnly["version"].asLong()))
        val est2 = ok(json(commercial, "POST", "/api/v1/enquiries/$branchEnquiryId/estimates", mapOf<String, Any>()))
        val savedEst2 = ok(json(commercial, "PUT", "/api/v1/estimates/${est2["id"].asString()}", mapOf(
            "currency" to "USD",
            "tariffId" to TARIFF_DEMO,
            "lines" to listOf(mapOf(
                "kind" to "REVENUE",
                "costHeadId" to COST_HEAD_STS_SERVICE,
                "description" to "STS fee",
                "quantity" to "1",
                "unitPrice" to "10000",
            )),
        ), est2["version"].asLong()))
        val noClientDraft = ok(json(commercial, "POST", "/api/v1/enquiries/$branchEnquiryId/quotation/versions",
            mapOf("estimateId" to savedEst2["id"].asString(), "validUntil" to validUntil)))["versions"][0]
        json(commercial, "POST", "/api/v1/quotation-versions/${noClientDraft["id"].asString()}/issue",
            mapOf("issuedTo" to "chartering@demo.invalid", "channel" to "EMAIL"), noClientDraft["version"].asLong())
            .andExpect(status().isUnprocessableContent)
            .andExpect(jsonPath("$.code").value("CLIENT_REQUIRED"))
    }

    @Test
    fun `multiple advances track remaining balance after owner acceptance and reopen status when costing increases`() {
        val commercial = login("commercial")
        val (enquiryId, estimateId) = costed(commercial)
        val draft = quote(commercial, enquiryId, estimateId)
        val versionId = draft["id"].asString()

        // Advances cannot be recorded before owner acceptance.
        json(commercial, "POST", "/api/v1/quotation-versions/$versionId/advances",
            mapOf("amount" to "1000"), draft["version"].asLong()).andExpect(status().isUnprocessableContent)

        val issued = ok(json(commercial, "POST", "/api/v1/quotation-versions/$versionId/issue",
            mapOf("issuedTo" to "chartering@demo.invalid", "channel" to "EMAIL"), draft["version"].asLong()))["versions"][0]
        val accepted = ok(json(commercial, "POST", "/api/v1/quotation-versions/$versionId/acceptance",
            mapOf("acceptedAt" to java.time.Instant.now().toString(), "acceptedByName" to "Demo Owner Rep")))["versions"][0]

        // Sub-total 10,000 + 18% GST = 11,800 final amount; initially UNPAID with RECORD_ADVANCE allowed.
        assertMoney("11800", accepted["finalAmount"])
        assertMoney("0", accepted["advancePaid"])
        assertMoney("11800", accepted["advanceRemaining"])
        assertEquals("UNPAID", accepted["advanceStatus"].asString())
        assertTrue(accepted["allowedActions"].any { it.asString() == "RECORD_ADVANCE" })

        // First partial advance (5,000) -> PARTIAL, remaining 6,800.
        val afterFirst = ok(json(commercial, "POST", "/api/v1/quotation-versions/$versionId/advances",
            mapOf("amount" to "5000", "note" to "Wire tranche 1"), accepted["version"].asLong()))["versions"][0]
        assertMoney("5000", afterFirst["advancePaid"])
        assertMoney("6800", afterFirst["advanceRemaining"])
        assertEquals("PARTIAL", afterFirst["advanceStatus"].asString())
        assertEquals(1, afterFirst["advancePayments"].size())

        // Cannot record more than remaining (6,800).
        json(commercial, "POST", "/api/v1/quotation-versions/$versionId/advances",
            mapOf("amount" to "7000"), afterFirst["version"].asLong()).andExpect(status().isBadRequest)

        // Second advance pays remaining 6,800 -> PAID (100%), RECORD_ADVANCE removed, further payment rejected.
        val afterSecond = ok(json(commercial, "POST", "/api/v1/quotation-versions/$versionId/advances",
            mapOf("amount" to "6800", "note" to "Wire tranche 2"), afterFirst["version"].asLong()))["versions"][0]
        assertMoney("11800", afterSecond["advancePaid"])
        assertMoney("0", afterSecond["advanceRemaining"])
        assertEquals("PAID", afterSecond["advanceStatus"].asString())
        assertTrue(afterSecond["allowedActions"].none { it.asString() == "RECORD_ADVANCE" })
        assertEquals(2, afterSecond["advancePayments"].size())
        json(commercial, "POST", "/api/v1/quotation-versions/$versionId/advances",
            mapOf("amount" to "10"), afterSecond["version"].asLong())
            .andExpect(status().isUnprocessableContent)
            .andExpect(jsonPath("$.code").value("ADVANCE_ALREADY_PAID"))

        // Costing increases (revenue 10,000 -> 12,000; final 14,160) -> status reverts PAID -> PARTIAL, remaining 2,360.
        val estNow = ok(json(commercial, "GET", "/api/v1/estimates/$estimateId"))
        ok(json(commercial, "PUT", "/api/v1/estimates/$estimateId", mapOf(
            "currency" to "USD",
            "lines" to listOf(
                lines[0], lines[1],
                mapOf("kind" to "REVENUE", "costHeadId" to COST_HEAD_STS_SERVICE, "description" to "Revised STS", "quantity" to "1", "unitPrice" to "12000"),
            ),
        ), estNow["version"].asLong()))
        val afterIncrease = ok(json(commercial, "GET", "/api/v1/enquiries/$enquiryId/quotation"))["versions"][0]
        assertMoney("14160", afterIncrease["finalAmount"])
        assertMoney("11800", afterIncrease["advancePaid"])
        assertMoney("2360", afterIncrease["advanceRemaining"])
        assertEquals("PARTIAL", afterIncrease["advanceStatus"].asString())
        assertTrue(afterIncrease["allowedActions"].any { it.asString() == "RECORD_ADVANCE" })

        // Third advance settles the newly added 2,360 -> back to PAID (100%).
        val afterThird = ok(json(commercial, "POST", "/api/v1/quotation-versions/$versionId/advances",
            mapOf("amount" to "2360", "note" to "Top-up after revision"), afterIncrease["version"].asLong()))["versions"][0]
        assertMoney("14160", afterThird["advancePaid"])
        assertMoney("0", afterThird["advanceRemaining"])
        assertEquals("PAID", afterThird["advanceStatus"].asString())
        assertEquals(3, afterThird["advancePayments"].size())
    }

    private companion object {
        const val COST_HEAD_FENDERS = "01920000-0000-7000-9000-000000000631"
        const val COST_HEAD_TUGS = "01920000-0000-7000-9000-000000000633"
        const val COST_HEAD_STS_SERVICE = "01920000-0000-7000-9000-000000000635"
        const val TARIFF_DEMO = "01920000-0000-7000-9000-000000000731"
        const val DOC_TYPE_Q88 = "01920000-0000-7000-8000-000000000901"
    }
}
