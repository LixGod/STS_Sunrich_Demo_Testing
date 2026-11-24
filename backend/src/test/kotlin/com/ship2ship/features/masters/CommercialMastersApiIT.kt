package com.ship2ship.features.masters

import com.ship2ship.support.IntegrationTest
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.random.Random

/** Commercial and finance masters: validation, uniqueness, effective dating, immutability and scope (FR-MD-01/02, D05). */
class CommercialMastersApiIT : IntegrationTest() {

    private fun code(prefix: String) = "$prefix-${Random.nextInt(10_000, 99_999)}"

    private fun postJson(session: MockHttpSession, url: String, payload: Any): ResultActions =
        mvc.perform(post(url).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body(payload)))

    /** A fresh synthetic IMO (unassigned 1xxxxxx range) with a correct check digit. */
    private fun syntheticImo(): String {
        val base = (100_000 + Random.nextInt(0, 900_000)).toString().padStart(6, '0').let { "1" + it.drop(1) }
        val check = (0 until 6).sumOf { (base[it] - '0') * (7 - it) } % 10
        return base + check
    }

    @Test
    fun `client keeps ordered company emails and phones separately from named contacts`() {
        val admin = login("sts.admin")
        val code = code("CL")
        val path = "/api/v1/masters/clients"
        val created = read(postJson(admin, path, mapOf(
            "code" to code, "legalName" to "Fleet Client", "roles" to listOf("CHARTERER"),
            "emails" to listOf("OPS@FLEET.TEST", "billing@fleet.test"),
            "phones" to listOf("+91 9000000000", "+91 9111111111"),
        )).andExpect(status().isCreated)
            .andExpect(jsonPath("$.emails[0]").value("ops@fleet.test"))
            .andExpect(jsonPath("$.emails[1]").value("billing@fleet.test"))
            .andExpect(jsonPath("$.phones[1]").value("+91 9111111111"))
            .andExpect(jsonPath("$.phone").value("+91 9000000000"))
            .andReturn().response.contentAsString)
        val id = created["id"].asString()
        val updated = read(mvc.perform(put("$path/$id").session(admin).with(csrf())
            .header(HttpHeaders.IF_MATCH, "\"${created["version"].asLong()}\"")
            .contentType(MediaType.APPLICATION_JSON).content(body(mapOf(
                "code" to code, "legalName" to "Fleet Client", "roles" to listOf("CHARTERER"),
                "phone" to "+91 9222222222",
            )))).andExpect(status().isOk).andReturn().response.contentAsString)
        org.junit.jupiter.api.Assertions.assertEquals("+91 9222222222", updated["phones"][0].asString())
        org.junit.jupiter.api.Assertions.assertEquals("+91 9111111111", updated["phones"][1].asString())
        org.junit.jupiter.api.Assertions.assertEquals("ops@fleet.test", updated["emails"][0].asString())
        postJson(admin, path, mapOf(
            "code" to code("CL"), "legalName" to "Invalid Client", "roles" to listOf("OWNER"),
            "emails" to listOf("same@fleet.test", "SAME@fleet.test"),
        )).andExpect(status().isBadRequest).andExpect(jsonPath("$.errors[0].field").value("emails[1]"))
    }

    @Test
    fun `vessels need a valid, unique IMO and stay unverified`() {
        val admin = login("admin")
        postJson(admin, "/api/v1/masters/vessels", mapOf("imo" to "1000008", "name" to "Bad check", "vesselType" to "OTHER"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("imo"))

        val imo = syntheticImo()
        postJson(admin, "/api/v1/masters/vessels", mapOf("imo" to imo, "name" to "demo new", "vesselType" to "LPG_CARRIER", "loaM" to 180.5))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.name").value("DEMO NEW"))
            .andExpect(jsonPath("$.particularsStatus").value("UNVERIFIED"))
        postJson(admin, "/api/v1/masters/vessels", mapOf("imo" to imo, "name" to "Again", "vesselType" to "OTHER"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("DUPLICATE_IMO"))

        mvc.perform(get("/api/v1/masters/vessels").session(admin).param("q", imo))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(1))
    }

    @Test
    fun `client keeps one primary contact and rejects unknown currencies`() {
        val commercial = login("sts.admin")
        postJson(commercial, "/api/v1/masters/clients", mapOf("code" to code("CL"), "legalName" to "X", "roles" to listOf("OWNER"), "defaultCurrency" to "ZZZ"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("defaultCurrency"))

        val id = read(postJson(commercial, "/api/v1/masters/clients",
            mapOf("code" to code("CL"), "legalName" to "DEMO Client (Synthetic)", "roles" to listOf("OWNER", "CHARTERER"), "defaultCurrency" to "usd", "countryCode" to "ae", "phone" to "+971 50 123 4567"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.roles.length()").value(2))
            .andExpect(jsonPath("$.defaultCurrency").value("USD"))
            .andExpect(jsonPath("$.countryCode").value("AE"))
            .andExpect(jsonPath("$.phone").value("+971 50 123 4567"))
            .andReturn().response.contentAsString)["id"].asString()

        mvc.perform(get("/api/v1/masters/clients/$id").session(commercial))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.countryCode").value("AE"))
            .andExpect(jsonPath("$.phone").value("+971 50 123 4567"))

        postJson(commercial, "/api/v1/masters/clients/$id/contacts", mapOf("fullName" to "First", "primary" to true)).andExpect(status().isCreated)
        postJson(commercial, "/api/v1/masters/clients/$id/contacts", mapOf("fullName" to "Second", "primary" to true)).andExpect(status().isCreated)
        mvc.perform(get("/api/v1/masters/clients/$id/contacts").session(commercial))
            .andExpect(jsonPath("$[0].fullName").value("Second"))
            .andExpect(jsonPath("$[0].primary").value(true))
            .andExpect(jsonPath("$[1].primary").value(false))

        mvc.perform(get("/api/v1/masters/clients").session(commercial).param("role", "CHARTERER").param("size", "100"))
            .andExpect(jsonPath("$.items[*].id").value(hasItem(id)))
    }

    @Test
    fun `commercial can add clients and vessels from an enquiry but cannot maintain masters`() {
        val commercial = login("commercial")
        val created = read(postJson(commercial, "/api/v1/masters/clients/quick-add",
            mapOf("legalName" to "Synthetic Quick Client", "email" to "quick@example.test", "countryCode" to "ae", "phone" to "+971 50 123 4567"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.client.countryCode").value("AE"))
            .andExpect(jsonPath("$.client.phone").value("+971 50 123 4567"))
            .andExpect(jsonPath("$.client.emails[0]").value("quick@example.test"))
            .andExpect(jsonPath("$.client.phones[0]").value("+971 50 123 4567"))
            .andExpect(jsonPath("$.contact.email").value("quick@example.test"))
            .andExpect(jsonPath("$.contact.phone").value("+971 50 123 4567"))
            .andReturn().response.contentAsString)
        val clientId = created["client"]["id"].asString()
        mvc.perform(get("/api/v1/masters/clients/$clientId").session(commercial))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.countryCode").value("AE"))
            .andExpect(jsonPath("$.phone").value("+971 50 123 4567"))
            .andExpect(jsonPath("$.phones[0]").value("+971 50 123 4567"))
            .andExpect(jsonPath("$.emails[0]").value("quick@example.test"))
        postJson(commercial, "/api/v1/masters/clients/quick-add",
            mapOf("legalName" to "Synthetic Quick Client Minimal", "email" to "minimal@example.test"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.client.countryCode").isEmpty)
            .andExpect(jsonPath("$.client.phones.length()").value(0))
            .andExpect(jsonPath("$.contact.phone").isEmpty)
        postJson(commercial, "/api/v1/masters/clients",
            mapOf("code" to code("CL"), "legalName" to "Forbidden", "roles" to listOf("OTHER")))
            .andExpect(status().isForbidden)
        mvc.perform(patch("/api/v1/masters/clients/$clientId/status").session(commercial).with(csrf())
            .header(HttpHeaders.IF_MATCH, "\"0\"").contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("active" to false, "reason" to "Test"))))
            .andExpect(status().isForbidden)

        val imo = syntheticImo()
        val vesselId = read(postJson(commercial, "/api/v1/masters/vessels",
            mapOf("imo" to imo, "name" to "Synthetic Quick Vessel", "vesselType" to "OTHER"))
            .andExpect(status().isCreated).andReturn().response.contentAsString)["id"].asString()
        mvc.perform(patch("/api/v1/masters/vessels/$vesselId/status").session(commercial).with(csrf())
            .header(HttpHeaders.IF_MATCH, "\"0\"").contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("active" to false, "reason" to "Test"))))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `finance cannot edit commercial masters and commercial cannot edit finance masters`() {
        postJson(login("finance"), "/api/v1/masters/units", mapOf("code" to code("U"), "name" to "X", "dimension" to "OTHER"))
            .andExpect(status().isForbidden)
        postJson(login("commercial"), "/api/v1/masters/cost-heads", mapOf("code" to code("C"), "name" to "X", "kind" to "EXPENSE"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `code lists keep their code and reject duplicates`() {
        val admin = login("admin")
        val unitCode = code("U")
        val created = read(postJson(admin, "/api/v1/masters/units", mapOf("code" to unitCode.lowercase(), "name" to "Test unit", "dimension" to "OTHER"))
            .andExpect(status().isCreated).andExpect(jsonPath("$.code").value(unitCode)).andReturn().response.contentAsString)
        postJson(admin, "/api/v1/masters/units", mapOf("code" to unitCode, "name" to "Again", "dimension" to "OTHER"))
            .andExpect(status().isConflict)
        mvc.perform(put("/api/v1/masters/units/${created["id"].asString()}").session(admin).with(csrf()).header(HttpHeaders.IF_MATCH, "\"0\"")
            .contentType(MediaType.APPLICATION_JSON).content(body(mapOf("code" to "OTHER-CODE", "name" to "Renamed", "dimension" to "OTHER"))))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("CODE_IMMUTABLE"))

        postJson(admin, "/api/v1/masters/currencies", mapOf("code" to "ABC", "name" to "Not ISO", "minorUnits" to 2))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `exchange rate and tax code master endpoints are removed`() {
        val finance = login("sts.admin")
        mvc.perform(get("/api/v1/masters/fx-rates").session(finance)).andExpect(status().isNotFound)
        mvc.perform(get("/api/v1/masters/tax-codes").session(finance)).andExpect(status().isNotFound)
    }

    @Test
    fun `tariffs allow only 24, 48 or 72 hour blocks, never overlap, and stay within the entity scope`() {
        val finance = login("sts.admin")
        val tariffCode = code("T")
        val base = mapOf(
            "code" to tariffCode, "name" to "Test tariff", "currency" to "USD",
            "baseHours" to 48, "baseCharge" to "1000.00", "extraHourRate" to "100.00", "effectiveFrom" to "2026-01-01",
            "effectiveTo" to "2026-06-30",
        )
        postJson(finance, "/api/v1/masters/tariffs", base + ("baseHours" to 36)).andExpect(status().isBadRequest)
        postJson(finance, "/api/v1/masters/tariffs", base).andExpect(status().isCreated)
            .andExpect(jsonPath("$.termsPending").value(true))
        postJson(finance, "/api/v1/masters/tariffs", base + mapOf("effectiveFrom" to "2026-06-01", "effectiveTo" to null))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("TARIFF_PERIOD_OVERLAP"))
        postJson(finance, "/api/v1/masters/tariffs", base + mapOf("effectiveFrom" to "2026-07-01", "effectiveTo" to null, "baseHours" to 72))
            .andExpect(status().isCreated)

        // Tariffs are company-wide: a branch-scoped user may not create one.
        postJson(login("ops.mumbai"), "/api/v1/masters/tariffs", base + mapOf("code" to code("T")))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `unused commercial masters can be deleted`() {
        val admin = login("sts.admin")
        val opId = read(postJson(admin, "/api/v1/masters/operation-types", mapOf("code" to code("OP"), "name" to "Temp Op"))
            .andExpect(status().isCreated).andReturn().response.contentAsString)["id"].asString()
        mvc.perform(delete("/api/v1/masters/operation-types/$opId").session(admin).with(csrf()).header(HttpHeaders.IF_MATCH, "\"0\""))
            .andExpect(status().isNoContent)

        val vesselId = read(postJson(admin, "/api/v1/masters/vessels", mapOf("imo" to syntheticImo(), "name" to "Temp Vessel", "vesselType" to "OTHER"))
            .andExpect(status().isCreated).andReturn().response.contentAsString)["id"].asString()
        mvc.perform(delete("/api/v1/masters/vessels/$vesselId").session(admin).with(csrf()).header(HttpHeaders.IF_MATCH, "\"0\""))
            .andExpect(status().isNoContent)

        val clientId = read(postJson(admin, "/api/v1/masters/clients", mapOf("code" to code("CL"), "legalName" to "Temp Client", "roles" to listOf("OTHER")))
            .andExpect(status().isCreated).andReturn().response.contentAsString)["id"].asString()
        mvc.perform(delete("/api/v1/masters/clients/$clientId").session(admin).with(csrf()).header(HttpHeaders.IF_MATCH, "\"0\""))
            .andExpect(status().isNoContent)
    }
}
