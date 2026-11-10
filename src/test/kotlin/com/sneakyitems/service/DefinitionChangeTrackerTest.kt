package com.sneakyitems.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DefinitionChangeTrackerTest {

    private fun ticksUntilSettle(tracker: DefinitionChangeTracker, max: Int = 100): Int {
        for (i in 1..max) if (tracker.tick()) return i
        return -1
    }

    @Test
    fun `a burst of changes settles once after the quiet window`() {
        val tracker = DefinitionChangeTracker(settleTicks = 5)
        repeat(1000) { tracker.markDirty() }
        // first tick notices the burst, then five quiet ticks
        assertEquals(6, ticksUntilSettle(tracker))
        assertTrue(tracker.pending)
        tracker.settle(mapOf("a" to 1L))
        assertFalse(tracker.pending)
        assertEquals(-1, ticksUntilSettle(tracker, 20))
    }

    @Test
    fun `a change during the quiet window restarts it`() {
        val tracker = DefinitionChangeTracker(settleTicks = 5)
        tracker.markDirty()
        repeat(3) { assertFalse(tracker.tick()) }
        tracker.markDirty()
        assertEquals(6, ticksUntilSettle(tracker))
    }

    @Test
    fun `no changes means nothing pending`() {
        val tracker = DefinitionChangeTracker(settleTicks = 1)
        repeat(10) { assertFalse(tracker.tick()) }
        assertFalse(tracker.pending)
    }

    @Test
    fun `settle reports added, changed and removed names`() {
        val tracker = DefinitionChangeTracker()
        assertEquals(setOf("a", "b", "c"), tracker.settle(mapOf("a" to 1L, "b" to 2L, "c" to 3L)))
        assertEquals(setOf("b", "c", "d"), tracker.settle(mapOf("a" to 1L, "b" to 20L, "d" to 4L)))
    }

    @Test
    fun `an unchanged reload reports nothing`() {
        val tracker = DefinitionChangeTracker()
        tracker.settle(mapOf("a" to 1L, "b" to 2L))
        assertTrue(tracker.settle(mapOf("a" to 1L, "b" to 2L)).isEmpty())
    }

    @Test
    fun `unversioned definitions always count as changed`() {
        assertEquals(setOf("a"), DefinitionChangeTracker.diff(mapOf("a" to 0L, "b" to 2L), mapOf("a" to 0L, "b" to 2L)))
    }
}
