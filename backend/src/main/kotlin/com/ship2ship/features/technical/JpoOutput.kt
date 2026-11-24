package com.ship2ship.features.technical

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import com.ship2ship.core.files.FileService
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.stereotype.Component
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.HexFormat
import java.util.Locale
import java.util.UUID

/**
 * Everything printed on a JPO, resolved to display text. The PDF and the Excel export are drawn from this one model, so
 * they always show the same content.
 */
data class JpoDocument(
    val title: String,
    val jobNumber: String,
    val revisionNo: Int,
    val status: String,
    val sections: List<Section>,
    val diagrams: List<Diagram>,
    val annexVersion: Int?,
    val annex: List<AnnexSectionDto>,
    /** Printed across every page until the revision is issued. */
    val draft: Boolean,
    val linkedMooring: LinkedMooringPlan? = null,
) {
    /** A heading with label/value rows and optional tables. */
    data class Section(val title: String, val rows: List<Row> = emptyList(), val tables: List<Table> = emptyList(), val note: String? = null)

    data class Row(val label: String, val value: String)

    data class Table(val caption: String?, val headers: List<String>, val rows: List<List<String>>)

    /** A diagram attached as a job document; PNG/JPEG files are embedded in the PDF, others are named. */
    data class Diagram(val title: String, val fileId: UUID, val fileName: String)

    companion object {
        private const val DASH = "—"

        internal fun build(
            jobNumber: String, revisionNo: Int, status: JpoStatus, content: JpoContent, snapshot: JpoSnapshot,
            annexSections: List<AnnexSectionDto>, annexVersion: Int?, approvedBy: String?, approvedAt: Instant?, issuedAt: Instant?,
            acceptances: List<JpoService.JpoAcceptanceRow>, documentFiles: Map<UUID, Pair<UUID, String>>, draft: Boolean, zone: ZoneId,
        ): JpoDocument {
            val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH)
            fun at(i: Instant?, z: ZoneId = zone) = i?.atZone(z)?.let { "${time.format(it)} (${z.id})" } ?: DASH
            fun t(v: String?) = v?.takeIf { it.isNotBlank() } ?: DASH
            fun n(v: BigDecimal?, unit: String = "") = v?.stripTrailingZeros()?.toPlainString()?.let { if (unit.isEmpty()) it else "$it $unit" } ?: DASH
            fun yes(v: Boolean) = if (v) "Yes" else "No"
            fun doc(id: UUID?) = id?.let { documentFiles[it]?.second ?: "Attached document" } ?: DASH
            val mother = snapshot.vessels.firstOrNull { it.role == "MOTHER" }
            val daughter = snapshot.vessels.firstOrNull { it.role == "DAUGHTER" }
            val allocatedAssets = snapshot.allocatedResources.filter { it.kind == "ASSET" && it.category in JpoService.EQUIPMENT }
            val allocatedCraft = snapshot.allocatedResources.filter { it.kind == "CRAFT" }

            val sections = mutableListOf<Section>()
            sections += Section("Operation", listOf(
                Row("Job number", jobNumber),
                Row("Date of operation", content.operationDate?.toString() ?: DASH),
                Row("Operation type", label(snapshot.operationType)),
                Row("Purpose", t(content.purpose)),
                Row("Reverse lightering", yes(content.reverseLightering)),
                Row("Berthing vessels of similar LOA", yes(content.similarLoaBerthing)),
            ))
            sections += Section("Vessels", tables = listOf(Table(null, listOf("Vessel", "Name", "IMO", "ETA"),
                snapshot.vessels.map { v ->
                    listOf(JpoService.roleName(v.role), t(v.name), t(v.imo),
                        at(v.etaUtc, v.etaTimezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: zone))
                })))
            sections += Section("Cargo", tables = listOf(Table(null, listOf("Grade", "Quantity", "Max transfer rate per line"),
                snapshot.cargo.map { c ->
                    val rate = content.cargoRates.firstOrNull { it.cargoGradeCode == c.gradeCode }
                    listOf(listOfNotNull(c.gradeName, c.gradeCode?.let { "($it)" }).joinToString(" ").ifBlank { DASH },
                        n(c.quantity, c.unitCode.orEmpty()), n(rate?.maxRatePerLine, rate?.rateUnit.orEmpty()))
                })))
            sections += Section("Mooring", listOf(Row("Mooring diagram", snapshot.linkedMooring?.let { "Issued mooring plan revision ${it.revisionNo}" }
                ?: doc(content.mooringDiagramDocumentId)), Row("Mooring notes", t(content.mooringNotes))))
            sections += Section("Manoeuvring and ship handling", listOf(
                Row("Constant heading: course and speed", t(content.constantHeadingCourseSpeed)),
                Row("Constant heading: propulsion", label(content.constantHeadingPropulsion)),
                Row("Constant heading: DP mode", label(content.constantHeadingDpMode)),
                Row("Constant heading: dead slow", t(content.constantHeadingDeadSlow)),
                Row("Manoeuvring vessel: course and speed", t(content.manoeuvringCourseSpeed)),
                Row("Manoeuvring vessel: propulsion", label(content.manoeuvringPropulsion)),
                Row("Manoeuvring vessel: DP mode", label(content.manoeuvringDpMode)),
                Row("Manoeuvring vessel: dead slow",
                    listOf(n(content.manoeuvringDeadSlowKn, "kn"), label(content.manoeuvringDeadSlowCondition)).filter { it != DASH }.joinToString(", ").ifBlank { DASH }),
                Row("Method of ship handling", label(content.shipHandling)),
                Row("Rudder type", t(content.rudderType)),
                Row("Maximum rudder angle", n(content.maxRudderAngleDeg, "°")),
                Row("Propeller handedness", label(content.handedness)),
                Row("Speed monitoring", t(content.speedMonitoring)),
            ))
            sections += Section("Approach and transfer", listOf(
                Row("Rendezvous", t(content.rendezvous)),
                Row("Transfer location", t(content.transferLocation)),
                Row("Vessel approach", label(content.approach)),
                Row("Approach notes", t(content.approachNotes)),
                Row("Support craft", if (allocatedCraft.isEmpty()) t(content.supportCraft)
                    else allocatedCraft.joinToString("; ") { "${it.identifier} (${at(it.startAt)} to ${at(it.endAt)})" }),
                Row("Additional requirements", t(content.additionalRequirements)),
                Row("Mooring rope failure", t(content.mooringRopeFailure)),
            ))
            val linkedLimits = snapshot.operatingLimits
            sections += if (linkedLimits != null) {
                Section("Operating limits for separation", tables = listOf(Table(null, listOf("Parameter", "Operation", "Limit", "Action"),
                    linkedLimits.map { l ->
                        listOf(l.parameterLabel, l.operation, "${if (l.comparison == "BELOW") "Below" else "Above"} ${l.threshold.toPlainString()} ${l.unit}", l.action)
                    })),
                    note = "The job's approved operating limits (revision ${snapshot.operatingLimitsRevision}). A value beyond a limit is a warning to act on, not an automatic decision.")
            } else {
                Section("Operating limits for separation", tables = listOf(Table(null, listOf("Parameter", "Limit", "Unit", "Action"),
                    content.operatingLimits.map { listOf(it.parameter, it.limit, t(it.unit), it.action) })),
                    note = "Limits as agreed for this operation and entered on the JPO.")
            }
            sections += Section("Security, contingency and personnel", listOf(
                Row("Security level", label(content.securityLevel)),
                Row("Contingency plans", t(content.contingency)),
                Row("Personnel transfer", t(content.personnelTransfer)),
            ))
            val fields = snapshot.voyage.map { it.fieldCode to it.label }.distinct()
            sections += Section("Voyage conditions (verified)", tables = listOf(Table(null,
                listOf("Value", mother?.name ?: "Discharging vessel", daughter?.name ?: "Receiving vessel"),
                fields.map { (code, name) ->
                    fun of(role: String) = snapshot.voyage.firstOrNull { it.role == role && it.fieldCode == code }?.let { "${it.value} ${it.unit}" } ?: DASH
                    listOf(name, of("MOTHER"), of("DAUGHTER"))
                })),
                note = if (fields.isEmpty()) "No verified voyage-condition values." else null)
            sections += Section("Unmooring", listOf(
                Row("Oil boom", t(content.oilBoom)),
                Row("Unmooring diagram", snapshot.linkedMooring?.let { "Issued mooring plan revision ${it.revisionNo}" }
                    ?: doc(content.unmooringDiagramDocumentId)),
                Row("Unmooring notes", t(content.unmooringNotes)),
            ))
            sections += Section("Equipment supplied", tables = listOf(
                Table(null, listOf("Equipment", "Number", "Dimensions", "Strings"),
                    content.equipment.map { row ->
                        val linked = allocatedAssets.filter { it.category == row.category }
                        if (linked.isEmpty()) listOf(label(row.category), row.number?.toString() ?: DASH, t(row.dimensions), row.strings?.toString() ?: DASH)
                        else listOf(label(row.category), linked.map { it.resourceId }.distinct().size.toString(),
                            linked.mapNotNull { it.dimensions }.distinct().joinToString("; ").ifBlank { DASH }, DASH)
                    }),
                Table("Ancillary equipment", listOf("Item", "Supplied"), content.ancillary.map { listOf(it.item, t(it.value)) }),
            ) + if (allocatedAssets.isEmpty()) emptyList() else listOf(Table("Allocated resource details",
                listOf("Category", "Asset ID", "From", "To", "Certificate / exception"), allocatedAssets.map {
                    listOf(label(it.category), it.identifier, at(it.startAt), at(it.endAt),
                        (it.eligibilityMessages + if (it.exceptionApproved) listOf("Collision exception approved") else emptyList())
                            .joinToString("; ").ifBlank { "No recorded warning" })
                })))
            if (snapshot.checklists.isNotEmpty()) {
                sections += Section("Checklists", tables = listOf(Table(null, listOf("Checklist", "Vessel", "Status"),
                    snapshot.checklists.map { listOf(it.name, it.role?.let(JpoService::roleName) ?: "Both", label(it.status)) })))
            }
            if (snapshot.warnings.isNotEmpty()) {
                sections += Section("Review notes", snapshot.warnings.mapIndexed { i, w -> Row("${i + 1}", w) },
                    note = "Warnings found when the job data was taken (${at(snapshot.takenAt)}). They do not stop the plan.")
            }
            if (snapshot.weatherReports.isNotEmpty()) {
                sections += Section("Weather", tables = listOf(Table(null, listOf("Report", "Source", "Issued / observed", "Valid"),
                    snapshot.weatherReports.map { w ->
                        listOf(if (w.kind == "FORECAST") "Forecast" else "Observation",
                            listOfNotNull(label(w.source), w.providerName, w.area).joinToString(" · "),
                            at(w.issuedAt ?: w.observedAt),
                            if (w.validFrom != null) "${at(w.validFrom)} – ${at(w.validTo)}" else DASH)
                    })),
                    note = "Weather reports accepted for this job.")
            }
            sections += Section("Approval and issue", listOf(
                Row("Revision", revisionNo.toString()),
                Row("Status", label(status.name)),
                Row("Approved by", approvedBy ?: DASH),
                Row("Approved at", at(approvedAt)),
                Row("Issued at", at(issuedAt)),
                Row("Annex wording", annexVersion?.let { "Version $it" } ?: DASH),
            ))
            sections += Section("Agreed and accepted", tables = listOf(Table(null, listOf("Party", "Name", "Accepted at", "Signed copy"),
                PARTY_LABELS.map { (code, name) ->
                    val a = acceptances.firstOrNull { it.party == code }
                    listOf(name, a?.personName ?: "", a?.let { at(it.acceptedAt) } ?: "", a?.signedDocumentId?.let(::doc) ?: "")
                })))

            val diagrams = listOfNotNull(
                if (snapshot.linkedMooring == null) content.mooringDiagramDocumentId?.let { id -> documentFiles[id]?.let { Diagram("Mooring diagram", it.first, it.second) } } else null,
                if (snapshot.linkedMooring == null) content.unmooringDiagramDocumentId?.let { id -> documentFiles[id]?.let { Diagram("Unmooring diagram", it.first, it.second) } } else null,
            )
            return JpoDocument("Joint Plan of Operation", jobNumber, revisionNo, status.name, sections, diagrams, annexVersion, annexSections, draft,
                snapshot.linkedMooring)
        }

        private val PARTY_LABELS = listOf("DISCHARGING_MASTER" to "Master, discharging vessel", "RECEIVING_MASTER" to "Master, receiving vessel",
            "STS_SUPERINTENDENT" to "STS Superintendent")

        private val LABELS = mapOf(
            "HELM_ORDERS" to "Helm orders", "COURSE_ORDERS" to "Course orders", "LEFT" to "Left-handed", "RIGHT" to "Right-handed",
            "CPP" to "CPP", "TELEGRAPH" to "Telegraph", "NOT_DP" to "Not DP", "DP" to "DP", "SEAGOING" to "Seagoing",
            "BALLAST" to "Ballast", "LADEN" to "Laden", "AT_ANCHOR" to "At anchor", "UNDERWAY" to "Underway", "WITH_TUGS" to "With tugs",
            "LEVEL_1" to "Level 1", "LEVEL_2" to "Level 2", "LEVEL_3" to "Level 3", "CARGO_HOSE" to "Cargo hoses",
            "VAPOUR_HOSE" to "Vapour hoses", "PRIMARY_FENDER" to "Primary fenders", "SECONDARY_FENDER" to "Secondary fenders",
        )

        fun label(code: String?): String = when {
            code.isNullOrBlank() -> DASH
            else -> LABELS[code] ?: code.lowercase().replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ENGLISH) }
        }
    }
}

