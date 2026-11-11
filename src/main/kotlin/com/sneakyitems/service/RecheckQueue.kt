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

/** Per-entry policy: coalescing may widen work, but must never drop legacy matching. */
data class RecheckRequest<K>(val key: K, val filter: RecheckFilter, val includeLegacy: Boolean)

/** FIFO with deduplication, merged filters and OR-ed legacy requirements. Main thread only. */
class RecheckQueue<K : Any> {

    private val entries = LinkedHashMap<K, RecheckRequest<K>>()

    val size: Int get() = entries.size

    fun isEmpty(): Boolean = entries.isEmpty()

    @JvmOverloads
    fun add(key: K, filter: RecheckFilter, includeLegacy: Boolean = true) {
        entries.merge(key, RecheckRequest(key, filter, includeLegacy)) { old, new ->
            RecheckRequest(key, old.filter.merge(new.filter), old.includeLegacy || new.includeLegacy)
        }
    }

    /** Removes and returns the oldest entry, or null when empty. */
    fun poll(): RecheckRequest<K>? {
        val iterator = entries.entries.iterator()
        if (!iterator.hasNext()) return null
        val first = iterator.next()
        iterator.remove()
        return first.value
    }

    fun clear() = entries.clear()
}
