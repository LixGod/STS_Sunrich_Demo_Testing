package com.ship2ship.features.movements

import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.movements.ChargeClockLookup
import com.ship2ship.core.persistence.UuidV7
import com.ship2ship.features.jobs.AlertService
import com.ship2ship.support.StatementCounter
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import com.ship2ship.features.technical.JobTestSupport
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MovementsApiIT : JobTestSupport() {
    @Autowired private lateinit var clockLookup: ChargeClockLookup

    private fun fixture(): Pair<String, String> {
        val job = newJob()["id"].asString()
        val admin = login("admin")
        val ops = login("ops.mumbai")
        val suffix = UUID.randomUUID().toString().take(8).uppercase()
        val cert = "MOVE_CERT_$suffix"
        ok(call(admin, "POST", "/api/v1/resources/certificate-types", mapOf("code" to cert, "label" to "Synthetic movement test")))
        val model = ok(call(admin, "POST", "/api/v1/resources/models", mapOf("categoryCode" to "PRIMARY_FENDER", "code" to "MOVE_MODEL_$suffix", "name" to "Synthetic fender")))
        val asset = ok(call(admin, "POST", "/api/v1/resources/assets", mapOf("modelId" to model["id"].asString(), "assetIdentifier" to "MOVE-FENDER-$suffix")))
        ok(call(admin, "POST", "/api/v1/resources/requirements",
            mapOf("resourceKind" to "ASSET", "scopeCode" to "PRIMARY_FENDER", "certificateTypeCode" to cert)))
        val policy = ok(call(admin, "GET", "/api/v1/resources/policy"))
        ok(call(admin, "PUT", "/api/v1/resources/policy", mapOf("mode" to "WARN",
            "warningDays" to null, "requirementsConfigured" to true), policy["version"].asLong()))
        val path = "/api/v1/jobs/$job/resources/allocations"
        val start = "2026-10-09T00:00:00Z"; val end = "2026-10-18T00:00:00Z"
        val a = ok(call(ops, "POST", path, mapOf("kind" to "ASSET", "resourceId" to asset["id"].asString(),
            "startAt" to start, "endAt" to end)))
        return job to a["id"].asString()
    }

    private fun trip(job: String, allocation: String, depart: String, returned: String?) =
        ok(call(login("ops.mumbai"), "POST", "/api/v1/jobs/$job/movements", mapOf(
            "departedAt" to depart, "returnedAt" to returned, "allocationIds" to listOf(allocation))))

    @Test
    fun `jetty departure uses entered time and active equipment without craft`() {
        val (job, allocation) = fixture()
        val ops = login("ops.mumbai")
        val path = "/api/v1/jobs/$job/movements/context"
        assertEquals("Asia/Kolkata", ok(call(ops, "GET", path))["timeZone"].asString())
        val now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        jdbc.sql("UPDATE job_resource_allocation SET start_at=:start,end_at=:end WHERE job_id=:job")
            .param("start", Uuids.toTimestamp(now.minusSeconds(60)))
            .param("end", Uuids.toTimestamp(now.plusSeconds(3600)))
            .param("job", Uuids.toBytes(UUID.fromString(job))).update()
        val departedAt = now.minusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val movement = ok(call(ops, "POST", "/api/v1/jobs/$job/movements/dispatch", mapOf("departedAt" to departedAt.toString())))
        assertEquals(allocation, movement["assets"][0]["allocationId"].asString())
        assertEquals(departedAt, Instant.parse(movement["departedAt"].asString()))
        assertTrue(movement["craftId"].isNull)
        assertEquals(movement["id"].asString(), ok(call(ops, "GET", "/api/v1/jobs/$job/movements/clock"))["clock"]["startMovementId"].asString())
        call(ops, "POST", "/api/v1/jobs/$job/movements/dispatch", mapOf("departedAt" to now.toString()))
            .andExpect(status().isUnprocessableContent)
        val voided = ok(call(ops, "POST", "/api/v1/jobs/$job/movements/${movement["id"].asString()}/void",
            mapOf("reason" to "Replace departure"), movement["version"].asLong()))
        ok(call(login("ops.manager"), "POST", "/api/v1/jobs/$job/movements/${movement["id"].asString()}/decision",
            mapOf("decision" to "APPROVE", "reason" to "Approved void"), voided["version"].asLong()))
        val futureAt = now.plusSeconds(1200)
        val recorded = ok(call(ops, "POST", "/api/v1/jobs/$job/movements/dispatch", mapOf("departedAt" to futureAt.toString())))
        assertEquals(futureAt, Instant.parse(recorded["departedAt"].asString()))
        assertEquals(allocation, recorded["assets"][0]["allocationId"].asString())
        call(ops, "POST", "/api/v1/jobs/$job/resources/allocations", mapOf("kind" to "CRAFT",
            "resourceId" to UUID.randomUUID().toString(), "startAt" to now.minusSeconds(60).toString(),
            "endAt" to now.plusSeconds(3600).toString())).andExpect(status().isBadRequest)
    }

    @Test
    fun `designated movements basis approval pending tariff and exact charge`() {
        val (job, allocation) = fixture()
        val ops = login("ops.mumbai"); val admin = login("ops.manager")
        val root = "/api/v1/jobs/$job/movements"
        val first = trip(job, allocation, "2026-10-10T00:00:00Z", "2026-10-12T01:07:00Z")
        var overview = ok(call(ops, "GET", "$root/clock"))
        var selected = overview["selection"]
        assertEquals("PENDING_TARIFF_TERMS", overview["clock"]["tariffTermsStatus"].asString())
        assertTrue(overview["clock"]["additionalCharge"].isNull)
        assertEquals(first["id"].asString(), overview["clock"]["startMovementId"].asString())
        assertEquals(first["id"].asString(), overview["clock"]["stopMovementId"].asString())
        assertEquals("DRAFT", overview["notice"]["status"].asString())
        ok(call(ops, "GET", "$root/clock"))
        assertEquals(1L, jdbc.sql("SELECT COUNT(*) FROM additional_hours_notice WHERE job_id=:job")
            .param("job", Uuids.toBytes(UUID.fromString(job))).query(Long::class.java).single())
        val released = ok(call(admin, "POST", "$root/clock/notice/release",
            version = overview["notice"]["version"].asLong()))
        assertEquals("RELEASED", released["status"].asString())
        assertEquals("RELEASED", ok(call(ops, "GET", "$root/clock"))["notice"]["status"].asString())
        selected = ok(call(admin, "POST", "$root/clock/basis", mapOf("basisHours" to 24,
            "reason" to "Synthetic 24-hour agreement"), selected["version"].asLong()))
        overview = ok(call(ops, "GET", "$root/clock"))
        assertEquals("2026-10-10T00:00:00Z", overview["clock"]["startAt"].asString())
        assertEquals("2026-10-12T01:07:00Z", overview["clock"]["stopAt"].asString())
        assertEquals(24, overview["clock"]["basisHours"].asInt())
        assertEquals("DRAFT", overview["notice"]["status"].asString())
        assertEquals(2L, jdbc.sql("SELECT COUNT(*) FROM additional_hours_notice WHERE job_id=:job")
            .param("job", Uuids.toBytes(UUID.fromString(job))).query(Long::class.java).single())
        val corrected = ok(call(ops, "PUT", "$root/${first["id"].asString()}", mapOf(
            "departedAt" to "2026-10-13T00:00:00Z", "returnedAt" to "2026-10-15T00:10:00Z",
            "allocationIds" to listOf(allocation), "reason" to "Synthetic nominated trip"), first["version"].asLong()))
        call(ops, "POST", "$root/${first["id"].asString()}/decision", mapOf("decision" to "APPROVE", "reason" to "Synthetic check"),
            corrected["version"].asLong()).andExpect(status().isUnprocessableContent)
        ok(call(admin, "POST", "$root/${first["id"].asString()}/decision", mapOf("decision" to "APPROVE",
            "reason" to "Synthetic independent approval"), corrected["version"].asLong()))
        overview = ok(call(ops, "GET", "$root/clock"))
        selected = overview["selection"]
        assertEquals(first["id"].asString(), overview["clock"]["startMovementId"].asString())
        assertEquals("2026-10-13T00:00:00Z", overview["clock"]["startAt"].asString())
        selected = ok(call(admin, "POST", "$root/clock/basis", mapOf("basisHours" to 72,
            "reason" to "Synthetic 72-hour agreement"), selected["version"].asLong()))
        overview = ok(call(ops, "GET", "$root/clock"))
        assertEquals(0, overview["clock"]["extraHours"].asInt())
        assertEquals("2026-10-15T00:10:00Z", overview["clock"]["stopAt"].asString())
        selected = ok(call(admin, "POST", "$root/clock/basis", mapOf("basisHours" to 48,
            "reason" to "Synthetic 48-hour agreement"), selected["version"].asLong()))
        val jobDetail = ok(call(login("sts.admin"), "GET", "/api/v1/jobs/$job"))
        ok(call(login("sts.admin"), "PUT", "/api/v1/jobs/$job/tariff-terms", mapOf(
            "billingIncrementMinutes" to 15, "graceMinutes" to 2, "roundingRule" to "ROUND_UP_TO_INCREMENT",
            "reason" to "Synthetic agreed increment and grace"), jobDetail["version"].asLong()))
        overview = ok(call(ops, "GET", "$root/clock"))
        assertEquals("CONFIRMED", overview["clock"]["tariffTermsStatus"].asString())
        assertEquals("0.250000", overview["clock"]["billableExtraUnits"].decimalValue().setScale(6).toPlainString())
        assertEquals("62.50", overview["clock"]["additionalCharge"].decimalValue().setScale(2).toPlainString())
        assertEquals(48, selected["basisHours"].asInt())
        val lookup = clockLookup.forJob(UUID.fromString(job))
        assertEquals(48, lookup.basisHours)
        assertEquals("62.50", lookup.additionalCharge!!.setScale(2).toPlainString())
    }

    @Test
    fun `missing return stays ongoing and correction requires a second person`() {
        val (job, allocation) = fixture()
        val ops = login("ops.mumbai"); val admin = login("approver")
        val root = "/api/v1/jobs/$job/movements"
        val movement = trip(job, allocation, "2026-10-10T00:00:00Z", null)
        val clock = ok(call(ops, "GET", "$root/clock"))["clock"]
        assertFalse(clock["final"].asBoolean())
        assertTrue(clock["additionalCharge"].isNull)
        val edit = mapOf("departedAt" to "2026-10-10T01:00:00Z",
            "returnedAt" to null, "allocationIds" to listOf(allocation), "reason" to "Corrected log")
        val requested = ok(call(ops, "PUT", "$root/${movement["id"].asString()}", edit, movement["version"].asLong()))
        assertEquals("CORRECT", requested["pendingAction"].asString())
        assertEquals("2026-10-10T00:00:00Z", requested["departedAt"].asString())
        call(ops, "POST", "$root/${movement["id"].asString()}/decision",
            mapOf("decision" to "APPROVE", "reason" to "Checked"), requested["version"].asLong())
            .andExpect(status().isUnprocessableContent)
        val approved = ok(call(admin, "POST", "$root/${movement["id"].asString()}/decision",
            mapOf("decision" to "APPROVE", "reason" to "Checked against log"), requested["version"].asLong()))
        assertEquals("2026-10-10T01:00:00Z", approved["departedAt"].asString())
        assertEquals("2026-10-10T01:00:00Z", ok(call(ops, "GET", "$root/clock"))["clock"]["startAt"].asString())
        ok(call(ops, "POST", "$root/${movement["id"].asString()}/return",
            mapOf("returnedAt" to "2026-10-11T01:00:00Z"), approved["version"].asLong()))
        val afterReturn = ok(call(ops, "GET", "$root/clock"))["clock"]
        assertTrue(afterReturn["final"].asBoolean())
        assertEquals("2026-10-11T01:00:00Z", afterReturn["stopAt"].asString())
    }

    @Test
    fun `short return blocks recovery closure until authorised resolution and job scope holds`() {
        val (job, allocation) = fixture()
        val ops = login("ops.mumbai"); val admin = login("ops.manager")
        trip(job, allocation, "2026-10-10T00:00:00Z", "2026-10-11T00:00:00Z")
        val root = "/api/v1/jobs/$job/recovery"
        var recovery = ok(call(ops, "GET", root))
        assertEquals(1, recovery["unresolved"].size())
        recovery = ok(call(ops, "POST", "$root/disembark", mapOf("at" to "2026-10-11T01:00:00Z"), 0))
        recovery = ok(call(ops, "PUT", "$root/assets/$allocation", mapOf("status" to "SHORT", "notes" to "One synthetic fender short")))
        call(admin, "POST", "$root/close", version = recovery["version"].asLong())
            .andExpect(status().isUnprocessableContent)
        val asset = recovery["assets"][0]
        recovery = ok(call(admin, "POST", "$root/assets/$allocation/resolve", mapOf("reason" to "Synthetic settlement recorded"),
            asset["version"].asLong()))
        assertTrue(recovery["unresolved"].isEmpty)
        assertEquals("CLOSED", ok(call(admin, "POST", "$root/close", version = recovery["version"].asLong()))["status"].asString())
        // Closure takes the short unit out of the usable stock.
        val assetId = ok(call(ops, "GET", "/api/v1/jobs/$job/resources/allocations"))["items"]
            .first { it["id"].asString() == allocation }["resourceId"].asString()
        assertEquals("LOST", ok(call(login("admin"), "GET", "/api/v1/resources/assets/$assetId"))["status"].asString())
        call(ops, "POST", "/api/v1/jobs/$job/movements", mapOf(
            "departedAt" to "2026-10-10T02:00:00Z", "allocationIds" to listOf(allocation)))
            .andExpect(status().isUnprocessableContent)
        val otherJob = newJob()["id"].asString()
        call(ops, "GET", "/api/v1/jobs/$otherJob/movements/${ok(call(ops,"GET","/api/v1/jobs/$job/movements"))["items"][0]["id"].asString()}")
            .andExpect(status().isNotFound)
        call(login("commercial"), "GET", root).andExpect(status().isForbidden)
    }

    @Autowired private lateinit var alerts: AlertService
    @Autowired private lateinit var dataSource: javax.sql.DataSource

    /**
     * Background monitor benchmark, run by hand against the volume database (README, "Measuring"): statements and time of
     * one clock-monitor pass (every open clock, then only the candidates) and of one alert refresh, and how long a row lock
     * that receipts and job workflows take waits while a refresh is running.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "SHIP2SHIP_BENCH", matches = "true")
    fun `background monitor benchmark`() {
        fun median(values: List<Double>) = values.sorted()[values.size / 2]
        fun pass(name: String, ids: () -> List<UUID>) {
            repeat(2) { ids().forEach(clocks::captureOverrun) } // the first pass raises the due notices and syncs
            val runs = (1..5).map {
                val started = System.nanoTime()
                val (count, statements) = StatementCounter.measure { ids().also { it.forEach(clocks::captureOverrun) }.size }
                Triple(count, statements, (System.nanoTime() - started) / 1e6)
            }
            println("MONITOR %-22s checked %5d clocks  statements %6d  time %8.1f ms".format(name, runs[0].first, runs.map { it.second }.sorted()[2],
                median(runs.map { it.third })))
        }
        pass("clock, every open", {
            jdbc.sql("SELECT c.job_id FROM job_charge_clock c JOIN sts_job j ON j.id=c.job_id WHERE j.status NOT IN ('CLOSED','CANCELLED')")
                .query { rs, _ -> Uuids.fromBytes(rs.getBytes(1))!! }.list()
        })
        pass("clock, candidates", monitor::candidates)

        repeat(2) { alerts.refresh() }
        val refresh = (1..5).map { val started = System.nanoTime(); val (_, s) = StatementCounter.measure { alerts.refresh() }; s to (System.nanoTime() - started) / 1e6 }
        val refreshMs = median(refresh.map { it.second })
        println("MONITOR %-22s statements %6d  time %8.1f ms (one transaction)".format("alert refresh", refresh.map { it.first }.sorted()[2], refreshMs))

        // A lock a user request takes, tried half-way through a refresh; it waits for the refresh to commit if the refresh holds it.
        val invoice = jdbc.sql("""SELECT i.id FROM invoice i WHERE i.doc_type='INVOICE' AND i.status='ISSUED' AND i.due_date<UTC_DATE()
            AND NOT EXISTS (SELECT 1 FROM receipt_allocation a WHERE a.invoice_id=i.id) LIMIT 1""").query { rs, _ -> rs.getBytes(1) }.single()
        val openJob = jdbc.sql("SELECT c.job_id FROM job_charge_clock c JOIN sts_job j ON j.id=c.job_id WHERE j.status='OPEN' LIMIT 1")
            .query { rs, _ -> rs.getBytes(1) }.single()
        for ((what, sql, key) in listOf(Triple("invoice row (receipt allocation)", "SELECT id FROM invoice WHERE id=? FOR UPDATE", invoice),
            Triple("job row (workflow, clock)", "SELECT id FROM sts_job WHERE id=? FOR UPDATE", openJob))) {
            fun lock() = dataSource.connection.use { c ->
                c.autoCommit = false
                val started = System.nanoTime()
                c.prepareStatement(sql).use { it.setBytes(1, key); it.executeQuery().close() }
                ((System.nanoTime() - started) / 1e6).also { c.rollback() }
            }
            val idle = (1..5).map { lock() }
            val waits = (1..5).map {
                val refresher = Thread { alerts.refresh() }.apply { start() }
                Thread.sleep((refreshMs * 0.6).toLong())
                lock().also { refresher.join() }
            }
            println("LOCKWAIT %-34s no refresh %5.1f ms; during refresh: median %7.1f ms  max %7.1f ms".format(what, median(idle), median(waits), waits.max()))
        }
    }

    @Autowired private lateinit var monitor: ClockMonitor
    @Autowired private lateinit var clocks: ChargeClockService
    @Autowired private lateinit var transactions: PlatformTransactionManager

    @Test
    fun `the clock monitor checks only clocks that can need work and ends in the same state as checking every clock`() {
        val now = Instant.now()
        fun b(id: String) = Uuids.toBytes(UUID.fromString(id))
        fun movement(job: String, departedHoursAgo: Long, returnedHoursAgo: Long?, status: String = "ACTIVE") = UuidV7.generate().toString().also {
            jdbc.sql("""INSERT INTO craft_movement (id,job_id,departed_at,returned_at,time_zone,status,created_at,created_by,updated_at,updated_by,version)
                VALUES (:id,:job,:d,:r,'UTC',:s,UTC_TIMESTAMP(6),:u,UTC_TIMESTAMP(6),:u,0)""").param("id", b(it)).param("job", b(job))
                .param("d", Uuids.toTimestamp(now.minusSeconds(departedHoursAgo * 3600)))
                .param("r", returnedHoursAgo?.let { h -> Uuids.toTimestamp(now.minusSeconds(h * 3600)) }).param("s", status).param("u", b(USER_OPS_MUMBAI)).update()
        }
        fun clock(job: String, start: String, stop: String?) =
            jdbc.sql("""INSERT INTO job_charge_clock (job_id,start_movement_id,stop_movement_id,basis_hours,created_at,created_by,updated_at,updated_by,version)
                VALUES (:job,:start,:stop,24,UTC_TIMESTAMP(6),:u,UTC_TIMESTAMP(6),:u,0)""").param("job", b(job)).param("start", b(start))
                .param("stop", stop?.let(::b)).param("u", b(USER_OPS_MUMBAI)).update()

        val job = listOf("stoppedInBasis", "runningInBasis", "runningOver", "overWithNotice", "outOfSync", "corrected", "voidedStart", "closed")
            .associateWith { newJob()["id"].asString() }
        movement(job.getValue("stoppedInBasis"), 30, 20).let { clock(job.getValue("stoppedInBasis"), it, it) }
        movement(job.getValue("runningInBasis"), 5, null).let { clock(job.getValue("runningInBasis"), it, null) }
        movement(job.getValue("runningOver"), 30, null).let { clock(job.getValue("runningOver"), it, null) }
        movement(job.getValue("overWithNotice"), 60, 30).let { clock(job.getValue("overWithNotice"), it, it) }
        clocks.captureOverrun(UUID.fromString(job.getValue("overWithNotice")))
        movement(job.getValue("outOfSync"), 10, 8).let { clock(job.getValue("outOfSync"), it, it) }
        movement(job.getValue("outOfSync"), 2, null)
        // A corrected return time is a new charging episode, which needs its own notice.
        movement(job.getValue("corrected"), 60, 30).let { m ->
            clock(job.getValue("corrected"), m, m)
            clocks.captureOverrun(UUID.fromString(job.getValue("corrected")))
            jdbc.sql("UPDATE craft_movement SET returned_at=returned_at + INTERVAL 1 HOUR WHERE id=:id").param("id", b(m)).update()
        }
        movement(job.getValue("voidedStart"), 10, null, "VOIDED").let { clock(job.getValue("voidedStart"), it, null) }
        movement(job.getValue("voidedStart"), 3, null)
        movement(job.getValue("closed"), 40, null).let { clock(job.getValue("closed"), it, null) }
        jdbc.sql("UPDATE sts_job SET status='CLOSED' WHERE id=:id").param("id", b(job.getValue("closed"))).update()

        val ours = job.values.map(UUID::fromString).toSet()
        val candidates = monitor.candidates().filter { it in ours }
        assertEquals(listOf("runningOver", "overWithNotice", "outOfSync", "corrected", "voidedStart").map { UUID.fromString(job.getValue(it)) }.toSet(),
            candidates.toSet())

        // Each pass runs in a transaction that is rolled back, from the same starting state.
        fun outcome(ids: Collection<UUID>): List<String> {
            var state = emptyList<String>()
            TransactionTemplate(transactions).executeWithoutResult { tx ->
                ids.forEach(clocks::captureOverrun)
                val keys = ours.map { Uuids.toBytes(it) }
                state = jdbc.sql("SELECT CONCAT('clock ',BIN_TO_UUID(job_id),' ',BIN_TO_UUID(start_movement_id),' ',IFNULL(BIN_TO_UUID(stop_movement_id),'-')) FROM job_charge_clock WHERE job_id IN (:ids)")
                    .param("ids", keys).query(String::class.java).list().filterNotNull() +
                    jdbc.sql("SELECT CONCAT('notice ',BIN_TO_UUID(job_id),' ',episode_key) FROM additional_hours_notice WHERE job_id IN (:ids)")
                        .param("ids", keys).query(String::class.java).list().filterNotNull()
                tx.setRollbackOnly()
            }
            return state.sorted()
        }
        // Before narrowing, the monitor checked every clock of an open job.
        val everyOpen = ours - UUID.fromString(job.getValue("closed"))
        val expected = outcome(everyOpen)
        assertEquals(expected, outcome(candidates))
        assertEquals(2, expected.count { it.startsWith("notice ${job.getValue("corrected")}") })
        assertTrue(expected.any { it.startsWith("notice ${job.getValue("runningOver")}") })
        assertFalse(expected.any { it.startsWith("notice ${job.getValue("stoppedInBasis")}") || it.startsWith("notice ${job.getValue("runningInBasis")}") })
    }
}
