package com.ship2ship.features.reports

import com.ship2ship.core.audit.AuditAction
import com.ship2ship.core.audit.AuditEntry
import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.audit.Uuids
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.CodeRef
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.security.ReferenceLookup
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.FieldError
import com.ship2ship.core.web.PageResponse
import com.ship2ship.core.web.Xlsx
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

private fun UUID.bytes(): ByteArray = Uuids.toBytes(this)
private fun ByteArray?.uuid(): UUID? = Uuids.fromBytes(this)
private fun ResultSet.utc(column: Int): Instant? = getObject(column, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)

/** One generated document (FR-DOC-01): any revision of a quotation, JPO, mooring plan, SOF, final report, invoice or FDA. */
data class RegisterRow(
    val kind: String, val id: UUID, val number: String?, val revision: Int, val status: String,
    val jobId: UUID?, val jobNumber: String?, val enquiryId: UUID?, val issuedAt: Instant?, val issuedBy: String?,
    val updatedAt: Instant, val pdfSha256: String?,
)

data class NamedOption(val id: UUID, val name: String)
data class CargoTotal(val grade: String, val unit: String, val quantity: BigDecimal)
data class HistoryJob(
    val jobId: UUID, val jobNumber: String, val status: String, val jobDate: LocalDate, val client: String?,
    val motherVessel: String?, val daughterVessel: String?, val cargo: List<CargoTotal>,
)
data class HistoryGroup(val key: String, val label: String, val jobs: Int, val cargo: List<CargoTotal>)
data class JobHistory(val groupBy: String, val groups: List<HistoryGroup>, val jobs: PageResponse<HistoryJob>, val capped: Boolean)
data class HistoryOptions(val clients: List<NamedOption>, val vessels: List<NamedOption>, val cargoGrades: List<NamedOption>)
data class HistoryFilter(val from: LocalDate?, val to: LocalDate?, val clientId: UUID?, val vesselId: UUID?, val cargoGradeId: UUID?,
    val status: String?)

/**
 * Reports & History (navigation group 6): the register of every generated document and the job history report.
 * Read-only; every row stays inside the user's entity/branch scope for the permission of its own kind.
 */
