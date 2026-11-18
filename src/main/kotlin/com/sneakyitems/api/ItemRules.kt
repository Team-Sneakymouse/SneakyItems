package com.sneakyitems.api

import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Item
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import java.util.function.Predicate

/**
 * Generic rules for a group of magic items, applied with [SneakyItemsAPI.itemRules]. Every rule is off by default;
 * turn on the ones the items need. Items are matched by their magic item id ([SneakyItemsAPI.keyOf]), never by look.
 *
 * - [carryLimit]: a player may carry at most this many items of the group (inventory, cursor and crafting grid
 *   together). Pickups and taking a placed one back are refused past it. [bypassPermission] / [bypass] exempt players.
 * - [noContainers]: never in a container (chests, ender chest, the 2x2 crafting grid, anvils...), a bundle, an item
 *   frame or other entity, an armour stand, a hopper, or an "insert" block (decorated pot, shelves, lectern, jukebox,
 *   flower pot, campfire, composter, vault). Only the creative tab is exempt.
 * - [protectOnGround]: a dropped item never despawns, can't be hurt (fire, lava, cactus, explosions), and no mob or
 *   hopper picks it up. Drops stay vanilla items: SneakyGroundItems is told to leave them alone.
 * - [dropOnLeave]: dropped where the player is when they die (even with keepInventory) or quit. With [unique], a
 *   character switch (SneakyCharacterManager) puts it back on the ground too.
 * - [unique]: each item id exists once. The group gives every made copy a serial ([serialKey]); only the newest serial
 *   is live, and any other copy vanishes when seen (duplicates, copies saved into a switched-out character, copies
 *   replaced by a respawn). The group tracks where each item is (held, on the ground, placed) in a file, checks every
 *   5 seconds, and when one is lost (void, /clear, a deleted entity) puts it back on the ground where it was last.
 *   A stack without a serial (a plain give of the definition) is not live: make copies with [ItemRuleGroup.create] /
 *   [ItemRuleGroup.give].
 * - [placement]: right-click the top of a block to set the item down as an `ItemDisplay` with a click hitbox; click it
 *   to take it back. Sneak to place on blocks that have their own use (chests, doors...).
 *
 * [hooks] are told what happened (refusals, pickups, drops, placing), for messages, sounds and effects.
 */
class ItemRules private constructor(b: Builder) {
  val carryLimit: Int = b.carryLimit
  val bypassPermission: String? = b.bypassPermission
  val bypass: Predicate<Player>? = b.bypass
  val noContainers: Boolean = b.noContainers
  val protectOnGround: Boolean = b.protectOnGround
  val dropOnLeave: Boolean = b.dropOnLeave
  val unique: Boolean = b.unique
  val serialKey: NamespacedKey = b.serialKey
  val placement: Placement? = b.placement
  val hooks: ItemRuleHooks = b.hooks

  fun toBuilder(): Builder = Builder().carryLimit(carryLimit).bypassPermission(bypassPermission).bypass(bypass)
      .noContainers(noContainers).protectOnGround(protectOnGround).dropOnLeave(dropOnLeave).unique(unique)
      .serialKey(serialKey).placement(placement).hooks(hooks)

  override fun toString(): String =
      "ItemRules(carryLimit=$carryLimit, noContainers=$noContainers, protectOnGround=$protectOnGround, " +
          "dropOnLeave=$dropOnLeave, unique=$unique, placement=$placement)"

  class Builder {
    internal var carryLimit = 0
    internal var bypassPermission: String? = null
    internal var bypass: Predicate<Player>? = null
    internal var noContainers = false
    internal var protectOnGround = false
    internal var dropOnLeave = false
    internal var unique = false
    internal var serialKey: NamespacedKey = DEFAULT_SERIAL_KEY
    internal var placement: Placement? = null
    internal var hooks: ItemRuleHooks = ItemRuleHooks.NONE

    /** At most [value] items of the group per player; 0 (the default) means no limit. */
    fun carryLimit(value: Int) = apply { require(value >= 0) { "carryLimit < 0" }; carryLimit = value }

    /** Players with this permission ignore [carryLimit]. */
    fun bypassPermission(value: String?) = apply { bypassPermission = value }

    /** Players this accepts ignore [carryLimit] (for example a character tag). Called on the main thread. */
    fun bypass(value: Predicate<Player>?) = apply { bypass = value }

    fun noContainers(value: Boolean) = apply { noContainers = value }
    fun protectOnGround(value: Boolean) = apply { protectOnGround = value }
    fun dropOnLeave(value: Boolean) = apply { dropOnLeave = value }
    fun unique(value: Boolean) = apply { unique = value }

    /** Where [unique] keeps a copy's serial (a STRING). Default `sneakyitems:serial`; set it to keep older copies live. */
    fun serialKey(value: NamespacedKey) = apply { serialKey = value }

    /** Set down on blocks; null (the default) turns placing off. */
    fun placement(value: Placement?) = apply { placement = value }

    fun hooks(value: ItemRuleHooks) = apply { hooks = value }

    fun build(): ItemRules = ItemRules(this)
  }

  companion object {
    @JvmField val DEFAULT_SERIAL_KEY: NamespacedKey = NamespacedKey("sneakyitems", "serial")

    @JvmStatic fun builder(): Builder = Builder()
  }
}

/**
 * How [ItemRules.placement] sets an item down: an `ItemDisplay` scaled to [scale], its centre [lift] blocks above
 * the block top, and an `Interaction` hitbox [hitbox] blocks wide and high standing on the block.
 */
data class Placement @JvmOverloads constructor(
    val scale: Float = 0.45f,
    val lift: Double = 0.22,
    val hitbox: Float = 0.45f,
)

/** Why a rule stopped a player, for [ItemRuleHooks.refused]. */
enum class RuleRefusal {
  /** [ItemRules.carryLimit] reached (pickup or taking a placed item back). */
  CARRY_LIMIT,

  /** [ItemRules.noContainers]: the item can't go there. */
  CONTAINER,

  /** Taking a placed item back with no free inventory slot. */
  POCKETS_FULL,
}

/**
 * Callbacks for a rule group; every method does nothing by default. Main thread. [refused] is rate-limited to once per
 * 2 seconds per player, so it can send a message as is. A hook that throws is logged and ignored.
 */
interface ItemRuleHooks {
  fun refused(player: Player, itemId: String, reason: RuleRefusal) {}

  /** [player] picked [itemId] up from the ground. */
  fun pickedUp(player: Player, itemId: String) {}

  /** [itemId] is on the ground as [item] (thrown, dropped on leave, respawned or spawned). */
  fun dropped(itemId: String, item: Item) {}

  /** [itemId] was set down at [at] as [display]. */
  fun placed(itemId: String, display: ItemDisplay, at: Location) {}

  /** [player] took the placed [itemId] back. */
  fun taken(player: Player, itemId: String) {}

  companion object {
    @JvmField val NONE: ItemRuleHooks = object : ItemRuleHooks {}
  }
}
