package com.ship2ship.features.finance

import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.features.technical.JobTestSupport
import org.apache.pdfbox.Loader
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JobCostsApiIT : JobTestSupport() {
    @Test
    fun `FDA is independently approved and issued as a frozen document only with final confirmed time`() {
        val job=newJob()["id"].asString(); val finance=login("ops.manager"); val approver=login("approver"); val admin=login("admin")
        val vendor=ok(call(admin,"POST","/api/v1/resources/vendors",mapOf("name" to "Synthetic FDA vendor ${UUID.randomUUID()}")))["id"].asString()
        val craft=ok(call(admin,"POST","/api/v1/resources/craft",mapOf("vendorId" to vendor,"identifier" to "FDA-${UUID.randomUUID().toString().take(8)}","name" to "Synthetic tug","craftType" to "TUG")))["id"].asString()
        val movement=UuidV7.generate()
        jdbc.sql("""INSERT INTO craft_movement(id,job_id,craft_id,vendor_id,departed_at,returned_at,time_zone,status,created_at,created_by,updated_at,updated_by)
            VALUES(:id,UUID_TO_BIN(:job),UUID_TO_BIN(:craft),UUID_TO_BIN(:vendor),DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 48 HOUR),DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 HOUR),'Asia/Kolkata','ACTIVE',UTC_TIMESTAMP(6),UUID_TO_BIN(:user),UTC_TIMESTAMP(6),UUID_TO_BIN(:user))""")
            .param("id",Uuids.toBytes(movement)).param("job",job).param("craft",craft).param("vendor",vendor).param("user",USER_OPS_MUMBAI).update()
        jdbc.sql("""INSERT INTO job_charge_clock(job_id,start_movement_id,stop_movement_id,basis_hours,created_at,created_by,updated_at,updated_by)
            VALUES(UUID_TO_BIN(:job),:movement,:movement,48,UTC_TIMESTAMP(6),UUID_TO_BIN(:user),UTC_TIMESTAMP(6),UUID_TO_BIN(:user))""")
            .param("job",job).param("movement",Uuids.toBytes(movement)).param("user",USER_OPS_MUMBAI).update()
        jdbc.sql("UPDATE tariff_snapshot SET billing_increment_minutes=60,grace_minutes=0,rounding_rule='ROUND_UP_TO_INCREMENT' WHERE job_id=UUID_TO_BIN(:job)").param("job",job).update()
        val path="/api/v1/jobs/$job/fda"
        var fda=ok(call(finance,"POST",path)); val id=fda["id"].asString()
        fda=ok(call(finance,"PUT","$path/$id",mapOf("reconciliationNote" to "Synthetic reconciliation against final records","noExternalCostsReason" to "Synthetic operation has no third-party invoices"),fda["version"].asLong()))
        fda=ok(call(finance,"POST","$path/$id/actions",mapOf("decision" to "SUBMIT"),fda["version"].asLong()))
        call(finance,"POST","$path/$id/actions",mapOf("decision" to "APPROVE"),fda["version"].asLong()).andExpect(status().isForbidden)
        fda=ok(call(approver,"POST","$path/$id/actions",mapOf("decision" to "APPROVE"),fda["version"].asLong()))
        fda=ok(call(finance,"POST","$path/$id/actions",mapOf("decision" to "ISSUE"),fda["version"].asLong()))
        assertEquals("ISSUED",fda["status"].asString())
        val pdf=mvc.perform(get("$path/$id/pdf").session(finance)).andExpect(status().isOk).andReturn().response.contentAsByteArray
        assertEquals(fda["pdfSha256"].asString(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf)))
        Loader.loadPDF(pdf).use { assertEquals(1, it.numberOfPages) }
        call(finance,"PUT","$path/$id",mapOf("notes" to "Cannot overwrite issued record"),fda["version"].asLong()).andExpect(jsonPath("$.code").value("INVALID_TRANSITION"))
    }

    @Test
    fun `vendor bills need evidence separate approval and frozen conversion rates`() {
        val job=newJob()["id"].asString(); val path="/api/v1/jobs/$job/costs"
        val admin=login("admin"); val finance=login("ops.manager"); val approver=login("finance")
        val currencies = ok(call(finance,"GET","$path/options"))["currencies"]
        assertTrue((0 until currencies.size()).any { currencies[it]["code"].asString() == "USD" && currencies[it]["minorUnits"].asInt() == 2 })
        val vendor=ok(call(admin,"POST","/api/v1/resources/vendors",mapOf("name" to "Synthetic bill vendor ${UUID.randomUUID()}")))["id"].asString()
        var payload=mapOf<String,Any?>("kind" to "BILL","vendorId" to vendor,"reference" to "BILL-${UUID.randomUUID()}","documentDate" to LocalDate.now().toString(),"currency" to "INR","baseRate" to "0.01","jobRate" to "0.01", "baseRateSource" to "Vendor bank", "jobRateSource" to "Vendor bank",
            "lines" to listOf(mapOf("costHeadCode" to "TUGS","description" to "Synthetic tug hire","quantity" to "2","unitPrice" to "10000")))
        call(approver,"POST",path,payload).andExpect(status().isForbidden)
        var bill=ok(call(finance,"POST",path,payload)); val id=bill["id"].asString()
        call(finance,"POST","$path/$id/actions",mapOf("decision" to "SUBMIT"),bill["version"].asLong()).andExpect(status().isBadRequest)
        val file=read(mvc.perform(multipart("$path/files").file(MockMultipartFile("file","vendor.pdf","application/pdf",PDF)).session(finance).with(csrf()))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        payload=payload+mapOf("fileId" to file["id"].asString())
        bill=ok(call(finance,"PUT","$path/$id",payload,bill["version"].asLong()))
        bill=ok(call(finance,"POST","$path/$id/actions",mapOf("decision" to "SUBMIT"),bill["version"].asLong()))
        call(finance,"POST","$path/$id/actions",mapOf("decision" to "APPROVE"),bill["version"].asLong()).andExpect(status().isForbidden)
        bill=ok(call(approver,"POST","$path/$id/actions",mapOf("decision" to "APPROVE"),bill["version"].asLong()))
        fun equalMoney(expected:String,field:String)=assertEquals(0,BigDecimal(expected).compareTo(bill[field].decimalValue()))
        equalMoney("20000","netAmount"); equalMoney("0","taxAmount"); equalMoney("200","baseAmount")
        val summary=ok(call(finance,"GET","$path/summary"))
        assertEquals(0,BigDecimal("200").compareTo(summary["actualGross"].decimalValue()))
        assertEquals(0,BigDecimal("200").compareTo(summary["variance"].decimalValue()))
        val reversed=ok(call(approver,"POST","$path/$id/actions",mapOf("decision" to "REVERSE","reason" to "Synthetic credit received"),bill["version"].asLong()))
        assertEquals("REVERSED",reversed["status"].asString())
        assertTrue(ok(call(finance,"GET","$path/summary"))["actualGross"].decimalValue().signum()==0)
        var fda=ok(call(finance,"POST","/api/v1/jobs/$job/fda"))
        fda=ok(call(finance,"PUT","/api/v1/jobs/$job/fda/${fda["id"].asString()}",mapOf("reconciliationNote" to "Synthetic reconciliation","noExternalCostsReason" to "Reversed vendor bill"),fda["version"].asLong()))
        call(finance,"POST","/api/v1/jobs/$job/fda/${fda["id"].asString()}/actions",mapOf("decision" to "SUBMIT"),fda["version"].asLong())
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.detail").value("Explain why extra hours are not billed: the charge clock is not final or the tariff terms are still pending."))
    }

    // ---------- Phase 5 completion (Phase-5-Proposal §3) ----------

    private fun vendor(): String = ok(call(login("admin"), "POST", "/api/v1/resources/vendors",
        mapOf("name" to "Synthetic vendor ${UUID.randomUUID()}")))["id"].asString()

    private fun evidence(path: String): String = read(mvc.perform(multipart("$path/files").file(MockMultipartFile("file", "vendor.pdf", "application/pdf", PDF))
        .session(login("ops.manager")).with(csrf())).andExpect(status().isOk).andReturn().response.contentAsString)["id"].asString()

    private fun line(head: String = "TUGS", qty: String = "1", price: String = "100") =
        mapOf("costHeadCode" to head, "description" to "Synthetic ${head.lowercase()}", "quantity" to qty, "unitPrice" to price)

    /** Operations submits a cost document; Finance approves it. */
    private fun approved(path: String, payload: Map<String, Any?>): JsonNode {
        val finance = login("ops.manager")
        var doc = ok(call(finance, "POST", path, payload))
        doc = ok(call(finance, "POST", "$path/${doc["id"].asString()}/actions", mapOf("decision" to "SUBMIT"), doc["version"].asLong()))
        val reviewer = if (payload["kind"] == "PO") "approver" else "finance"
        return ok(call(login(reviewer), "POST", "$path/${doc["id"].asString()}/actions", mapOf("decision" to "APPROVE"), doc["version"].asLong()))
    }

    @Test
    fun `POs are numbered at approval, closing releases the commitment, and over-PO and over-budget only warn`() {
        val job = newJob()["id"].asString(); val path = "/api/v1/jobs/$job/costs"; val finance = login("ops.manager")
        // Budget: one synthetic cost line of 150 for tug hire on the accepted estimate.
        jdbc.sql("""INSERT INTO estimate_line(id,estimate_id,line_no,kind,cost_head_id,description,quantity,unit_price,amount)
            SELECT :id,q.estimate_id,99,'COST',UUID_TO_BIN('01920000-0000-7000-9000-000000000633'),'Synthetic tug budget',1,150,150
            FROM sts_job j JOIN quotation_version q ON q.id=j.quotation_version_id WHERE j.id=UUID_TO_BIN(:job)""")
            .param("id", Uuids.toBytes(UuidV7.generate())).param("job", job).update()
        val supplier = vendor()
        val today = LocalDate.now().toString()

        // Field errors point at the line that is wrong.
        call(finance, "POST", path, mapOf("kind" to "PO", "vendorId" to supplier, "reference" to "PO-REQ-1", "documentDate" to today, "currency" to "USD",
            "lines" to listOf(line(qty = "0")))).andExpect(status().isBadRequest).andExpect(jsonPath("$.errors[0].field").value("lines[0].quantity"))

        val po = approved(path, mapOf("kind" to "PO", "vendorId" to supplier, "reference" to "PO-REQ-1", "documentDate" to today, "currency" to "USD",
            "lines" to listOf(line(price = "100"))))
        assertTrue(Regex("""PO/\d{4}/\d{5}""").matches(po["number"].asString()), po["number"].asString())
        var summary = ok(call(finance, "GET", "$path/summary"))
        assertEquals(0, BigDecimal("100").compareTo(summary["committed"].decimalValue()))

        // A bill above what is left on the PO is a warning, not a block; actual above budget warns too.
        val bill = approved(path, mapOf("kind" to "BILL", "vendorId" to supplier, "reference" to "INV-77", "documentDate" to today, "currency" to "USD",
            "purchaseOrderId" to po["id"].asString(), "fileId" to evidence(path), "lines" to listOf(line(price = "180"))))
        assertEquals("APPROVED", bill["status"].asString())
        assertTrue((0 until bill["warnings"].size()).any { bill["warnings"][it]["code"].asString() == "OVER_PURCHASE_ORDER" })
        summary = ok(call(finance, "GET", "$path/summary"))
        assertEquals(0, BigDecimal.ZERO.compareTo(summary["committed"].decimalValue()))
        assertTrue((0 until summary["warnings"].size()).any { summary["warnings"][it]["code"].asString() == "OVER_BUDGET" })
        val tugs = (0 until summary["heads"].size()).map { summary["heads"][it] }.first { it["code"].asString() == "TUGS" }
        assertEquals(0, BigDecimal("30").compareTo(tugs["variance"].decimalValue()))

        // A second PO billed for less is closed with a reason, which releases the rest of the commitment.
        val second = approved(path, mapOf("kind" to "PO", "vendorId" to supplier, "reference" to "PO-REQ-2", "documentDate" to today, "currency" to "USD",
            "lines" to listOf(line(price = "500"))))
        assertEquals(0, BigDecimal("500").compareTo(ok(call(finance, "GET", "$path/summary"))["committed"].decimalValue()))
        call(login("approver"), "POST", "$path/${second["id"].asString()}/actions", mapOf("decision" to "CLOSE"), second["version"].asLong())
            .andExpect(jsonPath("$.code").value("REASON_REQUIRED"))
        val closed = ok(call(login("approver"), "POST", "$path/${second["id"].asString()}/actions", mapOf("decision" to "CLOSE", "reason" to "Synthetic: no more work"),
            second["version"].asLong()))
        assertEquals("CLOSED", closed["status"].asString())
        assertEquals(0, BigDecimal.ZERO.compareTo(ok(call(finance, "GET", "$path/summary"))["committed"].decimalValue()))

        // Read-only roles cannot reverse; the budget export is audited; scope is enforced.
        call(login("commercial"), "POST", "$path/${bill["id"].asString()}/actions", mapOf("decision" to "REVERSE", "reason" to "x"), bill["version"].asLong())
            .andExpect(status().isForbidden)
        val before = auditCount("EXPORT", "JOB_COSTS")
        val xlsx = mvc.perform(get("$path/summary.xlsx").session(finance)).andExpect(status().isOk).andReturn().response.contentAsByteArray
        assertEquals("PK", String(xlsx.copyOf(2)))
        assertEquals(before + 1, auditCount("EXPORT", "JOB_COSTS"))
        ok(call(login("ops.mumbai"), "GET", "$path/summary"))
        call(finance, "GET", "/api/v1/jobs/${UUID.randomUUID()}/costs/summary").andExpect(status().isNotFound)
    }

    @Test
    fun `one vendor invoice can be split across jobs and keeps its entered conversion rate`() {
        val first = newJob()["id"].asString(); val second = newJob()["id"].asString(); val supplier = vendor()
        val source = "Synthetic bank ${UUID.randomUUID()}"
        val reference = "SPLIT-${UUID.randomUUID().toString().take(6)}"
        val bill = mapOf("kind" to "BILL", "vendorId" to supplier, "reference" to reference, "documentDate" to LocalDate.now().toString(),
            "currency" to "INR", "baseRate" to "0.012", "jobRate" to "0.012", "baseRateSource" to source, "jobRateSource" to source,
            "lines" to listOf(line(price = "10000")))
        val finance = login("ops.manager")
        val one = ok(call(finance, "POST", "/api/v1/jobs/$first/costs", bill))
        assertEquals(source, one["baseRateSource"].asString())
        assertEquals(LocalDate.now().toString(), one["baseRateDate"].asString())
        call(finance, "POST", "/api/v1/jobs/$first/costs", bill).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DUPLICATE_REFERENCE"))
        val two = ok(call(finance, "POST", "/api/v1/jobs/$second/costs", bill))
        assertTrue((0 until two["warnings"].size()).any { two["warnings"][it]["code"].asString() == "REFERENCE_ON_OTHER_JOB" })
        // The cross-job list finds both.
        val listed = ok(call(finance, "GET", "/api/v1/finance/cost-documents?kind=BILL&q=$reference"))
        assertEquals(2, listed["items"].size())
        // A missing or invalid document rate is refused.
        call(finance, "POST", "/api/v1/jobs/$first/costs", bill + mapOf("reference" to "INVALID-RATE-1", "baseRate" to "0"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.errors[0].field").value("baseRate"))
    }

    @Test
    fun `an FDA may be issued while extra-hour terms are pending if it says so, and profitability shows income, cost and margin`() {
        val job = newJob()["id"].asString(); val finance = login("ops.manager"); val approver = login("approver"); val path = "/api/v1/jobs/$job/costs"
        // Income: the quotation invoice (USD 10,000 net) issued.
        var invoice = ok(call(finance, "POST", "/api/v1/jobs/$job/invoices", mapOf("fromQuotation" to true)))
        val invoiceUrl = "/api/v1/finance/invoices/${invoice["id"].asString()}"
        invoice = ok(call(finance, "PUT", invoiceUrl, mapOf("invoiceDate" to LocalDate.now().toString(), "dueDate" to LocalDate.now().plusDays(30).toString(),
            "billingName" to invoice["billingName"].asString(), "lines" to listOf(mapOf("source" to "QUOTATION", "costHeadCode" to "STS-SERVICE", "quantity" to "1",
            "unitPrice" to "10000"))), invoice["version"].asLong()))
        invoice = ok(call(finance, "POST", "$invoiceUrl/submit", version = invoice["version"].asLong()))
        invoice = ok(call(login("finance"), "POST", "$invoiceUrl/decision", mapOf("decision" to "APPROVE"), invoice["version"].asLong()))
        ok(call(finance, "POST", "$invoiceUrl/issue", version = invoice["version"].asLong()))
        // Cost: one approved bill of 2,500.
        approved(path, mapOf("kind" to "BILL", "vendorId" to vendor(), "reference" to "COST-1", "documentDate" to LocalDate.now().toString(), "currency" to "USD",
            "fileId" to evidence(path), "lines" to listOf(line(price = "2500"))))

        val fdaPath = "/api/v1/jobs/$job/fda"
        var fda = ok(call(finance, "POST", fdaPath)); val id = fda["id"].asString()
        fda = ok(call(finance, "PUT", "$fdaPath/$id", mapOf("reconciliationNote" to "Synthetic reconciliation",
            "extraHoursNote" to "Synthetic: tariff increment, grace and rounding still pending owner confirmation"), fda["version"].asLong()))
        fda = ok(call(finance, "POST", "$fdaPath/$id/actions", mapOf("decision" to "SUBMIT"), fda["version"].asLong()))
        fda = ok(call(approver, "POST", "$fdaPath/$id/actions", mapOf("decision" to "APPROVE"), fda["version"].asLong()))
        fda = ok(call(finance, "POST", "$fdaPath/$id/actions", mapOf("decision" to "ISSUE"), fda["version"].asLong()))
        assertTrue(Regex("""FDA/\d{4}/\d{5}""").matches(fda["number"].asString()))
        val pdf = mvc.perform(get("$fdaPath/$id/pdf").session(finance)).andExpect(status().isOk).andReturn().response.contentAsByteArray
        assertEquals(fda["pdfSha256"].asString(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf)))
        assertTrue(ok(call(finance, "GET", "$fdaPath/preview")).has("clientOnAccount"))

        val rows = ok(call(login("finance"), "GET", "/api/v1/finance/profitability?size=100"))
        val row = (0 until rows["items"].size()).map { rows["items"][it] }.first { it["jobId"].asString() == job }
        assertEquals(0, BigDecimal("10000").compareTo(row["incomeNet"].decimalValue()))
        assertEquals(0, BigDecimal("2500").compareTo(row["costNet"].decimalValue()))
        assertEquals(0, BigDecimal("7500").compareTo(row["margin"].decimalValue()))
        assertEquals(0, BigDecimal("75.0").compareTo(row["marginPercent"].decimalValue()))
        assertTrue(row["fdaIssued"].asBoolean())
        val before = auditCount("EXPORT", "PROFITABILITY")
        mvc.perform(get("/api/v1/finance/profitability/xlsx").session(login("finance"))).andExpect(status().isOk)
        assertEquals(before + 1, auditCount("EXPORT", "PROFITABILITY"))
        call(login("commercial"), "GET", "/api/v1/finance/profitability").andExpect(status().isForbidden)
    }
}
