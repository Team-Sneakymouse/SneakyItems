package com.sneakyitems.service

import com.sneakyitems.events.CarriedItemChangeEvent
import com.sneakyitems.util.magicitems.MagicItemBehaviorKeys
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * Per-player counts of the magic items carried in the player's own inventory (PlayerInventory indices 0-35 storage
 * and hotbar, 36-39 armor, 40 offhand; never the cursor, open containers or bags). An item is counted exactly when
 * [MagicItemBehaviorKeys.readMagicItemName] (the identity `SneakyItemsAPI.keyOf` uses) returns its id.
 *
 * Main thread only. Counts are not updated by arithmetic on slot events: Paper also sends "empty to stack" slot
 * changes for slots that did not change (for example every inventory slot when a container opens), so deltas would
 * double count. Instead a slot change that involves a magic item only marks the player dirty, and the next flush
 * recounts the 41 slots once. A flush runs when the count is queried, or on the next tick when something listens to
 * [CarriedItemChangeEvent].
 */
class CarriedItemIndex(private val plugin: Plugin) {

  private class State(val uuid: UUID) {
    /** Immutable snapshot: replaced, never mutated, so views handed out stay stable. */
    var counts: Map<String, Int> = emptyMap()
    var ids: Set<String> = emptySet()
    var dirty = false
    var queued = false
    var deferred = false

    /** [MagicItemBehaviorKeys.namespaceRevision] the counts were made under. */
    var revision = 0
  }

  private val states = HashMap<UUID, State>()
  private val pending = ArrayList<State>()
  private var drainScheduled = false

  /** True while a batch of [CarriedItemChangeEvent]s is being delivered: nested recounts wait until it is done. */
  private var dispatching = false
  private val deferred = ArrayList<State>()

  /**
   * Internal subscriber (SneakyItems' own watch service): [changed] is called after a recount is committed, once per id
   * whose count changed, before the public [CarriedItemChangeEvent]s for that recount. While [wanted] is true it makes
   * stale players recount on the next tick, like a listener. Not called during a quit (counts are dropped silently).
   */
  interface Observer {
    /** True while the observer needs notifications (it has watches). */
    val wanted: Boolean

    fun changed(player: Player, itemId: String, oldCount: Int, newCount: Int)
  }

  /** Bound once by [bindObserver]; never replaced. */
  private var observer: Observer? = null

  /** Tasks waiting for the current batch of notifications to be delivered ([afterDispatch]). */
  private val afterDispatch = ArrayList<Runnable>()

  /** Binds the [Observer] (SneakyItems' wiring, once). Throws if one is bound already, so it can't be replaced. */
  internal fun bindObserver(value: Observer) {
    check(observer == null) { "CarriedItemIndex observer is already bound" }
    observer = value
  }

  /** True while a batch of notifications is being delivered: recounts requested now are deferred until it ends. */
  internal val dispatchingNow: Boolean get() = dispatching

  /** Runs [task] now, or right after the current batch (and the recounts it deferred) when one is being delivered. */
  internal fun afterDispatch(task: Runnable) {
    requireMainThread()
    if (dispatching) afterDispatch.add(task) else task.run()
  }

  /**
   * The amount of [itemId] in [player]'s 41 slots, read from the live inventory now. Commits nothing and fires
   * nothing, so it is safe while a batch is being delivered (when [refresh] is deferred).
   */
  internal fun liveCounts(player: Player): Map<String, Int> {
    requireMainThread()
    if (!player.isOnline) return emptyMap()
    var counts: HashMap<String, Int>? = null
    val inventory = player.inventory
    for (slot in SLOTS) {
      val stack = inventory.getItem(slot) ?: continue
      val id = MagicItemBehaviorKeys.readMagicItemName(stack) ?: continue
      if (counts == null) counts = HashMap()
      counts.merge(id, stack.amount, Int::plus)
    }
    return counts ?: emptyMap()
  }

  /** [liveCounts] for one id. */
  internal fun liveCount(player: Player, itemId: String): Int {
    requireMainThread()
    if (!player.isOnline) return 0
    var total = 0
    val inventory = player.inventory
    for (slot in SLOTS) {
      val stack = inventory.getItem(slot) ?: continue
      if (MagicItemBehaviorKeys.readMagicItemName(stack) == itemId) total += stack.amount
    }
    return total
  }

  /** Players currently tracked (a level for tests and diagnostics). */
  val trackedPlayers: Int get() = states.size

  // --- lifecycle (called by CarriedItemListener and the plugin) ---

  /** Starts tracking [player] with one full scan, replacing any earlier state. */
  fun join(player: Player) {
    requireMainThread()
    val state = State(player.uniqueId)
    states[player.uniqueId] = state
    recount(state, player)
  }

  /** Frees everything held for [uuid]. */
  fun quit(uuid: UUID) {
    states.remove(uuid)
  }

  /** Drops all state (plugin disable). */
  fun clear() {
    states.clear()
    pending.clear()
    deferred.clear()
    afterDispatch.clear()
  }

  /**
   * A slot of a player's inventory changed from [old] to [new] (`PlayerInventorySlotChangeEvent`). Does nothing
   * unless one of the stacks is a magic item whose id or amount changed. The common path reads two PDC views and
   * allocates nothing of its own.
   */
  fun onSlotChange(player: Player, old: ItemStack, new: ItemStack) {
    val newId = MagicItemBehaviorKeys.readMagicItemName(new)
    val oldId = MagicItemBehaviorKeys.readMagicItemName(old)
    if (newId == null && oldId == null) return
    if (newId == oldId && new.amount == old.amount) return // same magic stack, other data changed (durability, ...)
    invalidate(player.uniqueId)
  }

