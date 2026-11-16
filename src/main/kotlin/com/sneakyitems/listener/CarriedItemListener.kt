package com.sneakyitems.listener

import com.sneakyitems.service.CarriedItemIndex
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent

/** Feeds [CarriedItemIndex]: scan on join, free on quit, dirty-mark on slot changes, death, respawn and world change. */
class CarriedItemListener(private val index: CarriedItemIndex) : Listener {

  @EventHandler(priority = EventPriority.MONITOR)
  fun onJoin(event: PlayerJoinEvent) = index.join(event.player)

  @EventHandler(priority = EventPriority.MONITOR)
  fun onQuit(event: PlayerQuitEvent) = index.quit(event.player.uniqueId)

  @EventHandler(priority = EventPriority.MONITOR)
  fun onSlotChange(event: PlayerInventorySlotChangeEvent) =
      index.onSlotChange(event.player, event.oldItemStack, event.newItemStack)

  // Respawn creates a new player entity with a fresh menu, and death clears the inventory: recount instead of
  // trusting the slot-change stream across those boundaries. A world change keeps the inventory; safety net only.
  @EventHandler(priority = EventPriority.MONITOR)
  fun onDeath(event: PlayerDeathEvent) = index.invalidate(event.entity.uniqueId)

  @EventHandler(priority = EventPriority.MONITOR)
  fun onRespawn(event: PlayerRespawnEvent) = index.invalidate(event.player.uniqueId)

  @EventHandler(priority = EventPriority.MONITOR)
  fun onWorldChange(event: PlayerChangedWorldEvent) = index.invalidate(event.player.uniqueId)
}
