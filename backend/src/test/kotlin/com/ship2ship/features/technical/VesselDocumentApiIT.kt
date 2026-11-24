package com.ship2ship.features.technical

import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** TR-API-02 / FR-DOC-01: typed uploads, versions per document type, and short-lived, user-bound downloads. */
class VesselDocumentApiIT : TechnicalTestSupport() {

    @Test
    fun `uploads are versioned per type, fingerprinted and not yet scanned`() {
        val technical = login("technical")
        val vessel = newVessel()
        val first = read(upload(technical, vessel).andExpect(status().isCreated).andReturn().response.contentAsString)
        assertEquals(1, first["versionNo"].asInt())
        assertEquals("Q88", first["documentTypeCode"].asString())
        assertEquals("application/pdf", first["file"]["contentType"].asString())
        assertEquals("NOT_SCANNED", first["file"]["scanState"].asString())
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(PDF)), first["file"]["sha256"].asString())

        upload(technical, vessel, PNG, "page.png").andExpect(status().isCreated).andExpect(jsonPath("$.versionNo").value(2))
            .andExpect(jsonPath("$.file.contentType").value("image/png"))
        upload(technical, vessel, type = DOC_TYPE_GA).andExpect(status().isForbidden)
        upload(login("sts.admin"), vessel, type = DOC_TYPE_GA).andExpect(jsonPath("$.versionNo").value(1))

        val list = ok(call(technical, "GET", "/api/v1/technical/vessels/$vessel/documents"))
        assertEquals(3, list.size())
        val q88 = (0 until list.size()).map { list[it] }.filter { it["documentTypeCode"].asString() == "Q88" }
        assertEquals(listOf(true, false), q88.sortedByDescending { it["versionNo"].asInt() }.map { it["latest"].asBoolean() })
    }

    @Test
    fun `only PDF, PNG and JPEG by content, within the size limit, from users who may upload`() {
        val technical = login("technical")
        val vessel = newVessel()
        // An HTML page renamed to .pdf is refused: the type comes from the file's signature, not its name.
        upload(technical, vessel, "<html><script>alert(1)</script></html>".toByteArray(), "evil.pdf")
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"))
        upload(technical, vessel, PDF + ByteArray(1_048_577)).andExpect(status().isContentTooLarge)
            .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"))
        upload(technical, vessel, type = "01920000-0000-7000-8000-000000000999").andExpect(status().isBadRequest)
        upload(login("commercial"), vessel).andExpect(status().isForbidden)
        // Path parts of the original name are dropped.
        upload(technical, vessel, name = "..\\..\\etc/passwd.pdf").andExpect(jsonPath("$.file.name").value("passwd.pdf"))
    }

    @Test
    fun `downloads need a token that only its owner can use, and may be framed by this site only`() {
        val vessel = newVessel()
        val document = read(upload(login("technical"), vessel).andReturn().response.contentAsString)
        val fileId = document["file"]["id"].asString()

        val commercial = login("commercial") // DOCUMENT.READ
        val token = ok(call(commercial, "POST", "/api/v1/files/$fileId/download-token"))
        assertTrue(token["url"].asString().startsWith("/api/v1/files/download/"))
        val response = mvc.perform(get(token["url"].asString()).session(commercial))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "application/pdf"))
            .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
            .andExpect(header().string("Content-Security-Policy", "frame-ancestors 'self'"))
            .andExpect(header().string("Cache-Control", "private, no-store"))
            .andReturn().response
        assertContentEquals(PDF, response.contentAsByteArray)
        assertTrue(response.getHeader("Content-Disposition")!!.startsWith("inline"))

        jdbc.sql("UPDATE user_role_scope SET valid_to=CURRENT_DATE - INTERVAL 1 DAY WHERE id=UUID_TO_BIN('01920000-0000-7000-9000-000000000504')").update()
        try {
            mvc.perform(get(token["url"].asString()).session(commercial)).andExpect(status().isForbidden)
        } finally {
            jdbc.sql("UPDATE user_role_scope SET valid_to=NULL WHERE id=UUID_TO_BIN('01920000-0000-7000-9000-000000000504')").update()
        }

        // Another user cannot use the token; a user without DOCUMENT.READ gets no token.
        mvc.perform(get(token["url"].asString()).session(login("technical"))).andExpect(status().isNotFound)
        call(login("finance"), "POST", "/api/v1/files/$fileId/download-token").andExpect(status().isForbidden)
        // Other API responses still may not be framed at all.
        val json = mvc.perform(get("/api/v1/technical/vessels/$vessel/documents").session(commercial)).andReturn().response
        assertEquals("DENY", json.getHeader("X-Frame-Options"))
        assertFalse(json.getHeader("X-Frame-Options") == "SAMEORIGIN")
    }
}
