package com.sneakyitems.listener

import com.sneakyitems.SneakyItems
import com.sneakyitems.api.MagicItemRegistry
import com.sneakyitems.api.SneakyItemsAPI
import com.sneakyitems.events.SneakyItemsDefinitionsChangedEvent
import com.sneakyitems.platform.SneakyItemsPlatform
import com.sneakyitems.service.RecheckFilter
import com.sneakyitems.util.magicitems.MagicItemBehaviorKeys
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

class ItemRecheckServiceTest {

    private lateinit var server: ServerMock
    private lateinit var plugin: SneakyItems

    @BeforeEach
    fun setup() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(SneakyItems::class.java)
        // settle whatever earlier tests or enable registered
        server.scheduler.performTicks(MagicItemRegistry.changes.settleTicks + 2L)
    }

    @AfterEach
    fun cleanup() {
        SneakyItemsPlatform.setEnableUpdateItemData(false)
        MockBukkit.unmock()
    }

    /**
     * The test config turns updates off, and the queue only runs when they're on. Turned on after
     * spawning: the item-spawn listener calls getThrower, which MockBukkit doesn't implement.
     */
    private fun enableUpdates() = SneakyItemsPlatform.setEnableUpdateItemData(true)

    private fun template(name: String): ItemStack {
        val stack = ItemStack(Material.ENDER_EYE)
        val meta = stack.itemMeta
        meta.itemName(Component.text(name))
        stack.itemMeta = meta
        return stack
    }

    private fun tagged(name: String?): ItemStack {
        val stack = ItemStack(Material.STONE)
        if (name == null) return stack
        val meta = stack.itemMeta
        meta.persistentDataContainer.set(MagicItemBehaviorKeys.magicItem(), PersistentDataType.STRING, name)
        stack.itemMeta = meta
        return stack
    }

    @Test
    fun `a burst of registrations fires one change event after settling`() {
        val events = mutableListOf<SneakyItemsDefinitionsChangedEvent>()
        server.pluginManager.registerEvents(object : Listener {
            @EventHandler
            fun on(event: SneakyItemsDefinitionsChangedEvent) {
                events.add(event)
            }
        }, plugin)

        for (i in 1..50) SneakyItemsAPI.registerFromStack(plugin, "magicspells", "recheck-burst-$i", template("Item $i"))
        server.scheduler.performTicks(5)
        assertTrue(MagicItemRegistry.changes.pending)
        assertTrue(events.isEmpty())

        server.scheduler.performTicks(MagicItemRegistry.changes.settleTicks + 2L)
        assertEquals(1, events.size)
        assertTrue(events[0].changedNames.containsAll((1..50).map { "recheck-burst-$it" }))

        // registering the same definitions again changes nothing
        SneakyItemsAPI.clearOwner(plugin)
        for (i in 1..50) SneakyItemsAPI.registerFromStack(plugin, "magicspells", "recheck-burst-$i", template("Item $i"))
        server.scheduler.performTicks(MagicItemRegistry.changes.settleTicks + 2L)
        assertEquals(1, events.size)
    }

    @Test
    fun `queued entities drain at the per-tick limit`() {
        val world = server.addSimpleWorld("recheck")
        repeat(5) { world.dropItem(Location(world, 0.0, 64.0, 0.0), ItemStack(Material.STONE)) }
        enableUpdates()
        plugin.recheck.entitiesPerTick = 2
        plugin.recheck.queueAllLoaded(RecheckFilter.All)
        assertEquals(5, plugin.recheck.queuedEntities)

        server.scheduler.performOneTick()
        assertEquals(3, plugin.recheck.queuedEntities)
        server.scheduler.performTicks(2)
        assertEquals(0, plugin.recheck.queuedEntities)
    }

    @Test
    fun `nothing drains while definitions are still changing`() {
        val world = server.addSimpleWorld("recheck-pending")
        world.dropItem(Location(world, 0.0, 64.0, 0.0), ItemStack(Material.STONE))
        enableUpdates()
        plugin.recheck.queueAllLoaded(RecheckFilter.All)
        SneakyItemsAPI.registerFromStack(plugin, "magicspells", "recheck-pending", template("Pending"))

        server.scheduler.performTicks(5)
        assertEquals(1, plugin.recheck.queuedEntities)
        server.scheduler.performTicks(MagicItemRegistry.changes.settleTicks + 2L)
        assertEquals(0, plugin.recheck.queuedEntities)
    }

    @Test
    fun `name filter skips known items that did not change`() {
        SneakyItemsAPI.registerFromStack(plugin, "magicspells", "recheck-known", template("Known"))
        val filter = RecheckFilter.Names(setOf("recheck-other"))
        assertFalse(ItemRecheckService.shouldCheck(tagged("recheck-known"), filter))
        assertTrue(ItemRecheckService.shouldCheck(tagged("recheck-known"), RecheckFilter.Names(setOf("recheck-known"))))
        assertTrue(ItemRecheckService.shouldCheck(tagged("recheck-known"), RecheckFilter.All))
    }
}
