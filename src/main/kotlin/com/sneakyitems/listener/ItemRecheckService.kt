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
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

/**
 * Owns every bulk item re-check: loaded item entities, item displays and online players.
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
        if (entity is Item || entity is ItemDisplay) entities.add(entity.uniqueId, filter)
    }

    /** Queues every loaded item entity and item display, and every online player. */
    fun queueAllLoaded(filter: RecheckFilter) {
        if (!MagicItemUpdater.shouldProcessItems()) return
        for (world in Bukkit.getWorlds()) {
            for (entity in world.getEntitiesByClasses(Item::class.java, ItemDisplay::class.java)) {
                entities.add(entity.uniqueId, filter)
            }
        }
        for (player in Bukkit.getOnlinePlayers()) players.add(player.uniqueId, RecheckFilter.All)
    }

    internal fun tick() {
        if (tracker.tick()) settle()
        if (tracker.pending) return
        if (entities.isEmpty() && players.isEmpty()) return
        if (!MagicItemUpdater.shouldProcessItems()) return

        val t = PerformanceDiagnostics.start()
        try {
            repeat(playersPerTick) {
                val (id, _) = players.poll() ?: return@repeat
                val player = Bukkit.getPlayer(id) ?: return@repeat
                MagicItemUpdater.processPlayerInventories(player)
                PerformanceDiagnostics.count("recheck.players")
            }
            repeat(entitiesPerTick) {
                val (id, filter) = entities.poll() ?: return@repeat
                val entity = Bukkit.getEntity(id)
                if (entity == null || !entity.isValid) {
                    PerformanceDiagnostics.count("recheck.entities-gone")
                    return@repeat
                }
                recheckEntity(entity, filter)
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
        queueAllLoaded(RecheckFilter.Names(changed))
    }

    private fun recheckEntity(entity: Entity, filter: RecheckFilter) {
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
        val updated = MagicItemUpdater.processItemStack(stack, null) ?: return
        if (updated === stack) return
        when (entity) {
            is Item -> entity.itemStack = updated
            is ItemDisplay -> entity.setItemStack(updated)
        }
    }

    companion object {
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
