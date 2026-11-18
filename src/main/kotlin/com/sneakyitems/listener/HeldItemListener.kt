package com.sneakyitems.listener

import com.sneakyitems.service.HeldItemIndex
import io.papermc.paper.event.entity.EntityEquipmentChangedEvent
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockDispenseArmorEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerItemBreakEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent

/**
 * Feeds [HeldItemIndex]. Registered only once the index starts (the first `whileHolding`), so servers without such a
 * subscription don't pay for these handlers. All at MONITOR: the outcome, after other plugins changed or cancelled it.
 */
class HeldItemListener(private val index: HeldItemIndex) : Listener {

  @EventHandler(priority = EventPriority.MONITOR)
  fun onJoin(event: PlayerJoinEvent) = index.join(event.player)

  @EventHandler(priority = EventPriority.MONITOR)
  fun onQuit(event: PlayerQuitEvent) = index.quit(event.player.uniqueId)

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  fun onHeld(event: PlayerItemHeldEvent) = index.onHeldSlot(event.player, event.newSlot)

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  fun onSwap(event: PlayerSwapHandItemsEvent) = index.onSwap(event.player, event.mainHandItem, event.offHandItem)

  @EventHandler(priority = EventPriority.MONITOR)
  fun onSlotChange(event: PlayerInventorySlotChangeEvent) =
      index.onSlotChange(event.player, event.slot, event.newItemStack)

  @EventHandler(priority = EventPriority.MONITOR)
  fun onEquipment(event: EntityEquipmentChangedEvent) {
    val player = event.entity as? Player ?: return
    index.onEquipmentChanged(player, event.equipmentChanges.values.map { it.oldItem() to it.newItem() })
  }

  // Fired before the change is applied, or across a respawn: read the live inventory on the next tick.
  @EventHandler(priority = EventPriority.MONITOR)
  fun onBreak(event: PlayerItemBreakEvent) = index.verifyLater(event.player)

  @EventHandler(priority = EventPriority.MONITOR)
  fun onDeath(event: PlayerDeathEvent) = index.verifyLater(event.entity)

  @EventHandler(priority = EventPriority.MONITOR)
  fun onRespawn(event: PlayerRespawnEvent) = index.verifyLater(event.player)

  @EventHandler(priority = EventPriority.MONITOR)
  fun onWorldChange(event: PlayerChangedWorldEvent) = index.verifyLater(event.player)

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  fun onDispenseArmor(event: BlockDispenseArmorEvent) {
    val player = event.targetEntity as? Player ?: return
    index.verifyLater(player)
  }
}
