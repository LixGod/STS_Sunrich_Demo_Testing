package com.ship2ship.core.files

import com.ship2ship.core.audit.AuditService
import com.ship2ship.core.security.AccessPolicy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockMultipartFile
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileServiceTest {
    @TempDir lateinit var root: Path

    @Test
    fun `a rolled back upload removes its bytes`() {
        val jdbc = mock(JdbcClient::class.java)
        `when`(jdbc.sql(anyString())).thenThrow(IllegalStateException("database refused upload"))
        val service = FileService(LocalDiskFileStore(root.toString()), jdbc, mock(AccessPolicy::class.java),
            mock(AuditService::class.java), Clock.systemUTC(), 1024, 5)
        TransactionSynchronizationManager.initSynchronization()
        try {
            assertFailsWith<IllegalStateException> {
                service.store(MockMultipartFile("file", "test.pdf", "application/pdf", "%PDF-1.4\n".toByteArray()),
                    FileAccess("DOCUMENT.READ"))
            }
            val sync = TransactionSynchronizationManager.getSynchronizations().single()
            Files.walk(root).use { paths -> assertEquals(1, paths.filter { Files.isRegularFile(it) }.count()) }
            sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)
            Files.walk(root).use { paths -> assertEquals(0, paths.filter { Files.isRegularFile(it) }.count()) }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }
}
