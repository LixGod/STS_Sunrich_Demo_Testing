package com.ship2ship.core.web

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

/**
 * Optimistic concurrency over HTTP: reads return `ETag: "<version>"`, updates must send `If-Match` with the
 * version the client edited. A missing header is 428 and a mismatch is 409 (SRS TR-API-01).
 */
object Versioning {

    fun etag(version: Long?): String = "\"${version ?: 0}\""

    fun requireIfMatch(ifMatch: String?): Long {
        if (ifMatch.isNullOrBlank()) {
            throw ApiException(HttpStatus.PRECONDITION_REQUIRED, "VERSION_REQUIRED", "The If-Match header is required.")
        }
        return ifMatch.trim().removePrefix("W/").trim('"').toLongOrNull()
            ?: throw BadRequestException("INVALID_VERSION", "The If-Match header must contain a record version.")
    }

    fun checkCurrent(expected: Long, actual: Long?) {
        if (expected != actual) {
            throw ConflictException("STALE_VERSION", "The record was changed by someone else. Reload and try again.")
        }
    }

    fun <T : Any> ok(body: T, version: Long?): ResponseEntity<T> = ResponseEntity.ok().eTag(etag(version)).body(body)
}
