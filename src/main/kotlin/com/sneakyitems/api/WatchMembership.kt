package com.sneakyitems.api

import org.bukkit.entity.Player

/**
 * Optional callbacks for an [ItemWatch]: when a player enters its timer (the item arrived) and when they leave it (the
 * item went, they quit, or the watch was closed with [ItemWatch.close]). Pass it to the `whileCarrying` /
 * `whileHolding` overloads that take one.
 *
 * [entered] runs before the player's first tick from this watch; [left] runs after their last. They run on the main
 * thread, inside SneakyItems' own bookkeeping: keep them short and don't change inventories from them (an inventory
 * change made here is picked up on the next pass, not in this one). A callback that throws is logged and ignored.
 * When the owner disables, no [left] runs (the owner is going away anyway).
 */
interface WatchMembership {
  /** [player] just entered the timer. */
  fun entered(player: Player) {}

  /** [player] just left the timer. */
  fun left(player: Player) {}
}
