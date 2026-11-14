package com.sneakyitems.listener

import com.sneakyitems.events.MagicItemClickEvent
import com.sneakyitems.events.MagicItemClickEvent.Click
import com.sneakyitems.events.MagicItemClickEvent.Trigger
import com.sneakyitems.events.MagicItemSwingEvent
import com.sneakyitems.util.magicitems.MagicItemBehaviorKeys
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
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
 * Fires [MagicItemClickEvent] and [MagicItemSwingEvent] for a player holding a magic item.
 *
 * Left clicks start from MagicSpells' `CastListener` (without the spellbook / spell-cycling parts). Clicks known only
 * from the arm swing (left-click air, or the swing itself with `cast-on-animate`) fire a tick late, because the swing
 * of a drop arrives before the drop: a drop, a right-click on a container/door/placed block, or a block/entity hit
 * around the same tick means that swing was not a click of its own. Block hits (dig packet) and entity attacks
 * (attack packet) are never swing artefacts, so they fire at once and can be cancelled. Right clicks follow
 * MagicSpells' `RightClickListener`, reporting one physical click once.
 */
open class MagicItemClickListener(
    private val plugin: Plugin,
    private val clock: () -> Long = System::currentTimeMillis,
) : Listener {

    /** `click-events.cast-on-animate`: swing-only left clicks come from the arm-swing animation instead of left-click-air interacts. */
    var castOnAnimate = false

    /** Swing-derived left clicks are ignored until then (drops, container/door/placement right-clicks). */
    private val noClickUntil = HashMap<UUID, Long>()

    /** Swing events are ignored until then (drops). */
    private val noSwingUntil = HashMap<UUID, Long>()

    /** Tick of the last block/entity left click: the swing that came with it is not another click. */
    private val lastHitTick = HashMap<UUID, Int>()

    /** Last reported entity right click: the client's interact-at/interact pair and follow-up use click are the same click. */
    private val lastEntityRight = HashMap<UUID, EntityRight>()

    private data class EntityRight(val tick: Int, val hand: EquipmentSlot, val cancelled: Boolean)

    private val tick: Int get() = plugin.server.currentTick

    // --- interacts: left block hits and right clicks fire at once ---

    @EventHandler(priority = EventPriority.NORMAL)
    fun onInteract(event: PlayerInteractEvent) {
        val player = event.player
        when (event.action) {
            Action.LEFT_CLICK_BLOCK -> {
                val fired = fireInteract(event, player.inventory.itemInMainHand, EquipmentSlot.HAND, Click.LEFT) ?: return
                lastHitTick[player.uniqueId] = tick
                if (fired.isCancelled) event.isCancelled = true
            }
            Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK -> {
                val hand = event.hand ?: return
                val item = event.item ?: return
                val prev = lastEntityRight[player.uniqueId]
                if (prev != null && prev.hand == hand && tick - prev.tick <= 1) {
                    // the client's use click after an entity click: same click, already reported
                    if (prev.cancelled) event.isCancelled = true
                    return
                }
                val fired = fireInteract(event, item, hand, Click.RIGHT) ?: return
                if (fired.isCancelled) event.isCancelled = true
            }
            else -> {}
        }
    }

    private fun fireInteract(event: PlayerInteractEvent, item: ItemStack, hand: EquipmentSlot, click: Click): MagicItemClickEvent? {
        val block = event.clickedBlock
        val trigger = if (block != null) Trigger.BLOCK else Trigger.AIR
        return fire(
            event.player, item, hand, click, trigger, block, if (block != null) event.blockFace else null, null,
            interact = event, alreadyDenied = event.useItemInHand() == Event.Result.DENY,
        )
    }

    /** Swing-derived bookkeeping after everyone has had their say on the interact (MagicSpells `CastListener`). */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onInteractMonitor(event: PlayerInteractEvent) {
        val player = event.player

        if (event.action == Action.RIGHT_CLICK_BLOCK) {
            val clicked = event.clickedBlock
            val item = event.item
            if ((clicked != null && clicked.type in INTERACTABLE_BLOCKS) || (item != null && item.type.isBlock)) {
                // container/door/placement: its arm swing is not a left click
                suppressLeftClick(player)
                return
            }
        }

        if (event.action != Action.LEFT_CLICK_AIR || castOnAnimate) return
        val at = tick
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (player.isOnline && !isSwingClickSuppressed(player, at)) fireLeft(player, Trigger.AIR, null)
        }, 1L)
    }

    // --- entities ---

    /** Also receives `PlayerInteractAtEntityEvent` (it shares this handler list); the pair counts once. */
    @EventHandler(priority = EventPriority.NORMAL)
    fun onRightClickEntity(event: PlayerInteractEntityEvent) {
        val player = event.player
        val hand = event.hand
        val prev = lastEntityRight[player.uniqueId]
        if (prev != null && prev.hand == hand && tick - prev.tick <= 1) {
            if (prev.cancelled) event.isCancelled = true
            return
        }
        val item = player.inventory.getItem(hand)
        val fired = fire(
            player, item, hand, Click.RIGHT, Trigger.ENTITY, null, null, event.rightClicked,
            interact = null, alreadyDenied = event.isCancelled,
        ) ?: return
        if (fired.isCancelled) event.isCancelled = true
        lastEntityRight[player.uniqueId] = EntityRight(tick, hand, fired.isCancelled)
    }

    /** Any attack: mobs, players, and entities that take no damage (interaction hitboxes, item frames, displays). */
    @EventHandler(priority = EventPriority.NORMAL)
    fun onAttack(event: PrePlayerAttackEntityEvent) {
        val player = event.player
        val fired = fire(
            player, player.inventory.itemInMainHand, EquipmentSlot.HAND, Click.LEFT, Trigger.ENTITY, null, null,
            event.attacked, interact = null, alreadyDenied = event.isCancelled,
        ) ?: return
        lastHitTick[player.uniqueId] = tick
        if (fired.isCancelled) event.isCancelled = true
    }

    // --- swings ---

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerAnimation(event: PlayerAnimationEvent) {
        if (event.animationType != PlayerAnimationType.ARM_SWING) return
        val player = event.player
        val type = player.openInventory.type
        val noScreen = type == InventoryType.CRAFTING || type == InventoryType.CREATIVE
        val at = tick
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (!player.isOnline) return@Runnable
            if (!isSwingSuppressed(player)) fireSwing(player)
            if (castOnAnimate && noScreen && !isSwingClickSuppressed(player, at)) fireLeft(player, Trigger.ARM_SWING, null)
        }, 1L)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerDrop(event: PlayerDropItemEvent) {
        suppressLeftClick(event.player)
        noSwingUntil[event.player.uniqueId] = clock() + NO_CLICK_MILLIS
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val id = event.player.uniqueId
        noClickUntil.remove(id)
        noSwingUntil.remove(id)
        lastHitTick.remove(id)
        lastEntityRight.remove(id)
    }

    // --- shared ---

    /** Ignore swing-derived left clicks from [player] for [millis] (default 150 ms, as MagicSpells' `setNoCast`). */
    fun suppressLeftClick(player: Player, millis: Long = NO_CLICK_MILLIS) {
        noClickUntil[player.uniqueId] = clock() + millis
    }

    /** Whether a swing-derived left click (air / arm swing) seen at [swingTick] is not a click of its own. */
    fun isSwingClickSuppressed(player: Player, swingTick: Int): Boolean {
        val until = noClickUntil[player.uniqueId]
        if (until != null && clock() < until) return true
        val hit = lastHitTick[player.uniqueId] ?: return false
        return kotlin.math.abs(swingTick - hit) <= 1
    }

    private fun isSwingSuppressed(player: Player): Boolean {
        val until = noSwingUntil[player.uniqueId] ?: return false
        return clock() < until
    }

    private fun fireLeft(player: Player, trigger: Trigger, entity: Entity?): MagicItemClickEvent? =
        fire(
            player, player.inventory.itemInMainHand, EquipmentSlot.HAND, Click.LEFT, trigger, null, null, entity,
            interact = null, alreadyDenied = false,
        )

    private fun fireSwing(player: Player) {
        val item = player.inventory.itemInMainHand
        if (item.isEmpty) return
        val key = MagicItemBehaviorKeys.readMagicItemName(item) ?: return
        plugin.server.pluginManager.callEvent(MagicItemSwingEvent(player, key, item))
    }

    private fun fire(
        player: Player,
        item: ItemStack?,
        hand: EquipmentSlot,
        click: Click,
        trigger: Trigger,
        block: Block?,
        face: BlockFace?,
        entity: Entity?,
        interact: PlayerInteractEvent?,
        alreadyDenied: Boolean,
    ): MagicItemClickEvent? {
        if (item == null || item.isEmpty) return null
        val key = MagicItemBehaviorKeys.readMagicItemName(item) ?: return null
        val event = MagicItemClickEvent(player, key, item, hand, click, trigger, block, face, entity, interact, alreadyDenied)
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
