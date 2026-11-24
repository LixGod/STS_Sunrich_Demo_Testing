package com.ship2ship.features.jobs

import com.ship2ship.features.technical.JobTestSupport
import org.junit.jupiter.api.Test
import com.ship2ship.core.audit.Uuids
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.assertTrue
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals

class JobWorkflowIT : JobTestSupport() {
    @Test
    fun `overview sync advances only completed ordinary stages`() {
        val id = newJob()["id"].asString()
        val path = "/api/v1/jobs/$id/workflow"
        val ops = login("ops.mumbai")
        val first = ok(call(ops, "POST", "$path/sync", emptyMap<String, String>()))
        assertEquals("DOCUMENTS_PENDING", first["status"].asString())
        val second = ok(call(ops, "POST", "$path/sync", emptyMap<String, String>()))
        assertEquals("DOCUMENTS_PENDING", second["status"].asString())
        assertEquals(first["history"].size(), second["history"].size())
    }

    @Test
    fun `readiness is scoped versioned and blocked until the technical evidence is complete`() {
        val id=newJob()["id"].asString(); val path="/api/v1/jobs/$id/workflow"
        val ops=login("ops.mumbai")
        var state=ok(call(ops,"GET",path))
        call(login("finance"),"POST","$path/transitions",mapOf("to" to "DOCUMENTS_PENDING"),state["version"].asLong()).andExpect(status().isForbidden)
        state=ok(call(ops,"POST","$path/transitions",mapOf("to" to "DOCUMENTS_PENDING"),state["version"].asLong()))
        call(ops,"POST","$path/transitions",mapOf("to" to "PLANNING"),state["version"].asLong()-1).andExpect(status().isConflict)
        state=ok(call(ops,"POST","$path/transitions",mapOf("to" to "PLANNING"),state["version"].asLong()))
        state=ok(call(ops,"POST","$path/requests",mapOf("stage" to "READINESS"),state["version"].asLong()))
        call(login("marine"),"POST","$path/transitions",mapOf("to" to "APPROVED_FOR_READINESS"),state["version"].asLong())
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("JOB_BLOCKED"))
        assertEquals("PLANNING",ok(call(ops,"GET",path))["status"].asString())
    }

    @Test
    fun `closure requires a different approver and an audited override then reopening preserves history`() {
        val id=newJob()["id"].asString(); val path="/api/v1/jobs/$id/workflow"
        jdbc.sql("UPDATE sts_job SET status='FINANCIAL_CLOSING' WHERE id=UUID_TO_BIN(:id)").param("id",id).update()
        val manager=login("ops.manager"); val approver=login("approver")
        var state=ok(call(manager,"GET",path))
        state=ok(call(manager,"POST","$path/requests",mapOf("stage" to "CLOSURE"),state["version"].asLong()))
        call(approver,"POST","$path/transitions",mapOf("to" to "CLOSED"),state["version"].asLong())
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("JOB_BLOCKED"))
        val close=mapOf("to" to "CLOSED","overrideReason" to "Synthetic management exception with outstanding tasks")
        call(manager,"POST","$path/transitions",close,state["version"].asLong()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
        state=ok(call(approver,"POST","$path/transitions",close,state["version"].asLong()))
        call(login("ops.mumbai"),"POST","/api/v1/jobs/$id/operations/events",mapOf("typeCode" to "ALL_FAST","occurredAt" to "2026-10-10T00:00:00Z"))
            .andExpect(jsonPath("$.code").value("JOB_LOCKED"))
        call(approver,"POST","$path/transitions",mapOf("to" to "REPORT_PENDING"),state["version"].asLong()).andExpect(jsonPath("$.code").value("REASON_REQUIRED"))
        state=ok(call(approver,"POST","$path/transitions",mapOf("to" to "REPORT_PENDING","reason" to "Synthetic reopening for report correction"),state["version"].asLong()))
        assertEquals(2,state["revision"].asInt())
        assertEquals("CLOSED",state["history"][1]["toStatus"].asString())
    }

    @Autowired private lateinit var alerts: AlertService
    @Autowired private lateinit var transactions: PlatformTransactionManager

    /** The refresh as it was before it was split into UPDATE and insert-new (one upsert per rule), kept as the reference. */
    private fun previousRefresh() {
        val now = Uuids.toTimestamp(java.time.Instant.now())
        val rules = jdbc.sql("SELECT code,lead_minutes,escalation_minutes,enabled FROM alert_rule ORDER BY code").query { rs, _ ->
            listOf(rs.getString(1), rs.getObject(2) as Int?, rs.getObject(3) as Int?, rs.getBoolean(4)) }.list()
        for ((code, lead, escalation, enabled) in rules) {
            val source = alerts.sources[code as String] ?: continue
            if (enabled as Boolean) jdbc.sql("""INSERT INTO job_alert(id,job_id,rule_code,source_key,due_at,created_at,seen_at)
                SELECT UUID_TO_BIN(UUID()),s.job,:code,s.source,s.due,:now,:now FROM ($source) s JOIN sts_job j ON j.id=s.job
                WHERE (j.status NOT IN ('CLOSED','CANCELLED') OR :code='RECEIVABLE')
                ON DUPLICATE KEY UPDATE job_alert.version=job_alert.version+IF(job_alert.condition_active=FALSE OR NOT(job_alert.due_at <=> s.due),1,0),
                job_alert.condition_active=TRUE,job_alert.due_at=s.due,job_alert.seen_at=:now""")
                .param("code", code).param("now", now).param("lead", lead).update()
            jdbc.sql("UPDATE job_alert SET condition_active=FALSE,version=version+1 WHERE rule_code=:code AND condition_active=TRUE AND seen_at<:now")
                .param("code", code).param("now", now).update()
            if (escalation != null) jdbc.sql("""UPDATE job_alert SET escalated_at=:now,version=version+1
                WHERE rule_code=:code AND state<>'RESOLVED' AND condition_active=TRUE AND escalated_at IS NULL
                AND TIMESTAMPDIFF(MINUTE,COALESCE(due_at,created_at),:now)>=:minutes""")
                .param("code", code).param("now", now).param("minutes", escalation).update()
            if (code in setOf("CLOCK_DUE", "OVERRUN")) jdbc.sql("""UPDATE job_alert SET state='OPEN',escalated_at=COALESCE(escalated_at,:now),version=version+1
                WHERE rule_code=:code AND condition_active=TRUE AND due_at IS NOT NULL AND due_at<=:now AND (state<>'OPEN' OR escalated_at IS NULL)""")
                .param("code", code).param("now", now).update()
        }
    }

    /**
     * After [refresh], rolled back: a fingerprint of every alert (count and checksums) and the job's own alerts in full.
     * Times written by that run read RUN, so two runs compare by content.
     */
    private fun alertsAfter(job: String, refresh: () -> Unit): Pair<String, List<String>> {
        var result = "" to emptyList<String>()
        TransactionTemplate(transactions).apply { isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED }.executeWithoutResult { tx ->
            val started = Uuids.toTimestamp(java.time.Instant.now().minusMillis(1))
            refresh()
            val row = """CONCAT_WS('|',BIN_TO_UUID(job_id),rule_code,source_key,IFNULL(due_at,'-'),condition_active,state,version,
                IF(created_at>=:t,'RUN',created_at),IF(seen_at>=:t,'RUN',seen_at),IFNULL(IF(escalated_at>=:t,'RUN',escalated_at),'-'))"""
            result = jdbc.sql("SELECT CONCAT_WS(' ',COUNT(*),BIT_XOR(CRC32($row)),SUM(CRC32($row))) FROM job_alert").param("t", started)
                .query(String::class.java).single() to
                jdbc.sql("SELECT $row FROM job_alert WHERE job_id=UUID_TO_BIN(:job) ORDER BY 1").param("t", started).param("job", job)
                    .query(String::class.java).list().filterNotNull()
            tx.setRollbackOnly()
        }
        return result
    }

    @Test
    fun `the split alert refresh leaves every alert exactly as the previous upsert did`() {
        val job = newJob()["id"].asString()
        alerts.refresh()
        val transitions = jdbc.sql("SELECT BIN_TO_UUID(id) FROM workflow_transition WHERE entity_type='JOB' AND entity_id=UUID_TO_BIN(:job) ORDER BY occurred_at")
            .param("job", job).query(String::class.java).list().filterNotNull()
        assertTrue(transitions.isNotEmpty())
        // From that state: a re-dated change, an alert switched off by hand, a source that no longer exists, a new change, escalation on.
        jdbc.sql("UPDATE workflow_transition SET occurred_at=occurred_at - INTERVAL 2 HOUR WHERE id=UUID_TO_BIN(:id)").param("id", transitions.first()).update()
        jdbc.sql("UPDATE job_alert SET condition_active=FALSE WHERE job_id=UUID_TO_BIN(:job) AND rule_code='JOB_CHANGE' AND source_key=HEX(UUID_TO_BIN(:id))")
            .param("job", job).param("id", transitions.last()).update()
        jdbc.sql("""INSERT INTO job_alert(id,job_id,rule_code,source_key,due_at,created_at,seen_at) VALUES (UUID_TO_BIN(UUID()),UUID_TO_BIN(:job),'JOB_CHANGE',
            'GONE',NULL,UTC_TIMESTAMP(6) - INTERVAL 1 DAY,UTC_TIMESTAMP(6) - INTERVAL 1 DAY)""").param("job", job).update()
        jdbc.sql("""INSERT INTO workflow_transition (id,entity_type,entity_id,from_status,to_status,occurred_at)
            VALUES (UUID_TO_BIN(UUID()),'JOB',UUID_TO_BIN(:job),'OPEN','OPEN',UTC_TIMESTAMP(6) - INTERVAL 3 HOUR)""").param("job", job).update()
        jdbc.sql("UPDATE alert_rule SET escalation_minutes=60 WHERE code='JOB_CHANGE'").update()
        try {
            val expected = alertsAfter(job, ::previousRefresh)
            assertEquals(expected, alertsAfter(job, alerts::refresh))
            println("ALERTS same after both refreshes: ${expected.first} (count, xor, sum)")
            val ours = expected.second.filter { it.startsWith("$job|JOB_CHANGE|") }
            assertTrue(ours.any { it.contains("|GONE|") && it.split('|')[4] == "0" }, "the vanished source is switched off")
            assertTrue(ours.count { it.split('|')[7] == "RUN" } == 1, "only the new change is inserted")
            assertTrue(ours.any { it.split('|')[9] == "RUN" }, "escalation applied")
        } finally {
            jdbc.sql("UPDATE alert_rule SET escalation_minutes=NULL WHERE code='JOB_CHANGE'").update()
        }
    }

    @Test
    fun `the alert refresh raises each job change once and keeps it on the next refresh`() {
        val job = newJob()["id"].asString()
        fun rows() = jdbc.sql("SELECT CONCAT(rule_code,' ',source_key,' ',condition_active) FROM job_alert WHERE job_id=UUID_TO_BIN(:job) ORDER BY 1")
            .param("job", job).query(String::class.java).list().filterNotNull()
        alerts.refresh()
        val first = rows()
        assertTrue(first.any { it.startsWith("JOB_CHANGE ") && it.endsWith(" 1") }, first.toString())
        alerts.refresh()
        assertEquals(first, rows())
    }
}
