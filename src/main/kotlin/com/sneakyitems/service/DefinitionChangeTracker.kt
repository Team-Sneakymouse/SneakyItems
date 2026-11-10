package com.sneakyitems.service

import java.util.concurrent.atomic.AtomicLong

/**
 * Collapses bursts of definition changes into one settle, then reports which names changed.
 *
 * [markDirty] is safe from any thread (async config reloads). [tick] and [settle] run on the main
 * thread only. A settle is due once [settleTicks] ticks pass with no new change.
 */
class DefinitionChangeTracker(@Volatile var settleTicks: Int = 20) {

    private val changes = AtomicLong()
    private var seenChanges = 0L
    private var quietTicks = 0
    private var snapshot: Map<String, Long> = emptyMap()

    /** True while changes are waiting to settle. Item re-checks hold off meanwhile. */
    var pending = false
        private set

    fun markDirty() {
        changes.incrementAndGet()
    }

    /** Call once per tick. Returns true when the changes have settled and [settle] should run. */
    fun tick(): Boolean {
        val now = changes.get()
        if (now != seenChanges) {
            seenChanges = now
            quietTicks = 0
            pending = true
            return false
        }
        if (!pending) return false
        quietTicks++
        return quietTicks >= settleTicks
    }

    /** Compares [current] (name to definition version) against the last settle and stores it. */
    fun settle(current: Map<String, Long>): Set<String> {
        val changed = diff(snapshot, current)
        snapshot = HashMap(current)
        pending = false
        return changed
    }

    companion object {
        /** Added, removed, or changed names. Version 0 means unversioned, so it always counts as changed. */
        @JvmStatic
        fun diff(previous: Map<String, Long>, current: Map<String, Long>): Set<String> {
            val changed = HashSet<String>()
            for ((name, version) in current) {
                if (version == 0L || previous[name] != version) changed.add(name)
            }
            for (name in previous.keys) {
                if (name !in current) changed.add(name)
            }
            return changed
        }
    }
}
