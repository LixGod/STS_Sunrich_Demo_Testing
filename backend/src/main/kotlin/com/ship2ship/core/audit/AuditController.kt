package com.ship2ship.core.audit

import com.ship2ship.core.security.AccessPolicy
import com.ship2ship.core.security.Permissions
import com.ship2ship.core.web.BadRequestException
import com.ship2ship.core.web.CursorResponse
import com.ship2ship.core.web.Paging
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

data class AuditEventDto(
    val seq: Long,
    val occurredAt: Instant,
    val actorUserId: UUID?,
    val actorName: String?,
    val action: String,
    val entityType: String?,
    val entityId: UUID?,
    val details: Any?,
)

/** Newest-first audit trail, keyset-paginated by sequence number and filtered to the reader's scope. */
@RestController
@RequestMapping("/api/v1/audit-events")
class AuditController(
    private val jdbc: JdbcClient,
    private val accessPolicy: AccessPolicy,
    private val paging: Paging,
    private val jsonMapper: JsonMapper,
) {

    @GetMapping
    @Transactional(readOnly = true)
    fun list(
        @RequestParam entityType: String?,
        @RequestParam entityId: UUID?,
        @RequestParam after: String?,
        @RequestParam limit: Int?,
    ): CursorResponse<AuditEventDto> {
        val scope = accessPolicy.scope(Permissions.AUDIT_READ)
        val pageLimit = paging.limit(limit)
        val afterSeq = after?.let { it.toLongOrNull() ?: throw BadRequestException("INVALID_CURSOR", "Invalid cursor.") }

        val conditions = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        afterSeq?.let { conditions += "seq < :after"; params["after"] = it }
        entityType?.let { conditions += "entity_type = :entityType"; params["entityType"] = it }
        entityId?.let { conditions += "entity_id = :entityId"; params["entityId"] = Uuids.toBytes(it) }
        scope.sqlCondition("branch_id", params)?.let { conditions += it }
        val where = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")

        val rows = jdbc.sql(
            """
            SELECT seq, occurred_at, actor_user_id, actor_name, action, entity_type, entity_id, details
            FROM audit_event $where ORDER BY seq DESC LIMIT :limit
            """,
        ).params(params).param("limit", pageLimit + 1).query { rs, _ ->
            AuditEventDto(
                seq = rs.getLong("seq"),
                occurredAt = Uuids.fromTimestamp(rs.getObject("occurred_at", LocalDateTime::class.java)),
                actorUserId = Uuids.fromBytes(rs.getBytes("actor_user_id")),
                actorName = rs.getString("actor_name"),
                action = rs.getString("action"),
                entityType = rs.getString("entity_type"),
                entityId = Uuids.fromBytes(rs.getBytes("entity_id")),
                details = rs.getString("details")?.let { jsonMapper.readTree(it) },
            )
        }.list()

        val hasMore = rows.size > pageLimit
        val items = rows.take(pageLimit)
        return CursorResponse(items, if (hasMore) items.last().seq.toString() else null)
    }
}
