package com.sneakyitems.service

import com.sneakyitems.api.HeldSlot
import com.sneakyitems.api.ItemWatch
import com.sneakyitems.listener.HeldItemListener
import com.sneakyutils.triggers.PlayerScope
import com.sneakyutils.triggers.PlayerTick
import com.sneakyutils.triggers.PlayerTimer
import com.sneakyutils.triggers.PlayerTimerOptions
import com.sneakyutils.triggers.Triggers
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * `SneakyItemsAPI.whileCarrying` / `whileHolding`: SneakyUtils per-player timers whose membership SneakyItems keeps
 * equal to "carries the item" ([CarriedItemIndex]) or "holds it there" ([HeldItemIndex]).
 *
 * Each [ItemWatch] wraps the consumer's own SneakyUtils [PlayerTimer] (owner = the consumer, so it closes when the
 * consumer disables and `/triggers` attributes it to the consumer). This service only calls `add` / `remove` on it when
 * an item id changes for a player, so players without the item are in no timer and cost nothing per tick.
 *
 * Membership is driven by the indexes' internal notifications (after a recount or resync is committed), never by the
 * public `CarriedItemChangeEvent`, which any plugin can construct. Tracking runs only while watches of its kind exist:
 * the carried observer and the held listener are removed with the last watch, and the held index is cleared.
 *
 * Main thread only.
 */
