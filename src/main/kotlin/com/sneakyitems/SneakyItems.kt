package com.sneakyitems

import com.sneakyitems.commands.SneakyItemsCommand
import com.sneakyitems.events.SneakyItemsLoadedEvent
import com.sneakyitems.integration.Probe
import com.sneakyitems.listener.OwnerDefinitionCleanupListener
import com.sneakyitems.platform.SneakyItemsPlatform
import com.sneakyitems.service.LegacyItemTagMappings
import com.sneakyitems.util.items.CharacterExpirationHook
import com.sneakyitems.util.items.ItemExpirationListener
import com.sneakyitems.util.items.ItemExpirationScheduler
import com.sneakyitems.util.items.ItemSoulboundListener
import com.sneakyitems.util.magicitems.MagicItemBehaviorReceiveListener
import com.sneakyitems.util.magicitems.MagicItemUpdater
import com.sneakyitems.util.performance.PerformanceDiagnostics
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

    /** SneakyDiagnostics measurements (optional); sections and counts are listed in the README. */
    val probe = Probe(this, "sneakyitems-ticks-1")

    override fun onLoad() {
        instance = this
        SneakyItemsPlatform.init(this)
    }

    override fun onEnable() {
        saveDefaultConfig()
        LegacyItemTagMappings.ensureDataFile(this)
        reloadPlugin()
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(
                SneakyItemsCommand(this).create(),
                "SneakyItems reload and legacy item-tag controls",
            )
        }
        PerformanceDiagnostics.attach(probe)
        probe.hook()
        logger.info("SneakyItems enabled.")
    }

    /** Shared configuration entry point for startup and reload. Resets runtime legacy-tag enablement from config. */
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
        probe.unhook()
        PerformanceDiagnostics.detach()
        ItemExpirationScheduler.shutdown()
        HandlerList.unregisterAll(this)
        logger.info("SneakyItems disabled.")
    }

    private var listenersRegistered = false

    private fun registerRuntimeListeners() {
        if (listenersRegistered) return
        val pm = server.pluginManager
        val expiration = ItemExpirationListener()
        pm.registerEvents(expiration, this)
        pm.registerEvents(CharacterExpirationHook(this, expiration), this)
        pm.registerEvents(ItemSoulboundListener(), this)
        // Dependents register during enable before the first server tick.
        val initialPlayers = server.onlinePlayers.toList()
        if (initialPlayers.isNotEmpty()) {
            server.scheduler.runTask(this, Runnable {
                if (isEnabled) {
                    initialPlayers.filter { it.isOnline }.forEach { expiration.joinOrLoadCharacter(it) }
                }
            })
        }
        pm.registerEvents(MagicItemUpdater.PersistentDataUpdater(), this)
        pm.registerEvents(MagicItemUpdater.ChunkMagicItemListener(), this)
        pm.registerEvents(MagicItemUpdater.CharacterPersistentDataUpdater(), this)
        pm.registerEvents(MagicItemBehaviorReceiveListener(), this)
        pm.registerEvents(OwnerDefinitionCleanupListener(), this)
        listenersRegistered = true
    }
}