@Service
class ReportsService(private val jdbc: JdbcClient, private val policy: AccessPolicy, private val references: ReferenceLookup,
    private val audit: AuditService) {

    // Each kind: its read permission and a SELECT returning the shared register columns plus the branch for scope.
    private val kinds = linkedMapOf(
        "QUOTATION" to (Permissions.QUOTATION_READ to """SELECT 'QUOTATION' kind,v.id,q.reference number,v.version_no revision,v.status,
            j.id job_id,j.job_number,q.enquiry_id,v.issued_at,v.issued_by,v.updated_at,v.pdf_sha256,q.branch_id br
            FROM quotation_version v JOIN quotation q ON q.id=v.quotation_id LEFT JOIN sts_job j ON j.quotation_version_id=v.id"""),
        "JPO" to (Permissions.TECH_JPO_READ to """SELECT 'JPO',r.id,NULL,r.revision_no,r.status,j.id,j.job_number,j.enquiry_id,
            r.issued_at,r.issued_by,r.updated_at,r.pdf_sha256,j.branch_id
            FROM jpo_revision r JOIN jpo p ON p.id=r.jpo_id JOIN sts_job j ON j.id=p.job_id"""),
        "MOORING_PLAN" to (Permissions.TECH_MOORING_READ to """SELECT 'MOORING_PLAN',r.id,NULL,r.revision_no,r.status,j.id,j.job_number,
            j.enquiry_id,r.issued_at,r.issued_by,r.updated_at,r.pdf_sha256,j.branch_id
            FROM mooring_plan_revision r JOIN mooring_plan p ON p.id=r.plan_id JOIN sts_job j ON j.id=p.job_id"""),
        "SOF" to (Permissions.OPS_LIVE_READ to """SELECT 'SOF',r.id,NULL,r.revision_no,r.status,j.id,j.job_number,j.enquiry_id,
            r.issued_at,r.issued_by,COALESCE(r.issued_at,r.prepared_at),r.pdf_sha256,j.branch_id
            FROM sof_revision r JOIN sts_job j ON j.id=r.job_id"""),
        "FINAL_REPORT" to (Permissions.REPORT_FINAL_READ to """SELECT 'FINAL_REPORT',r.id,NULL,r.revision_no,r.status,j.id,j.job_number,
            j.enquiry_id,r.issued_at,r.issued_by,r.updated_at,r.pdf_sha256,j.branch_id
            FROM final_report_revision r JOIN final_report p ON p.id=r.report_id JOIN sts_job j ON j.id=p.job_id"""),
        "INVOICE" to (Permissions.FIN_INVOICE_READ to """SELECT i.doc_type,i.id,i.number,1,i.status,j.id,j.job_number,j.enquiry_id,
            i.issued_at,i.issued_by,i.updated_at,i.pdf_sha256,i.branch_id
            FROM invoice i JOIN sts_job j ON j.id=i.job_id"""),
        "FDA" to (FDA_READ to """SELECT 'FDA',r.id,r.number,r.revision_no,r.status,j.id,j.job_number,j.enquiry_id,
            r.issued_at,r.issued_by,r.updated_at,r.pdf_sha256,j.branch_id
            FROM fda_revision r JOIN sts_job j ON j.id=r.job_id"""),
    )

    @Transactional(readOnly = true)
    fun documents(kind: String?, status: String?, from: LocalDate?, to: LocalDate?, q: String?, page: Int, size: Int): PageResponse<RegisterRow> {
        val user = policy.currentUser()
        val params = mutableMapOf<String, Any>()
        val wanted = kind?.takeIf { it.isNotBlank() }?.let { if (it == "CREDIT_NOTE") "INVOICE" else it }
        if (wanted != null && wanted !in kinds) bad("kind", "Unknown document kind.")
        val parts = kinds.entries.filter { wanted == null || it.key == wanted }.mapIndexedNotNull { i, (_, def) ->
            val scope = user.scopeFor(def.first)
            if (scope.isEmpty) return@mapIndexedNotNull null
            val condition = scope.sqlCondition("x.br", params, "s$i")
            "SELECT * FROM (${def.second}) x ($COLUMNS)" + (condition?.let { " WHERE $it" } ?: "")
        }
        if (parts.isEmpty()) {
            policy.scope(kinds[wanted ?: "QUOTATION"]!!.first) // no readable kind: the usual access-denied answer
            return PageResponse(emptyList(), page, size, 0)
        }
        val where = listOfNotNull(
            kind?.takeIf { it.isNotBlank() }?.let { params["kind"] = it; "d.kind=:kind" },
            status?.takeIf { it.isNotBlank() }?.let { params["status"] = it; "d.status=:status" },
            from?.let { params["from"] = it.atStartOfDay(); "COALESCE(d.issued_at,d.updated_at)>=:from" },
            to?.let { params["to"] = it.plusDays(1).atStartOfDay(); "COALESCE(d.issued_at,d.updated_at)<:to" },
            q?.trim()?.takeIf { it.isNotEmpty() }?.let { params["q"] = "%${it.take(60)}%"; "(d.job_number LIKE :q OR d.number LIKE :q)" },
        ).joinToString(" AND ").let { if (it.isEmpty()) "" else "WHERE $it" }
        val union = parts.joinToString(" UNION ALL ")
        val total = jdbc.sql("SELECT COUNT(*) FROM ($union) d $where").params(params).query(Long::class.java).single()
        val rows = jdbc.sql("""SELECT d.kind,d.id,d.number,d.revision,d.status,d.job_id,d.job_number,d.enquiry_id,d.issued_at,
            u.full_name,d.updated_at,d.pdf_sha256 FROM ($union) d LEFT JOIN app_user u ON u.id=d.issued_by $where
            ORDER BY COALESCE(d.issued_at,d.updated_at) DESC,d.id LIMIT :limit OFFSET :offset""")
            .params(params).param("limit", size).param("offset", page.toLong() * size).query { rs, _ ->
                RegisterRow(rs.getString(1), rs.getBytes(2).uuid()!!, rs.getString(3), rs.getInt(4), rs.getString(5),
                    rs.getBytes(6).uuid(), rs.getString(7), rs.getBytes(8).uuid(), rs.utc(9), rs.getString(10),
                    rs.utc(11)!!, rs.getString(12))
            }.list()
        return PageResponse(rows, page, size, total)
    }

    /**
     * Jobs matching the filter with a summary per group, both from SQL over every matching job; only the requested page
     * of jobs is read in detail. Groups are keyed by master id (never by name); planned cargo is totalled per grade and
     * unit, never across units. `capped` stays in the response for the screen and is always false.
     */
    @Transactional(readOnly = true)
    fun jobHistory(filter: HistoryFilter, groupBy: String, page: Int, size: Int): JobHistory {
        if (groupBy !in GROUPS) bad("groupBy", "Choose client, vessel, cargo grade or month.")
        val params = mutableMapOf<String, Any>()
        val where = historyWhere(filter, params)
        val total = jdbc.sql("SELECT COUNT(*) FROM sts_job j $where").params(params).query(Long::class.java).single()
        val jobs = if (total == 0L) emptyList() else historyJobs(where, params, size, page.toLong() * size)
        return JobHistory(groupBy, groups(groupBy, where, params), PageResponse(jobs, page, size, total), false)
    }

    /** The values that occur on jobs in scope, so each filter is a short dropdown (no master-data permission needed). */
    @Transactional(readOnly = true)
    fun historyOptions(): HistoryOptions {
        val params = mutableMapOf<String, Any>()
        val scope = policy.scope(Permissions.REPORT_MANAGEMENT_READ).sqlCondition("j.branch_id", params)
        val where = scope?.let { "WHERE $it" } ?: ""
        fun ids(sql: String) = jdbc.sql(sql).params(params).query { rs, _ -> rs.getBytes(1).uuid()!! }.list()
        val clients = ids("SELECT DISTINCT j.client_id FROM sts_job j $where")
        val vessels = ids("SELECT DISTINCT v.vessel_id FROM job_vessel v JOIN sts_job j ON j.id=v.job_id $where")
        val grades = ids("SELECT DISTINCT c.cargo_grade_id FROM job_cargo c JOIN sts_job j ON j.id=c.job_id $where")
        fun sorted(map: Map<UUID, String>) = map.map { NamedOption(it.key, it.value) }.sortedBy { it.name }
        return HistoryOptions(
            sorted(references.clients(clients).mapValues { it.value.legalName }),
            sorted(references.vessels(vessels).mapValues { "${it.value.name} (IMO ${it.value.imo})" }),
            sorted(references.cargoGrades(grades).mapValues { it.value.name }),
        )
    }

    /** The job list and the group summary as one workbook; every export is audited. */
    @Transactional
    fun jobHistoryXlsx(filter: HistoryFilter, groupBy: String): Pair<String, ByteArray> {
        val report = jobHistory(filter, groupBy, 0, 1)
        val params = mutableMapOf<String, Any>()
        val where = historyWhere(filter, params)
        fun cargo(list: List<CargoTotal>) = list.joinToString("; ") { "${it.grade} ${it.quantity.stripTrailingZeros().toPlainString()} ${it.unit}" }
        val rows = sequence<List<Any?>> {
            report.groups.forEach { yield(listOf("Group", it.label, it.jobs, cargo(it.cargo), null, null, null)) }
            yield(emptyList())
            var offset = 0L
            while (offset < report.jobs.totalElements) {
                val page = historyJobs(where, params, EXPORT_PAGE_SIZE, offset)
                if (page.isEmpty()) break
                page.forEach { yield(listOf(it.jobNumber, it.client, it.status, it.jobDate.toString(), it.motherVessel, it.daughterVessel,
                    cargo(it.cargo))) }
                offset += page.size
            }
        }
        val bytes = Xlsx.workbook("Job history", "Job history by ${groupBy.lowercase()} (planned cargo per grade and unit)",
            listOf("Job / group", "Client / label", "Status / jobs", "Date / cargo", "Discharging vessel", "Receiving vessel", "Cargo (planned)"),
            rows.asIterable())
        audit.record(AuditEntry(AuditAction.EXPORT, "JOB_HISTORY", null, null, mapOf("format" to "xlsx", "rows" to report.jobs.totalElements,
            "groupBy" to groupBy, "from" to filter.from?.toString(), "to" to filter.to?.toString(), "clientId" to filter.clientId,
            "vesselId" to filter.vesselId, "cargoGradeId" to filter.cargoGradeId, "status" to filter.status)))
        return "job-history.xlsx" to bytes
    }

    private fun historyWhere(f: HistoryFilter, params: MutableMap<String, Any>): String =
        listOfNotNull(
            policy.scope(Permissions.REPORT_MANAGEMENT_READ).sqlCondition("j.branch_id", params),
            f.from?.let { params["from"] = it.atStartOfDay(); "j.created_at>=:from" },
            f.to?.let { params["to"] = it.plusDays(1).atStartOfDay(); "j.created_at<:to" },
            f.clientId?.let { params["client"] = it.bytes(); "j.client_id=:client" },
            f.status?.takeIf { it.isNotBlank() }?.let { params["status"] = it; "j.status=:status" },
            f.vesselId?.let { params["vessel"] = it.bytes(); "EXISTS (SELECT 1 FROM job_vessel v WHERE v.job_id=j.id AND v.vessel_id=:vessel)" },
            f.cargoGradeId?.let { params["grade"] = it.bytes(); "EXISTS (SELECT 1 FROM job_cargo c WHERE c.job_id=j.id AND c.cargo_grade_id=:grade)" },
        ).joinToString(" AND ").let { if (it.isEmpty()) "" else "WHERE $it" }

    /** One page of matching jobs, newest first, with names and per-job cargo totals looked up for that page only. */
    private fun historyJobs(where: String, params: Map<String, Any>, limit: Int, offset: Long): List<HistoryJob> {
        data class Row(val id: UUID, val number: String, val status: String, val date: LocalDate, val client: UUID)
        val page = jdbc.sql("""SELECT j.id,j.job_number,j.status,j.created_at,j.client_id FROM sts_job j $where
            ORDER BY j.created_at DESC,j.id DESC LIMIT :limit OFFSET :offset""").params(params).param("limit", limit).param("offset", offset).query { rs, _ ->
            Row(rs.getBytes(1).uuid()!!, rs.getString(2), rs.getString(3), rs.getObject(4, LocalDateTime::class.java).toLocalDate(),
                rs.getBytes(5).uuid()!!)
        }.list()
        if (page.isEmpty()) return emptyList()
        val ids = page.map { it.id.bytes() }
        val vessels = jdbc.sql("SELECT job_id,role,vessel_id FROM job_vessel WHERE job_id IN (:ids)").param("ids", ids)
            .query { rs, _ -> Triple(rs.getBytes(1).uuid()!!, rs.getString(2), rs.getBytes(3).uuid()!!) }.list()
        val cargo = jdbc.sql("SELECT job_id,cargo_grade_id,unit_id,quantity FROM job_cargo WHERE job_id IN (:ids) ORDER BY line_no")
            .param("ids", ids).query { rs, _ -> rs.getBytes(1).uuid()!! to CargoLine(rs.getBytes(2).uuid()!!, rs.getBytes(3).uuid()!!, rs.getBigDecimal(4)) }.list()
        val clientNames = references.clients(page.map { it.client }.distinct())
        val vesselNames = references.vessels(vessels.map { it.third }.distinct())
        val names = CargoNames(references.cargoGrades(cargo.map { it.second.grade }.distinct()), references.units(cargo.map { it.second.unit }.distinct()))
        val vesselsByJob = vessels.groupBy { it.first }
        val cargoByJob = cargo.groupBy({ it.first }, { it.second })
        return page.map { j ->
            val own = vesselsByJob[j.id].orEmpty()
            HistoryJob(j.id, j.number, j.status, j.date, clientNames[j.client]?.legalName,
                own.firstOrNull { it.second == "MOTHER" }?.let { vesselNames[it.third]?.name },
                own.firstOrNull { it.second == "DAUGHTER" }?.let { vesselNames[it.third]?.name },
                names.totals(cargoByJob[j.id].orEmpty()))
        }
    }

    /**
     * The group summary in two grouped queries over every matching job. Each member row is one (group, job) pair, so a
     * job with two vessels or several grades counts once in each group it belongs to and its cargo is never doubled.
     */
    private fun groups(groupBy: String, where: String, params: Map<String, Any>): List<HistoryGroup> {
        val members = when (groupBy) {
            "CLIENT" -> "SELECT j.client_id k,j.id job_id FROM sts_job j $where"
            "MONTH" -> "SELECT DATE_FORMAT(j.created_at,'%Y-%m') k,j.id job_id FROM sts_job j $where"
            "VESSEL" -> "SELECT DISTINCT jv.vessel_id k,j.id job_id FROM sts_job j LEFT JOIN job_vessel jv ON jv.job_id=j.id $where"
            else -> "SELECT DISTINCT jc.cargo_grade_id k,j.id job_id FROM sts_job j JOIN job_cargo jc ON jc.job_id=j.id $where"
        }
        fun key(rs: ResultSet): Any? = if (groupBy == "MONTH") rs.getString(1) else rs.getBytes(1).uuid()
        val counts = jdbc.sql("SELECT m.k,COUNT(*) FROM ($members) m GROUP BY m.k").params(params)
            .query { rs, _ -> key(rs) to rs.getInt(2) }.list()
        // Cargo groups total only their own grade's lines.
        val cargo = jdbc.sql("""SELECT m.k,c.cargo_grade_id,c.unit_id,SUM(c.quantity) FROM ($members) m JOIN job_cargo c ON c.job_id=m.job_id
            ${if (groupBy == "CARGO") "WHERE c.cargo_grade_id=m.k" else ""} GROUP BY m.k,c.cargo_grade_id,c.unit_id""").params(params)
            .query { rs, _ -> key(rs) to CargoLine(rs.getBytes(2).uuid()!!, rs.getBytes(3).uuid()!!, rs.getBigDecimal(4)) }.list()
            .groupBy({ it.first }, { it.second })
        val ids = counts.mapNotNull { it.first as? UUID }
        val names = CargoNames(references.cargoGrades(cargo.values.flatten().map { it.grade }.distinct() + if (groupBy == "CARGO") ids else emptyList()),
            references.units(cargo.values.flatten().map { it.unit }.distinct()))
        val labels: Map<UUID, String> = when (groupBy) {
            "CLIENT" -> references.clients(ids).mapValues { it.value.legalName }
            "VESSEL" -> references.vessels(ids).mapValues { it.value.name }
            "CARGO" -> names.grades.mapValues { it.value.name }
            else -> emptyMap()
        }
        return counts.map { (k, jobs) ->
            HistoryGroup(k?.toString() ?: "—", when (k) { null -> "—"; is UUID -> labels[k] ?: if (groupBy == "CARGO") "?" else "—"; else -> k.toString() },
                jobs, names.totals(cargo[k].orEmpty()))
        }.sortedWith(compareByDescending<HistoryGroup> { it.jobs }.thenBy { it.label }.thenBy { it.key })
    }

    private data class CargoLine(val grade: UUID, val unit: UUID, val quantity: BigDecimal)

    private class CargoNames(val grades: Map<UUID, CodeRef>, val units: Map<UUID, CodeRef>) {
        /** Totals per grade and unit identity, labelled by name and code, sorted for display. */
        fun totals(lines: List<CargoLine>) = lines.groupBy { it.grade to it.unit }
            .map { (k, v) -> CargoTotal(grades[k.first]?.name ?: "?", units[k.second]?.code ?: "?", v.fold(BigDecimal.ZERO) { t, c -> t + c.quantity }) }
            .sortedWith(compareBy({ it.grade }, { it.unit }))
    }

    private fun bad(field: String, message: String): Nothing =
        throw BadRequestException("VALIDATION_FAILED", "The request has invalid fields.", listOf(FieldError(field, "invalid", message)))

    companion object {
        const val FDA_READ = "FIN.FDA.READ"
        const val COLUMNS = "kind,id,number,revision,status,job_id,job_number,enquiry_id,issued_at,issued_by,updated_at,pdf_sha256,br"
        private const val EXPORT_PAGE_SIZE = 250
        val GROUPS = setOf("CLIENT", "VESSEL", "CARGO", "MONTH")
    }
}

