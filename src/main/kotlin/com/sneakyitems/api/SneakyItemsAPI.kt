package com.sneakyitems.api

import com.sneakyitems.util.magicitems.MagicItem
import com.sneakyitems.util.magicitems.MagicItemData
import com.sneakyitems.util.magicitems.MagicItems
import com.sneakyitems.util.magicitems.MagicItemUpdater
import org.bukkit.configuration.ConfigurationSection
import com.sneakyitems.util.magicitems.MagicItemBehaviorKeys
import com.sneakyitems.util.magicitems.MagicItemTagging
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

  private const val OFFHAND_SLOT = 40
  private val INVENTORY_SLOTS: List<Int> = (0..35).toList() + OFFHAND_SLOT
  private val ARMOR_SLOTS = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
}
