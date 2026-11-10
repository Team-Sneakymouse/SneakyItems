package com.sneakyitems.service

/** Which stacks a queued re-check should look at. */
sealed interface RecheckFilter {
    /** Every stack. */
    data object All : RecheckFilter

    /** Only stacks tagged with one of these names (plus untagged or unknown-tag stacks, see the updater). */
    data class Names(val names: Set<String>) : RecheckFilter

    fun merge(other: RecheckFilter): RecheckFilter = when {
        this is All || other is All -> All
        this == other -> this
        else -> Names((this as Names).names + (other as Names).names)
    }
}

/**
 * First-in, first-out queue of re-check work. A key queued again keeps its place, and its
 * filters merge. Main thread only.
 */
class RecheckQueue<K : Any> {

    private val entries = LinkedHashMap<K, RecheckFilter>()

    val size: Int get() = entries.size

    fun isEmpty(): Boolean = entries.isEmpty()

    fun add(key: K, filter: RecheckFilter) {
        entries.merge(key, filter) { old, new -> old.merge(new) }
    }

    /** Removes and returns the oldest entry, or null when empty. */
    fun poll(): Pair<K, RecheckFilter>? {
        val iterator = entries.entries.iterator()
        if (!iterator.hasNext()) return null
        val first = iterator.next()
        iterator.remove()
        return first.key to first.value
    }

    fun clear() = entries.clear()
}