@RestController
@RequestMapping("/api/v1/reports")
class ReportsController(private val service: ReportsService) {
    @GetMapping("/documents")
    fun documents(@RequestParam kind: String?, @RequestParam status: String?,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?, @RequestParam q: String?,
        @RequestParam page: Int?, @RequestParam size: Int?) =
        service.documents(kind, status, from, to, q, (page ?: 0).coerceAtLeast(0), (size ?: 25).coerceIn(1, 100))

    @GetMapping("/job-history")
    fun jobHistory(filter: HistoryFilterParams, @RequestParam groupBy: String?, @RequestParam page: Int?, @RequestParam size: Int?) =
        service.jobHistory(filter.toFilter(), groupBy ?: "CLIENT", (page ?: 0).coerceAtLeast(0), (size ?: 25).coerceIn(1, 100))

    @GetMapping("/job-history/options")
    fun options() = service.historyOptions()

    @GetMapping("/job-history/xlsx")
    fun jobHistoryXlsx(filter: HistoryFilterParams, @RequestParam groupBy: String?): ResponseEntity<ByteArray> =
        service.jobHistoryXlsx(filter.toFilter(), groupBy ?: "CLIENT").let { (name, bytes) -> Xlsx.response(name, bytes) }
}

/** Query parameters of the job history report. */
data class HistoryFilterParams(
    @param:DateTimeFormat(iso = DateTimeFormat.ISO.DATE) val from: LocalDate? = null,
    @param:DateTimeFormat(iso = DateTimeFormat.ISO.DATE) val to: LocalDate? = null,
    val clientId: UUID? = null, val vesselId: UUID? = null, val cargoGradeId: UUID? = null,
    val status: String? = null,
) {
    fun toFilter() = HistoryFilter(from, to, clientId, vesselId, cargoGradeId, status)
}
