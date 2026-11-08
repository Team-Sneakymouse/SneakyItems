package com.sneakyitems.service

import com.sneakyitems.SneakyItems
import com.sneakyitems.util.magicitems.MagicItemData
import com.sneakyitems.util.magicitems.MagicItemTagging
import com.sneakyitems.util.magicitems.MagicItemTagging.TagMapping
import com.sneakyitems.util.magicitems.MagicItems
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * Parses data-folder legacy ItemTag mapping YAML (`legacy-item-tag-mappings.yml`).
 * The jar ships a default; [ensureDataFile] copies it on first use when missing.
 */
object LegacyItemTagMappings {

  const val FILE_NAME = "legacy-item-tag-mappings.yml"

  /** In-memory enablement; config /reload resets this from `load-legacy-item-tag-mappings`. */
  @Volatile
  var runtimeEnabled: Boolean = false
    private set

  fun clear(plugin: SneakyItems) {
    MagicItemTagging.unregister(plugin)
    runtimeEnabled = false
  }

  /**
   * Loads mappings from the data-folder file when [enabled] is true; otherwise clears.
   * Does not write `config.yml`.
   */
  fun setRuntimeEnabled(plugin: SneakyItems, enabled: Boolean): Int {
    if (!enabled) {
      clear(plugin)
      return 0
    }
    return reload(plugin)
  }

  /** Copies the jar default into the data folder when the file is absent. */
  fun ensureDataFile(plugin: SneakyItems): File {
    val file = File(plugin.dataFolder, FILE_NAME)
    if (!file.exists()) {
      plugin.saveResource(FILE_NAME, false)
    }
    return file
  }

  /**
   * Re-parses the data-folder YAML and registers mappings. Returns how many mappings loaded
   * (0 if the file is missing/empty or nothing parsed).
   */
  fun reload(plugin: SneakyItems): Int {
    MagicItemTagging.unregister(plugin)
    val mappings = load(plugin)
    if (mappings.isNotEmpty()) {
      MagicItemTagging.register(plugin, mappings)
      runtimeEnabled = true
      plugin.logger.info("Loaded ${mappings.size} legacy item-tag mapping(s) from $FILE_NAME.")
    } else {
      runtimeEnabled = false
      plugin.logger.info("No legacy item-tag mappings loaded from $FILE_NAME.")
    }
    return mappings.size
  }

  private fun load(plugin: SneakyItems): List<TagMapping> {
    val file = ensureDataFile(plugin)
    if (!file.isFile) {
      plugin.logger.warning("Legacy item-tag mappings file missing: ${file.path}")
      return emptyList()
    }
    val yaml = try {
      YamlConfiguration.loadConfiguration(file)
    } catch (ex: Exception) {
      plugin.logger.warning("Failed to read $FILE_NAME: ${ex.message}")
      return emptyList()
    }
    val mappingSection = yaml.getConfigurationSection("mapping") ?: return emptyList()
    val result = mutableListOf<TagMapping>()
    for (key in mappingSection.getKeys(false)) {
      try {
        val itemSection = mappingSection.getConfigurationSection(key) ?: continue
        val magicItem = MagicItems.getMagicItemFromSection(itemSection) ?: continue
        val data = magicItem.magicItemData
        data.ignoredAttributes.add(MagicItemData.MagicItemAttribute.AMOUNT)
        data.ignoredAttributes.add(MagicItemData.MagicItemAttribute.MAGIC_ITEM_NAME)
        val internalName = itemSection.getString("internal-name", key)
        result.add(TagMapping(internalName, data, magicItem))
      } catch (ex: Exception) {
        plugin.logger.warning("Skipping legacy item-tag mapping '$key': ${ex.message}")
      }
    }
    return result
  }
}
