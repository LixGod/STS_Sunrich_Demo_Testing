package com.ship2ship.core.web

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.domain.Sort
import kotlin.test.assertEquals

class PagingTest {

    private val paging = Paging(PagingProperties(defaultSize = 25, maxSize = 100))
    private val sorts = mapOf("name" to "legalName")

    @Test
    fun `uses default page and size`() {
        val pageable = paging.pageable(null, null, null, sorts, Sort.by("legalName"))
        assertEquals(0, pageable.pageNumber)
        assertEquals(25, pageable.pageSize)
    }

    @Test
    fun `clamps oversized and undersized page sizes`() {
        assertEquals(100, paging.pageable(0, 50_000, null, sorts, Sort.unsorted()).pageSize)
        assertEquals(1, paging.pageable(0, 0, null, sorts, Sort.unsorted()).pageSize)
        assertEquals(100, paging.limit(1_000))
    }

    @Test
    fun `maps whitelisted sort to entity property`() {
        val order = paging.pageable(0, 10, "name,desc", sorts, Sort.unsorted()).sort.getOrderFor("legalName")
        assertEquals(Sort.Direction.DESC, order?.direction)
    }

    @Test
    fun `rejects unknown sort fields, bad directions and negative pages`() {
        assertEquals("INVALID_SORT", assertThrows<BadRequestException> {
            paging.pageable(0, 10, "password", sorts, Sort.unsorted())
        }.code)
        assertEquals("INVALID_SORT", assertThrows<BadRequestException> {
            paging.pageable(0, 10, "name,sideways", sorts, Sort.unsorted())
        }.code)
        assertEquals("INVALID_PAGE", assertThrows<BadRequestException> {
            paging.pageable(-1, 10, null, sorts, Sort.unsorted())
        }.code)
    }
}
