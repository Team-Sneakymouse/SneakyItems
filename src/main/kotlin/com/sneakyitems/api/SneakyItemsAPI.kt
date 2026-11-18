package com.sneakyitems.api

import com.sneakyitems.SneakyItems
import com.sneakyitems.util.magicitems.MagicItem
import com.sneakyitems.util.magicitems.MagicItemData
import com.sneakyitems.util.magicitems.MagicItems
import com.sneakyitems.util.magicitems.MagicItemUpdater
import org.bukkit.configuration.ConfigurationSection
import com.sneakyitems.util.magicitems.MagicItemBehaviorKeys
import com.sneakyitems.util.magicitems.MagicItemTagging
import com.sneakyutils.triggers.PlayerTick
import com.sneakyutils.triggers.PlayerTimerOptions
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.io.File

/** Public entry points for registering and resolving magic items. */
object SneakyItemsAPI {

  @JvmStatic
  fun register(owner: Plugin, pdcNamespace: String, section: ConfigurationSection): MagicItem? =
      MagicItemRegistry.register(owner, pdcNamespace, section)

  @JvmStatic
  fun registerYaml(owner: Plugin, pdcNamespace: String, yamlFile: File): Int =
      MagicItemRegistry.registerFromYaml(owner, pdcNamespace, yamlFile)

  /** MagicsSpells-style file: uses `magic-items:` if present, else flat top-level item keys. */
  @JvmStatic
  fun registerMagicItemsFile(owner: Plugin, pdcNamespace: String, yamlFile: File): Int =
      MagicItemRegistry.registerMagicItemsFile(owner, pdcNamespace, yamlFile)

  @JvmStatic
  fun registerFromRoot(owner: Plugin, pdcNamespace: String, root: ConfigurationSection): Int =
      MagicItemRegistry.registerFromRoot(owner, pdcNamespace, root)

  @JvmStatic
  fun registerFromStack(
      owner: Plugin,
      pdcNamespace: String,
      internalName: String,
      template: ItemStack,
  ): MagicItem? = MagicItemRegistry.registerFromStack(owner, pdcNamespace, internalName, template)

  /** Drops [owner]'s definitions and its legacy mappings ([registerLegacyKey], [registerLegacyPattern]). */
  @JvmStatic
  fun clearOwner(owner: Plugin) = MagicItemRegistry.clearOwner(owner)

  /**
   * Migrates stacks made before [internalName] was a magic item: any stack carrying [key] (whatever its value) gets
   * stamped as [internalName] the next time the updater sees it (inventories on join/open, item entities on load).
   * The rebuild keeps the stack's other PDC keys, so per-copy data stays. Use it for items with your own PDC
   * identity; for items known only by their look, use [registerLegacyPattern]. Cleared by [clearOwner].
   */
  @JvmStatic
  fun registerLegacyKey(owner: Plugin, key: NamespacedKey, internalName: String) =
      MagicItemTagging.addKey(MagicItemRegistry.legacyOwner(owner), key, internalName)

  /**
   * Migrates stacks known only by their look: any stack matching [pattern] (a magic-item data string, for example
   * `paper{"item-model":"lom:coach_whistle"}`; only the attributes it names are compared, amount is ignored) gets
   * stamped as [internalName]. Same mechanism as `legacy-item-tag-mappings.yml`, shipped by the owning plugin.
   * Returns false when [pattern] doesn't parse. Cleared by [clearOwner].
   */
  @JvmStatic
  fun registerLegacyPattern(owner: Plugin, internalName: String, pattern: String): Boolean {
    val data = MagicItems.getMagicItemDataFromString(pattern) ?: return false
    data.ignoredAttributes.add(MagicItemData.MagicItemAttribute.AMOUNT)
    data.ignoredAttributes.add(MagicItemData.MagicItemAttribute.MAGIC_ITEM_NAME)
    val item = MagicItems.getMagicItemFromData(data) ?: return false
    MagicItemTagging.add(MagicItemRegistry.legacyOwner(owner), MagicItemTagging.TagMapping(internalName, data, item))
    return true
  }

  @JvmStatic
  fun getByInternalName(name: String): MagicItem? = MagicItems.getMagicItemByInternalName(name)

  @JvmStatic
  fun getItemStack(name: String): ItemStack? = MagicItems.getItemByInternalName(name)

  @JvmStatic
  fun getDataFromStack(stack: ItemStack?): MagicItemData? = MagicItems.getMagicItemDataFromItemStack(stack)

