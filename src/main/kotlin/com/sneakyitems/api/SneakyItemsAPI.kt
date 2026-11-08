package com.sneakyitems.api

import com.sneakyitems.util.magicitems.MagicItem
import com.sneakyitems.util.magicitems.MagicItemData
import com.sneakyitems.util.magicitems.MagicItems
import com.sneakyitems.util.magicitems.MagicItemUpdater
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
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

  @JvmStatic
  fun registerFromStack(
      owner: Plugin,
      pdcNamespace: String,
      internalName: String,
      template: ItemStack,
  ): MagicItem? = MagicItemRegistry.registerFromStack(owner, pdcNamespace, internalName, template)

  @JvmStatic
  fun clearOwner(owner: Plugin) = MagicItemRegistry.clearOwner(owner)

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
}
