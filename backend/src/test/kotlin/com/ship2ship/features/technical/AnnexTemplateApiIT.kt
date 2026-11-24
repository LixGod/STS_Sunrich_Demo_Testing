package com.ship2ship.features.technical

import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals

/** Phase 3c: JPO annex wording as versioned templates, published by a second person with the owner's confirmation. */
class AnnexTemplateApiIT : JobTestSupport() {

    @Test
    fun `untouched draft reports missing wording rather than a server error`() {
        val id = java.util.UUID.randomUUID().toString()
        val sectionId = java.util.UUID.randomUUID().toString()
        jdbc.sql("""INSERT INTO annex_template (id,version_no,status,name,created_at,updated_at,version)
            VALUES (UUID_TO_BIN(:id),(SELECT n FROM (SELECT COALESCE(MAX(version_no),0)+1 n FROM annex_template) t),
            'DRAFT','Synthetic blank annex',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)""")
            .param("id", id).update()
        jdbc.sql("""INSERT INTO annex_template_section (id,template_id,sort_order,code,title,body,applies_when)
            VALUES (UUID_TO_BIN(:section),UUID_TO_BIN(:id),10,'RISK','Risk','','ALWAYS')""")
            .param("section", sectionId).param("id", id).update()
        try {
            val publisher = login("sts.admin")
            call(publisher, "POST", "/api/v1/technical/annex-templates/$id/publish",
                mapOf("ownerConfirmation" to "Synthetic owner"), 0)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("ANNEX_WORDING_MISSING"))
            jdbc.sql("UPDATE annex_template_section SET body='Synthetic wording' WHERE template_id=UUID_TO_BIN(:id)")
                .param("id", id).update()
            call(publisher, "POST", "/api/v1/technical/annex-templates/$id/publish",
                mapOf("ownerConfirmation" to " "), 0)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"))
        } finally {
            jdbc.sql("DELETE FROM annex_template_section WHERE template_id=UUID_TO_BIN(:id)").param("id", id).update()
            jdbc.sql("DELETE FROM annex_template WHERE id=UUID_TO_BIN(:id)").param("id", id).update()
        }
    }

    @Test
    fun `annex wording is published only when complete, confirmed and by someone other than its last editor`() {
        val admin = login("sts.admin")
        val technical = login("admin")
        val previous = publishAnnex()
        assertEquals("PUBLISHED", previous["status"].asString())

        val draft = ok(call(admin, "POST", "/api/v1/technical/annex-templates"))
        assertEquals(previous["versionNo"].asInt() + 1, draft["versionNo"].asInt())
        assertEquals(previous["sections"].size(), draft["sections"].size()) // copied from the newest version
        call(admin, "POST", "/api/v1/technical/annex-templates").andExpect(jsonPath("$.code").value("ANNEX_DRAFT_OPEN"))
        call(login("commercial"), "POST", "/api/v1/technical/annex-templates").andExpect(status().isForbidden)

        val url = "/api/v1/technical/annex-templates/${draft["id"].asString()}"
        call(admin, "PUT", url, mapOf("name" to "X", "sections" to listOf(mapOf("code" to "bad code", "title" to "T", "body" to "B"))),
            draft["version"].asLong()).andExpect(status().isBadRequest)
        call(admin, "PUT", url, mapOf("name" to "X", "sections" to listOf(
            mapOf("code" to "A", "title" to "T", "body" to "B"), mapOf("code" to "A", "title" to "T2", "body" to "B2"),
        )), draft["version"].asLong()).andExpect(status().isBadRequest) // duplicate codes

        val withGap = ok(call(admin, "PUT", url, mapOf("name" to "Synthetic annex", "sections" to listOf(
            mapOf("code" to "RISK", "title" to "Risk", "body" to "Synthetic wording", "appliesWhen" to "ALWAYS"),
            mapOf("code" to "REVERSE", "title" to "Reverse", "body" to "", "appliesWhen" to "REVERSE_LIGHTERING"),
        )), draft["version"].asLong()))
        call(technical, "POST", "$url/publish", mapOf("ownerConfirmation" to "Synthetic"), withGap["version"].asLong())
            .andExpect(jsonPath("$.code").value("ANNEX_WORDING_MISSING"))

        val complete = ok(call(admin, "PUT", url, mapOf("name" to "Synthetic annex", "sections" to listOf(
            mapOf("code" to "RISK", "title" to "Risk", "body" to "Synthetic wording", "appliesWhen" to "ALWAYS"),
            mapOf("code" to "REVERSE", "title" to "Reverse", "body" to "Synthetic reverse wording", "appliesWhen" to "REVERSE_LIGHTERING"),
        )), withGap["version"].asLong()))
        call(admin, "POST", "$url/publish", mapOf("ownerConfirmation" to "Synthetic"), complete["version"].asLong())
            .andExpect(jsonPath("$.code").value("SECOND_PERSON_REQUIRED"))
        call(technical, "POST", "$url/publish", mapOf("ownerConfirmation" to " "), complete["version"].asLong())
            .andExpect(jsonPath("$.code").value("REASON_REQUIRED"))
        val published = ok(call(technical, "POST", "$url/publish", mapOf("ownerConfirmation" to "Synthetic owner"), complete["version"].asLong()))
        assertEquals("PUBLISHED", published["status"].asString())
        assertEquals("Synthetic owner", published["ownerConfirmation"].asString())
        // Only one version is published at a time; a published version cannot be edited.
        assertEquals("RETIRED", ok(call(admin, "GET", "/api/v1/technical/annex-templates/${previous["id"].asString()}"))["status"].asString())
        call(admin, "PUT", url, mapOf("name" to "Y", "sections" to emptyList<Any>()), published["version"].asLong())
            .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"))

        publishAnnex() // leave a full annex published for the other tests
    }
}
