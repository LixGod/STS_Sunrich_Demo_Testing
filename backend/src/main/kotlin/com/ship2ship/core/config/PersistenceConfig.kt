package com.ship2ship.core.config

import com.ship2ship.core.security.SessionUser
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.auditing.DateTimeProvider
import org.springframework.data.domain.AuditorAware
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.Optional
import java.util.UUID

@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
class PersistenceConfig {

    /** All server time is UTC; tests may replace this bean with a fixed clock. */
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    /** DATETIME(6) keeps microseconds, so in-memory values are truncated to match what is stored. */
    @Bean
    fun auditingDateTimeProvider(clock: Clock) = DateTimeProvider {
        Optional.of(clock.instant().truncatedTo(ChronoUnit.MICROS))
    }

    @Bean
    fun auditorAware() = AuditorAware<UUID> {
        Optional.ofNullable((SecurityContextHolder.getContext().authentication?.principal as? SessionUser)?.userId)
    }
}
