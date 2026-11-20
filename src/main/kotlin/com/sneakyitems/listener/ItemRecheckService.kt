package com.sneakyitems.listener

import com.sneakyitems.events.SneakyItemsDefinitionsChangedEvent
import com.sneakyitems.events.SneakyItemsLoadedEvent
import com.sneakyitems.service.DefinitionChangeTracker
import com.sneakyitems.service.RecheckFilter
import com.sneakyitems.service.RecheckQueue
import com.sneakyitems.util.magicitems.MagicItemBehaviorKeys
import com.sneakyitems.util.magicitems.MagicItemTagging
import com.sneakyitems.util.magicitems.MagicItemUpdater
import com.sneakyitems.util.magicitems.MagicItems
import com.sneakyitems.util.performance.PerformanceDiagnostics
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

/**
 * Owns every bulk item re-check: loaded item entities, item displays, equipment worn or held by other living
 * entities (mobs, armor stands) and online players.
 *
 * Work is queued and drained a few entries per tick. Nothing drains while definitions are still
 * changing ([DefinitionChangeTracker.pending]). Scheduled tasks only start once the server ticks,
 * so entities queued during boot wait until startup is done.
 */
open class ItemRecheckService(
    private val plugin: Plugin,
    private val tracker: DefinitionChangeTracker,
) : Listener {

    private val entities = RecheckQueue<UUID>()
    private val players = RecheckQueue<UUID>()
    private var task: BukkitTask? = null
    private var firstDefinitionRecheck = true
    private var legacyRevision = MagicItemTagging.revision()

    var entitiesPerTick = 10
    var playersPerTick = 1

    val queuedEntities: Int get() = entities.size
    val queuedPlayers: Int get() = players.size

    fun start() {
        if (task != null) return
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 1L, 1L)
    }

    fun stop() {
        task?.cancel()
        task = null
        entities.clear()
        players.clear()
    }

    @EventHandler
    fun onEntitiesLoad(event: EntitiesLoadEvent) {
        if (!MagicItemUpdater.shouldProcessItems()) return
        for (entity in event.entities) queueEntity(entity, RecheckFilter.All)
    }

    /** SneakyItems enable or `/sneakyitems reload`: settings or legacy mappings may differ, so check everything. */
    @EventHandler
    fun onSneakyItemsLoaded(event: SneakyItemsLoadedEvent) {
        queueAllLoaded(RecheckFilter.All)
    }

    fun queueEntity(entity: Entity, filter: RecheckFilter) {
        if (holdsItems(entity)) entities.add(entity.uniqueId, filter)
    }

    /** Queues every loaded item entity, item display and equipped living entity, and every online player. */
    @JvmOverloads
    fun queueAllLoaded(filter: RecheckFilter, includeLegacy: Boolean = true) {
        if (!MagicItemUpdater.shouldProcessItems()) return
        if (filter == RecheckFilter.All && includeLegacy) firstDefinitionRecheck = false
        for (world in Bukkit.getWorlds()) {
            for (entity in world.getEntitiesByClasses(Item::class.java, ItemDisplay::class.java, LivingEntity::class.java)) {
                if (holdsItems(entity)) entities.add(entity.uniqueId, filter, includeLegacy)
            }
        }
        for (player in Bukkit.getOnlinePlayers()) players.add(player.uniqueId, RecheckFilter.All, includeLegacy)
    }

    internal fun tick() {
        val revision = MagicItemTagging.revision()
        if (revision != legacyRevision) {
            legacyRevision = revision
            queueAllLoaded(RecheckFilter.All)
        }
        if (tracker.tick()) settle()
        if (tracker.pending) return
        if (entities.isEmpty() && players.isEmpty()) return
        if (!MagicItemUpdater.shouldProcessItems()) return

        val t = PerformanceDiagnostics.start()
        try {
            repeat(playersPerTick) {
                val (id, _, includeLegacy) = players.poll() ?: return@repeat
                val player = Bukkit.getPlayer(id) ?: return@repeat
                MagicItemUpdater.processPlayerInventories(player, includeLegacy)
                PerformanceDiagnostics.count("recheck.players")
            }
            repeat(entitiesPerTick) {
                val (id, filter, includeLegacy) = entities.poll() ?: return@repeat
                val entity = Bukkit.getEntity(id)
                if (entity == null || !entity.isValid) {
                    PerformanceDiagnostics.count("recheck.entities-gone")
                    return@repeat
                }
                recheckEntity(entity, filter, includeLegacy)
            }
        } finally {
            PerformanceDiagnostics.end("task.recheck", t)
        }
    }

    private fun settle() {
        val versions = HashMap<String, Long>()
        for ((name, item) in MagicItems.getMagicItems()) versions[name] = item.definitionVersion
        val changed = tracker.settle(versions)
        if (changed.isEmpty()) return
        plugin.logger.info("${changed.size} magic item definition(s) changed; queueing item re-check.")
        Bukkit.getPluginManager().callEvent(SneakyItemsDefinitionsChangedEvent(changed))
        // Startup must migrate legacy stacks even when registration coalesces with the loaded event.
        queueAllLoaded(RecheckFilter.Names(changed), firstDefinitionRecheck)
        firstDefinitionRecheck = false
    }

    private fun recheckEntity(entity: Entity, filter: RecheckFilter, includeLegacy: Boolean) {
        if (entity is LivingEntity) {
            recheckEquipment(entity, filter, includeLegacy)
            return
        }
        val stack = when (entity) {
            is Item -> entity.itemStack
            is ItemDisplay -> entity.itemStack
            else -> return
        }
        if (!shouldCheck(stack, filter)) {
            PerformanceDiagnostics.count("recheck.entities-filtered")
            return
        }
        PerformanceDiagnostics.count("recheck.entities")
        val updated = MagicItemUpdater.processItemStack(stack, null, includeLegacy) ?: return
        if (updated === stack) return
        when (entity) {
            is Item -> entity.itemStack = updated
            is ItemDisplay -> entity.setItemStack(updated)
        }
    }

    private fun recheckEquipment(entity: LivingEntity, filter: RecheckFilter, includeLegacy: Boolean) {
        val equipment = entity.equipment ?: return
        PerformanceDiagnostics.count("recheck.equipment")
        for (slot in EQUIPMENT_SLOTS) {
            val stack = equipment.getItem(slot)
            if (!shouldCheck(stack, filter)) continue
            val updated = MagicItemUpdater.processItemStack(stack, null, includeLegacy) ?: continue
            if (updated !== stack) equipment.setItem(slot, updated)
        }
    }

    companion object {
        /**
         * Item entities and item displays always; other living entities only while they wear or hold something, so
         * empty-handed mobs never enter the queue. Real players have their own queue; player-type NPCs (Citizens
         * marks them with `NPC` metadata) are not online players, so they go through this one.
         */
        private fun holdsItems(entity: Entity): Boolean = when (entity) {
            is Item, is ItemDisplay -> true
            is Player if !entity.hasMetadata(NPC_METADATA) -> false
            is LivingEntity -> {
                val equipment = entity.equipment
                equipment != null && EQUIPMENT_SLOTS.any { !equipment.getItem(it).type.isAir }
            }
            else -> false
        }

        /** Metadata key Citizens sets on its NPC entities. */
        private const val NPC_METADATA = "NPC"

        /** Hands and armor, which every living entity has. Body/saddle slots (horse armor, saddles) are not checked. */
        private val EQUIPMENT_SLOTS = listOf(
            EquipmentSlot.HAND, EquipmentSlot.OFF_HAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
        )

        /**
         * A stack tagged with a registered name is only checked when that name changed. Untagged stacks
         * and unknown tags may be legacy items, so they are checked whenever legacy mappings exist.
         */
        @JvmStatic
        fun shouldCheck(stack: ItemStack, filter: RecheckFilter): Boolean {
            if (filter !is RecheckFilter.Names) return true
            if (stack.type.isAir) return false
            val name = MagicItemBehaviorKeys.getMagicItemName(stack)
            if (name != null && MagicItems.getMagicItems().containsKey(name)) return name in filter.names
            return MagicItemTagging.hasMappings()
        }
    }
}