  @JvmStatic
  fun getDataFromString(spec: String?): MagicItemData? = MagicItems.getMagicItemDataFromString(spec)

  @JvmStatic
  fun matches(pattern: MagicItemData?, stack: ItemStack?): Boolean = MagicItems.matches(pattern, stack)

  @JvmStatic
  fun createForPlayer(spec: String, player: Player, amount: Int): ItemStack? =
      MagicItems.getItemStackForPlayer(spec, player, amount)

  @JvmStatic
  fun refreshPlayerInventories(player: Player) = MagicItemUpdater.processPlayerInventories(player)

  @JvmStatic
  fun registerMenuInventorySkip(predicate: java.util.function.Predicate<org.bukkit.inventory.Inventory>) =
      SneakyItemsHooks.registerMenuInventorySkip(predicate)

  // --- lookups by internal name (the stamped `magicitem` PDC value; reads no meta clone) ---

  /** Internal name of the magic item [stack] is, or null when it is not a magic item. */
  @JvmStatic
  fun keyOf(stack: ItemStack?): String? = MagicItemBehaviorKeys.readMagicItemName(stack)

  /** True when [stack] is the magic item [key]. */
  @JvmStatic
  fun isItem(stack: ItemStack?, key: String): Boolean = keyOf(stack) == key

  @JvmStatic
  fun inMainHand(player: Player, key: String): Boolean = isItem(player.inventory.itemInMainHand, key)

  @JvmStatic
  fun inOffHand(player: Player, key: String): Boolean = isItem(player.inventory.itemInOffHand, key)

  @JvmStatic
  fun inEitherHand(player: Player, key: String): Boolean = inMainHand(player, key) || inOffHand(player, key)

  /** Hotbar slots 0-8 (the offhand is not part of the hotbar). */
  @JvmStatic
  fun onHotbar(player: Player, key: String): Boolean = (0..8).any { isItem(player.inventory.getItem(it), key) }

  /** Main inventory (hotbar included) or offhand. Armor slots and the cursor do not count; see [wearing]. */
  @JvmStatic
  fun inInventory(player: Player, key: String): Boolean = INVENTORY_SLOTS.any { isItem(player.inventory.getItem(it), key) }

  /**
   * True when [player] wears [key] in an armor slot: [slot] when given (HEAD, CHEST, LEGS or FEET),
   * otherwise any of them.
   */
  @JvmStatic
  @JvmOverloads
  fun wearing(player: Player, key: String, slot: EquipmentSlot? = null): Boolean {
    if (slot != null) {
      require(slot in ARMOR_SLOTS) { "Not an armor slot: $slot" }
      return isItem(player.inventory.getItem(slot), key)
    }
    return ARMOR_SLOTS.any { isItem(player.inventory.getItem(it), key) }
  }

  /** [org.bukkit.inventory.PlayerInventory] slot indices holding [key], in [inInventory] scope (0-35, 40 = offhand). */
  @JvmStatic
  fun findSlots(player: Player, key: String): List<Int> = INVENTORY_SLOTS.filter { isItem(player.inventory.getItem(it), key) }

  /** Total amount of [key] in [inInventory] scope. */
  @JvmStatic
  fun count(player: Player, key: String): Int =
      INVENTORY_SLOTS.sumOf { slot -> player.inventory.getItem(slot)?.takeIf { isItem(it, key) }?.amount ?: 0 }

  // --- carried magic items (live per-player index; see CarriedItemChangeEvent) ---
  //
  // "Carried" = the player's own inventory: storage, hotbar, armor and offhand. Not the cursor, open containers or
  // bags (unlike [count] / [inInventory], armor counts here). An item counts exactly when [keyOf] says it is the id.
  // Main thread only: these throw IllegalStateException elsewhere. They read a cached count, never rescan per call;
  // the cache follows Paper's PlayerInventorySlotChangeEvent, which fires when Paper next syncs the player's menu, so
  // a change made earlier in the same tick may not show yet (call [refreshCarried] to force it).

  /** Total amount of the magic item [itemId] [player] carries (0 when none, or when [player] is offline). */
  @JvmStatic
  fun carriedCount(player: Player, itemId: String): Int = carriedIndex().count(player, itemId)

  /** True when [player] carries at least one of the magic item [itemId]. */
  @JvmStatic
  fun carries(player: Player, itemId: String): Boolean = carriedIndex().carries(player, itemId)

