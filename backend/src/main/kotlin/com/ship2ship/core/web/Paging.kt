package com.ship2ship.core.web

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component

@ConfigurationProperties("ship2ship.paging")
data class PagingProperties(val defaultSize: Int = 25, val maxSize: Int = 100)

/** List response envelope: `{ items, page, size, totalElements }`. */
data class PageResponse<T : Any>(val items: List<T>, val page: Int, val size: Int, val totalElements: Long) {
    companion object {
        fun <E : Any, T : Any> of(page: Page<E>, mapper: (E) -> T) =
            PageResponse(page.content.map(mapper), page.number, page.size, page.totalElements)
    }
}

/** Keyset response for append-only streams (audit, events): `{ items, nextCursor }`. */
data class CursorResponse<T : Any>(val items: List<T>, val nextCursor: String?)

/**
 * Builds a validated [Pageable]. The page size is clamped to the configured maximum so no request can pull an
 * unbounded result set, and only whitelisted sort fields are accepted (`sort=field,asc|desc`).
 */
@Component
class Paging(private val properties: PagingProperties) {

    fun pageable(page: Int?, size: Int?, sort: String?, sortFields: Map<String, String>, defaultSort: Sort): Pageable {
        val pageIndex = page ?: 0
        if (pageIndex < 0) throw BadRequestException("INVALID_PAGE", "page must be zero or greater.")
        val pageSize = (size ?: properties.defaultSize).coerceIn(1, properties.maxSize)
        return PageRequest.of(pageIndex, pageSize, parseSort(sort, sortFields) ?: defaultSort)
    }

    fun limit(limit: Int?): Int = (limit ?: properties.defaultSize).coerceIn(1, properties.maxSize)

    private fun parseSort(sort: String?, sortFields: Map<String, String>): Sort? {
        if (sort.isNullOrBlank()) return null
        val parts = sort.split(',').map(String::trim)
        val property = sortFields[parts[0]]
            ?: throw BadRequestException("INVALID_SORT", "Sorting by '${parts[0]}' is not supported.")
        val direction = when (parts.getOrNull(1)?.lowercase()) {
            null, "asc" -> Sort.Direction.ASC
            "desc" -> Sort.Direction.DESC
            else -> throw BadRequestException("INVALID_SORT", "Sort direction must be 'asc' or 'desc'.")
        }
        return Sort.by(direction, property)
    }
}