/** Renders a JPO as PDF (openhtmltopdf + Thymeleaf XHTML) and as an Excel workbook (Apache POI). */
@Component
class JpoOutput(private val files: FileService, private val mooringOutput: MooringPlanOutput) {

    private val engine = TemplateEngine().apply {
        setTemplateResolver(ClassLoaderTemplateResolver().apply {
            prefix = "templates/pdf/"
            suffix = ".xhtml"
            templateMode = TemplateMode.XML
            characterEncoding = "UTF-8"
            isCacheable = true
        })
    }

    /** One image or a note per diagram, for the template. */
    data class PrintedDiagram(val title: String, val fileName: String, val dataUri: String?)

    fun pdf(document: JpoDocument): ByteArray {
        val diagrams = document.diagrams.map { d ->
            val file = files.readForRendering(d.fileId)
            val uri = file?.takeIf { it.first.contentType in IMAGE_TYPES }
                ?.let { "data:${it.first.contentType};base64," + Base64.getEncoder().encodeToString(it.second) }
            PrintedDiagram(d.title, d.fileName, uri)
        }.toMutableList()
        document.linkedMooring?.let { linked ->
            val mooring = mooringOutput.drawingPng(document.jobNumber, linked.revisionNo, linked.model, linked.snapshot)
            val unmooring = mooringOutput.drawingPng(document.jobNumber, linked.revisionNo, linked.model, linked.snapshot, true)
            diagrams += PrintedDiagram("Mooring diagram - plan revision ${linked.revisionNo}", "Issued mooring plan",
                "data:image/png;base64," + Base64.getEncoder().encodeToString(mooring))
            diagrams += PrintedDiagram("Unmooring diagram - plan revision ${linked.revisionNo}", "Issued mooring plan",
                "data:image/png;base64," + Base64.getEncoder().encodeToString(unmooring))
        }
        val html = engine.process("jpo", Context(Locale.ENGLISH, mapOf("j" to document, "diagrams" to diagrams)))
        val out = ByteArrayOutputStream()
        PdfRendererBuilder().useFastMode().withHtmlContent(html, null).toStream(out).run()
        return out.toByteArray()
    }

