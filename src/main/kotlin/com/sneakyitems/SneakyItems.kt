package com.sneakyitems

import com.sneakyitems.commands.ReloadCommand
import com.sneakyitems.listener.OwnerDefinitionCleanupListener
import com.sneakyitems.events.SneakyItemsLoadedEvent
import com.sneakyitems.platform.SneakyItemsPlatform
import com.sneakyitems.service.LegacyItemTagMappings
import com.sneakyitems.util.magicitems.MagicItemBehaviorReceiveListener
import com.sneakyitems.util.magicitems.MagicItemUpdater
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Bukkit
import org.bukkit.event.HandlerList
import org.bukkit.plugin.java.JavaPlugin

open class SneakyItems : JavaPlugin() {

    companion object {
        const val IDENTIFIER = "sneakyitems"
        lateinit var instance: SneakyItems

        fun log(message: String) {
            instance.logger.info(message)
        }
    }

    override fun onLoad() {
        instance = this
        SneakyItemsPlatform.init(this)
    }

    override fun onEnable() {
        saveDefaultConfig()
        reloadPlugin()
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(ReloadCommand(this).create(), "Reload SneakyItems definitions and mappings")
        }
        logger.info("SneakyItems enabled.")
    }

    /** Shared configuration entry point for startup and reload. */
    fun reloadPlugin() {
        reloadConfig()
        SneakyItemsPlatform.setEnableUpdateItemData(config.getBoolean("update-item-data", true))
        SneakyItemsPlatform.setHideMagicItemTooltips(config.getBoolean("hide-magic-item-tooltips", false))
        if (config.getBoolean("load-legacy-item-tag-mappings", true)) {
            LegacyItemTagMappings.reload(this)
        } else {
            LegacyItemTagMappings.clear(this)
        }
        registerRuntimeListeners()
        Bukkit.getPluginManager().callEvent(SneakyItemsLoadedEvent())
    }

  override fun onDisable() {
        HandlerList.unregisterAll(this)
        logger.info("SneakyItems disabled.")
    }

    private var listenersRegistered = false

    private fun registerRuntimeListeners() {
        if (listenersRegistered) return
        val pm = server.pluginManager
        pm.registerEvents(MagicItemUpdater.PersistentDataUpdater(), this)
        pm.registerEvents(MagicItemUpdater.ChunkMagicItemListener(), this)
        pm.registerEvents(MagicItemUpdater.CharacterPersistentDataUpdater(), this)
        pm.registerEvents(MagicItemBehaviorReceiveListener(), this)
        pm.registerEvents(OwnerDefinitionCleanupListener(), this)
        listenersRegistered = true
    }
}
