package com.sneakyitems.listener

import com.sneakyitems.api.MagicItemRegistry
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent

/** Drops magic-item definitions owned by a plugin when it disables or reloads. */
class OwnerDefinitionCleanupListener : Listener {

  @EventHandler
  fun onDisable(event: PluginDisableEvent) {
    val plugin = event.plugin
    if (plugin.name.equals("SneakyItems", ignoreCase = true)) return
    MagicItemRegistry.clearOwner(plugin)
  }
}