    fun xlsx(document: JpoDocument): ByteArray = XSSFWorkbook().use { book ->
        val bold = book.createFont().apply { setBold(true) }
        val title = book.createCellStyle().apply { setFont(book.createFont().apply { setBold(true); fontHeightInPoints = 14 }) }
        val heading = book.createCellStyle().apply {
            setFont(bold); fillForegroundColor = IndexedColors.GREY_25_PERCENT.index; fillPattern = FillPatternType.SOLID_FOREGROUND
        }
        val label = book.createCellStyle().apply { setFont(bold); verticalAlignment = VerticalAlignment.TOP; wrapText = true }
        val wrap = book.createCellStyle().apply { verticalAlignment = VerticalAlignment.TOP; wrapText = true }

        val sheet = book.createSheet("JPO")
        var r = 0
        fun line(vararg values: String, style: XSSFCellStyle? = wrap, firstStyle: XSSFCellStyle? = style) {
            val row = sheet.createRow(r++)
            values.forEachIndexed { i, v -> row.createCell(i).apply { setCellValue(v.take(CELL_MAX)); cellStyle = if (i == 0) firstStyle else style } }
        }
        line("${document.title} — ${document.jobNumber}, revision ${document.revisionNo}${if (document.draft) " (DRAFT)" else ""}", style = title)
        r++
        document.sections.forEach { s ->
            line(s.title, style = heading)
            s.rows.forEach { line(it.label, it.value, firstStyle = label) }
            s.tables.forEach { t ->
                t.caption?.let { line(it, style = label) }
                line(*t.headers.toTypedArray(), style = label)
                t.rows.forEach { line(*it.toTypedArray()) }
            }
            s.note?.let { line(it) }
            r++
        }
        document.diagrams.forEach { line(it.title, "Attached document: ${it.fileName}", firstStyle = label) }
        sizeColumns(sheet, listOf(40, 45, 30, 40))
        if (document.annex.isEmpty()) return@use ByteArrayOutputStream().also { book.write(it) }.toByteArray()

        val annex = book.createSheet("Annex")
        r = 0
        fun annexLine(vararg values: String, style: XSSFCellStyle? = wrap) {
            val row = annex.createRow(r++)
            values.forEachIndexed { i, v -> row.createCell(i).apply { setCellValue(v); cellStyle = style } }
        }
        annexLine("Annex${document.annexVersion?.let { ", wording version $it" }.orEmpty()}", style = title)
        r++
        document.annex.forEach { a ->
            annexLine(a.title, style = heading)
            // A cell holds at most 32,767 characters; long wording continues on the following rows.
            a.body.chunked(CELL_MAX).forEach { annexLine(it) }
            r++
        }
        sizeColumns(annex, listOf(120))

        ByteArrayOutputStream().also { book.write(it) }.toByteArray()
    }

    private fun sizeColumns(sheet: Sheet, widths: List<Int>) = widths.forEachIndexed { i, w -> sheet.setColumnWidth(i, w * 256) }

    companion object {
        private const val CELL_MAX = 32_000
        private val IMAGE_TYPES = setOf("image/png", "image/jpeg")

        fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
