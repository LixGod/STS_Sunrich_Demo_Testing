package com.ship2ship.features.operations

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

/** Everything printed on a Statement of Facts, as display text. */
data class SofDocument(
    val jobNumber: String,
    val revision: String,
    val timezone: String,
    val vessels: List<Pair<String, String>>,
    /** Phase heading → rows of (time, event, vessel, remarks). */
    val sections: List<Section>,
    val issued: String?,
    val draft: Boolean,
) {
    data class Section(val title: String, val rows: List<Row>)
    data class Row(val time: String, val event: String, val vessel: String, val remarks: String)

    companion object {
        private val PHASES = listOf("BERTHING" to "Berthing and mooring", "TRANSFER" to "Cargo transfer", "COMPLETION" to "Completion",
            "UNMOORING" to "Unmooring", "OTHER" to "Other events")

        fun build(
            jobNumber: String, revisionNo: Int?, vessels: List<Pair<String, String>>, events: List<SofEvent>, zone: ZoneId,
            draft: Boolean, issuedAt: Instant?, issuedBy: String?,
        ): SofDocument {
            val format = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH).withZone(zone)
            val hm = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH).withZone(zone)
            fun time(e: SofEvent): String = when {
                e.occurredAt != null -> format.format(e.occurredAt)
                e.endedAt != null && e.startedAt != null && format.format(e.startedAt).take(11) == format.format(e.endedAt).take(11) ->
                    "${format.format(e.startedAt)}–${hm.format(e.endedAt)}"
                e.endedAt != null -> "${format.format(e.startedAt!!)} – ${format.format(e.endedAt)}"
                else -> "${format.format(e.startedAt!!)} – (ongoing)"
            }
            val sections = PHASES.mapNotNull { (code, title) ->
                events.filter { it.phase == code }.takeIf { it.isNotEmpty() }?.let { list ->
                    Section(title, list.map { Row(time(it), it.typeLabel, it.vessel ?: "", it.remarks ?: "") })
                }
            }
            return SofDocument(jobNumber, revisionNo?.let { "Revision $it" } ?: "Current events", zone.id, vessels, sections,
                issuedAt?.let { "Issued ${format.format(it)} (${zone.id})${issuedBy?.let { b -> " by $b" } ?: ""}" }, draft)
        }
    }
}

/** Renders the Statement of Facts PDF on the server (openhtmltopdf + Thymeleaf XHTML), as for the quotation and the JPO. */
@Component
class SofOutput {
    private val engine = TemplateEngine().apply {
        setTemplateResolver(ClassLoaderTemplateResolver().apply {
            prefix = "templates/pdf/"
            suffix = ".xhtml"
            templateMode = TemplateMode.XML
            characterEncoding = "UTF-8"
            isCacheable = true
        })
    }

    fun pdf(document: SofDocument): ByteArray {
        val html = engine.process("sof", Context(Locale.ENGLISH, mapOf("s" to document)))
        val out = ByteArrayOutputStream()
        PdfRendererBuilder().useFastMode().withHtmlContent(html, null).toStream(out).run()
        return out.toByteArray()
    }
}