  /**
   * The active PDC namespace changed, so `readMagicItemName` answers differently: every tracked player is stale.
   * Called by [MagicItemBehaviorKeys]; may run on any thread, the work happens on the main thread.
   */
  fun onNamespaceChanged() {
    if (!Bukkit.isPrimaryThread()) {
      if (plugin.isEnabled) plugin.server.scheduler.runTask(plugin, Runnable { onNamespaceChanged() })
      return
    }
    val listening = wantsChanges()
    for (state in states.values) {
      state.dirty = true
      if (listening && !state.queued) enqueue(state)
    }
  }

  /** Marks [uuid]'s counts stale: recounted at the next query, or next tick when someone listens. */
  fun invalidate(uuid: UUID) {
    val state = states[uuid] ?: return
    state.dirty = true
    if (!state.queued && wantsChanges()) enqueue(state)
  }

  // --- queries (called by SneakyItemsAPI) ---

  fun count(player: Player, itemId: String): Int = current(player)?.counts?.get(itemId) ?: 0

  fun carries(player: Player, itemId: String): Boolean = current(player)?.counts?.containsKey(itemId) ?: false

  /** Unmodifiable snapshot that never changes after it is returned (a later change gives a new set). */
  fun ids(player: Player): Set<String> = current(player)?.ids ?: emptySet()

  /** Recounts [player] now, whatever the dirty flag says. */
  fun refresh(player: Player) {
    requireMainThread()
    val state = states[player.uniqueId]
    if (state == null) current(player) else recount(state, player)
  }

  // --- internals ---

  private fun current(player: Player): State? {
    requireMainThread()
    val state = states[player.uniqueId]
    if (state == null) {
      if (!player.isOnline) return null
      val created = State(player.uniqueId)
      states[player.uniqueId] = created
      recount(created, player)
      return created
    }
    if (state.dirty || state.revision != MagicItemBehaviorKeys.namespaceRevision()) recount(state, player)
    return state
  }

  private fun enqueue(state: State) {
    state.queued = true
    pending.add(state)
    if (drainScheduled || !plugin.isEnabled) return
    drainScheduled = true
    plugin.server.scheduler.runTask(plugin, Runnable { drain() })
  }

  private fun drain() {
    drainScheduled = false
    if (pending.isEmpty()) return
    val batch = ArrayList(pending)
    pending.clear()
    for (state in batch) {
      state.queued = false
      if (!state.dirty || states[state.uuid] !== state) continue
      val player = plugin.server.getPlayer(state.uuid) ?: continue
      recount(state, player)
    }
  }

  private fun recount(state: State, player: Player) {
    if (dispatching) {
      // A listener asked for a recount mid-delivery. Running it now would deliver its events before the rest of the
      // current batch; run it right after the batch instead (its counts then chain from what listeners have seen).
      state.dirty = true
      if (!state.deferred) {
        state.deferred = true
        deferred.add(state)
      }
      return
    }
    state.dirty = false
    state.revision = MagicItemBehaviorKeys.namespaceRevision()
    var next: HashMap<String, Int>? = null
    val inventory = player.inventory
    for (slot in SLOTS) {
      val stack = inventory.getItem(slot) ?: continue
      val id = MagicItemBehaviorKeys.readMagicItemName(stack) ?: continue
      if (next == null) next = HashMap()
      next.merge(id, stack.amount, Int::plus)
    }
    val previous = state.counts
    val counts: Map<String, Int> = next ?: emptyMap()
    if (counts == previous) return
    state.counts = counts
    state.ids = if (next == null) emptySet() else java.util.Collections.unmodifiableSet(next.keys)
    val watcher = observer?.takeIf { it.wanted }
    val listening = hasListeners()
    if (watcher == null && !listening) return
    val changes = ArrayList<Change>()
    for ((id, was) in previous) {
      val now = counts[id] ?: 0
      if (now != was) changes.add(Change(id, was, now))
    }
    for ((id, now) in counts) {
      if (!previous.containsKey(id)) changes.add(Change(id, 0, now))
    }
    dispatching = true
    try {
      if (watcher != null) for (c in changes) watcher.changed(player, c.id, c.was, c.now)
      if (listening) for (c in changes) Bukkit.getPluginManager().callEvent(CarriedItemChangeEvent(player, c.id, c.was, c.now))
    } finally {
      dispatching = false
    }
    runDeferred()
  }

  private fun runDeferred() {
    while (deferred.isNotEmpty()) {
      val batch = ArrayList(deferred)
      deferred.clear()
      for (state in batch) {
        state.deferred = false
        if (!state.dirty || states[state.uuid] !== state) continue
        val player = plugin.server.getPlayer(state.uuid) ?: continue
        recount(state, player)
      }
    }
    if (!dispatching && afterDispatch.isNotEmpty()) {
      val tasks = ArrayList(afterDispatch)
      afterDispatch.clear()
      for (task in tasks) task.run()
    }
  }

  private fun wantsChanges(): Boolean = observer?.wanted == true || hasListeners()

  private fun hasListeners(): Boolean = CarriedItemChangeEvent.getHandlerList().registeredListeners.isNotEmpty()

  private fun requireMainThread() {
    check(Bukkit.isPrimaryThread()) { "Carried-item queries must be made from the main server thread" }
  }

  private class Change(val id: String, val was: Int, val now: Int)

  private companion object {
    val SLOTS = 0..40
  }
}
