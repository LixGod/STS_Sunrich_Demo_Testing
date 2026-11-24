package com.ship2ship.features.finalreport

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import org.springframework.stereotype.Component
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Everything printed on the final report, as display text, in the sections of the sample report. */
data class ReportDocument(
    val reference: String,
    val documentId: String,
    val templateVersion: Int,
    val client: String,
    val date: String,
    val vhf: String,
    val vessels: List<Pair<String, String>>,
    val cargo: List<String>,
    val summary: String,
    val q88: List<List<String>>,
    val equipment: List<List<String>>,
    val attendance: List<List<String>>,
    val berthing: List<List<String>>,
    val cargoProgress: List<Grade>,
    val completion: List<List<String>>,
    val incidents: List<List<String>>,
    val delays: List<List<String>>,
    val remarks: String,
    val attachments: List<String>,
    val sof: String,
    val approval: String,
    val timezone: String,
    val draft: Boolean,
) {
    data class Grade(val title: String, val rows: List<List<String>>)

    companion object {
        private const val DASH = "—"

        fun build(
            reference: String, documentId: UUID, templateVersion: Int, c: ReportContent, s: ReportSnapshot, approvedBy: String?, approvedAt: Instant?,
            issuedAt: Instant?, draft: Boolean,
        ): ReportDocument {
            val zone = ZoneId.of(s.timezone)
            val f = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH).withZone(zone)
            val day = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH).withZone(zone)
            fun t(i: Instant?) = i?.let(f::format) ?: DASH
            fun time(e: SnapEvent) = when {
                e.occurredAt != null -> f.format(e.occurredAt)
                e.endedAt != null -> "${t(e.startedAt)} – ${t(e.endedAt)}"
                else -> "${t(e.startedAt)} – ongoing"
            }
            fun role(r: String) = if (r == "MOTHER") "Discharging vessel" else "Receiving vessel"
            val completionPhases = setOf("COMPLETION", "UNMOORING")
            return ReportDocument(
                reference = reference, documentId = documentId.toString(), templateVersion = templateVersion,
                client = s.client ?: DASH,
                date = s.events.firstOrNull()?.let { (it.occurredAt ?: it.startedAt)?.let(day::format) } ?: DASH,
                vhf = c.vhfChannel ?: DASH,
                vessels = s.vessels.map { role(it.role) to listOfNotNull(it.type?.replace('_', ' ')?.lowercase()?.replaceFirstChar { ch -> ch.titlecase() }, it.name)
                    .joinToString(" ").ifBlank { DASH } },
                cargo = s.cargo.map { "${it.gradeName ?: it.gradeCode ?: DASH}: ${it.quantity.toPlainString()} ${it.unit ?: ""}".trim() },
                summary = c.summary ?: "",
                q88 = s.q88.map { listOf(it.vessel ?: DASH, it.imo ?: DASH, it.documentName, day.format(it.uploadedAt)) },
                equipment = c.equipment.map { listOf(label(it.category), it.description, it.quantity?.toString() ?: DASH) },
                attendance = c.attendance.map { listOf(it.capacity, it.name) },
                berthing = s.events.filter { it.phase !in completionPhases }.map { listOf(time(it), it.label, it.vessel ?: "", it.remarks ?: "") },
                cargoProgress = s.grades.map { g ->
                    Grade("${g.gradeName ?: g.gradeCode ?: DASH} — planned ${g.planned.toPlainString()} ${g.unit ?: ""}".trim(), g.readings.map { r ->
                        listOf(f.format(r.readAt), r.cumulative.toPlainString(), (g.planned - r.cumulative).toPlainString(),
                            r.ratePerHour?.toPlainString() ?: DASH, listOfNotNull(if (r.kind == "NORMAL") null else r.kind.lowercase(), r.remarks).joinToString(" · "))
                    })
                },
                completion = s.events.filter { it.phase in completionPhases }.map { listOf(time(it), it.label, it.vessel ?: "", it.remarks ?: "") } +
                    s.grades.filter { it.completed }.map { listOf("", "Completion: ${it.gradeName ?: it.gradeCode}", "", "${it.finalQuantity?.toPlainString()} ${it.unit ?: ""}".trim()) },
                incidents = s.incidents.map { listOf(t(it.occurredAt), "${it.category} (${it.severity})", it.description, it.status.lowercase().replace('_', ' ') +
                    (it.closingNote?.let { n -> " — $n" } ?: "")) },
                delays = s.delays.map { listOf("${t(it.startedAt)} – ${it.endedAt?.let(f::format) ?: "ongoing"}", it.category, it.description) },
                remarks = c.remarks ?: "",
                attachments = s.attachments.map { listOfNotNull(it.name, it.type?.let { ty -> "($ty)" }).joinToString(" ") },
                sof = s.sof?.let { "Statement of Facts revision ${it.revisionNo}${it.issuedAt?.let { i -> ", issued ${f.format(i)}" } ?: ""}" } ?: "Statement of Facts not issued",
                approval = listOfNotNull(approvedBy?.let { "Approved by $it" }, approvedAt?.let { "on ${f.format(it)}" }, issuedAt?.let { "· issued ${f.format(it)}" })
                    .joinToString(" ").ifBlank { "Not approved" },
                timezone = zone.id, draft = draft,
            )
        }

        private fun label(category: String) = when (category) { "FENDER" -> "Fender"; "HOSE" -> "Hose"; else -> "Other" }
    }
}

/** Renders the final report PDF on the server (openhtmltopdf + Thymeleaf XHTML), as for the JPO and the SOF. */
@Component
class FinalReportOutput {
    private val engine = TemplateEngine().apply {
        setTemplateResolver(ClassLoaderTemplateResolver().apply {
            prefix = "templates/pdf/"
            suffix = ".xhtml"
            templateMode = TemplateMode.XML
            characterEncoding = "UTF-8"
            isCacheable = true
        })
    }

    fun pdf(document: ReportDocument): ByteArray {
        val html = engine.process("final-report", Context(Locale.ENGLISH, mapOf("r" to document)))
        val out = ByteArrayOutputStream()
        PdfRendererBuilder().useFastMode().withHtmlContent(html, null).toStream(out).run()
        return out.toByteArray()
    }
}
