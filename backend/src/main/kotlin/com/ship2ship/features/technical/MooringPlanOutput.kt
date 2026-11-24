package com.ship2ship.features.technical

import com.ship2ship.core.files.FileService
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.rendering.PDFRenderer
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.time.Instant
import javax.imageio.ImageIO
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min

/** A PDFBox projection of the submitted object model, never a browser screenshot. */
@Component
class MooringPlanOutput(private val jdbc: JdbcClient, private val files: FileService) {
    private val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    private val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)

    fun pdf(jobNumber: String, revisionNo: Int, status: MooringStatus, model: MooringModel, snapshot: MooringSnapshot,
            approvedBy: String? = null, approvedAt: Instant? = null, issuedBy: String? = null, issuedAt: Instant? = null): ByteArray {
        PDDocument().use { pdf ->
            val page = PDPage(PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width))
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { canvas ->
                title(canvas, jobNumber, revisionNo, status)
                drawing(pdf, canvas, model, snapshot)
                if (status !in setOf(MooringStatus.ISSUED, MooringStatus.SUPERSEDED)) {
                    canvas.setNonStrokingColor(0.75f, 0.75f, 0.75f)
                    write(canvas, "DRAFT", 335f, 290f, 64f, bold)
                    canvas.setNonStrokingColor(0f, 0f, 0f)
                }
                write(canvas, "Scale is metric; source dimensions require verification. No automatic safety acceptance.", 36f, 28f, 9f)
            }
            tables(pdf, jobNumber, revisionNo, status, model, snapshot, approvedBy, approvedAt, issuedBy, issuedAt)
            return ByteArrayOutputStream().also(pdf::save).toByteArray()
        }
    }

    fun drawingPng(jobNumber: String, revisionNo: Int, model: MooringModel, snapshot: MooringSnapshot,
                   unmooring: Boolean = false): ByteArray {
        val source = PDDocument().use { pdf ->
            val page = PDPage(PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width))
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { canvas ->
                title(canvas, jobNumber, revisionNo, MooringStatus.ISSUED)
                write(canvas, if (unmooring) "UNMOORING SEQUENCE" else "MOORING SEQUENCE", 36f, 515f, 10f, bold)
                drawing(pdf, canvas, model, snapshot, unmooring)
            }
            ByteArrayOutputStream().also(pdf::save).toByteArray()
        }
        return Loader.loadPDF(source).use { document ->
            ByteArrayOutputStream().also { out -> ImageIO.write(PDFRenderer(document).renderImageWithDPI(0, 110f), "png", out) }.toByteArray()
        }
    }

    private fun title(c: PDPageContentStream, job: String, no: Int, status: MooringStatus) {
        write(c, "SHIP 2 SHIP - MOORING PLAN", 36f, 558f, 18f, bold)
        write(c, "Job $job  |  Revision $no  |  ${status.name}", 36f, 539f, 10f)
    }

    private fun drawing(pdf: PDDocument, c: PDPageContentStream, model: MooringModel, snapshot: MooringSnapshot,
                        unmooring: Boolean? = null) {
        val dimensionValues = snapshot.vessels.associate { it.jobVesselId to it.dimensions.associate { d -> d.code to d.value?.toDouble() } }
        val extents = snapshot.vessels.flatMap { v ->
            val d = dimensionValues[v.jobVesselId].orEmpty()
            listOfNotNull(d["BOW_TO_MANIFOLD"], d["STERN_TO_MANIFOLD"])
        } + model.objects.map { abs(it.xM.toDouble()) }
        val maxX = (extents.maxOrNull() ?: 100.0).coerceAtLeast(10.0)
        val maxY = (model.objects.map { abs(it.yM.toDouble()) }.maxOrNull() ?: 30.0).coerceAtLeast(30.0)
        val scale = min(355.0 / maxX, 132.0 / maxY).coerceAtMost(4.0).toFloat()
        val ox = 421f
        val oy = 335f
        fun x(m: Double) = ox + m.toFloat() * scale
        fun y(m: Double) = oy + m.toFloat() * scale

        c.setStrokingColor(0.72f, 0.72f, 0.72f)
        c.moveTo(ox, 110f); c.lineTo(ox, 500f); c.stroke()
        write(c, "CENTER MANIFOLD", ox + 5f, 488f, 8f)
        // Reference images are underneath vector objects. PDFs are named in the tables instead.
        model.layers.filter { it.visible }.forEach { layer ->
            val frozen = snapshot.referenceFiles.firstOrNull { it.documentVersionId == layer.documentVersionId }
            val fileId = frozen?.fileId ?: jdbc.sql("SELECT file_id FROM document_version WHERE id=:id")
                .param("id", layer.documentVersionId.bytes())
                .query { rs, _ -> rs.getBytes(1).uuid() }.optional().orElse(null)
            val source = fileId?.let(files::readForRendering)
            if (source != null && (frozen == null || source.first.sha256 == frozen.sha256) &&
                source.first.contentType in setOf("image/png", "image/jpeg")) {
                runCatching {
                    val img = PDImageXObject.createFromByteArray(pdf, source.second, "mooring reference")
                    val w = (img.width * layer.scale.toFloat() * 0.1f).coerceAtMost(250f)
                    val h = w * img.height / img.width
                    c.drawImage(img, x(layer.xM.toDouble()), y(layer.yM.toDouble()), w, h)
                }
            }
        }
        c.setStrokingColor(0.14f, 0.25f, 0.39f)
        snapshot.vessels.forEach { vessel ->
            val layout = model.vessels.firstOrNull { it.jobVesselId == vessel.jobVesselId }
            val d = dimensionValues[vessel.jobVesselId].orEmpty()
            val bow = d["BOW_TO_MANIFOLD"]
            val stern = d["STERN_TO_MANIFOLD"]
            val beam = d["BEAM"]
            if (layout == null || bow == null || stern == null || beam == null) {
                write(c, "${vessel.role}: verified dimensions missing", 42f, if (vessel.role == "MOTHER") 447f else 171f, 9f)
            } else {
                val offset = layout.lateralOffsetM?.toDouble() ?: if (vessel.role == "MOTHER") 35.0 else -35.0
                val left = if (layout.orientation == "BOW_UP") -bow else -stern
                val width = bow + stern
                val bottom = offset - beam / 2
                c.addRect(x(left), y(bottom), (width * scale).toFloat(), (beam * scale).toFloat()); c.stroke()
                write(c, "${vessel.name ?: vessel.role} (${layout.heading ?: "heading pending"})", x(left), y(bottom + beam) + 5f, 9f, bold)
            }
        }
        val points = model.objects.associateBy { it.id }
        model.lines.forEach { line ->
            val a = points[line.fromPointId]; val b = points[line.toPointId]
            if (a != null && b != null) {
                c.moveTo(x(a.xM.toDouble()), y(a.yM.toDouble()))
                c.lineTo(x(b.xM.toDouble()), y(b.yM.toDouble())); c.stroke()
                val sequenceLabel = when (unmooring) {
                    true -> "${line.unmooringOrder ?: "-"}. ${line.label}"
                    false -> "${line.berthingOrder ?: "-"}. ${line.label}"
                    null -> line.label
                }
                write(c, sequenceLabel, (x(a.xM.toDouble()) + x(b.xM.toDouble())) / 2,
                    (y(a.yM.toDouble()) + y(b.yM.toDouble())) / 2 + 3f, 8f)
            }
        }
        model.objects.forEach { obj ->
            val px = x(obj.xM.toDouble()); val py = y(obj.yM.toDouble())
            when (obj.type) {
                "PRIMARY_FENDER" -> { c.addRect(px - 6f, py - 3f, 12f, 6f); c.stroke() }
                "SECONDARY_FENDER" -> { c.addRect(px - 3f, py - 2f, 6f, 4f); c.stroke() }
                "TUG" -> { c.moveTo(px - 6f, py - 2f); c.lineTo(px + 5f, py - 2f); c.lineTo(px + 3f, py + 3f);
                    c.lineTo(px - 4f, py + 3f); c.closePath(); c.stroke() }
                "STS_SUPPORT_CRAFT" -> { c.moveTo(px, py - 5f); c.lineTo(px + 5f, py + 4f);
                    c.lineTo(px - 5f, py + 4f); c.closePath(); c.stroke() }
                "MANOEUVRING_MOORING" -> { c.moveTo(px, py - 4f); c.lineTo(px + 4f, py); c.lineTo(px, py + 4f);
                    c.lineTo(px - 4f, py); c.closePath(); c.stroke() }
                "FENDER_MOORING" -> { c.addRect(px - 2f, py - 2f, 4f, 4f); c.stroke() }
                "ANNOTATION" -> Unit
                else -> { c.addRect(px - 3f, py - 3f, 6f, 6f); c.stroke() }
            }
            write(c, obj.label?.take(30) ?: obj.type.replace('_', ' ').lowercase(), px + 5f, py + 5f, 7f)
        }
        model.rulerStepM?.toDouble()?.takeIf { it > 0 }?.let { step ->
            c.moveTo(46f, 78f); c.lineTo(795f, 78f); c.stroke()
            for (i in -12..12) {
                val px = x(i * step)
                if (px in 46f..795f) {
                    c.moveTo(px, 75f); c.lineTo(px, 83f); c.stroke()
                    write(c, "${(i * step).toInt()}m", px - 8f, 64f, 7f)
                }
            }
        }
    }

    private fun tables(pdf: PDDocument, job: String, revision: Int, status: MooringStatus,
                       model: MooringModel, snapshot: MooringSnapshot,
                       approvedBy: String?, approvedAt: Instant?, issuedBy: String?, issuedAt: Instant?) {
        val lines = mutableListOf<String>()
        lines += "SHIP 2 SHIP - MOORING PLAN | $job | revision $revision | ${status.name}"
        lines += "Approved by: ${approvedBy ?: "PENDING"}  at ${approvedAt ?: "-"}"
        lines += "Issued by: ${issuedBy ?: "PENDING"}  at ${issuedAt ?: "-"}"
        lines += "Legend: primary fender; secondary fender; tug boat; STS support craft; constant-heading vessel moorings;"
        lines += "         manoeuvring-vessel moorings; fender moorings. Annotations and connection points are marked on the drawing."
        snapshot.vessels.forEach { vessel ->
            lines += "${vessel.role}: ${vessel.name ?: "name pending"}  IMO ${vessel.imo ?: "pending"}"
            vessel.dimensions.forEach { d -> lines += "  ${d.code}: ${d.value?.toPlainString() ?: "MISSING"} ${d.unit}" }
            model.particulars.filter { it.jobVesselId == vessel.jobVesselId }.forEach { p ->
                lines += "  ${p.code}: ${p.value?.toPlainString() ?: "MISSING"} ${p.unit} | source ${p.sourceText ?: p.sourceDocumentId ?: "pending"}"
            }
        }
        lines += "Line table (unknown/pending capacities are never assumed):"
        model.lines.sortedBy { it.berthingOrder ?: Int.MAX_VALUE }.forEach { line ->
            lines += "  ${line.label}: berthing ${line.berthingOrder ?: "-"}, unmooring ${line.unmooringOrder ?: "-"}"
            snapshot.capacityColumns.forEach { column ->
                val value = line.capacities.firstOrNull { it.columnCode == column.code }
                lines += "    ${column.label}: ${value?.value?.toPlainString() ?: "MISSING"} ${column.unit ?: "unit pending"}"
            }
        }
        lines += "Mooring sequence: ${model.lines.sortedBy { it.berthingOrder ?: Int.MAX_VALUE }.joinToString { it.label }}"
        lines += "Unmooring sequence: ${model.lines.sortedBy { it.unmooringOrder ?: Int.MAX_VALUE }.joinToString { it.label }}"
        model.layers.forEach { lines += "Reference layer: ${it.documentVersionId} (${if (it.visible) "shown" else "hidden"})" }
        if (status !in setOf(MooringStatus.ISSUED, MooringStatus.SUPERSEDED)) lines += "DRAFT - not issued"
        lines.chunked(60).forEach { block ->
            val page = PDPage(PDRectangle.A4)
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { c ->
                block.forEachIndexed { index, value -> write(c, value.take(115), 36f, 802f - index * 12f, 8f,
                    if (index == 0) bold else font) }
            }
        }
    }

    private fun write(c: PDPageContentStream, value: String, x: Float, y: Float, size: Float, face: PDType1Font = font) {
        c.beginText(); c.setFont(face, size); c.newLineAtOffset(x, y)
        c.showText(value.map { if (it.code in 32..255) it else '?' }.joinToString(""))
        c.endText()
    }
}
