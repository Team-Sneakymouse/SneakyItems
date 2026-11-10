package com.sneakyitems.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RecheckQueueTest {

    @Test
    fun `entries come out in order and a re-queued key keeps its place`() {
        val queue = RecheckQueue<String>()
        queue.add("a", RecheckFilter.All)
        queue.add("b", RecheckFilter.All)
        queue.add("a", RecheckFilter.All)
        assertEquals(2, queue.size)
        assertEquals("a", queue.poll()!!.first)
        assertEquals("b", queue.poll()!!.first)
        assertNull(queue.poll())
    }

    @Test
    fun `name filters merge, and All wins`() {
        val queue = RecheckQueue<String>()
        queue.add("a", RecheckFilter.Names(setOf("x")))
        queue.add("a", RecheckFilter.Names(setOf("y")))
        assertEquals(RecheckFilter.Names(setOf("x", "y")), queue.poll()!!.second)

        queue.add("b", RecheckFilter.Names(setOf("x")))
        queue.add("b", RecheckFilter.All)
        queue.add("b", RecheckFilter.Names(setOf("y")))
        assertEquals(RecheckFilter.All, queue.poll()!!.second)
    }
}
