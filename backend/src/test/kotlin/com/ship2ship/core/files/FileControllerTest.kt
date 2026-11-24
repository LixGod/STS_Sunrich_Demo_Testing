package com.ship2ship.core.files

import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.assertEquals

class FileControllerTest {
    @Test
    fun `image preview limits decoded dimensions and preserves transparency`() {
        val image = BufferedImage(2048, 1024, BufferedImage.TYPE_INT_ARGB)
        val source = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        val files = mock(FileService::class.java)
        val ref = StoredFileRef(UUID.randomUUID(), "plan.png", "image/png", source.size.toLong(), "hash", ScanState.NOT_SCANNED)
        `when`(files.open("token")).thenReturn(OpenedFile(ref, ByteArrayInputStream(source)))

        val response = FileController(files).preview("token")
        val preview = ImageIO.read(ByteArrayInputStream(response.body!!))

        assertEquals("image/png", response.headers.contentType.toString())
        assertEquals(1024, preview.width)
        assertEquals(512, preview.height)
        assertEquals(0, preview.getRGB(0, 0) ushr 24)
    }
}
