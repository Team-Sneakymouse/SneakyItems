package com.sneakyitems.service

import com.sneakyitems.SneakyItems
import com.sneakyitems.api.SneakyItemsAPI
import com.sneakyitems.events.CarriedItemChangeEvent
import com.sneakyitems.util.magicitems.MagicItemBehaviorKeys
import com.sneakyitems.util.magicitems.MagicItemTagging
import com.sneakyitems.util.magicitems.MagicItemUpdater
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.HumanEntityMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.inventory.SimpleInventoryViewMock

/**
 * MockBukkit does not fire `PlayerInventorySlotChangeEvent` (Paper's server does, from the container menu), so these
 * tests change the inventory and then fire the event the way Paper would. What Paper really fires was checked by
 * reading its source, not by these tests.
 */
class CarriedItemIndexTest {

    private lateinit var server: ServerMock
    private lateinit var plugin: SneakyItems
    private lateinit var player: PlayerMock
    private val changes = mutableListOf<CarriedItemChangeEvent>()

    private val recorder = object : Listener {
        @EventHandler
        fun on(event: CarriedItemChangeEvent) {
            changes.add(event)
        }
    }

    @BeforeEach
    fun setup() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(SneakyItems::class.java)
        player = server.addPlayer()
        installConvertibleView()
    }

    /**
     * MockBukkit's default view throws from `convertSlot`, which the event's constructor calls, and `openInventory`
     * fires events SneakyItems cannot handle in MockBukkit. Swap the view in directly.
     */
    private fun installConvertibleView() {
        val view = object : SimpleInventoryViewMock(player, player.inventory, player.inventory, InventoryType.PLAYER) {
            override fun convertSlot(rawSlot: Int): Int = rawSlot
        }
        val field = HumanEntityMock::class.java.getDeclaredField("inventoryView")
        field.isAccessible = true
        field.set(player, view)
    }

    @AfterEach
    fun cleanup() {
        HandlerList.unregisterAll(recorder)
        MockBukkit.unmock()
    }

    private fun magic(id: String, amount: Int = 1, type: Material = Material.STICK): ItemStack {
        val stack = ItemStack(type, amount)
        stack.editPersistentDataContainer { it.set(MagicItemBehaviorKeys.magicItem(), PersistentDataType.STRING, id) }
        return stack
    }

    private fun listen() = server.pluginManager.registerEvents(recorder, plugin)

    private fun slotChanged(slot: Int, old: ItemStack?, new: ItemStack?) {
        server.pluginManager.callEvent(
            PlayerInventorySlotChangeEvent(player, slot, old?.clone() ?: ItemStack.empty(), new?.clone() ?: ItemStack.empty()),
        )
    }

    /** Sets [slot] and reports the change the way Paper would. */
    private fun put(slot: Int, stack: ItemStack?) {
        val old = player.inventory.getItem(slot)
        player.inventory.setItem(slot, stack)
        slotChanged(slot, old, stack)
    }

    private fun counts(vararg ids: String) = ids.associateWith { SneakyItemsAPI.carriedCount(player, it) }

    @Test
    fun `join scan counts storage, hotbar, armor and offhand`() {
        player.inventory.setItem(0, magic("wand"))
        player.inventory.setItem(20, magic("wand", 3))
        player.inventory.setHelmet(magic("crown"))
        player.inventory.setBoots(magic("boots"))
        player.inventory.setItemInOffHand(magic("tome", 2))
        player.inventory.setItem(5, ItemStack(Material.STONE, 64))
        server.pluginManager.callEvent(PlayerJoinEvent(player, Component.empty()))

        assertEquals(mapOf("wand" to 4, "crown" to 1, "boots" to 1, "tome" to 2, "none" to 0), counts("wand", "crown", "boots", "tome", "none"))
        assertEquals(setOf("wand", "crown", "boots", "tome"), SneakyItemsAPI.carriedIds(player))
        assertTrue(SneakyItemsAPI.carries(player, "tome"))
        assertFalse(SneakyItemsAPI.carries(player, "none"))
    }

    @Test
    fun `adding, removing and moving between slots`() {
        assertEquals(0, SneakyItemsAPI.carriedCount(player, "wand"))
        put(3, magic("wand"))
        assertEquals(1, SneakyItemsAPI.carriedCount(player, "wand"))

        // move 3 -> 30: the vacated slot and the new slot change one after the other
        val moved = player.inventory.getItem(3)!!
        put(3, null)
        put(30, moved)
        assertEquals(1, SneakyItemsAPI.carriedCount(player, "wand"))

        put(30, null)
        assertEquals(0, SneakyItemsAPI.carriedCount(player, "wand"))
        assertFalse(SneakyItemsAPI.carries(player, "wand"))
        assertTrue(SneakyItemsAPI.carriedIds(player).isEmpty())
    }

    @Test
    fun `armor and offhand slots are counted`() {
        player.inventory.setChestplate(magic("cuirass"))
        slotChanged(38, null, player.inventory.chestplate)
        player.inventory.setItemInOffHand(magic("tome"))
        slotChanged(40, null, player.inventory.itemInOffHand)
        assertEquals(1, SneakyItemsAPI.carriedCount(player, "cuirass"))
        assertEquals(1, SneakyItemsAPI.carriedCount(player, "tome"))
    }

    @Test
    fun `counts above one, stack merges and splits`() {
        put(1, magic("sand", 10))
        put(2, magic("sand", 20))
        assertEquals(30, SneakyItemsAPI.carriedCount(player, "sand"))

        // merge slot 2 into slot 1
        val before = player.inventory.getItem(1)!!
        player.inventory.setItem(1, magic("sand", 30))
        slotChanged(1, before, player.inventory.getItem(1))
        put(2, null)
        assertEquals(30, SneakyItemsAPI.carriedCount(player, "sand"))

        // split half off
        val full = player.inventory.getItem(1)!!
        player.inventory.setItem(1, magic("sand", 15))
        slotChanged(1, full, player.inventory.getItem(1))
        put(9, magic("sand", 15))
        assertEquals(30, SneakyItemsAPI.carriedCount(player, "sand"))
    }

    @Test
    fun `non-magic items are ignored, including ones with other PDC keys`() {
        put(0, ItemStack(Material.STONE, 5))
        val other = ItemStack(Material.STICK)
        other.editPersistentDataContainer { it.set(NamespacedKey("other", "thing"), PersistentDataType.STRING, "wand") }
        put(1, other)
        assertTrue(SneakyItemsAPI.carriedIds(player).isEmpty())
        assertEquals(0, SneakyItemsAPI.carriedCount(player, "wand"))
    }

    @Test
    fun `the legacy magicspells key counts when another namespace is active, as keyOf says`() {
        val old = ItemStack(Material.STICK)
        old.editPersistentDataContainer { it.set(NamespacedKey("magicspells", "magicitem"), PersistentDataType.STRING, "wand") }
        try {
            MagicItemBehaviorKeys.setPdcNamespace("sneakyother")
            assertEquals("wand", SneakyItemsAPI.keyOf(old))
            put(0, old)
            assertEquals(1, SneakyItemsAPI.carriedCount(player, "wand"))
        } finally {
            MagicItemBehaviorKeys.setPdcNamespace("magicspells")
        }
    }

    @Test
    fun `a stack matching a legacy mapping counts only once the updater stamps it`() {
        val template = ItemStack(Material.PAPER)
        template.editMeta { it.itemName(Component.text("Award")) }
        SneakyItemsAPI.registerFromStack(plugin, "magicspells", "carried-test-award", template)
        val awardKey = NamespacedKey("sneakyoffice", "carried_award")
        SneakyItemsAPI.registerLegacyKey(plugin, awardKey, "carried-test-award")
        try {
            val oldCopy = ItemStack(Material.PAPER)
            oldCopy.editMeta { it.itemName(Component.text("Award")) }
            oldCopy.editPersistentDataContainer { it.set(awardKey, PersistentDataType.BYTE, 1) }
            player.inventory.setItem(4, oldCopy)
            slotChanged(4, null, oldCopy)
            assertEquals(0, SneakyItemsAPI.carriedCount(player, "carried-test-award")) // not a magic item yet

            // what the updater does to a slot on join/open: stamp via the legacy mapping, rebuild, write back
            val tagged = MagicItemTagging.applyTagIfNeeded(oldCopy)
            assertTrue(tagged.tagged)
            val rebuilt = MagicItemUpdater.updateItem(tagged.stack, SneakyItemsAPI.getByInternalName("carried-test-award")!!)
            player.inventory.setItem(4, rebuilt)
            assertEquals("carried-test-award", SneakyItemsAPI.keyOf(player.inventory.getItem(4)))
            slotChanged(4, oldCopy, player.inventory.getItem(4)) // Paper reports the replaced stack
            assertEquals(1, SneakyItemsAPI.carriedCount(player, "carried-test-award"))
        } finally {
            SneakyItemsAPI.clearOwner(plugin)
        }
    }

    @Test
    fun `a spurious empty-to-stack event for an unchanged slot does not double count`() {
        player.inventory.setItem(7, magic("wand", 2))
        server.pluginManager.callEvent(PlayerJoinEvent(player, Component.empty()))
        assertEquals(2, SneakyItemsAPI.carriedCount(player, "wand"))
        // Paper reports every slot as empty -> stack when a container menu opens
        slotChanged(7, null, player.inventory.getItem(7))
        slotChanged(7, null, player.inventory.getItem(7))
        assertEquals(2, SneakyItemsAPI.carriedCount(player, "wand"))
    }

    @Test
    fun `the cursor is not carried`() {
        player.setItemOnCursor(magic("wand"))
        server.pluginManager.callEvent(PlayerJoinEvent(player, Component.empty()))
        assertEquals(0, SneakyItemsAPI.carriedCount(player, "wand"))
    }

    @Test
    fun `events carry the old and new counts, one per id, after the next tick`() {
        listen()
        server.pluginManager.callEvent(PlayerJoinEvent(player, Component.empty())) // empty inventory: nothing fires
        assertTrue(changes.isEmpty())

        put(0, magic("wand", 2))
        assertTrue(changes.isEmpty()) // deferred to the next scheduler pass
        server.scheduler.performOneTick()
        assertEquals(listOf(Triple("wand", 0, 2)), changes.map { Triple(it.itemId, it.oldCount, it.newCount) })
        assertEquals(player, changes[0].player)
        changes.clear()

        put(1, magic("wand", 3))
        put(2, magic("tome"))
        server.scheduler.performOneTick()
        assertEquals(
            setOf(Triple("wand", 2, 5), Triple("tome", 0, 1)),
            changes.map { Triple(it.itemId, it.oldCount, it.newCount) }.toSet(),
        )
        changes.clear()

        put(0, null)
        put(1, null)
        server.scheduler.performOneTick()
        assertEquals(listOf(Triple("wand", 5, 0)), changes.map { Triple(it.itemId, it.oldCount, it.newCount) })
        changes.clear()

        put(2, magic("tome")) // same count again: swapping a stack for an identical one changes nothing
        server.scheduler.performOneTick()
        assertTrue(changes.isEmpty())
    }

    @Test
    fun `a query flushes pending changes at once and the later tick fires nothing twice`() {
        listen()
        put(0, magic("wand"))
        assertEquals(1, SneakyItemsAPI.carriedCount(player, "wand"))
        assertEquals(1, changes.size)
        server.scheduler.performOneTick()
        assertEquals(1, changes.size)
    }

    @Test
    fun `the first scan of a player with items reports changes from zero`() {
        player.inventory.setItem(0, magic("wand", 2))
        listen()
        server.pluginManager.callEvent(PlayerJoinEvent(player, Component.empty()))
        assertEquals(listOf(Triple("wand", 0, 2)), changes.map { Triple(it.itemId, it.oldCount, it.newCount) })
    }

    @Test
    fun `with no listener nothing is queued and queries are still right`() {
        put(0, magic("wand"))
        put(1, magic("wand"))
        server.scheduler.performOneTick()
        assertEquals(2, SneakyItemsAPI.carriedCount(player, "wand"))
    }

    @Test
    fun `slot changes that involve no magic item never trigger a recount`() {
        listen()
        server.pluginManager.callEvent(PlayerJoinEvent(player, Component.empty()))
        player.inventory.setItem(0, magic("wand")) // not reported to the index
        slotChanged(10, null, ItemStack(Material.STONE))
        slotChanged(10, ItemStack(Material.STONE), ItemStack(Material.DIRT, 3))
        val other = ItemStack(Material.STICK)
        other.editPersistentDataContainer { it.set(NamespacedKey("other", "thing"), PersistentDataType.STRING, "x") }
        slotChanged(11, null, other)
        server.scheduler.performOneTick()
        assertTrue(changes.isEmpty())
        assertEquals(0, SneakyItemsAPI.carriedCount(player, "wand")) // cache untouched: no recount ran
        SneakyItemsAPI.refreshCarried(player)
        assertEquals(1, SneakyItemsAPI.carriedCount(player, "wand"))
    }

    @Test
    fun `a change of other data on the same magic stack does not recount`() {
        player.inventory.setItem(0, magic("wand"))
        server.pluginManager.callEvent(PlayerJoinEvent(player, Component.empty()))
        assertEquals(1, SneakyItemsAPI.carriedCount(player, "wand"))
        player.inventory.setItem(1, magic("wand")) // silent change, not reported
        slotChanged(0, magic("wand"), magic("wand")) // e.g. durability: same id, same amount
        assertEquals(1, SneakyItemsAPI.carriedCount(player, "wand")) // cache kept; only a reported change recounts
        SneakyItemsAPI.refreshCarried(player)
        assertEquals(2, SneakyItemsAPI.carriedCount(player, "wand"))
    }

    @Test
    fun `refreshCarried sees an unreported change and fires events`() {
        listen()
        server.pluginManager.callEvent(PlayerJoinEvent(player, Component.empty()))
        player.inventory.setItem(0, magic("wand"))
        assertEquals(0, SneakyItemsAPI.carriedCount(player, "wand"))
        SneakyItemsAPI.refreshCarried(player)
        assertEquals(1, SneakyItemsAPI.carriedCount(player, "wand"))
        assertEquals(1, changes.size)
    }

    @Test
    fun `the returned id set is a stable snapshot`() {
        put(0, magic("wand"))
        val before = SneakyItemsAPI.carriedIds(player)
        put(1, magic("tome"))
        assertEquals(setOf("wand"), before)
        assertEquals(setOf("wand", "tome"), SneakyItemsAPI.carriedIds(player))
        assertThrows(UnsupportedOperationException::class.java) { (before as MutableSet<String>).add("x") }
    }

    @Test
    fun `death and respawn force a recount`() {
        player.inventory.setItem(0, magic("wand"))
        server.pluginManager.callEvent(PlayerJoinEvent(player, Component.empty()))
        assertEquals(1, SneakyItemsAPI.carriedCount(player, "wand"))
        player.inventory.clear() // death without keepInventory; slot events may lag
        plugin.carried.invalidate(player.uniqueId)
        assertEquals(0, SneakyItemsAPI.carriedCount(player, "wand"))
    }

    @Test
    fun `state is freed on quit and not recreated for an offline player`() {
        put(0, magic("wand"))
        assertEquals(1, plugin.carried.trackedPlayers)
        server.pluginManager.callEvent(PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED))
        assertEquals(0, plugin.carried.trackedPlayers)

        player.disconnect()
        assertEquals(0, SneakyItemsAPI.carriedCount(player, "wand"))
        assertTrue(SneakyItemsAPI.carriedIds(player).isEmpty())
        assertEquals(0, plugin.carried.trackedPlayers)
        // late slot events for a gone player are ignored
        slotChanged(0, null, magic("wand"))
        assertEquals(0, plugin.carried.trackedPlayers)
    }

    @Test
    fun `players already online when the plugin enables are scanned`() {
        MockBukkit.unmock()
        server = MockBukkit.mock()
        player = server.addPlayer()
        installConvertibleView()
        player.inventory.setItem(0, magic("wand", 4))
        plugin = MockBukkit.load(SneakyItems::class.java)
        assertEquals(1, plugin.carried.trackedPlayers)
        assertEquals(4, SneakyItemsAPI.carriedCount(player, "wand"))
    }

    @Test
    fun `a listener that removes items and refreshes during dispatch cannot reorder transitions`() {
        player.inventory.setItem(0, magic("wand"))
        player.inventory.setItem(1, magic("tome"))
        var first = true
        val nested = object : Listener {
            @EventHandler
            fun on(event: CarriedItemChangeEvent) {
                changes.add(event)
                if (first) {
                    first = false
                    player.inventory.clear()
                    SneakyItemsAPI.refreshCarried(player) // deferred until the batch is delivered
                }
            }
        }
        server.pluginManager.registerEvents(nested, plugin)
        try {
            server.pluginManager.callEvent(PlayerJoinEvent(player, Component.empty())) // scan: wand 0->1, tome 0->1
        } finally {
            // keep the recorder list for the assertions; unregister afterwards
        }
        val seen = changes.map { Triple(it.itemId, it.oldCount, it.newCount) }
        HandlerList.unregisterAll(nested)
        // both additions reach the listener before either removal
        assertEquals(setOf(Triple("wand", 0, 1), Triple("tome", 0, 1)), seen.take(2).toSet())
        assertEquals(setOf(Triple("wand", 1, 0), Triple("tome", 1, 0)), seen.drop(2).toSet())
        assertEquals(4, seen.size)
        // per id the chain has no gaps
        for (id in listOf("wand", "tome")) {
            val chain = seen.filter { it.first == id }
            assertEquals(0, chain.first().second)
            chain.zipWithNext().forEach { (a, b) -> assertEquals(a.third, b.second) }
        }
        assertEquals(0, SneakyItemsAPI.carriedCount(player, "wand"))
        assertTrue(SneakyItemsAPI.carriedIds(player).isEmpty())
    }

    @Test
    fun `a listener that changes the inventory and refreshes gets chained events`() {
        player.inventory.setItem(0, magic("wand", 2))
        var first = true
        val nested = object : Listener {
            @EventHandler
            fun on(event: CarriedItemChangeEvent) {
                changes.add(event)
                if (first) {
                    first = false
                    player.inventory.setItem(0, magic("wand", 5))
                    SneakyItemsAPI.refreshCarried(player)
                }
            }
        }
        server.pluginManager.registerEvents(nested, plugin)
        server.pluginManager.callEvent(PlayerJoinEvent(player, Component.empty()))
        HandlerList.unregisterAll(nested)
        assertEquals(listOf(Triple("wand", 0, 2), Triple("wand", 2, 5)), changes.map { Triple(it.itemId, it.oldCount, it.newCount) })
    }

    @Test
    fun `changing the active namespace recounts on the next query`() {
        val nsA = NamespacedKey("carriedtesta", "magicitem")
        val stack = ItemStack(Material.STICK)
        stack.editPersistentDataContainer { it.set(nsA, PersistentDataType.STRING, "wand") }
        try {
            MagicItemBehaviorKeys.setPdcNamespace("carriedtesta")
            put(0, stack)
            assertEquals(1, SneakyItemsAPI.carriedCount(player, "wand"))
            MagicItemBehaviorKeys.setPdcNamespace("carriedtestb") // no inventory change, no slot event
            assertEquals(null, SneakyItemsAPI.keyOf(stack))
            assertEquals(0, SneakyItemsAPI.carriedCount(player, "wand"))
            MagicItemBehaviorKeys.setPdcNamespace("carriedtesta")
            assertEquals(1, SneakyItemsAPI.carriedCount(player, "wand"))
        } finally {
            MagicItemBehaviorKeys.setPdcNamespace("magicspells")
        }
    }

    @Test
    fun `changing the active namespace delivers the transitions to event-only consumers`() {
        val nsA = NamespacedKey("carriedtesta", "magicitem")
        val stack = ItemStack(Material.STICK)
        stack.editPersistentDataContainer { it.set(nsA, PersistentDataType.STRING, "wand") }
        try {
            MagicItemBehaviorKeys.setPdcNamespace("carriedtesta")
            player.inventory.setItem(0, stack)
            server.pluginManager.callEvent(PlayerJoinEvent(player, Component.empty()))
            listen()
            MagicItemBehaviorKeys.setPdcNamespace("carriedtestb")
            assertTrue(changes.isEmpty())
            server.scheduler.performOneTick() // nobody queried: the scheduled recount reports it
            assertEquals(listOf(Triple("wand", 1, 0)), changes.map { Triple(it.itemId, it.oldCount, it.newCount) })
        } finally {
            MagicItemBehaviorKeys.setPdcNamespace("magicspells")
        }
    }

    @Test
    fun `queries off the main thread fail fast`() {
        var failure: Throwable? = null
        val thread = Thread {
            try {
                SneakyItemsAPI.carriedCount(player, "wand")
            } catch (t: Throwable) {
                failure = t
            }
        }
        thread.start()
        thread.join()
        assertNotNull(failure)
        assertTrue(failure is IllegalStateException, failure.toString())

        var carriesFailure: Throwable? = null
        val other = Thread {
            try {
                SneakyItemsAPI.carriedIds(player)
            } catch (t: Throwable) {
                carriesFailure = t
            }
        }
        other.start()
        other.join()
        assertTrue(carriesFailure is IllegalStateException)
    }
}
