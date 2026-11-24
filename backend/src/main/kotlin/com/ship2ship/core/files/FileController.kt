package com.ship2ship.core.files

import org.springframework.core.io.InputStreamResource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.imageio.ImageIO

/**
 * File downloads. The page first asks for a token (permission checked against the file's owning record), then points
 * the browser's own viewer (or an iframe on the same site) at the token URL. Uploads go through the owning feature.
 */
@RestController
@RequestMapping("/api/v1/files")
class FileController(private val files: FileService) {

    @GetMapping("/{id}")
    fun metadata(@PathVariable id: UUID):StoredFileRef = files.requireReadable(id)

    @PostMapping("/{id}/download-token")
    fun token(@PathVariable id: UUID): DownloadToken = files.downloadToken(id)

    @GetMapping("/download/{token}")
    fun download(@PathVariable token: String): ResponseEntity<InputStreamResource> {
        val opened = files.open(token)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(opened.ref.contentType))
            .contentLength(opened.ref.sizeBytes)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.inline().filename(opened.ref.originalName, StandardCharsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(InputStreamResource(opened.stream))
    }

    @GetMapping("/download/{token}/preview")
    fun preview(@PathVariable token: String): ResponseEntity<ByteArray> {
        val opened = files.open(token)
        if (opened.ref.contentType !in setOf("image/png", "image/jpeg")) {
            opened.stream.close()
            throw ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        }
        val bytes = opened.stream.use { source ->
            ImageIO.createImageInputStream(source).use { input ->
                val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull()
                    ?: throw ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                try {
                    reader.input = input
                    val size = maxOf(reader.getWidth(0), reader.getHeight(0))
                    val sample = maxOf(1, (size - 1) / 1024 + 1)
                    val params = reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }
                    val image = reader.read(0, params)
                    ByteArrayOutputStream().also { ImageIO.write(image, if (opened.ref.contentType == "image/png") "png" else "jpeg", it) }.toByteArray()
                } finally {
                    reader.dispose()
                }
            }
        }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(opened.ref.contentType))
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(bytes)
    }
}
