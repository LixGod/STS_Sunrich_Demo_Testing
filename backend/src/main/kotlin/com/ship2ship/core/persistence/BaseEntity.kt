package com.ship2ship.core.persistence

import jakarta.persistence.Column
import jakarta.persistence.EntityListeners
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.Version
import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.util.UUID

/**
 * Standard columns for versioned business records (SRS TR-DB-02): UUIDv7 key, who/when metadata
 * and an optimistic-lock version. A null version marks the entity as new for Spring Data.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener::class)
abstract class BaseEntity {

    @Id
    @Column(columnDefinition = "BINARY(16)")
    var id: UUID = UuidV7.generate()

    @CreatedDate
    @Column(nullable = false, updatable = false)
    var createdAt: Instant? = null

    @CreatedBy
    @Column(updatable = false, columnDefinition = "BINARY(16)")
    var createdBy: UUID? = null

    @LastModifiedDate
    @Column(nullable = false)
    var updatedAt: Instant? = null

    @LastModifiedBy
    @Column(columnDefinition = "BINARY(16)")
    var updatedBy: UUID? = null

    @Version
    var version: Long? = null
}
