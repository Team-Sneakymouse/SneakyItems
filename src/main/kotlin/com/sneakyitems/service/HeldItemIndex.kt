package com.sneakyitems.service

import com.sneakyitems.api.HeldSlot
import com.sneakyitems.util.magicitems.MagicItemBehaviorKeys
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * Which magic item id (same identity as [MagicItemBehaviorKeys.readMagicItemName] / `SneakyItemsAPI.keyOf`) each
 * player has in the main hand (selected hotbar slot), the offhand and each armor slot.
 *
 * Main thread only. Off until [start] (the first `whileHolding` subscription), so it costs nothing on servers that
 * don't use it. Never updated by deltas: every update re-reads the six places from the live inventory (six
 * `getItem` + PDC reads) and compares with the cached ids, so Paper's spurious slot-change events (a container
 * opening reports unchanged slots) change nothing. Events fired before the change is applied (held slot, hand swap)
 * are read with the event's values, then checked again against the live inventory on the next tick
 * ([verifyLater]); so are death, respawn, item breaks, dispenser equips and world changes.
 */
class HeldItemIndex(private val plugin: Plugin) {

  /** Called after a player's ids changed, with every id that left or entered one of the six places. Bound once. */
  private var onChange: ((Player, Set<String>) -> Unit)? = null

  /** Binds the change callback (SneakyItems' wiring, once). Throws if one is bound already, so it can't be replaced. */
  internal fun bindOnChange(callback: (Player, Set<String>) -> Unit, isQuitting: (UUID) -> Boolean) {
    check(onChange == null) { "HeldItemIndex callback is already bound" }
    onChange = callback
    quitting = isQuitting
  }

  /** True for a player whose quit has started (bound with [bindOnChange]): never tracked again until they rejoin. */
  private var quitting: (UUID) -> Boolean = { false }

  /** Ids per place: [MAIN], [OFF], then head, chest, legs, feet. Replaced, never mutated. */
  private val states = HashMap<UUID, Array<String?>>()
  private val verify = LinkedHashSet<UUID>()
  private var verifyScheduled = false

  /** True once [start] ran (until [clear]). */
  var active = false
    private set

  /** Players currently tracked (a level for tests). */
  val trackedPlayers: Int get() = states.size

  /** Starts tracking: reads every online player once. Fires nothing (callers scan with [holds] themselves). */
  fun start() {
    requireMainThread()
    if (active) return
    active = true
    for (p in plugin.server.onlinePlayers) if (!quitting(p.uniqueId)) states[p.uniqueId] = read(p)
  }

  /**
   * Re-reads every online player from the live inventory now and reports what changed, so a cache that missed a
   * change made earlier this tick (a plugin's `setItem` Paper hasn't reported yet) is corrected before it is used.
   */
  fun refreshAll() {
    requireMainThread()
    if (!active) return
    for (p in plugin.server.onlinePlayers) resync(p)
  }

  /** Stops tracking and drops all state (plugin disable, or the last `whileHolding` ended). */
  fun clear() {
    active = false
    states.clear()
    verify.clear()
  }

  /** True when [player] has [itemId] in [where], from the cached state (false when not tracked). */
  fun holds(player: Player, itemId: String, where: HeldSlot): Boolean {
    requireMainThread()
    val ids = states[player.uniqueId] ?: return false
    return when (where) {
      HeldSlot.MAIN_HAND -> ids[MAIN] == itemId
      HeldSlot.OFF_HAND -> ids[OFF] == itemId
      HeldSlot.EITHER_HAND -> ids[MAIN] == itemId || ids[OFF] == itemId
      HeldSlot.ARMOR -> (FIRST_ARMOR until PLACES).any { ids[it] == itemId }
      HeldSlot.ANY -> ids.any { it == itemId }
    }
  }

  // --- lifecycle and events (called by HeldItemListener and the plugin) ---

  /** A (re)joining player: compared against nothing, so every id they hold is reported as entering. */
  fun join(player: Player) {
    if (!active) return
    states.remove(player.uniqueId)
    resync(player)
  }

  /** Drops [uuid]'s state. Reports nothing: SneakyUtils takes quitting players out of every timer itself. */
  fun quit(uuid: UUID) {
    states.remove(uuid)
    verify.remove(uuid)
  }

  /**
   * `PlayerInventorySlotChangeEvent`: fired when Paper syncs the menu, after the change. Only the selected hotbar
   * slot, armor (36-39) and the offhand (40) matter. If the live stack doesn't match the event's new stack (another
   * change already happened), the next tick checks again.
   */
  fun onSlotChange(player: Player, slot: Int, newStack: ItemStack) {
    if (!active) return
    val inventory = player.inventory
    if (slot !in FEET_SLOT..OFFHAND_SLOT && slot != inventory.heldItemSlot) return
    if (id(inventory.getItem(slot)) != id(newStack)) verifyLater(player)
    resync(player)
  }