class ItemWatchService(
    private val plugin: Plugin,
    private val carried: CarriedItemIndex,
    private val held: HeldItemIndex,
    private val triggers: () -> Triggers,
) : Listener {

  private val carriedWatches = HashMap<String, ArrayList<ItemWatch>>()
  private val heldWatches = HashMap<String, ArrayList<ItemWatch>>()
  private var heldListener: Listener? = null
  private var stopCheckScheduled = false

  init {
    // Bound once and never replaceable: nothing outside this wiring can disconnect live watches.
    carried.bindObserver(object : CarriedItemIndex.Observer {
      override val wanted: Boolean get() = carriedWatches.isNotEmpty()
      override fun changed(player: Player, itemId: String, oldCount: Int, newCount: Int) = onCarriedChange(player, itemId, newCount)
    })
    held.bindOnChange({ player, ids -> onHeldChange(player, ids) }, { it in quitting })
  }

  /**
   * Players whose `PlayerQuitEvent` has started. Paper fires it while the player is still in the online list (and
   * `isOnline` is true), and SneakyUtils drops quitting players from timers at MONITOR: a watch made or updated after
   * that (from a later quit handler) would enroll a player who is about to disconnect and keep calling them. So they
   * are never enrolled from the start of their quit until they rejoin (or the next tick, when they are gone).
   */
  private val quitting = HashSet<UUID>()

  @EventHandler(priority = EventPriority.LOWEST)
  fun onQuitStart(event: PlayerQuitEvent) {
    val uuid = event.player.uniqueId
    quitting.add(uuid)
    if (plugin.isEnabled) plugin.server.scheduler.runTask(plugin, Runnable { if (plugin.server.getPlayer(uuid)?.isOnline != true) quitting.remove(uuid) })
  }

  @EventHandler(priority = EventPriority.LOWEST)
  fun onJoinStart(event: PlayerJoinEvent) {
    quitting.remove(event.player.uniqueId)
  }

  /** Online players who may be enrolled (not quitting). */
  private fun enrollable(): List<Player> = plugin.server.onlinePlayers.filter { it.uniqueId !in quitting }

  /** Watches not yet known to be closed (a level for tests). */
  val size: Int get() = carriedWatches.values.sumOf { it.size } + heldWatches.values.sumOf { it.size }

  /** True while the carried index notifies this service (a level for tests). */
  val trackingCarried: Boolean get() = carriedWatches.isNotEmpty()

  /** True while held tracking runs (a level for tests). */
  val trackingHeld: Boolean get() = heldListener != null

  fun whileCarrying(owner: Plugin, itemId: String, intervalTicks: Int, handler: PlayerTick, options: PlayerTimerOptions): ItemWatch {
    val watch = ItemWatch(this, itemId, null, newTimer(owner, itemId, intervalTicks, handler, options))
    add(carriedWatches, watch)
    if (carried.dispatchingNow) {
      // Inside a CarriedItemChangeEvent listener: recounts are deferred until the batch is delivered (so
      // notifications stay ordered), so seed from a live read now, queue the recounts, and once they ran set the
      // membership to the committed counts (catching a change made after this call within the same batch).
      for (p in enrollable()) if (carried.liveCount(p, itemId) > 0) watch.playerTimer.add(p)
      for (p in enrollable()) carried.refresh(p)
      carried.afterDispatch { settleAndReconcile(watch) }
      return watch
    }
    // Recount from the live inventory: a change made earlier this tick that Paper hasn't reported yet must not seed
    // the timer wrong. Differences reach every carried watch (this one included) through the observer.
    for (p in enrollable()) carried.refresh(p)
    settleAndReconcile(watch)
    return watch
  }

  fun whileHolding(
      owner: Plugin,
      itemId: String,
      where: HeldSlot,
      intervalTicks: Int,
      handler: PlayerTick,
      options: PlayerTimerOptions,
  ): ItemWatch {
    val watch = ItemWatch(this, itemId, where, newTimer(owner, itemId, intervalTicks, handler, options))
    add(heldWatches, watch)
    if (heldListener == null) {
      held.start() // reads the live inventory of every online player
      val listener = HeldItemListener(held)
      plugin.server.pluginManager.registerEvents(listener, plugin)
      heldListener = listener
    } else {
      held.refreshAll() // live re-read; differences reach every held watch through onChange
    }
    for (p in enrollable()) {
      if (!watch.isActive) break
      if (held.holds(p, itemId, where)) watch.playerTimer.add(p)
    }
    return watch
  }

  /**
   * The recounts above fire public `CarriedItemChangeEvent`s, and a listener may change inventories in response
   * (and no slot event may follow a change Paper ends up not seeing), for this watch's item or any other watched one.
   * So compare each player's committed counts with the live inventory for every watched id and recount until they
   * agree (each recount reaches every watch through the observer), then set [watch]'s players from the committed
   * counts. A listener that keeps changing the inventory on every recount is cut off after [SETTLE_LIMIT] tries; that
   * player is marked stale and recounted on the next tick.
   */
  private fun settleAndReconcile(watch: ItemWatch) {
    for (p in enrollable()) {
      var tries = 0
      while (carriedMismatch(p)) {
        if (++tries > SETTLE_LIMIT) {
          carried.invalidate(p.uniqueId)
          break
        }
        carried.refresh(p)
      }
    }
    reconcileCarried(watch)
  }

  /** True when [player]'s committed count differs from the live inventory for any id a carried watch follows. */
  private fun carriedMismatch(player: Player): Boolean {
    if (carriedWatches.isEmpty()) return false
    val live = carried.liveCounts(player)
    for (id in ArrayList(carriedWatches.keys)) {
      if ((live[id] ?: 0) != carried.count(player, id)) return true
    }
    return false
  }

  /** Sets [watch]'s players to exactly the online players the carried index says carry its item. */
  private fun reconcileCarried(watch: ItemWatch) {
    for (p in plugin.server.onlinePlayers) {
      if (!watch.isActive) return
      if (p.uniqueId !in quitting && carried.carries(p, watch.itemId)) watch.playerTimer.add(p) else watch.playerTimer.remove(p)
    }
  }

  /** [ItemWatch.close]: closes the timer and forgets the watch now. */
  fun close(watch: ItemWatch) {
    check(Bukkit.isPrimaryThread()) { "ItemWatch.close must be called on the main server thread" }
    watch.playerTimer.close()
    val map = if (watch.where == null) carriedWatches else heldWatches
    map[watch.itemId]?.let { list ->
      list.remove(watch)
      if (list.isEmpty()) map.remove(watch.itemId)
    }
    stopUnusedTracking()
  }

  /** Closes every watch (SneakyItems disables: nothing would keep their membership right any more). */
  fun clear() {
    quitting.clear()
    val all = carriedWatches.values.flatten() + heldWatches.values.flatten()
    carriedWatches.clear()
    heldWatches.clear()
    stopUnusedTracking()
    for (w in all) runCatching { w.playerTimer.close() }
  }

  /** Drops (and closes, idempotently) the watches of a disabling plugin. */
  @EventHandler(priority = EventPriority.MONITOR)
  fun onPluginDisable(event: PluginDisableEvent) {
    if (event.plugin === plugin) return
    val owner = event.plugin
    for (map in listOf(carriedWatches, heldWatches)) {
      val it = map.values.iterator()
      while (it.hasNext()) {
        val list = it.next()
        list.removeIf { w -> (w.owner === owner).also { gone -> if (gone) w.playerTimer.close() } }
        if (list.isEmpty()) it.remove()
      }
    }
    stopUnusedTracking()
  }

  private companion object {
    const val SETTLE_LIMIT = 8
  }

  private fun newTimer(owner: Plugin, itemId: String, intervalTicks: Int, handler: PlayerTick, options: PlayerTimerOptions): PlayerTimer {
    check(Bukkit.isPrimaryThread()) { "whileCarrying / whileHolding must be called on the main server thread" }
    check(plugin.isEnabled) { "SneakyItems is not enabled" }
    require(itemId.isNotBlank()) { "itemId is blank" }
    require(owner.isEnabled) { "owner ${owner.name} is not enabled" }
    require(options.scope == PlayerScope.ADDED) { "options.scope must be ADDED: SneakyItems decides who is in the timer" }
    return triggers().everyForPlayers(owner, intervalTicks, handler, options)
  }

  private fun add(map: HashMap<String, ArrayList<ItemWatch>>, watch: ItemWatch) {
    val list = map.getOrPut(watch.itemId) { ArrayList() }
    list.removeIf { !it.isActive } // timers closed behind our back (owner disable is handled above)
    list.add(watch)
  }

  /**
   * Unregisters the held listener and clears the held index once no held watch is left. (The carried observer stops
   * by itself: it is only [CarriedItemIndex.Observer.wanted] while carried watches exist.)
   */
  private fun stopUnusedTracking() {
    if (heldWatches.isEmpty()) {
      heldListener?.let { HandlerList.unregisterAll(it) }
      heldListener = null
      held.clear()
    }
  }

  private fun onCarriedChange(player: Player, itemId: String, newCount: Int) {
    val list = carriedWatches[itemId] ?: return
    val iterator = list.iterator()
    while (iterator.hasNext()) {
      val watch = iterator.next()
      if (!watch.isActive) {
        iterator.remove()
        continue
      }
      if (newCount > 0 && player.uniqueId !in quitting) watch.playerTimer.add(player) else watch.playerTimer.remove(player)
    }
    if (list.isEmpty()) carriedWatches.remove(itemId)
  }

  private fun onHeldChange(player: Player, ids: Set<String>) {
    for (id in ids) {
      val list = heldWatches[id] ?: continue
      val iterator = list.iterator()
      while (iterator.hasNext()) {
        val watch = iterator.next()
        if (!watch.isActive) {
          iterator.remove()
          continue
        }
        if (player.uniqueId !in quitting && held.holds(player, id, watch.where!!)) watch.playerTimer.add(player)
        else watch.playerTimer.remove(player)
      }
      if (list.isEmpty()) heldWatches.remove(id)
    }
    // A timer closed behind our back (SneakyUtils closing it) was the last held watch: stop tracking, but not from
    // inside this resync; on the next tick, if still nothing is watched.
    if (heldWatches.isEmpty() && heldListener != null && !stopCheckScheduled && plugin.isEnabled) {
      stopCheckScheduled = true
      plugin.server.scheduler.runTask(plugin, Runnable {
        stopCheckScheduled = false
        stopUnusedTracking()
      })
    }
  }
}
