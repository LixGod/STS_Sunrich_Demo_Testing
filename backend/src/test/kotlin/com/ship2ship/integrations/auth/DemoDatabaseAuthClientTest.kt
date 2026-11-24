package com.ship2ship.integrations.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.ArgumentMatchers.anyString
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.util.Optional

class DemoDatabaseAuthClientTest {
    @Test
    fun `accepts only a matching stored password`() {
        val hash = BCryptPasswordEncoder().encode("a strong demo password")
        val client = clientWithHash(hash)

        assertEquals("commercial", client.authenticate("commercial", "a strong demo password")?.subjectId)
        assertNull(client.authenticate("commercial", "wrong password"))
    }

    @Test
    fun `rejects users without a stored credential`() {
        assertNull(clientWithHash(null).authenticate("commercial", "anything"))
    }

    private fun clientWithHash(hash: String?): DemoDatabaseAuthClient {
        val jdbc = mock(JdbcClient::class.java)
        val statement = mock(JdbcClient.StatementSpec::class.java)
        @Suppress("UNCHECKED_CAST")
        val query = mock(JdbcClient.MappedQuerySpec::class.java) as JdbcClient.MappedQuerySpec<String?>
        `when`(jdbc.sql(anyString())).thenReturn(statement)
        `when`(statement.param("username", "commercial")).thenReturn(statement)
        `when`(statement.query(String::class.java)).thenReturn(query)
        `when`(query.optional()).thenReturn(Optional.ofNullable(hash))
        return DemoDatabaseAuthClient(jdbc)
    }
}
