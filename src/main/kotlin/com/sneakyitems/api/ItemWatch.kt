package com.sneakyitems.api

import com.sneakyitems.service.ItemWatchService
import com.sneakyutils.triggers.PlayerTimer
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * A [SneakyItemsAPI.whileCarrying] / [SneakyItemsAPI.whileHolding] subscription: a SneakyUtils per-player timer whose
 * players SneakyItems keeps equal to the players who carry or hold [itemId].
 *
 * [close] ends it: it closes the timer and SneakyItems forgets it at once. It also ends when its owner disables
 * (SneakyUtils closes the timer) and when SneakyItems disables. Main thread only, like every SneakyUtils call.
 *
 * Not a SneakyUtils `Subscription` itself (only SneakyUtils implements that interface), and it never hands out the
 * timer: [timerId] and [label] are the timer's `/triggers` id and diagnostics label, read-only.
 */
class ItemWatch internal constructor(
    private val service: ItemWatchService,
    /** Internal name of the magic item watched. */
    val itemId: String,
    /** Where the item must be ([SneakyItemsAPI.whileHolding]), or null for "carried" ([SneakyItemsAPI.whileCarrying]). */
    val where: HeldSlot?,
    private val timer: PlayerTimer,
    private val membership: WatchMembership? = null,
) : AutoCloseable {

  /** The plugin that owns it (the `owner` it was made with). */
  val owner: Plugin get() = timer.owner

  /** False once closed, by [close], owner disable or SneakyItems disable. */
  val isActive: Boolean get() = timer.active

  /** The SneakyUtils timer's id, as `/triggers` and its logs show it (`<owner>:player-timer#<n>`). */
  val timerId: String get() = timer.id

  /** The diagnostics label from the options it was made with, or null. */
  val label: String? get() = timer.label

  /** True when [player] is in the timer now (it may be paused while dead; see [SneakyItemsAPI.whileCarrying]). */
  fun contains(player: Player): Boolean = timer.contains(player)

  /** Players in the timer now. */
  val size: Int get() = timer.size

  /** Closes the timer and drops the watch. Idempotent; main thread only. */
  override fun close() = service.close(this)

  internal val playerTimer: PlayerTimer get() = timer

  /** Adds [player] to the timer; [WatchMembership.entered] runs when they were not in it before. */
  internal fun enter(player: Player) {
    if (timer.contains(player)) return
    timer.add(player)
    if (membership != null && timer.contains(player)) notify(player) { membership.entered(player) }
  }

  /** Removes [player] from the timer; [WatchMembership.left] runs when they were in it. */
  internal fun leave(player: Player) {
    if (!timer.contains(player)) return
    timer.remove(player)
    if (membership != null) notify(player) { membership.left(player) }
  }

  /** A throwing callback must not break SneakyItems' own bookkeeping: log it against the owner and go on. */
  private inline fun notify(player: Player, call: () -> Unit) {
    try {
      call()
    } catch (e: Exception) {
      owner.logger.log(java.util.logging.Level.WARNING, "ItemWatch($itemId) membership callback failed for ${player.name}", e)
    }
  }

  override fun toString(): String = "ItemWatch(itemId=$itemId, where=$where, timer=${timer.id}, active=$isActive)"
}
