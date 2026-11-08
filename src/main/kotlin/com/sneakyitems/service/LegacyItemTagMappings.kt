package com.sneakyitems.service

import com.sneakyitems.SneakyItems
import com.sneakyitems.util.magicitems.MagicItemData
import com.sneakyitems.util.magicitems.MagicItemTagging
import com.sneakyitems.util.magicitems.MagicItemTagging.TagMapping
import com.sneakyitems.util.magicitems.MagicItems
import org.bukkit.configuration.file.YamlConfiguration

/** Parses SneakyItems-owned legacy ItemTag mapping YAML. */
object LegacyItemTagMappings {

  private const val RESOURCE = "legacy-item-tag-mappings.yml"

  fun clear(plugin: SneakyItems) {
    MagicItemTagging.unregister(plugin)
  }

  fun reload(plugin: SneakyItems) {
    MagicItemTagging.unregister(plugin)
    val mappings = load(plugin)
    if (mappings.isNotEmpty()) {
      MagicItemTagging.register(plugin, mappings)
      plugin.logger.info("Loaded ${mappings.size} legacy item-tag mapping(s).")
    }
  }

  private fun load(plugin: SneakyItems): List<TagMapping> {
    val stream = plugin.getResource(RESOURCE) ?: return emptyList()
    stream.use {
      val yaml = YamlConfiguration.loadConfiguration(it.reader())
      val mappingSection = yaml.getConfigurationSection("mapping") ?: return emptyList()
      val result = mutableListOf<TagMapping>()
      for (key in mappingSection.getKeys(false)) {
        val itemSection = mappingSection.getConfigurationSection(key) ?: continue
        val magicItem = MagicItems.getMagicItemFromSection(itemSection) ?: continue
        val data = magicItem.magicItemData
        data.ignoredAttributes.add(MagicItemData.MagicItemAttribute.AMOUNT)
        data.ignoredAttributes.add(MagicItemData.MagicItemAttribute.MAGIC_ITEM_NAME)
        val internalName = itemSection.getString("internal-name", key)
        result.add(TagMapping(internalName, data, magicItem))
      }
      return result
    }
  }
}
