package com.ship2ship.core.web

import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import java.io.ByteArrayOutputStream
import java.math.BigDecimal

/** Server-side Excel exports shared by the feature reports. */
object Xlsx {
    const val CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    /** A one-sheet workbook: title row, header row, then the rows (numbers stay numbers). */
    fun workbook(sheetName: String, title: String, headers: List<String>, rows: Iterable<List<Any?>>): ByteArray = streaming { book ->
        val bold = book.createCellStyle().apply { setFont(book.createFont().apply { setBold(true) }) }
        val heading = book.createCellStyle().apply {
            setFont(book.createFont().apply { setBold(true) }); fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
        }
        val sheet = book.createSheet(sheetName)
        sheet.createRow(0).createCell(0).apply { setCellValue(title); cellStyle = bold }
        sheet.createRow(2).let { row -> headers.forEachIndexed { i, h -> row.createCell(i).apply { setCellValue(h); cellStyle = heading } } }
        rows.forEachIndexed { r, values ->
            val row = sheet.createRow(r + 3)
            values.forEachIndexed { i, v ->
                row.createCell(i).apply { when (v) { is BigDecimal -> setCellValue(v.toDouble()); is Number -> setCellValue(v.toDouble()); null -> {}; else -> setCellValue(v.toString()) } }
            }
        }
        headers.indices.forEach { sheet.setColumnWidth(it, if (it < 2) 24 * 256 else 16 * 256) }
    }

    /**
     * Builds a workbook that keeps only a window of rows in memory and spills the rest to a temporary file, then returns
     * the file's bytes. Rows must be created in order. The temporary files are deleted even when building fails.
     */
    fun streaming(build: (SXSSFWorkbook) -> Unit): ByteArray {
        val book = SXSSFWorkbook(ROW_WINDOW)
        try {
            build(book)
            return ByteArrayOutputStream().also(book::write).toByteArray()
        } finally {
            book.dispose()
            book.close()
        }
    }

    private const val ROW_WINDOW = 200

    /** The workbook as a private, uncached download. */
    fun response(name: String, bytes: ByteArray): ResponseEntity<ByteArray> =
        ResponseEntity.ok().contentType(MediaType.parseMediaType(CONTENT_TYPE))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(bytes)
}