  /**
   * Ids of the magic items [player] carries. An unmodifiable snapshot: it never changes after it is returned, so it is
   * safe to iterate while inventories change; call again for newer data. Empty when none, or when [player] is offline.
   */
  @JvmStatic
  fun carriedIds(player: Player): Set<String> = carriedIndex().ids(player)

  /**
   * Recounts [player]'s inventory now (one 41-slot scan) and fires [com.sneakyitems.events.CarriedItemChangeEvent]
   * for differences. Only needed to see a change made earlier in the same tick that Paper has not synced yet.
   * Called from a [com.sneakyitems.events.CarriedItemChangeEvent] listener it is deferred until every listener has
   * seen the current batch, so events always chain (each old count is the previous new count).
   */
  @JvmStatic
  fun refreshCarried(player: Player) = carriedIndex().refresh(player)

  // --- per-player timers while a magic item is carried or held (SneakyUtils triggers) ---
  //
  // These replace "poll every online player's hand/inventory every few ticks". Each returns an [ItemWatch] around a
  // SneakyUtils per-player timer (`Triggers.everyForPlayers`, owner = [owner]) whose players SneakyItems adds and
  // removes as the item comes and goes; a player without the item is in no timer and costs nothing per tick.
  // Close the watch to stop; it also ends when [owner] disables (SneakyUtils' owner model) and when SneakyItems
  // disables. Membership starts from a fresh read of every online player's inventory. Main thread only.

  /**
   * Calls [handler] every [intervalTicks] ticks (1..72000) for each player who carries [itemId] ([carries]: storage,
   * hotbar, armor or offhand). A player enters the timer when the carried index sees the count go above 0 (on the
   * next scheduler pass after Paper reports the slot change, see [carries]) and leaves when it drops to 0 or they
   * quit; players carrying it when you subscribe (recounted now, so a `setItem` made just before counts) are added
   * at once. Dead players are paused until they respawn
   * (SneakyUtils' default; pass [PlayerTimerOptions] with `skipDead(false)` to keep calling them).
   * Independent of other subscriptions for the same item.
   */
  @JvmStatic
  fun whileCarrying(owner: Plugin, itemId: String, intervalTicks: Int, handler: PlayerTick): ItemWatch =
      whileCarrying(owner, itemId, intervalTicks, handler, PlayerTimerOptions.DEFAULT)

  /**
   * [whileCarrying] with SneakyUtils timer options (a diagnostics `label`, `skipDead`). The scope must be
   * [com.sneakyutils.triggers.PlayerScope.ADDED] (the default): SneakyItems decides who is in the timer.
   */
  @JvmStatic
  fun whileCarrying(
      owner: Plugin,
      itemId: String,
      intervalTicks: Int,
      handler: PlayerTick,
      options: PlayerTimerOptions,
  ): ItemWatch = SneakyItems.instance.watches.whileCarrying(owner, itemId, intervalTicks, handler, options)

  /**
   * Calls [handler] every [intervalTicks] ticks (1..72000) for each player who has [itemId] in [where] (main hand =
   * selected hotbar slot, offhand, armor; see [HeldSlot]). A player enters the timer as soon as SneakyItems sees the
   * item arrive there (hotbar scroll and hand swap: in the event itself; inventory moves, pickups, equips: when
   * Paper reports the slot or equipment change) and leaves when it goes; players holding it when you subscribe are
   * added at once (read from the live inventory now). Dead players are paused until they respawn, as for
   * [whileCarrying].
   */
  @JvmStatic
  fun whileHolding(owner: Plugin, itemId: String, where: HeldSlot, intervalTicks: Int, handler: PlayerTick): ItemWatch =
      whileHolding(owner, itemId, where, intervalTicks, handler, PlayerTimerOptions.DEFAULT)

  /** [whileHolding] with SneakyUtils timer options; the scope must be `ADDED` (see [whileCarrying]). */
  @JvmStatic
  fun whileHolding(
      owner: Plugin,
      itemId: String,
      where: HeldSlot,
      intervalTicks: Int,
      handler: PlayerTick,
      options: PlayerTimerOptions,
  ): ItemWatch = SneakyItems.instance.watches.whileHolding(owner, itemId, where, intervalTicks, handler, options)

  private fun carriedIndex() = SneakyItems.instance.carried

  private const val OFFHAND_SLOT = 40
  private val INVENTORY_SLOTS: List<Int> = (0..35).toList() + OFFHAND_SLOT
  private val ARMOR_SLOTS = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
}
