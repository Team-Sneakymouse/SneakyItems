package com.sneakyitems.listener

import com.sneakyitems.events.MagicItemClickEvent
import com.sneakyitems.events.MagicItemClickEvent.Click
import com.sneakyitems.events.MagicItemClickEvent.Trigger
import com.sneakyitems.util.magicitems.MagicItemBehaviorKeys
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerAnimationType
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.EnumSet
import java.util.UUID

/**
 * Fires [MagicItemClickEvent] for clicks with a magic item.
 *
 * Left clicks are a port of MagicSpells' `CastListener` (without the spellbook / spell-cycling
 * parts): fired a tick late so a drop or a right-click on a container/door/placed block can
 * suppress the arm swing that comes with it. Right clicks follow MagicSpells' `RightClickListener`.
 */
open class MagicItemClickListener(
    private val plugin: Plugin,
    private val clock: () -> Long = System::currentTimeMillis,
) : Listener {

    /** `click-events.cast-on-animate`: left clicks come from the arm-swing animation instead of interacts/melee. */
    var castOnAnimate = false

    private val noClickUntil = HashMap<UUID, Long>()

    // --- left click (CastListener) ---

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player

        if (event.action == Action.RIGHT_CLICK_BLOCK) {
            val clicked = event.clickedBlock
            val item = event.item
            if ((clicked != null && clicked.type in INTERACTABLE_BLOCKS) || (item != null && item.type.isBlock)) {
                // Container/door/placement: its arm swing is not a left click.
                suppressLeftClick(player)
                return
            }
        }

        if (event.action != Action.LEFT_CLICK_AIR && event.action != Action.LEFT_CLICK_BLOCK) return
        if (castOnAnimate) return
        val trigger = if (event.action == Action.LEFT_CLICK_BLOCK) Trigger.BLOCK else Trigger.AIR
        val block = event.clickedBlock
        val face = if (block != null) event.blockFace else null
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (player.isOnline && !isLeftClickSuppressed(player)) fireLeft(player, trigger, block, face, null)
        }, 1L)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerDamage(event: EntityDamageByEntityEvent) {
        if (castOnAnimate) return
        if (event.cause != DamageCause.ENTITY_ATTACK) return
        val player = event.damager as? Player ?: return
        if (player.uniqueId == event.entity.uniqueId) return
        if (isLeftClickSuppressed(player)) return

        val fired = fireLeft(player, Trigger.ENTITY, null, null, event.entity) ?: return
        // The same attack can also produce a left-click interact; don't report it twice.
        suppressLeftClick(player)
        if (fired.isCancelled) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerAnimation(event: PlayerAnimationEvent) {
        if (!castOnAnimate || event.animationType != PlayerAnimationType.ARM_SWING) return
        val player = event.player
        val type = player.openInventory.type
        if (type != InventoryType.CRAFTING && type != InventoryType.CREATIVE) return

        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (player.isOnline && !isLeftClickSuppressed(player)) fireLeft(player, Trigger.ARM_SWING, null, null, null)
        }, 1L)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerDrop(event: PlayerDropItemEvent) {
        suppressLeftClick(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        noClickUntil.remove(event.player.uniqueId)
    }

    // --- right click (RightClickListener) ---

    @EventHandler
    fun onRightClick(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        val item = event.item ?: return
        val hand = event.hand ?: return
        val trigger = if (event.action == Action.RIGHT_CLICK_BLOCK) Trigger.BLOCK else Trigger.AIR
        val block = event.clickedBlock
        val fired = fire(event.player, item, hand, Click.RIGHT, trigger, block, if (block != null) event.blockFace else null, null)
        if (fired?.isCancelled == true) event.isCancelled = true
    }

    @EventHandler
    fun onRightClickEntity(event: PlayerInteractEntityEvent) {
        val item = event.player.inventory.getItem(event.hand)
        val fired = fire(event.player, item, event.hand, Click.RIGHT, Trigger.ENTITY, null, null, event.rightClicked)
        if (fired?.isCancelled == true) event.isCancelled = true
    }

    // --- shared ---

    /** Ignore left clicks from [player] for [millis] (default 150 ms, as MagicSpells' `setNoCast`). */
    fun suppressLeftClick(player: Player, millis: Long = NO_CLICK_MILLIS) {
        noClickUntil[player.uniqueId] = clock() + millis
    }

    fun isLeftClickSuppressed(player: Player): Boolean {
        val until = noClickUntil[player.uniqueId] ?: return false
        return clock() < until
    }

    private fun fireLeft(player: Player, trigger: Trigger, block: Block?, face: BlockFace?, entity: Entity?): MagicItemClickEvent? =
        fire(player, player.inventory.itemInMainHand, EquipmentSlot.HAND, Click.LEFT, trigger, block, face, entity)

    private fun fire(
        player: Player,
        item: ItemStack?,
        hand: EquipmentSlot,
        click: Click,
        trigger: Trigger,
        block: Block?,
        face: BlockFace?,
        entity: Entity?,
    ): MagicItemClickEvent? {
        if (item == null || item.isEmpty) return null
        val key = MagicItemBehaviorKeys.readMagicItemName(item) ?: return null
        val event = MagicItemClickEvent(player, key, item, hand, click, trigger, block, face, entity)
        plugin.server.pluginManager.callEvent(event)
        return event
    }

    companion object {
        const val NO_CLICK_MILLIS = 150L

        /** MagicSpells `CastListener`'s "special block" list: right-clicking these swings the arm. */
        private val INTERACTABLE_BLOCKS: Set<Material> = EnumSet.noneOf(Material::class.java).apply {
            val exact = setOf(
                Material.BARREL, Material.BEACON, Material.BLAST_FURNACE, Material.BREWING_STAND,
                Material.CARTOGRAPHY_TABLE, Material.COMPARATOR, Material.CRAFTING_TABLE,
                Material.DAYLIGHT_DETECTOR, Material.DISPENSER, Material.DROPPER,
                Material.ENCHANTING_TABLE, Material.ENDER_CHEST, Material.FURNACE, Material.GRINDSTONE,
                Material.HOPPER, Material.LEVER, Material.LOOM, Material.NOTE_BLOCK,
                Material.POLISHED_BLACKSTONE_BUTTON, Material.REPEATER, Material.SMITHING_TABLE,
                Material.SMOKER, Material.STONECUTTER, Material.CHEST, Material.TRAPPED_CHEST,
            )
            for (m in Material.entries) {
                if (m.isLegacy) continue
                val n = m.name
                if (m in exact || n.contains("_DOOR") || n.contains("_FENCE_GATE") || n.contains("_TRAPDOOR") ||
                    n.contains("SHULKER_BOX") || n.contains("_BUTTON") || n.contains("_BED") || n.contains("ANVIL")
                ) add(m)
            }
        }
    }
}
