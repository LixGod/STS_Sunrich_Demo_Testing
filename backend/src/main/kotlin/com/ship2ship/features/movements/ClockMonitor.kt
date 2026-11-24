package com.ship2ship.features.movements

import com.ship2ship.core.audit.Uuids
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

@Component
class ClockMonitor(private val jdbc:JdbcClient,private val clocks:ChargeClockService,
    @Value("\${ship2ship.alerts.enabled:true}") private val enabled:Boolean, private val clock: Clock) {
    @Scheduled(fixedDelayString="\${ship2ship.alerts.refresh-ms:60000}",initialDelayString="\${ship2ship.alerts.refresh-ms:60000}")
    fun check() {
        if(!enabled) return
        candidates().forEach(clocks::captureOverrun)
    }

    /**
     * Open jobs whose clock may need work from [ChargeClockService.captureOverrun], which re-checks each one: the clock no
     * longer follows the latest active movement (a sync write), or its basis hours have passed (an extra-hours notice).
     * Clocks inside their basis are skipped; the filter is deliberately wider than the exact rule, never narrower.
     */
    internal fun candidates(): List<UUID> = jdbc.sql("""SELECT c.job_id FROM job_charge_clock c JOIN sts_job j ON j.id=c.job_id
        LEFT JOIN craft_movement s ON s.id=c.start_movement_id AND s.status='ACTIVE'
        LEFT JOIN craft_movement e ON e.id=c.stop_movement_id
        LEFT JOIN craft_movement latest ON latest.id=(SELECT m.id FROM craft_movement m WHERE m.job_id=c.job_id AND m.status='ACTIVE'
            ORDER BY m.departed_at DESC,m.id DESC LIMIT 1)
        WHERE j.status NOT IN ('CLOSED','CANCELLED') AND (
            (latest.id IS NOT NULL AND (s.id IS NULL OR c.start_movement_id<>latest.id
                OR NOT (c.stop_movement_id <=> IF(latest.returned_at IS NULL,NULL,latest.id))))
            OR (s.id IS NOT NULL AND DATE_ADD(s.departed_at,INTERVAL c.basis_hours HOUR) < COALESCE(e.returned_at,:now)))""")
        .param("now", Uuids.toTimestamp(clock.instant())).query { rs,_->rs.getBytes(1).uuid()!! }.list()
}
