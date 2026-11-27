package com.ship2ship.features.technical

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.Properties

class MailAttachmentParserTest {
    @Test
    fun `collects supported attachments and skips inline images and invalid files`() {
        val raw = """MIME-Version: 1.0
Content-Type: multipart/mixed; boundary="parts"

--parts
Content-Type: text/plain

Owner documents attached
--parts
Content-Type: application/pdf
Content-Disposition: attachment; filename="report.pdf"

%PDF-1.7 example
--parts
Content-Type: image/png
Content-Disposition: inline; filename="logo.png"

logo
--parts
Content-Type: application/pdf
Content-Disposition: attachment; filename="fake.pdf"

not a PDF
--parts--
""".replace("\n", "\r\n")
        val mail = MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(raw.toByteArray()))

        val attachments = MailAttachmentParser.parse(mail)

        assertEquals(listOf("report.pdf"), attachments.map { it.fileName })
        assertEquals("application/pdf", attachments.single().contentType)
    }
}
