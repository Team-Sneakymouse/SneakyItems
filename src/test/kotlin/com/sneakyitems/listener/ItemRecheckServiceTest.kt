package com.sneakyitems.listener

import com.sneakyitems.SneakyItems
import com.sneakyitems.api.MagicItemRegistry
import com.sneakyitems.api.SneakyItemsAPI
import com.sneakyitems.events.SneakyItemsDefinitionsChangedEvent
import com.sneakyitems.platform.SneakyItemsPlatform
import com.sneakyitems.service.RecheckFilter
import com.sneakyitems.util.magicitems.MagicItemBehaviorKeys
import com.sneakyitems.util.magicitems.MagicItemData
import com.sneakyitems.util.magicitems.MagicItems
import com.sneakyitems.util.magicitems.MagicItemTagging
import com.sneakyitems.util.magicitems.MagicItemUpdater
import org.bukkit.block.Container
import org.bukkit.inventory.meta.BlockStateMeta
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
    fun `later definition rechecks leave legacy items for normal inventory processing`() = verifyLegacyPolicy(false)

    @Test
    fun `completed startup full check is not repeated by first late registration`() = verifyLegacyPolicy(true)

    private fun verifyLegacyPolicy(explicitStartup: Boolean) {
        // MockBukkit 4.117 lacks Inventory.getHolder(false); supply only that boundary.
        val player = object : org.mockbukkit.mockbukkit.entity.PlayerMock(server, "Recheck") {
            private val storage by lazy {
                object : org.mockbukkit.mockbukkit.inventory.PlayerInventoryMock(this) {
                    override fun getHolder(useSnapshot: Boolean) = getHolder()
                }
            }
            override fun getInventory(): org.bukkit.inventory.PlayerInventory = storage
            override fun getEnderChest(): org.bukkit.inventory.Inventory = ender
            private val ender by lazy {
                object : org.mockbukkit.mockbukkit.inventory.InventoryMock(this, org.bukkit.event.inventory.InventoryType.ENDER_CHEST) {
                    override fun getHolder(useSnapshot: Boolean) = getHolder()
                }
            }
        }
        server.addPlayer(player)
        val legacy = ItemStack(Material.STONE)
        val data = MagicItems.getMagicItemDataFromItemStack(legacy)!!
        data.ignoredAttributes.add(MagicItemData.MagicItemAttribute.AMOUNT)
        data.ignoredAttributes.add(MagicItemData.MagicItemAttribute.MAGIC_ITEM_NAME)
        MagicItemTagging.register(plugin, listOf(
            MagicItemTagging.TagMapping("legacy-recheck", data, null)))
        try {
            enableUpdates()
            if (explicitStartup) {
                player.inventory.setItem(0, legacy.clone())
                plugin.recheck.queueAllLoaded(RecheckFilter.All)
                server.scheduler.performOneTick()
                assertEquals("legacy-recheck", MagicItemBehaviorKeys.getMagicItemName(player.inventory.getItem(0)))
                player.inventory.setItem(0, legacy.clone())
                SneakyItemsAPI.registerFromStack(plugin, "magicspells", "recheck-late", template("Late"))
                server.scheduler.performTicks(MagicItemRegistry.changes.settleTicks + 2L)
                assertEquals(null, MagicItemBehaviorKeys.getMagicItemName(player.inventory.getItem(0)),
                    "a completed full startup pass must not be repeated for late definitions")
                return
            }
            player.inventory.setItem(0, legacy.clone())
            SneakyItemsAPI.registerFromStack(plugin, "magicspells", "recheck-first", template("First"))
            server.scheduler.performTicks(MagicItemRegistry.changes.settleTicks + 2L)
            assertEquals("legacy-recheck", MagicItemBehaviorKeys.getMagicItemName(player.inventory.getItem(0)))

            player.inventory.setItem(0, legacy.clone())
            player.enderChest.setItem(0, legacy.clone())
            player.inventory.setHelmet(legacy.clone())
            player.inventory.setItem(1, SneakyItemsAPI.getItemStack("recheck-first"))
            SneakyItemsAPI.registerFromStack(plugin, "magicspells", "recheck-first", template("Updated first"))
            SneakyItemsAPI.registerFromStack(plugin, "magicspells", "recheck-second", template("Second"))
            server.scheduler.performTicks(MagicItemRegistry.changes.settleTicks + 2L)
            assertEquals(null, MagicItemBehaviorKeys.getMagicItemName(player.inventory.getItem(0)),
                "definition-only rechecks must not match legacy items after the first pass")
            assertEquals(null, MagicItemBehaviorKeys.getMagicItemName(player.enderChest.getItem(0)))
            assertEquals(null, MagicItemBehaviorKeys.getMagicItemName(player.inventory.helmet))
            assertEquals(Component.text("Updated first"), player.inventory.getItem(1)!!.itemMeta.itemName())
            MagicItemUpdater.PersistentDataUpdater().joinOrLoadCharacter(player)
            assertEquals("legacy-recheck", MagicItemBehaviorKeys.getMagicItemName(player.inventory.getItem(0)))
            assertEquals("legacy-recheck", MagicItemBehaviorKeys.getMagicItemName(player.enderChest.getItem(0)))
            assertEquals("legacy-recheck", MagicItemBehaviorKeys.getMagicItemName(player.inventory.helmet))

            for (fullFirst in listOf(true, false)) {
                player.inventory.setItem(0, legacy.clone())
                if (fullFirst) plugin.recheck.queueAllLoaded(RecheckFilter.All)
                plugin.recheck.queueAllLoaded(RecheckFilter.All, false)
                if (!fullFirst) plugin.recheck.queueAllLoaded(RecheckFilter.All)
                server.scheduler.performOneTick()
                assertEquals("legacy-recheck", MagicItemBehaviorKeys.getMagicItemName(player.inventory.getItem(0)))
            }

            player.inventory.setItem(0, legacy.clone())
            MagicItemTagging.register(plugin, listOf(
                MagicItemTagging.TagMapping("legacy-changed", data, null)))
            SneakyItemsAPI.registerFromStack(plugin, "magicspells", "recheck-third", template("Third"))
            server.scheduler.performTicks(MagicItemRegistry.changes.settleTicks + 2L)
            assertEquals("legacy-changed", MagicItemBehaviorKeys.getMagicItemName(player.inventory.getItem(0)),
                "changed mappings require legacy matching even on a definition recheck")
        } finally {
            MagicItemTagging.unregister(plugin)
        }
    }

    @Test
    fun `rebuilt containers propagate update only mode without leaking into normal calls`() {
        val legacy = ItemStack(Material.STONE)
        val data = MagicItems.getMagicItemDataFromItemStack(legacy)!!
        data.ignoredAttributes.add(MagicItemData.MagicItemAttribute.AMOUNT)
        data.ignoredAttributes.add(MagicItemData.MagicItemAttribute.MAGIC_ITEM_NAME)
        MagicItemTagging.register(plugin, listOf(MagicItemTagging.TagMapping("nested-legacy", data, null)))
        try {
            enableUpdates()
            val box = ItemStack(Material.SHULKER_BOX)
            val meta = box.itemMeta as BlockStateMeta
            val state = meta.blockState as Container
            state.inventory.setItem(0, legacy.clone())
            meta.blockState = state
            meta.persistentDataContainer.set(MagicItemBehaviorKeys.magicItem(), PersistentDataType.STRING, "nested-box")
            box.itemMeta = meta
            val replacement = ItemStack(Material.SHULKER_BOX)
            val replacementMeta = replacement.itemMeta
            replacementMeta.itemName(Component.text("New box"))
            replacement.itemMeta = replacementMeta
            SneakyItemsAPI.registerFromStack(plugin, "magicspells", "nested-box", replacement)

            fun child(stack: ItemStack): ItemStack? =
                ((stack.itemMeta as BlockStateMeta).blockState as Container).inventory.getItem(0)

            val updated = MagicItemUpdater.processItemStack(box, null, false)
            assertEquals(Component.text("New box"), updated.itemMeta.itemName())
            assertEquals(null, MagicItemBehaviorKeys.getMagicItemName(child(updated)))
            val normal = MagicItemUpdater.processItemStack(box, null)
            assertEquals("nested-legacy", MagicItemBehaviorKeys.getMagicItemName(child(normal)))
            assertEquals("nested-legacy", MagicItemBehaviorKeys.getMagicItemName(MagicItemUpdater.processItemStack(legacy, null)))
        } finally {
            MagicItemTagging.unregister(plugin)
        }
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
