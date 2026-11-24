package com.ship2ship.features.reports

import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.core.web.Xlsx
import com.ship2ship.features.technical.JobTestSupport
import com.ship2ship.support.StatementCounter
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import java.lang.management.ManagementFactory
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportsApiIT : JobTestSupport() {

    @Autowired
    private lateinit var transactions: PlatformTransactionManager

    /**
     * Export memory benchmark, run by hand against the synthetic benchmark database (README, "Measuring exports"):
     * allocation on the request thread, time and file size per export, then peak heap and GC with 8 exports at once.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "SHIP2SHIP_BENCH", matches = "true")
    fun `export memory benchmark`() {
        val approver = login("approver")
        val finance = login("finance")
        val job = jdbc.sql("SELECT BIN_TO_UUID(id) FROM sts_job WHERE job_number='BJ-1'").query(String::class.java).single()
        val cases = listOf(
            Triple("jobHistory 1k", approver, "/api/v1/reports/job-history/xlsx?groupBy=CLIENT&from=2016-01-01&to=2017-08-05"),
            Triple("jobHistory 5k", approver, "/api/v1/reports/job-history/xlsx?groupBy=CLIENT&from=2016-01-01&to=2023-12-27"),
            Triple("receivables 1k", finance, "/api/v1/finance/receivables/xlsx?asOf=2040-01-01&currency=AED"),
            Triple("receivables 5k", finance, "/api/v1/finance/receivables/xlsx?asOf=2070-01-01&currency=USD"),
            Triple("profitability 1k", finance, "/api/v1/finance/profitability/xlsx?from=2016-01-01&to=2017-08-05"),
            Triple("profitability 5k", finance, "/api/v1/finance/profitability/xlsx?from=2016-01-01&to=2023-12-27"),
            Triple("jobCosts (5 heads)", finance, "/api/v1/jobs/$job/costs/summary.xlsx"),
            Triple("receivables json 5k", finance, "/api/v1/finance/receivables?asOf=2070-01-01&currency=USD"),
            Triple("receivables json now", finance, "/api/v1/finance/receivables?asOf=2026-10-27"),
            Triple("profitability json", finance, "/api/v1/finance/profitability?from=2016-01-01&to=2023-12-27&size=100"),
        )
        // With SHIP2SHIP_BENCH_OUT set, each output is also written there as text (cells or JSON) to compare runs.
        val out = System.getenv("SHIP2SHIP_BENCH_OUT")?.let { java.io.File(it).apply { mkdirs() } }
        fun dump(name: String, bytes: ByteArray) = out?.resolve(name.replace(' ', '_') + ".txt")?.writeText(
            if (bytes.take(2) == listOf('P'.code.toByte(), 'K'.code.toByte())) XSSFWorkbook(bytes.inputStream()).use { book ->
                book.getSheetAt(0).joinToString("\n") { row -> row.joinToString("\t") { it.toString() } }
            } else String(bytes))
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        fun export(session: org.springframework.mock.web.MockHttpSession, url: String): ByteArray =
            mvc.perform(get(url).session(session)).andExpect(status().isOk).andReturn().response.contentAsByteArray
        for ((name, session, url) in cases) {
            repeat(2) { export(session, url) }
            val runs = (1..3).map {
                val allocated = threads.currentThreadAllocatedBytes
                val started = System.nanoTime()
                val (bytes, statements) = StatementCounter.measure { export(session, url) }
                val millis = (System.nanoTime() - started) / 1_000_000
                val used = threads.currentThreadAllocatedBytes - allocated
                if (it == 1) dump(name, bytes)
                Triple(used, millis, Triple(bytes.size, if (url.contains("xlsx")) XSSFWorkbook(bytes.inputStream()).use { b -> b.getSheetAt(0).lastRowNum } else -1, statements))
            }.sortedBy { it.first }
            val median = runs[1]
            println("EXPORT %-20s allocated %6d MB  time %5d ms  file %5d KB  last row %5d  statements %d".format(name, median.first / 1_048_576, median.second,
                median.third.first / 1024, median.third.second, median.third.third))
        }
        // The workbook alone, 5,000 rows of 8 cells, without the data reads.
        val rows = (1..5_000).map { listOf<Any?>("BJ-$it", "Bench Client $it", "CLOSED", "2020-01-01", BigDecimal("1234.5600"), "VESSEL $it", "LOC", "Propane 1000 MT") }
        repeat(2) { Xlsx.workbook("Bench", "Bench", (1..8).map { "H$it" }, rows) }
        val before = threads.currentThreadAllocatedBytes
        val started = System.nanoTime()
        Xlsx.workbook("Bench", "Bench", (1..8).map { "H$it" }, rows)
        println("EXPORT %-20s allocated %6d MB  time %5d ms".format("workbook only 5k", (threads.currentThreadAllocatedBytes - before) / 1_048_576,
            (System.nanoTime() - started) / 1_000_000))

        // 8 concurrent exports of each 5k case: peak heap in use (sampled) and the largest heap left after a GC.
        val heapPools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == java.lang.management.MemoryType.HEAP }.map { it.name }.toSet()
        val gcs = ManagementFactory.getGarbageCollectorMXBeans()
        for ((name, session, url) in cases.filter { it.first.endsWith("5k") }) {
            System.gc()
            val memory = ManagementFactory.getMemoryMXBean()
            val baseline = memory.heapMemoryUsage.used
            val gcBefore = gcs.sumOf { it.collectionCount } to gcs.sumOf { it.collectionTime }
            val afterGc = java.util.concurrent.atomic.AtomicLong()
            val listener = javax.management.NotificationListener { n, _ ->
                if (n.type == com.sun.management.GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION) {
                    val info = com.sun.management.GarbageCollectionNotificationInfo.from(n.userData as javax.management.openmbean.CompositeData)
                    afterGc.accumulateAndGet(info.gcInfo.memoryUsageAfterGc.filterKeys { it in heapPools }.values.sumOf { it.used }, ::maxOf)
                }
            }
            gcs.forEach { (it as javax.management.NotificationEmitter).addNotificationListener(listener, null, null) }
            val peak = java.util.concurrent.atomic.AtomicLong()
            val sampling = java.util.concurrent.atomic.AtomicBoolean(true)
            val sampler = Thread { while (sampling.get()) { peak.accumulateAndGet(memory.heapMemoryUsage.used, ::maxOf); Thread.sleep(2) } }.apply { start() }
            val started = System.nanoTime()
            val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
            val failed = try {
                (1..8).map { pool.submit<ByteArray> { export(session, url) } }.count { runCatching { it.get() }.isFailure }
            } finally {
                pool.shutdown()
                sampling.set(false)
                sampler.join()
                gcs.forEach { (it as javax.management.NotificationEmitter).removeNotificationListener(listener) }
            }
            println("CONCURRENT %-18s 8 exports in %5d ms  failed %d  peak heap +%5d MB  max after GC +%5d MB  GCs %3d (%d ms)  heap max %d MB".format(name,
                (System.nanoTime() - started) / 1_000_000, failed, (peak.get() - baseline) / 1_048_576, (afterGc.get() - baseline).coerceAtLeast(0) / 1_048_576,
                gcs.sumOf { it.collectionCount } - gcBefore.first, gcs.sumOf { it.collectionTime } - gcBefore.second, memory.heapMemoryUsage.max / 1_048_576))
        }
    }

    @Test
    fun `document register lists the issued quotation within the reader's own kinds and scope`() {
        val job = newJob()
        val number = job["jobNumber"].asString()
        val approver = login("approver")
        val rows = ok(call(approver, "GET", "/api/v1/reports/documents?q=$number"))["items"]
        val quotation = rows.first { it["kind"].asString() == "QUOTATION" }
        assertEquals(number, quotation["jobNumber"].asString())
        assertTrue(quotation["pdfSha256"].asString().length == 64)
        // Kinds the reader cannot open never appear: the technical officer has no invoice permission.
        call(login("technical"), "GET", "/api/v1/reports/documents?kind=INVOICE").andExpect(status().isForbidden)
        call(approver, "GET", "/api/v1/reports/documents?kind=NOPE").andExpect(status().isBadRequest)
    }

    @Test
    fun `job history groups jobs, totals planned cargo per grade and unit, exports and stays permission bound`() {
        val job = newJob()
        val approver = login("approver")
        val report = ok(call(approver, "GET", "/api/v1/reports/job-history?groupBy=CARGO&from=2020-01-01&size=100"))
        assertTrue(report["jobs"]["items"].any { it["jobId"].asString() == job["id"].asString() })
        val propane = report["groups"].first { it["label"].asString() == "Propane" }
        assertTrue(propane["jobs"].asInt() >= 1)
        assertTrue(propane["cargo"].all { it["grade"].asString() == "Propane" })
        val options = ok(call(approver, "GET", "/api/v1/reports/job-history/options"))
        assertTrue(options["cargoGrades"].any { it["name"].asString() == "Propane" })
        val bytes = mvc.perform(get("/api/v1/reports/job-history/xlsx?groupBy=CLIENT").session(approver)).andExpect(status().isOk)
            .andReturn().response.contentAsByteArray
        // The streamed workbook keeps the layout: title, styled header row, groups then jobs, numbers as numbers, column widths.
        XSSFWorkbook(bytes.inputStream()).use { book ->
            val sheet = book.getSheet("Job history")
            val title = sheet.getRow(0).getCell(0)
            assertEquals("Job history by client (planned cargo per grade and unit)", title.stringCellValue)
            assertTrue(book.getFontAt(title.cellStyle.fontIndex).bold)
            val header = sheet.getRow(2)
            assertEquals(listOf("Job / group", "Client / label", "Status / jobs", "Date / cargo", "Discharging vessel", "Receiving vessel",
                "Cargo (planned)"), (0 until 7).map { header.getCell(it).stringCellValue })
            assertEquals(IndexedColors.GREY_25_PERCENT.index, header.getCell(0).cellStyle.fillForegroundColor)
            assertEquals(FillPatternType.SOLID_FOREGROUND, header.getCell(0).cellStyle.fillPattern)
            assertEquals("Group", sheet.getRow(3).getCell(0).stringCellValue)
            assertEquals(CellType.NUMERIC, sheet.getRow(3).getCell(2).cellType)
            assertEquals(listOf(24 * 256, 24 * 256, 16 * 256), (0..2).map { sheet.getColumnWidth(it) })
        }
        call(approver, "GET", "/api/v1/reports/job-history?groupBy=WEEK").andExpect(status().isBadRequest)
        call(login("commercial"), "GET", "/api/v1/reports/job-history").andExpect(status().isForbidden)
    }

    private data class SynthJob(val id: UUID, val branch: String, val client: String, val vessels: List<String>,
        val cargo: List<Triple<String, String, BigDecimal>>, val createdAt: LocalDateTime)

    private fun b(id: String) = Uuids.toBytes(UUID.fromString(id))

    /**
     * Synthetic jobs inserted straight into the tables (enquiry and quotation links are not needed by the report), with
     * two clients and two vessels sharing a name, jobs without vessels or cargo, two units and two branches.
     */
    private fun synthJobs(year: Int, count: Int, clients: List<String>, vessels: List<String>) = (0 until count).map { i ->
        val none = i % 10 == 9
        SynthJob(UuidV7.generate(), if (i % 4 == 0) BRANCH_KDL else BRANCH_MUM, clients[i % clients.size],
            if (none) emptyList() else listOf(vessels[i % 4], vessels[(i + 1) % 4]),
            if (none) emptyList() else buildList {
                add(Triple(GRADE_PROPANE, UNIT_MT, BigDecimal(i % 7 + 1)))
                if (i % 2 == 0) add(Triple(GRADE_BUTANE, UNIT_M3, BigDecimal("2.5")))
                if (i % 6 == 0) add(Triple(GRADE_PROPANE, UNIT_M3, BigDecimal.ONE))
            },
            LocalDateTime.of(year, 1, 1, 0, 0).plusMinutes(i * 97L % (360 * 24 * 60)).plusSeconds(i % 60L))
    }

    private fun insert(jobs: List<SynthJob>) = TransactionTemplate(transactions).executeWithoutResult {
        jdbc.sql("SET FOREIGN_KEY_CHECKS=0").update()
        try {
            jobs.chunked(400).forEach { chunk ->
                jdbc.sql("""INSERT INTO sts_job (id,job_number,branch_id,status,enquiry_id,enquiry_reference,quotation_version_id,quotation_reference,
                    client_id,currency,quoted_amount,created_at,updated_at,version,workflow_revision) VALUES """ +
                    chunk.joinToString(",") { "(?,?,?,'OPEN',?,'SYN',?,'SYN',?,'USD',0,?,?,0,0)" })
                    .params(chunk.flatMap { j -> listOf(Uuids.toBytes(j.id), "SYN-${j.id}", b(j.branch), Uuids.toBytes(UUID.randomUUID()),
                        Uuids.toBytes(UUID.randomUUID()), b(j.client), j.createdAt, j.createdAt) }).update()
                val vessels = chunk.flatMap { j -> j.vessels.mapIndexed { r, v -> Triple(j, if (r == 0) "MOTHER" else "DAUGHTER", v) } }
                if (vessels.isNotEmpty()) jdbc.sql("INSERT INTO job_vessel (id,job_id,role,vessel_id) VALUES " + vessels.joinToString(",") { "(?,?,?,?)" })
                    .params(vessels.flatMap { (j, role, v) -> listOf(Uuids.toBytes(UuidV7.generate()), Uuids.toBytes(j.id), role, b(v)) }).update()
                val cargo = chunk.flatMap { j -> j.cargo.mapIndexed { n, c -> Triple(j, n + 1, c) } }
                if (cargo.isNotEmpty()) jdbc.sql("INSERT INTO job_cargo (id,job_id,line_no,cargo_grade_id,quantity,unit_id) VALUES " + cargo.joinToString(",") { "(?,?,?,?,?,?)" })
                    .params(cargo.flatMap { (j, n, c) -> listOf(Uuids.toBytes(UuidV7.generate()), Uuids.toBytes(j.id), n, b(c.first), c.third, b(c.second)) }).update()
            }
        } finally {
            jdbc.sql("SET FOREIGN_KEY_CHECKS=1").update()
        }
    }

    private fun name(sql: String, id: String) = jdbc.sql(sql).param("id", b(id)).query(String::class.java).single()

    /** The expected groups, worked out here from the inserted model: "key|label|jobs|grade/unit/quantity;…". */
    private fun expected(jobs: List<SynthJob>, groupBy: String): Set<String> {
        val clients = jobs.map { it.client }.distinct().associateWith { name("SELECT legal_name FROM client WHERE id=:id", it) }
        val vessels = jobs.flatMap { it.vessels }.distinct().associateWith { name("SELECT name FROM vessel WHERE id=:id", it) }
        val grades = mapOf(GRADE_PROPANE to "Propane", GRADE_BUTANE to "Butane")
        val units = mapOf(UNIT_MT to "MT", UNIT_M3 to "M3")
        val members: List<Triple<String, String, SynthJob>> = when (groupBy) {
            "CLIENT" -> jobs.map { Triple(it.client, clients.getValue(it.client), it) }
            "MONTH" -> jobs.map { j -> j.createdAt.toString().take(7).let { Triple(it, it, j) } }
            "VESSEL" -> jobs.flatMap { j -> j.vessels.distinct().map { Triple(it, vessels.getValue(it), j) }.ifEmpty { listOf(Triple("—", "—", j)) } }
            else -> jobs.flatMap { j -> j.cargo.map { it.first }.distinct().map { Triple(it, grades.getValue(it), j) } }
        }
        return members.groupBy { it.first to it.second }.map { (k, list) ->
            val lines = list.flatMap { it.third.cargo }.filter { groupBy != "CARGO" || it.first == k.first }
            val cargo = lines.groupBy { grades.getValue(it.first) to units.getValue(it.second) }
                .map { (gu, l) -> Triple(gu.first, gu.second, l.fold(BigDecimal.ZERO) { s, c -> s + c.third }) }
                .sortedWith(compareBy({ it.first }, { it.second }))
            "${k.first}|${k.second}|${list.size}|" + cargo.joinToString(";") { "${it.first}/${it.second}/${it.third.stripTrailingZeros().toPlainString()}" }
        }.toSet()
    }

    private fun actual(report: JsonNode): Set<String> = report["groups"].list().map { g ->
        "${g["key"].asString()}|${g["label"].asString()}|${g["jobs"].asInt()}|" +
            g["cargo"].list().joinToString(";") { "${it["grade"].asString()}/${it["unit"].asString()}/${it["quantity"].decimalValue().stripTrailingZeros().toPlainString()}" }
    }.toSet()

    @Test
    fun `job history totals every matching job in SQL, groups by identity not name, pages the detail and keeps branch scope`() {
        val twinClients = (1..2).map {
            UuidV7.generate().toString().also { id ->
                jdbc.sql("""INSERT INTO client (id,code,legal_name,active,created_at,updated_at,version)
                    VALUES (:id,:code,'Synthetic Twin Charterers',TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)""")
                    .param("id", b(id)).param("code", "TWIN-" + id.takeLast(8)).update()
            }
        }
        val twinVessels = (1..2).map { newVessel().also { id -> jdbc.sql("UPDATE vessel SET name='SYNTHETIC TWIN' WHERE id=:id").param("id", b(id)).update() } }
        val clients = twinClients + CLIENT_CHARTERER
        val vessels = twinVessels + listOf(VESSEL_MOTHER, VESSEL_DAUGHTER)
        val windows = mapOf(2001 to 0, 2002 to 1, 2003 to 25, 2004 to 5_050, 2005 to 1_000, 2006 to 5_000)
        val jobs = windows.mapValues { (year, count) -> synthJobs(year, count, clients, vessels) }
        val scopeId = UuidV7.generate()
        insert(jobs.values.flatten())
        // A branch-restricted reader: the Operations user gets the report permission for Mumbai only.
        jdbc.sql("""INSERT INTO user_role_scope (id,user_id,role_id,branch_id,valid_from,created_at)
            VALUES (:id,:user,UUID_TO_BIN('01920000-0000-7000-8000-000000000002'),:branch,'2000-01-01',UTC_TIMESTAMP(6))""")
            .param("id", Uuids.toBytes(scopeId)).param("user", b(USER_OPS_MUMBAI)).param("branch", b(BRANCH_MUM)).update()
        try {
            val approver = login("approver")
            val restricted = login("ops.mumbai")
            fun report(session: org.springframework.mock.web.MockHttpSession, year: Int, groupBy: String, page: Int = 0) =
                ok(call(session, "GET", "/api/v1/reports/job-history?from=$year-01-01&to=$year-12-31&groupBy=$groupBy&page=$page&size=25"))

            val (big, statements) = StatementCounter.measure { report(approver, 2004, "VESSEL") }
            val started = System.nanoTime()
            report(approver, 2004, "CLIENT")
            val millis = (System.nanoTime() - started) / 1_000_000
            println("STATEMENTS jobHistory=$statements jobs=${big["jobs"]["totalElements"].asLong()} clientReportMs=$millis")
            assertTrue(statements <= 25, "job history used $statements statements")

            for ((year, model) in jobs) {
                for (groupBy in listOf("CLIENT", "VESSEL", "CARGO", "MONTH")) {
                    val result = report(approver, year, groupBy)
                    assertEquals(expected(model, groupBy), actual(result), "$year $groupBy")
                    val counts = result["groups"].list().map { it["jobs"].asInt() }
                    assertEquals(counts.sortedDescending(), counts, "groups are ordered by job count")
                    assertEquals(model.size.toLong(), result["jobs"]["totalElements"].asLong())
                    assertEquals(false, result["capped"].asBoolean())
                }
            }
            // Same-name masters stay apart; a job carrying both twin vessels counts once in each.
            val twins = report(approver, 2004, "VESSEL")["groups"].list().filter { it["label"].asString() == "SYNTHETIC TWIN" }
            assertEquals(twinVessels.toSet(), twins.map { it["key"].asString() }.toSet())

            // The detail is paged newest first over all 5,050 jobs, not only the first 5,000.
            val newest = jobs.getValue(2004).sortedByDescending { it.createdAt }
            assertEquals(newest.take(25).map { it.id.toString() }, big["jobs"]["items"].list().map { it["jobId"].asString() })
            val last = report(approver, 2004, "CLIENT", page = 201)
            assertEquals(newest.drop(201 * 25).map { it.id.toString() }, last["jobs"]["items"].list().map { it["jobId"].asString() })
            val exported = mvc.perform(get("/api/v1/reports/job-history/xlsx?groupBy=CLIENT&from=2004-01-01&to=2004-12-31")
                .session(approver)).andExpect(status().isOk).andReturn().response.contentAsByteArray
            XSSFWorkbook(exported.inputStream()).use { book ->
                val sheet = book.getSheet("Job history")
                assertEquals(5_050, (3..sheet.lastRowNum).count { sheet.getRow(it)?.getCell(0)?.stringCellValue?.startsWith("SYN-") == true })
            }
            val profitability = mvc.perform(get("/api/v1/finance/profitability/xlsx?from=2004-01-01&to=2004-12-31")
                .session(login("finance"))).andExpect(status().isOk).andReturn().response.contentAsByteArray
            XSSFWorkbook(profitability.inputStream()).use { book ->
                val sheet = book.getSheet("Profitability")
                assertEquals(5_050, (3..sheet.lastRowNum).count { sheet.getRow(it)?.getCell(0)?.stringCellValue?.startsWith("SYN-") == true })
            }

            val mumbai = jobs.getValue(2004).filter { it.branch == BRANCH_MUM }
            for (groupBy in listOf("CLIENT", "VESSEL", "CARGO")) assertEquals(expected(mumbai, groupBy), actual(report(restricted, 2004, groupBy)))
            assertEquals(mumbai.size.toLong(), report(restricted, 2004, "MONTH")["jobs"]["totalElements"].asLong())
        } finally {
            jdbc.sql("DELETE FROM user_role_scope WHERE id=:id").param("id", Uuids.toBytes(scopeId)).update()
            val ids = jobs.values.flatten().map { Uuids.toBytes(it.id) }
            ids.chunked(1_000).forEach { chunk ->
                jdbc.sql("DELETE FROM job_cargo WHERE job_id IN (:ids)").param("ids", chunk).update()
                jdbc.sql("DELETE FROM job_vessel WHERE job_id IN (:ids)").param("ids", chunk).update()
                jdbc.sql("DELETE FROM sts_job WHERE id IN (:ids)").param("ids", chunk).update()
            }
            jdbc.sql("DELETE FROM client WHERE id IN (:ids)").param("ids", twinClients.map(::b)).update()
        }
    }
}

private const val UNIT_M3 = "01920000-0000-7000-9000-000000000602"

private fun JsonNode.list(): List<JsonNode> = (0 until size()).map { get(it) }
