package com.sneakyitems

import com.sneakyitems.commands.ReloadCommand
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
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
    }

    override fun onEnable() {
        saveDefaultConfig()
        reloadPlugin()
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(ReloadCommand(this).create(), "Reload the plugin configuration")
        }
        logger.info("SneakyItems enabled.")
    }

    /** Shared configuration entry point for startup and reload. */
    fun reloadPlugin() {
        reloadConfig()
    }

    override fun onDisable() {
        logger.info("SneakyItems disabled.")
    }
}
