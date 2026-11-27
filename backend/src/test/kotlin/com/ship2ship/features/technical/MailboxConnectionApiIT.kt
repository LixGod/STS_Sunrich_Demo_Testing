package com.ship2ship.features.technical

import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class MailboxConnectionApiIT : TechnicalTestSupport() {
    @Test
    fun `only a global technical administrator sees mailbox settings and no password is returned`() {
        val admin = login("sts.admin")
        call(admin, "GET", "/api/v1/technical/mailbox-connection")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.passwordSet").value(false))
            .andExpect(jsonPath("$.password").doesNotExist())
        call(login("commercial"), "GET", "/api/v1/technical/mailbox-connection")
            .andExpect(status().isForbidden)
        call(admin, "PUT", "/api/v1/technical/mailbox-connection", mapOf(
            "host" to "bad host", "port" to 993, "username" to "owner@example.test", "password" to "test"))
            .andExpect(status().isBadRequest)
    }
}