  /** `PlayerItemHeldEvent` (not cancelled): fired before the slot changes, so read the new slot explicitly. */
  fun onHeldSlot(player: Player, newSlot: Int) {
    if (!active) return
    resync(player, mainOverride = id(player.inventory.getItem(newSlot)), override = OVERRIDE_MAIN)
    verifyLater(player)
  }

  /** `PlayerSwapHandItemsEvent` (not cancelled): fired before the swap; the event holds the items after it. */
  fun onSwap(player: Player, newMain: ItemStack?, newOff: ItemStack?) {
    if (!active) return
    resync(player, mainOverride = id(newMain), offOverride = id(newOff), override = OVERRIDE_BOTH)
    verifyLater(player)
  }

  /**
   * `EntityEquipmentChangedEvent` for a player: fired after the change (Paper compares equipment each tick). Resyncs
   * only when an id changed, so durability loss on a held tool costs two PDC reads per changed slot and nothing else.
   */
  fun onEquipmentChanged(player: Player, changes: Collection<Pair<ItemStack?, ItemStack?>>) {
    if (!active) return
    if (changes.none { (old, new) -> id(old) != id(new) }) return
    resync(player)
  }

  /**
   * The active PDC namespace changed, so ids read earlier may be wrong: check every tracked player next tick.
   * May run on any thread.
   */
  fun onNamespaceChanged() {
    if (!Bukkit.isPrimaryThread()) {
      if (plugin.isEnabled) plugin.server.scheduler.runTask(plugin, Runnable { onNamespaceChanged() })
      return
    }
    if (!active) return
    for (uuid in states.keys) plugin.server.getPlayer(uuid)?.let { verifyLater(it) }
  }

  /** Re-reads [player]'s six places from the live inventory on the next tick. */
  fun verifyLater(player: Player) {
    if (!active || !verify.add(player.uniqueId) || verifyScheduled || !plugin.isEnabled) return
    verifyScheduled = true
    plugin.server.scheduler.runTask(plugin, Runnable { runVerify() })
  }

  // --- internals ---

  private fun runVerify() {
    verifyScheduled = false
    if (!active || verify.isEmpty()) return
    val batch = ArrayList(verify)
    verify.clear()
    for (uuid in batch) plugin.server.getPlayer(uuid)?.let { resync(it) }
  }

  private fun resync(player: Player, mainOverride: String? = null, offOverride: String? = null, override: Int = 0) {
    requireMainThread()
    if (!player.isOnline || quitting(player.uniqueId)) return
    val now = read(player)
    if (override and OVERRIDE_MAIN != 0) now[MAIN] = mainOverride
    if (override and OVERRIDE_OFF != 0) now[OFF] = offOverride
    val old = states[player.uniqueId] ?: EMPTY
    if (old.contentEquals(now)) return
    states[player.uniqueId] = now
    val changed = LinkedHashSet<String>()
    for (i in 0 until PLACES) {
      if (old[i] == now[i]) continue
      old[i]?.let { changed.add(it) }
      now[i]?.let { changed.add(it) }
    }
    onChange?.invoke(player, changed)
  }

  private fun read(player: Player): Array<String?> {
    val inventory = player.inventory
    val ids = arrayOfNulls<String>(PLACES)
    ids[MAIN] = id(inventory.getItem(inventory.heldItemSlot))
    ids[OFF] = id(inventory.getItem(OFFHAND_SLOT))
    ids[2] = id(inventory.getItem(HEAD_SLOT))
    ids[3] = id(inventory.getItem(CHEST_SLOT))
    ids[4] = id(inventory.getItem(LEGS_SLOT))
    ids[5] = id(inventory.getItem(FEET_SLOT))
    return ids
  }

  private fun id(stack: ItemStack?): String? = MagicItemBehaviorKeys.readMagicItemName(stack)

  private fun requireMainThread() {
    check(Bukkit.isPrimaryThread()) { "Held-item tracking must run on the main server thread" }
  }

  private companion object {
    const val MAIN = 0
    const val OFF = 1
    const val FIRST_ARMOR = 2
    const val PLACES = 6

    // PlayerInventory indices.
    const val FEET_SLOT = 36
    const val LEGS_SLOT = 37
    const val CHEST_SLOT = 38
    const val HEAD_SLOT = 39
    const val OFFHAND_SLOT = 40

    const val OVERRIDE_MAIN = 1
    const val OVERRIDE_OFF = 2
    const val OVERRIDE_BOTH = OVERRIDE_MAIN or OVERRIDE_OFF

    val EMPTY = arrayOfNulls<String>(PLACES)
  }
}
