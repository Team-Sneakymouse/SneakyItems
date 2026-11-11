package com.sneakyitems.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RecheckQueueTest {

    @Test
    fun `coalesced requests never downgrade legacy matching in either order`() {
        for (first in listOf(true, false)) {
            val queue = RecheckQueue<String>()
            queue.add("a", RecheckFilter.All, first)
            queue.add("a", RecheckFilter.Names(setOf("changed")), !first)
            val request = queue.poll()!!
            assertEquals(RecheckFilter.All, request.filter)
            assertEquals(true, request.includeLegacy)
            queue.add("b", RecheckFilter.All, false)
            assertEquals(false, queue.poll()!!.includeLegacy)
        }
    }


    @Test
    fun `entries come out in order and a re-queued key keeps its place`() {
        val queue = RecheckQueue<String>()
        queue.add("a", RecheckFilter.All)
        queue.add("b", RecheckFilter.All)
        queue.add("a", RecheckFilter.All)
        assertEquals(2, queue.size)
        assertEquals("a", queue.poll()!!.key)
        assertEquals("b", queue.poll()!!.key)
        assertNull(queue.poll())
    }

    @Test
    fun `name filters merge, and All wins`() {
        val queue = RecheckQueue<String>()
        queue.add("a", RecheckFilter.Names(setOf("x")))
        queue.add("a", RecheckFilter.Names(setOf("y")))
        assertEquals(RecheckFilter.Names(setOf("x", "y")), queue.poll()!!.filter)

        queue.add("b", RecheckFilter.Names(setOf("x")))
        queue.add("b", RecheckFilter.All)
        queue.add("b", RecheckFilter.Names(setOf("y")))
        assertEquals(RecheckFilter.All, queue.poll()!!.filter)
    }
}
