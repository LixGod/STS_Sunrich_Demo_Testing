package com.ship2ship.features.jobs

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.jobs.JobFinancialClosure
import com.ship2ship.core.persistence.StatusHistory
import com.ship2ship.core.persistence.TransitionRecord
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.BusinessRuleException
import com.ship2ship.core.web.FieldError
import com.ship2ship.core.web.NotFoundException
import com.ship2ship.core.web.Versioning
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class JobTransitionInput(val to: JobStatus, val reason: String? = null, val overrideReason: String? = null)
data class JobApprovalRequest(val stage: String)
data class JobWorkflowIssue(val code: String, val message: String)
data class JobWorkflowAction(val to: String, val reasonRequired: Boolean, val blockers: List<JobWorkflowIssue>)
data class JobWorkflowDto(val status: String, val revision: Int, val version: Long,
    val readinessRequestedBy: UUID?, val closureRequestedBy: UUID?, val canRequest: Boolean,
    val canOverride: Boolean, val actions: List<JobWorkflowAction>, val history: List<TransitionRecord>)

@Service
class JobWorkflowService(private val jdbc: JdbcClient, private val access: AccessPolicy,
    private val history: StatusHistory, private val audit: AuditService, private val clock: Clock,
    private val financialClosure: JobFinancialClosure) {
    private data class Row(val id: UUID, val branch: UUID, val status: String,
        val revision: Int, val version: Long, val readinessBy: UUID?, val closureBy: UUID?, val resume: String?)
    private data class Rule(val to: String, val permission: String, val reason: Boolean)

    @Transactional
    fun sync(id: UUID): JobWorkflowDto {
        var current = row(id, true)
        require(current, Permissions.JOB_READ)
        while (true) {
            val next = when (current.status) {
                "OPEN" -> "DOCUMENTS_PENDING"
                "DOCUMENTS_PENDING" -> if (count(id, "SELECT COUNT(*) FROM job_document_request WHERE job_id=:job") > 0 &&
                    count(id, "SELECT COUNT(*) FROM job_request_item i JOIN job_document_request r ON r.id=i.request_id WHERE r.job_id=:job AND i.required=TRUE AND i.phase='PRE_ARRIVAL' AND i.status NOT IN ('RECEIVED','REVIEWED')") == 0L) "PLANNING" else null
                "APPROVED_FOR_READINESS" -> "MOBILISING"
                "MOBILISING" -> if (count(id, "SELECT COUNT(*) FROM operation_event WHERE job_id=:job AND status='ACTIVE' AND type_code='FENDERS_HOSES_DELIVERED'") > 0) "IN_PROGRESS" else null
                "IN_PROGRESS" -> if (count(id, "SELECT COUNT(*) FROM operation_event WHERE job_id=:job AND status='ACTIVE' AND type_code='HOSES_DISCONNECTED'") > 0) "DEMOBILISING" else null
                "DEMOBILISING" -> if (count(id, "SELECT COUNT(*) FROM job_recovery WHERE job_id=:job AND status='CLOSED'") > 0) "REPORT_PENDING" else null
                "REPORT_PENDING" -> if (count(id, "SELECT COUNT(*) FROM final_report_revision r JOIN final_report f ON f.id=r.report_id WHERE f.job_id=:job AND r.status='ISSUED'") > 0) "FINANCIAL_CLOSING" else null
                "FINANCIAL_CLOSING" -> if (count(id, "SELECT COUNT(*) FROM fda_revision WHERE job_id=:job AND status IN ('APPROVED','ISSUED')") > 0) "CLOSED" else null
                else -> null
            } ?: break
            if (blockers(id, next).isNotEmpty()) break
            jdbc.sql("UPDATE sts_job SET status=:to,closure_requested_by=NULL,closure_requested_at=NULL,updated_at=:now,updated_by=:actor,version=version+1 WHERE id=:id")
                .param("to", next).param("now", Uuids.toTimestamp(clock.instant()))
                .param("actor", Uuids.toBytes(access.currentUser().id)).param("id", Uuids.toBytes(id)).update()
            history.record("JOB", id, current.status, next, "Stage completed", access.currentUser().id)
            audit.record(AuditEntry(AuditAction.STATUS_CHANGE, "JOB", id, current.branch,
                mapOf("from" to current.status, "to" to next, "automatic" to true)))
            current = row(id, true)
        }
        return overview(id)
    }

    @Transactional(readOnly = true)
    fun overview(id: UUID): JobWorkflowDto {
        val row = row(id)
        require(row, Permissions.JOB_READ)
        val user = access.currentUser()
        return JobWorkflowDto(row.status, row.revision, row.version, row.readinessBy, row.closureBy,
            user.scopeFor(Permissions.JOB_WRITE).coversBranch(row.branch),
            user.scopeFor(Permissions.JOB_OVERRIDE).coversBranch(row.branch),
            rules(row).filter { user.scopeFor(it.permission).coversBranch(row.branch) &&
                (it.to != "APPROVED_FOR_READINESS" || row.readinessBy != user.id) &&
                (it.to != "CLOSED" || row.closureBy != user.id) }
                .map { JobWorkflowAction(it.to, it.reason, if(row.status=="CLOSED") emptyList() else blockers(id, it.to)) }, history.history("JOB", id))
    }

    @Transactional
    fun request(id: UUID, expected: Long, input: JobApprovalRequest): JobWorkflowDto {
        val row = row(id, true)
        require(row, Permissions.JOB_WRITE)
        Versioning.checkCurrent(expected, row.version)
        val readiness = when (input.stage) {
            "READINESS" -> true
            "CLOSURE" -> false
            else -> throw BadRequestException("VALIDATION_FAILED", "Choose READINESS or CLOSURE.")
        }
        if (row.status != if (readiness) "PLANNING" else "FINANCIAL_CLOSING")
            throw BusinessRuleException("INVALID_TRANSITION", "Request approval from the corresponding workflow stage.")
        if ((if (readiness) row.readinessBy else row.closureBy) != null)
            throw BusinessRuleException("INVALID_TRANSITION", "An approval is already requested.")
        val column = if (readiness) "readiness" else "closure"
        jdbc.sql("UPDATE sts_job SET ${column}_requested_by=:actor,${column}_requested_at=:now,updated_at=:now,updated_by=:actor,version=version+1 WHERE id=:id")
            .param("actor", Uuids.toBytes(access.currentUser().id)).param("now", Uuids.toTimestamp(clock.instant()))
            .param("id", Uuids.toBytes(id)).update()
        audit.record(AuditEntry(AuditAction.UPDATE, "JOB", id, row.branch, mapOf("approvalRequested" to input.stage)))
        return overview(id)
    }

    @Transactional
    fun transition(id: UUID, expected: Long, input: JobTransitionInput): JobWorkflowDto {
        val row = row(id, true)
        val rule = rules(row).firstOrNull { it.to == input.to.name }
            ?: throw BusinessRuleException("INVALID_TRANSITION", "This workflow transition is not enabled.")
        require(row, rule.permission)
        Versioning.checkCurrent(expected, row.version)
        val why = input.reason?.trim()?.takeIf { it.isNotEmpty() }
        val override = input.overrideReason?.trim()?.takeIf { it.isNotEmpty() }
        if ((why?.length ?: 0) > 500 || (override?.length ?: 0) > 500)
            throw BadRequestException("VALIDATION_FAILED", "Reasons may contain at most 500 characters.")
        if (rule.reason && why == null) throw BadRequestException("REASON_REQUIRED", "Enter a reason for this change.")
        if (override != null) require(row, Permissions.JOB_OVERRIDE)
        val issues = if (row.status == "CLOSED") emptyList() else blockers(id, rule.to)
        if (issues.isNotEmpty() && override == null) throw BadRequestException("JOB_BLOCKED", "Complete the required job tasks first.",
            issues.map { FieldError(it.code, "required", it.message) })
        val requester = when (input.to) {
            JobStatus.APPROVED_FOR_READINESS -> row.readinessBy
            JobStatus.CLOSED -> row.closureBy
            else -> null
        }
        if (input.to in setOf(JobStatus.APPROVED_FOR_READINESS, JobStatus.CLOSED)) {
            if (requester == null) throw BusinessRuleException("INVALID_TRANSITION", "A colleague must request this approval first.")
            if (requester == access.currentUser().id) throw BusinessRuleException("SELF_APPROVAL_FORBIDDEN", "A different person must approve this request.")
        }
        val reopen = row.status == "CLOSED"
        val resume = if (input.to in setOf(JobStatus.ON_HOLD, JobStatus.SUSPENDED)) row.status else null
        jdbc.sql("""UPDATE sts_job SET status=:to,resume_status=:resume,workflow_revision=workflow_revision+:revision,
            readiness_requested_by=CASE WHEN :reopen THEN NULL ELSE readiness_requested_by END,
            readiness_requested_at=CASE WHEN :reopen THEN NULL ELSE readiness_requested_at END,
            closure_requested_by=CASE WHEN :reopen THEN NULL ELSE closure_requested_by END,
            closure_requested_at=CASE WHEN :reopen THEN NULL ELSE closure_requested_at END,
            updated_at=:now,updated_by=:actor,version=version+1 WHERE id=:id""")
            .param("to", input.to.name).param("resume", resume).param("revision", if (reopen) 1 else 0).param("reopen", reopen)
            .param("now", Uuids.toTimestamp(clock.instant())).param("actor", Uuids.toBytes(access.currentUser().id))
            .param("id", Uuids.toBytes(id)).update()
        val recordedReason = listOfNotNull(why, override?.let { "Management override: $it" }).joinToString("; ").takeIf { it.isNotEmpty() }
        history.record("JOB", id, row.status, input.to.name, recordedReason, access.currentUser().id)
        audit.record(AuditEntry(AuditAction.STATUS_CHANGE, "JOB", id, row.branch,
            mapOf("from" to row.status, "to" to input.to.name, "reason" to why, "overrideReason" to override,
                "overriddenBlockers" to if (override == null) emptyList<String>() else issues.map { it.code }, "revision" to row.revision + if (reopen) 1 else 0)))
        return overview(id)
    }

    private fun blockers(id: UUID, target: String): List<JobWorkflowIssue> = buildList {
        fun missing(code: String, message: String, sql: String) {
            if (count(id, sql) == 0L) add(JobWorkflowIssue(code, message))
        }
        fun pending(code: String, message: String, sql: String) {
            if (count(id, sql) > 0L) add(JobWorkflowIssue(code, message))
        }
        if (target in setOf("APPROVED_FOR_READINESS", "MOBILISING")) {
            missing("documents", "Create and review the required per-vessel document request.",
                "SELECT COUNT(*) FROM job_document_request WHERE job_id=:job")
            pending("documentsPending", "Review all required pre-arrival documents.",
                "SELECT COUNT(*) FROM job_request_item i JOIN job_document_request r ON r.id=i.request_id WHERE r.job_id=:job AND i.required=TRUE AND i.phase='PRE_ARRIVAL' AND i.status<>'REVIEWED'")
            missing("jpo", "Complete the Joint Plan of Operation.",
                "SELECT COUNT(*) FROM jpo_revision r JOIN jpo j ON j.id=r.jpo_id WHERE j.job_id=:job AND r.status IN ('DONE','ISSUED')")
            missing("mooring", "Issue the mooring plan.",
                "SELECT COUNT(*) FROM mooring_plan_revision r JOIN mooring_plan p ON p.id=r.plan_id WHERE p.job_id=:job AND r.status='ISSUED'")
            missing("acceptances", "Record the three acceptances on the completed JPO.",
                "SELECT COUNT(*) FROM jpo_revision r JOIN jpo j ON j.id=r.jpo_id WHERE j.job_id=:job AND r.status IN ('DONE','ISSUED') AND (SELECT COUNT(*) FROM jpo_acceptance a WHERE a.revision_id=r.id)=3")
            pending("allocationExceptions", "Decide pending allocation exceptions.",
                "SELECT COUNT(*) FROM job_resource_allocation WHERE job_id=:job AND status='PENDING_EXCEPTION'")
        }
        if (target == "IN_PROGRESS") missing("operationStarted", "Record the start of the operation.",
            "SELECT COUNT(*) FROM operation_event WHERE job_id=:job AND status='ACTIVE' AND type_code='FENDERS_HOSES_DELIVERED'")
        if (target in setOf("DEMOBILISING", "CLOSED")) {
            pending("cargo", "Record a final reading for each cargo grade.",
                "SELECT COUNT(*) FROM job_cargo c WHERE c.job_id=:job AND NOT EXISTS (SELECT 1 FROM cargo_reading r WHERE r.job_cargo_id=c.id AND r.kind='FINAL')")
        }
        if (target in setOf("REPORT_PENDING", "CLOSED")) {
            missing("recovery", "Complete and close equipment recovery.", "SELECT COUNT(*) FROM job_recovery WHERE job_id=:job AND status='CLOSED'")
            pending("movements", "Record all craft returns and decide pending corrections.",
                "SELECT COUNT(*) FROM craft_movement WHERE job_id=:job AND status='ACTIVE' AND (returned_at IS NULL OR pending_action IS NOT NULL)")
            pending("documentsPendingAll", "Review all required operational documents and signed checklists.",
                "SELECT COUNT(*) FROM job_request_item i JOIN job_document_request r ON r.id=i.request_id WHERE r.job_id=:job AND i.required=TRUE AND i.status<>'REVIEWED'")
        }
        if (target in setOf("FINANCIAL_CLOSING", "CLOSED")) {
            missing("finalReport", "Issue the final operation report.",
                "SELECT COUNT(*) FROM final_report_revision r JOIN final_report f ON f.id=r.report_id WHERE f.job_id=:job AND r.status='ISSUED'")
        }
        if (target == "CLOSED") {
            pending("incidents", "Close pending incidents and corrective actions.", "SELECT COUNT(*) FROM job_incident WHERE job_id=:job AND status<>'CLOSED'")
            pending("invoices", "Finish draft invoices and credit notes.", "SELECT COUNT(*) FROM invoice WHERE job_id=:job AND status<>'ISSUED'")
            pending("planningApprovals", "Finish pending JPO approvals.",
                "SELECT COUNT(*) FROM jpo_revision r JOIN jpo j ON j.id=r.jpo_id WHERE j.job_id=:job AND r.status IN ('DRAFT','SUBMITTED','APPROVED')")
            pending("mooringApprovals", "Finish pending mooring approvals.",
                "SELECT COUNT(*) FROM mooring_plan_revision r JOIN mooring_plan p ON p.id=r.plan_id WHERE p.job_id=:job AND r.status IN ('DRAFT','SUBMITTED','APPROVED')")
            pending("clockApprovals", "Decide pending charge clock changes and adjustments.",
                "SELECT (SELECT COUNT(*) FROM job_charge_clock WHERE job_id=:job AND pending_start_id IS NOT NULL)+(SELECT COUNT(*) FROM clock_adjustment WHERE job_id=:job AND status='PENDING')")
            missing("fda", "Issue and reconcile the Final Disbursement Account.",
                "SELECT COUNT(*) FROM fda_revision WHERE job_id=:job AND status IN ('APPROVED','ISSUED')")
            if(!financialClosure.reconciled(id)) add(JobWorkflowIssue("financeReconciliation", "Reconcile current costs, outstanding commitments, invoices and receipts against the issued FDA."))
        }
    }

    private fun count(id: UUID, sql: String): Long = jdbc.sql(sql).param("job", Uuids.toBytes(id)).query(Long::class.java).single()
    private fun rules(row: Row): List<Rule> =
        if (row.status in setOf("ON_HOLD", "SUSPENDED") && row.resume != null) listOf(Rule(row.resume, Permissions.JOB_OVERRIDE, true))
        else jdbc.sql("SELECT to_status,permission_code,reason_required FROM job_transition_rule WHERE from_status=:from AND enabled=TRUE ORDER BY to_status")
            .param("from", row.status).query { rs, _ -> Rule(rs.getString(1), rs.getString(2), rs.getBoolean(3)) }.list()
    private fun require(row: Row, permission: String) = access.requireBranch(permission, row.branch, "JOB", row.id)
    private fun row(id: UUID, locked: Boolean = false): Row = jdbc.sql("""SELECT branch_id,status,workflow_revision,version,
        readiness_requested_by,closure_requested_by,resume_status FROM sts_job WHERE id=:id${if (locked) " FOR UPDATE" else ""}""")
        .param("id", Uuids.toBytes(id)).query { rs, _ -> Row(id, Uuids.fromBytes(rs.getBytes(1))!!,
            rs.getString(2), rs.getInt(3), rs.getLong(4), Uuids.fromBytes(rs.getBytes(5)), Uuids.fromBytes(rs.getBytes(6)), rs.getString(7)) }
        .optional().orElseThrow { NotFoundException("Job") }
}

@RestController
@RequestMapping("/api/v1/jobs/{id}/workflow")
class JobWorkflowController(private val service: JobWorkflowService) {
    @GetMapping fun get(@PathVariable id: UUID) = service.overview(id)
    @PostMapping("/sync") fun sync(@PathVariable id: UUID) = service.sync(id)
    @PostMapping("/requests") fun request(@PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) version: String?, @RequestBody input: JobApprovalRequest) =
        service.request(id, Versioning.requireIfMatch(version), input)
    @PostMapping("/transitions") fun transition(@PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) version: String?, @RequestBody input: JobTransitionInput) =
        service.transition(id, Versioning.requireIfMatch(version), input)
}
