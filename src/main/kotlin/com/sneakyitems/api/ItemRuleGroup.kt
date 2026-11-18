package com.sneakyitems.api

import com.sneakyitems.service.ItemRulesService
import com.sneakyitems.service.UniqueItemStore
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.Item
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f

/**
 * A group of magic items under one [ItemRules] set ([SneakyItemsAPI.itemRules]). Use it to make, give, place and find
 * the group's items. Main thread only. Ends with [close], when [owner] disables and when SneakyItems disables.
 */
class ItemRuleGroup internal constructor(
    private val service: ItemRulesService,
    /** The plugin that registered the group. */
    val owner: Plugin,
    /** The group's name, unique per owner. */
    val groupId: String,
    /** The magic item ids in the group. */
    val itemIds: Set<String>,
    val rules: ItemRules,
    internal val store: UniqueItemStore,
) : AutoCloseable {

  /** False once closed. */
  var isActive: Boolean = true
    internal set

  /** The group item [stack] is (its magic item id), or null when it is not one of [itemIds]. */
  fun idOf(stack: ItemStack?): String? = SneakyItemsAPI.keyOf(stack)?.takeIf { it in itemIds }

  fun isMember(stack: ItemStack?): Boolean = idOf(stack) != null

  /** The serial [stack] carries ([ItemRules.serialKey]), or null. */
  fun serial(stack: ItemStack?): String? =
      stack?.takeIf { it.hasItemMeta() }?.persistentDataContainer?.get(rules.serialKey, PersistentDataType.STRING)

  /** True when [stack] is a group item and, for [ItemRules.unique], carries its item's live serial. */
  fun isLive(stack: ItemStack?): Boolean {
    val id = idOf(stack) ?: return false
    return !rules.unique || (serial(stack) ?: return false) == store.serial(id)
  }

  /** Group items [player] has: inventory (armor and offhand included), cursor, and an open crafting grid. */
  fun carried(player: Player): List<ItemStack> {
    val out = ArrayList<ItemStack>(1)
    for (it in player.inventory.contents) if (isMember(it)) out += it!!
    if (isMember(player.itemOnCursor)) out += player.itemOnCursor
    val top: org.bukkit.inventory.Inventory? = player.openInventory.topInventory // null in some test servers
    if (top != null && (top.type == InventoryType.CRAFTING || top.type == InventoryType.WORKBENCH)) {
      for (it in top.contents) if (isMember(it)) out += it!!
    }
    return out
  }

  /** True when [player] ignores [ItemRules.carryLimit]. */
  fun bypasses(player: Player): Boolean =
      rules.bypassPermission?.let { player.hasPermission(it) } == true || rules.bypass?.test(player) == true

  /** True when [player] may take one more group item. */
  fun mayTakeAnother(player: Player): Boolean =
      rules.carryLimit <= 0 || carried(player).sumOf { it.amount } < rules.carryLimit || bypasses(player)

  /**
   * A new copy of [itemId] (null when the definition isn't registered). For [ItemRules.unique] it gets a fresh serial,
   * so every older copy goes stale; put it somewhere at once ([give], [respawn], [place] do).
   */
  fun create(itemId: String): ItemStack? {
    require(itemId in itemIds) { "$itemId is not in group $groupId" }
    val stack = SneakyItemsAPI.getItemStack(itemId) ?: return null
    if (rules.unique) {
      val serial = store.newSerial(itemId)
      stack.editPersistentDataContainer { it.set(rules.serialKey, PersistentDataType.STRING, serial) }
    }
    if (rules.protectOnGround) ItemRulesService.markNotGroundPlaceable(stack)
    return stack
  }

  /** Makes [itemId] ([create]) into [player]'s inventory; when it is full, drops it at their feet. False when no definition. */
  fun give(itemId: String, player: Player): Boolean {
    val stack = create(itemId) ?: return false
    val left = player.inventory.addItem(stack)
    if (left.isEmpty()) store.held(itemId, player) else for (s in left.values) dropAt(itemId, s, player.location)
    return true
  }

  /** Makes [itemId] ([create]) on the ground at [at], lifted out of the void. Null when no definition. */
  fun respawn(itemId: String, at: Location): Item? {
    val stack = create(itemId) ?: return null
    return dropAt(itemId, stack, at)
  }

  private fun dropAt(itemId: String, stack: ItemStack, at: Location): Item {
    val loc = at.clone()
    val world = loc.world
    if (loc.y < world.minHeight + 1) loc.y = world.getHighestBlockYAt(loc).toDouble() + 1.5
    service.removePlacedNear(this, itemId, loc)
    val item = world.dropItem(loc, stack)
    service.onGround(this, itemId, item)
    return item
  }

  /**
   * Sets [itemId] down at [at] (the block top centre) as placed ([ItemRules.placement] must be set). For a unique item
   * this is its live copy wherever it was: other copies go stale. False when no definition.
   */
  fun place(itemId: String, at: Location): Boolean {
    val placement = requireNotNull(rules.placement) { "group $groupId has no placement" }
    require(itemId in itemIds) { "$itemId is not in group $groupId" }
    val stack = SneakyItemsAPI.getItemStack(itemId) ?: return false
    var serial: String? = null
    if (rules.unique) {
      serial = store.serial(itemId) ?: store.newSerial(itemId)
      stack.editPersistentDataContainer { it.set(rules.serialKey, PersistentDataType.STRING, serial) }
    }
    placeStack(itemId, stack, serial, at, placement)
    return true
  }

  internal fun placeStack(itemId: String, stack: ItemStack, serial: String?, at: Location, placement: Placement) {
    val world = at.world
    val one = stack.clone().also { it.amount = 1 }
    val display = world.spawn(at.clone().add(0.0, placement.lift, 0.0), ItemDisplay::class.java) { d ->
      d.setItemStack(one)
      d.transformation = Transformation(Vector3f(), AxisAngle4f(),
          Vector3f(placement.scale, placement.scale, placement.scale), AxisAngle4f())
      service.tagPlaced(d, itemId, serial)
    }
    val hit = world.spawn(at, Interaction::class.java) { i ->
      i.interactionWidth = placement.hitbox
      i.interactionHeight = placement.hitbox
      service.tagPlaced(i, itemId, serial)
    }
    store.placed(itemId, hit.uniqueId, display.uniqueId, at)
    service.hook(this) { it.placed(itemId, display, at) }
  }

  /** [itemId]'s state ([ItemRules.unique] groups; otherwise always NONE). */
  fun state(itemId: String): UniqueItemState = store.state(itemId)

  /** Best known position of [itemId]: its holder, its loaded entity, else the last saved spot. Null when never seen. */
  fun locate(itemId: String): Location? = store.locate(itemId)

  /** [itemId]'s entity while on the ground (the item) or placed (the display), when loaded. */
  fun body(itemId: String): Entity? {
    val s = store.state(itemId)
    val id = when (s.where) {
      UniqueWhere.GROUND -> s.entity
      UniqueWhere.PLACED -> s.display
      else -> null
    } ?: return null
    return Bukkit.getEntity(id)?.takeIf { it.isValid }
  }

  /**
   * Migration: makes [serial] [itemId]'s live serial when it has none yet (copies made by an older plugin stay live).
   * [location] and [where] seed its last known spot. Returns false when it already had a serial.
   */
  @JvmOverloads
  fun adopt(itemId: String, serial: String, where: UniqueWhere = UniqueWhere.NONE, location: Location? = null): Boolean {
    require(itemId in itemIds) { "$itemId is not in group $groupId" }
    return store.adopt(itemId, serial, where, location)
  }

  /** Stops the rules for these items and saves the state. Idempotent. */
  override fun close() = service.close(this)

  override fun toString(): String = "ItemRuleGroup(${owner.name}:$groupId, items=$itemIds, active=$isActive)"
}
