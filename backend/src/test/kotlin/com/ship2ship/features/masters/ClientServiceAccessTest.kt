package com.ship2ship.features.masters

import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.CurrentUser
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.security.ScopeGrant
import com.ship2ship.core.web.NotFoundException
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.Optional
import java.util.UUID
import kotlin.test.assertFailsWith

class ClientServiceAccessTest {
    @Test
    fun `full client detail requires commercial master read even for an enquiry reader`() {
        val clients = mock(ClientRepository::class.java)
        val access = mock(AccessPolicy::class.java)
        val id = UUID.randomUUID()
        `when`(access.currentUser()).thenReturn(CurrentUser(id, "Enquiry reader", "en",
            listOf(ScopeGrant("ENQUIRY_ONLY", null, setOf(Permissions.ENQUIRY_READ)))))
        `when`(clients.findById(id)).thenReturn(Optional.empty())
        val service = ClientService(clients, mock(ClientContactRepository::class.java),
            mock(CurrencyRepository::class.java), access, mock(AuditService::class.java))

        assertFailsWith<NotFoundException> { service.get(id) }
        verify(access).requireAny(Permissions.MASTER_COMMERCIAL_READ)
    }
}
