package com.ship2ship.features.technical

import com.ship2ship.support.IntegrationTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import java.util.concurrent.atomic.AtomicInteger

/** Helpers shared by the technical tests: a fresh synthetic vessel per test and document uploads. */
abstract class TechnicalTestSupport : IntegrationTest() {

    protected fun call(session: MockHttpSession, method: String, url: String, payload: Any? = null, version: Long? = null): ResultActions {
        val builder = when (method) {
            "POST" -> post(url)
            "PUT" -> put(url)
            "PATCH" -> patch(url)
            else -> get(url)
        }.session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
        version?.let { builder.header(HttpHeaders.IF_MATCH, "\"$it\"") }
        payload?.let { builder.content(body(it)) }
        return mvc.perform(builder)
    }

    protected fun ok(actions: ResultActions): JsonNode = read(actions.andExpect(status().is2xxSuccessful).andReturn().response.contentAsString)

    /** A new vessel with a valid, unused IMO number (synthetic 2xxxxxx range). */
    protected fun newVessel(): String {
        while (true) {
            val body = (2_000_000 / 10 + SEQUENCE.incrementAndGet() + (System.nanoTime() % 50_000).toInt()).toString().padStart(6, '0').takeLast(6)
            val check = body.mapIndexed { i, c -> (c - '0') * (7 - i) }.sum() % 10
            val res = call(login("admin"), "POST", "/api/v1/masters/vessels", mapOf(
                "imo" to "$body$check", "name" to "DEMO TECH $body", "vesselType" to "PRODUCT_TANKER", "loaM" to "150",
            ))
            if (res.andReturn().response.status == 201) {
                return read(res.andReturn().response.contentAsString)["id"].asString()
            }
        }
    }

    protected fun upload(
        session: MockHttpSession, vesselId: String, bytes: ByteArray = PDF, name: String = "q88.pdf", type: String = DOC_TYPE_Q88,
    ): ResultActions = mvc.perform(
        multipart("/api/v1/technical/vessels/$vesselId/documents")
            .file(MockMultipartFile("file", name, if (name.endsWith(".png")) "image/png" else "application/pdf", bytes))
            .param("documentTypeId", type)
            .session(session).with(csrf()),
    )

    protected companion object {
        private val SEQUENCE = AtomicInteger()
        val PDF = "%PDF-1.4\n% synthetic test document\n%%EOF\n".toByteArray()
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)
        const val DOC_TYPE_Q88 = "01920000-0000-7000-8000-000000000901"
        const val DOC_TYPE_GA = "01920000-0000-7000-8000-000000000902"
    }
}
