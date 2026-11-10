package com.sneakyitems.api

import com.sneakyitems.platform.SneakyItemsPlatform
import com.sneakyitems.util.magicitems.MagicItem
import com.sneakyitems.util.magicitems.MagicItems
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Global magic-item definitions keyed by internal name.
 * Owner plugins may replace only their own entries; foreign conflicts are logged and rejected.
 */
object MagicItemRegistry {

    private val ownersByName = ConcurrentHashMap<String, Plugin>()
    private val namespaceByOwner = ConcurrentHashMap<Plugin, String>()
    private val defsByOwner = ConcurrentHashMap<Plugin, MutableSet<String>>()

    @JvmStatic
    fun register(owner: Plugin, pdcNamespace: String, internalName: String, item: MagicItem): Boolean {
        val existingOwner = ownersByName[internalName]
        if (existingOwner != null && existingOwner !== owner) {
            SneakyItemsPlatform.error(
                "Magic item '$internalName' already registered by ${existingOwner.name}; " +
                    "${owner.name} cannot replace it.",
            )
            return false
        }
        val prior = MagicItems.getMagicItems()[internalName]
        if (prior != null && existingOwner == null) {
            SneakyItemsPlatform.error(
                "Magic item '$internalName' has no owner record but is present; refusing replace from ${owner.name}.",
            )
            return false
        }
        namespaceByOwner[owner] = pdcNamespace
        SneakyItemsPlatform.applyOwnerNamespace(pdcNamespace)
        val parsed = stampFromStackIfNeeded(internalName, item) ?: return false
        MagicItems.getMagicItems()[internalName] = parsed
        ownersByName[internalName] = owner
        defsByOwner.computeIfAbsent(owner) { ConcurrentHashMap.newKeySet() }.add(internalName)
        return true
    }

    @JvmStatic
    fun register(
        owner: Plugin,
        pdcNamespace: String,
        section: ConfigurationSection,
    ): MagicItem? {
        val internalName = section.name
        SneakyItemsPlatform.applyOwnerNamespace(pdcNamespace)
        val magicItem = MagicItems.getMagicItemFromSection(section) ?: return null
        return if (register(owner, pdcNamespace, internalName, magicItem)) magicItem else null
    }

    @JvmStatic
    fun registerFromYaml(owner: Plugin, pdcNamespace: String, yamlFile: File): Int {
        if (!yamlFile.isFile) return 0
        val yaml = YamlConfiguration.loadConfiguration(yamlFile)
        return registerFromRoot(owner, pdcNamespace, yaml)
    }

    /**
     * Registers MagicsSpells-style item YAML: prefers a top-level `magic-items:` map,
     * otherwise treats each top-level key as an item section (flat file).
     */
    @JvmStatic
    fun registerMagicItemsFile(owner: Plugin, pdcNamespace: String, yamlFile: File): Int =
        registerFromYaml(owner, pdcNamespace, yamlFile)

    @JvmStatic
    fun registerFromRoot(owner: Plugin, pdcNamespace: String, root: ConfigurationSection): Int {
        val items = root.getConfigurationSection("magic-items") ?: root
        var count = 0
        for (key in items.getKeys(false)) {
            val section = items.getConfigurationSection(key) ?: continue
            SneakyItemsPlatform.applyOwnerNamespace(pdcNamespace)
            val magicItem = MagicItems.getMagicItemFromSection(section) ?: continue
            if (register(owner, pdcNamespace, key, magicItem)) count++
        }
        return count
    }

    @JvmStatic
    fun registerFromStack(
        owner: Plugin,
        pdcNamespace: String,
        internalName: String,
        template: ItemStack,
    ): MagicItem? {
        SneakyItemsPlatform.applyOwnerNamespace(pdcNamespace)
        val data = MagicItems.getMagicItemDataFromItemStack(template) ?: return null
        data.setAttribute(
            com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute.MAGIC_ITEM_NAME,
            internalName,
        )
        val magicItem = MagicItems.getMagicItemFromData(data) ?: return null
        // a version from the template's data, so held copies are rebuilt when the owner changes the template
        // (left at 0, a copy stamped once is skipped by the updater forever)
        com.sneakyitems.util.magicitems.MagicItemDefinitionVersions.applyToMagicItem(
            magicItem,
            com.sneakyitems.util.magicitems.MagicItemDefinitionVersions.fingerprintString(data.toString()),
        )
        return if (register(owner, pdcNamespace, internalName, magicItem)) magicItem else null
    }

    @JvmStatic
    fun clearOwner(owner: Plugin) {
        val names = defsByOwner.remove(owner) ?: return
        namespaceByOwner.remove(owner)
        val map = MagicItems.getMagicItems()
        for (name in names) {
            if (ownersByName[name] === owner) {
                ownersByName.remove(name)
                map.remove(name)
            }
        }
        MagicItems.clearItemStackCache()
    }

    @JvmStatic
    fun ownerOf(internalName: String): Plugin? = ownersByName[internalName]

    @JvmStatic
    fun pdcNamespace(owner: Plugin): String? = namespaceByOwner[owner]

    private fun stampFromStackIfNeeded(internalName: String, item: MagicItem): MagicItem? {
        val stack = item.itemStack.clone()
        val meta = stack.itemMeta ?: return item
        meta.persistentDataContainer.set(
            com.sneakyitems.util.magicitems.MagicItemBehaviorKeys.magicItem(),
            org.bukkit.persistence.PersistentDataType.STRING,
            internalName,
        )
        stack.itemMeta = meta
        item.setItemStack(stack)
        return item
    }
}
