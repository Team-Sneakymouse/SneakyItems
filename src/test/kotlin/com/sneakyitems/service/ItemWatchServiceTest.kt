package com.sneakyitems.service

import com.sneakyitems.SneakyItems
import com.sneakyitems.api.HeldSlot
import com.sneakyitems.api.ItemWatch
import com.sneakyitems.api.SneakyItemsAPI
import com.sneakyitems.events.CarriedItemChangeEvent
import com.sneakyitems.util.magicitems.MagicItemBehaviorKeys
import com.sneakyutils.SneakyUtils
import com.sneakyutils.triggers.PlayerScope
import com.sneakyutils.triggers.PlayerTimerOptions
import io.papermc.paper.event.entity.EntityEquipmentChangedEvent
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent
import org.bukkit.GameRule
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockDispenseArmorEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerItemBreakEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.HumanEntityMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.inventory.SimpleInventoryViewMock
import org.mockbukkit.mockbukkit.world.WorldMock

/**
 * `whileCarrying` / `whileHolding` on the real SneakyUtils trigger service in MockBukkit.
 *
 * MockBukkit fires none of `PlayerInventorySlotChangeEvent`, `EntityEquipmentChangedEvent`, `PlayerItemHeldEvent`,
 * `PlayerSwapHandItemsEvent`, `PlayerItemBreakEvent` or `BlockDispenseArmorEvent` itself, so these tests change the
 * inventory and fire the events in the order Paper does (held slot, swap, break and dispense before the change; slot
 * and equipment changes after it). That order comes from reading Paper's behaviour, not from these tests.
 */
class ItemWatchServiceTest {

    private lateinit var server: ServerMock
    private lateinit var world: WorldMock
    private lateinit var plugin: SneakyItems
    private lateinit var owner: Plugin
    private lateinit var player: PlayerMock

    @BeforeEach
    fun setup() {
        server = MockBukkit.mock()
        world = server.addSimpleWorld("world")
        MockBukkit.load(SneakyUtils::class.java)
        plugin = MockBukkit.load(SneakyItems::class.java)
        owner = MockBukkit.createMockPlugin("Consumer")
        player = addPlayer("alice")
    }

    @AfterEach
    fun cleanup() = MockBukkit.unmock()

    private fun addPlayer(name: String): PlayerMock {
        val p = server.addPlayer(name)
        installConvertibleView(p)
        return p
    }

    /** MockBukkit's default view throws from `convertSlot`, which the slot-change event's constructor calls. */
    private fun installConvertibleView(p: PlayerMock) {
        val view = object : SimpleInventoryViewMock(p, p.inventory, p.inventory, InventoryType.PLAYER) {
            override fun convertSlot(rawSlot: Int): Int = rawSlot
        }
        val field = HumanEntityMock::class.java.getDeclaredField("inventoryView")
        field.isAccessible = true
        field.set(p, view)
    }

    private fun magic(id: String, amount: Int = 1, type: Material = Material.STICK): ItemStack {
        val stack = ItemStack(type, amount)
        stack.editPersistentDataContainer { it.set(MagicItemBehaviorKeys.magicItem(), PersistentDataType.STRING, id) }
        return stack
    }

    /** Sets [slot] and reports it the way Paper does when it syncs the menu. */
    private fun put(slot: Int, stack: ItemStack?, p: PlayerMock = player) {
        val old = p.inventory.getItem(slot)
        p.inventory.setItem(slot, stack)
        server.pluginManager.callEvent(
            PlayerInventorySlotChangeEvent(p, slot, old?.clone() ?: ItemStack.empty(), stack?.clone() ?: ItemStack.empty()),
        )
    }

    private fun keepInventory(keep: Boolean) {
        player.world.setGameRule(GameRule.KEEP_INVENTORY, keep)
    }

    private fun tick(n: Int = 1) = server.scheduler.performTicks(n.toLong())

    private class Calls {
        val byPlayer = HashMap<String, Int>()
        fun of(p: PlayerMock) = byPlayer[p.name] ?: 0
    }

    private fun carrying(id: String, calls: Calls = Calls(), interval: Int = 1): Pair<ItemWatch, Calls> =
        SneakyItemsAPI.whileCarrying(owner, id, interval, { p -> calls.byPlayer.merge(p.name, 1, Int::plus) }) to calls

    private fun holding(id: String, where: HeldSlot, calls: Calls = Calls(), interval: Int = 1): Pair<ItemWatch, Calls> =
        SneakyItemsAPI.whileHolding(owner, id, where, interval, { p -> calls.byPlayer.merge(p.name, 1, Int::plus) }) to calls

    // --- whileCarrying ---

    @Test
    fun `carrying - pickup adds on the next pass, drop removes, players without it are never called`() {
        val bob = addPlayer("bob")
        val (sub, calls) = carrying("wand")
        tick(5)
        assertEquals(0, sub.size)
        assertEquals(emptyMap<String, Int>(), calls.byPlayer)

        put(20, magic("wand")) // pickup into storage
        tick()
        assertTrue(sub.contains(player))
        assertFalse(sub.contains(bob))
        val before = calls.of(player)
        tick(3)
        assertEquals(before + 3, calls.of(player), "called every tick while carried (interval 1)")
        assertEquals(0, calls.of(bob))

        put(20, null) // drop
        tick()
        assertFalse(sub.contains(player))
        val after = calls.of(player)
        tick(5)
        assertEquals(after, calls.of(player))
    }

    @Test
    fun `carrying - players already carrying are added at once, armor and offhand count, moves keep membership`() {
        put(39, magic("crown"))
        val (sub, calls) = carrying("crown")
        assertTrue(sub.contains(player))
        tick()
        assertEquals(1, calls.of(player))

        // move helmet -> storage: vacated slot then new slot
        val crown = player.inventory.helmet!!
        put(39, null)
        put(10, crown)
        tick()
        assertTrue(sub.contains(player))
        put(10, null)
        put(40, crown)
        tick()
        assertTrue(sub.contains(player))
        put(40, null)
        tick()
        assertFalse(sub.contains(player))
    }

    @Test
    fun `carrying - quit removes, rejoin adds again`() {
        put(0, magic("wand"))
        val (sub, _) = carrying("wand")
        assertTrue(sub.contains(player))
        player.disconnect()
        assertFalse(sub.contains(player))
        assertEquals(0, sub.size)
        player.reconnect()
        assertTrue(player.isOnline)
        tick()
        assertTrue(sub.contains(player), "the join scan reports the wand again")
    }

    @Test
    fun `carrying - death that drops the item removes, keepInventory death pauses until respawn`() {
        put(0, magic("wand"))
        val (sub, calls) = carrying("wand")
        tick()
        assertEquals(1, calls.of(player))

        // keepInventory: still carried, still in the timer, but paused while dead (SneakyUtils' skipDead default)
        keepInventory(true)
        player.health = 0.0
        assertTrue(player.isDead)
        tick(3)
        assertTrue(sub.contains(player))
        assertEquals(1, calls.of(player))
        player.respawn()
        tick()
        assertEquals(2, calls.of(player))

        // death without keepInventory: the inventory is emptied
        keepInventory(false)
        player.health = 0.0
        player.inventory.clear()
        tick()
        assertFalse(sub.contains(player))
        player.respawn()
        tick(3)
        assertFalse(sub.contains(player))
        assertEquals(2, calls.of(player))
    }

    @Test
    fun `carrying - skipDead false keeps calling a dead carrier`() {
        put(0, magic("wand"))
        keepInventory(true)
        var calls = 0
        val sub = SneakyItemsAPI.whileCarrying(owner, "wand", 1, { calls++ }, PlayerTimerOptions.builder().skipDead(false).label("test:wand").build())
        player.health = 0.0
        tick(3)
        assertEquals(3, calls)
        assertEquals("test:wand", sub.label)
        assertTrue(sub.timerId.startsWith("Consumer:"))
    }

    // --- whileHolding ---

    @Test
    fun `holding - hotbar scroll adds in the event itself, scroll away removes, cancelled scroll does nothing`() {
        player.inventory.heldItemSlot = 0
        player.inventory.setItem(3, magic("wand"))
        val (main, calls) = holding("wand", HeldSlot.MAIN_HAND)
        val (any, _) = holding("wand", HeldSlot.ANY)
        assertFalse(main.contains(player))
        assertFalse(any.contains(player))

        val cancelled = PlayerItemHeldEvent(player, 0, 3).apply { isCancelled = true }
        server.pluginManager.callEvent(cancelled)
        tick()
        assertFalse(main.contains(player))

        server.pluginManager.callEvent(PlayerItemHeldEvent(player, 0, 3)) // fired before the slot changes
        assertTrue(main.contains(player))
        assertTrue(any.contains(player))
        player.inventory.heldItemSlot = 3
        tick()
        assertTrue(main.contains(player), "the next-tick check against the live inventory agrees")
        assertEquals(1, calls.of(player))

        server.pluginManager.callEvent(PlayerItemHeldEvent(player, 3, 4))
        assertFalse(main.contains(player))
        player.inventory.heldItemSlot = 4
        tick(3)
        assertFalse(main.contains(player))
        assertEquals(1, calls.of(player))
    }

    @Test
    fun `holding - swap hands moves membership between main and off hand`() {
        player.inventory.heldItemSlot = 0
        put(0, magic("tome"))
        val (main, _) = holding("tome", HeldSlot.MAIN_HAND)
        val (off, _) = holding("tome", HeldSlot.OFF_HAND)
        val (either, _) = holding("tome", HeldSlot.EITHER_HAND)
        assertTrue(main.contains(player))
        assertFalse(off.contains(player))

        val tome = player.inventory.itemInMainHand.clone()
        server.pluginManager.callEvent(PlayerSwapHandItemsEvent(player, ItemStack.empty(), tome)) // before the swap
        assertFalse(main.contains(player))
        assertTrue(off.contains(player))
        assertTrue(either.contains(player))
        player.inventory.setItemInMainHand(null)
        player.inventory.setItemInOffHand(tome)
        tick()
        assertFalse(main.contains(player))
        assertTrue(off.contains(player))

        // a cancelled swap changes nothing
        val cancelled = PlayerSwapHandItemsEvent(player, tome, ItemStack.empty()).apply { isCancelled = true }
        server.pluginManager.callEvent(cancelled)
        tick()
        assertTrue(off.contains(player))
        assertFalse(main.contains(player))
    }

    @Test
    fun `holding - moving items in the inventory and pickup into the held slot`() {
        player.inventory.heldItemSlot = 2
        val (main, _) = holding("wand", HeldSlot.MAIN_HAND)
        val (carried, _) = carrying("wand")
        put(20, magic("wand"))
        tick()
        assertFalse(main.contains(player))
        assertTrue(carried.contains(player))

        val wand = player.inventory.getItem(20)!!
        put(20, null)
        put(2, wand) // into the selected hotbar slot
        assertTrue(main.contains(player), "slot changes of the held slot apply in the event")
        put(2, null)
        put(5, wand) // another hotbar slot, not selected
        assertFalse(main.contains(player))
        tick()
        assertTrue(carried.contains(player))
    }

    @Test
    fun `holding - spurious and stale slot events follow the live inventory`() {
        player.inventory.heldItemSlot = 0
        player.inventory.setItem(0, magic("wand"))
        val (main, _) = holding("wand", HeldSlot.MAIN_HAND)
        assertTrue(main.contains(player))

        // a container opening re-reports unchanged slots as "empty -> stack"
        server.pluginManager.callEvent(PlayerInventorySlotChangeEvent(player, 0, ItemStack.empty(), magic("wand")))
        assertTrue(main.contains(player))

        // a stale event saying the wand arrived while the live slot is already empty again
        player.inventory.setItem(0, null)
        server.pluginManager.callEvent(PlayerInventorySlotChangeEvent(player, 0, ItemStack.empty(), magic("wand")))
        assertFalse(main.contains(player))
        tick()
        assertFalse(main.contains(player))
    }

    @Test
    fun `holding - armor equip and unequip by slot change, equipment change and dispenser`() {
        val (armor, calls) = holding("crown", HeldSlot.ARMOR)
        val (any, _) = holding("crown", HeldSlot.ANY)
        val (hands, _) = holding("crown", HeldSlot.EITHER_HAND)

        put(39, magic("crown")) // inventory drag or shift-click
        assertTrue(armor.contains(player))
        assertTrue(any.contains(player))
        assertFalse(hands.contains(player))
        tick()
        assertEquals(1, calls.of(player))
        put(39, null)
        assertFalse(armor.contains(player))

        // right-click equip seen through Paper's equipment comparison (fired after the change)
        val crown = magic("crown")
        player.inventory.setHelmet(crown)
        val change = object : EntityEquipmentChangedEvent.EquipmentChange {
            override fun oldItem() = ItemStack.empty()
            override fun newItem() = crown
        }
        server.pluginManager.callEvent(EntityEquipmentChangedEvent(player, mapOf(EquipmentSlot.HEAD to change)))
        assertTrue(armor.contains(player))
        player.inventory.setHelmet(null)
        val removal = object : EntityEquipmentChangedEvent.EquipmentChange {
            override fun oldItem() = crown
            override fun newItem() = ItemStack.empty()
        }
        server.pluginManager.callEvent(EntityEquipmentChangedEvent(player, mapOf(EquipmentSlot.HEAD to removal)))
        assertFalse(armor.contains(player))

        // dispenser equip: the event fires before the armor is put on; checked against the live inventory next tick
        server.pluginManager.callEvent(BlockDispenseArmorEvent(world.getBlockAt(0, 64, 0), crown, player))
        player.inventory.setHelmet(crown)
        assertFalse(armor.contains(player))
        tick()
        assertTrue(armor.contains(player))
    }

    @Test
    fun `holding - durability-only equipment changes don't resync`() {
        player.inventory.heldItemSlot = 0
        val sword = magic("blade", type = Material.IRON_SWORD)
        player.inventory.setItem(0, sword)
        val (main, _) = holding("blade", HeldSlot.MAIN_HAND)
        assertTrue(main.contains(player))
        // the live slot is swapped behind the index's back; an id-preserving change event must not look at it
        player.inventory.setItem(0, null)
        val damaged = sword.clone()
        val change = object : EntityEquipmentChangedEvent.EquipmentChange {
            override fun oldItem() = sword
            override fun newItem() = damaged
        }
        server.pluginManager.callEvent(EntityEquipmentChangedEvent(player, mapOf(EquipmentSlot.HAND to change)))
        assertTrue(main.contains(player))
    }

    @Test
    fun `holding - item break removes on the next tick`() {
        player.inventory.heldItemSlot = 0
        val pick = magic("pick", type = Material.IRON_PICKAXE)
        player.inventory.setItem(0, pick)
        val (main, _) = holding("pick", HeldSlot.MAIN_HAND)
        assertTrue(main.contains(player))
        server.pluginManager.callEvent(PlayerItemBreakEvent(player, pick)) // before the stack shrinks
        player.inventory.setItem(0, null)
        tick()
        assertFalse(main.contains(player))
    }

    @Test
    fun `holding - death, respawn, quit, rejoin and world change`() {
        player.inventory.heldItemSlot = 0
        player.inventory.setItem(0, magic("wand"))
        val (main, calls) = holding("wand", HeldSlot.MAIN_HAND)
        tick()
        assertEquals(1, calls.of(player))

        // keepInventory death: paused, then called again after respawn
        keepInventory(true)
        player.health = 0.0
        tick(2)
        assertTrue(main.contains(player))
        assertEquals(1, calls.of(player))
        player.respawn()
        tick()
        assertEquals(2, calls.of(player))

        // death that empties the inventory
        keepInventory(false)
        player.health = 0.0
        player.inventory.clear()
        tick()
        assertFalse(main.contains(player))
        player.respawn()
        tick()
        assertFalse(main.contains(player))

        // quit and rejoin with the wand in hand
        player.inventory.setItem(0, magic("wand"))
        player.disconnect()
        assertFalse(main.contains(player))
        player.reconnect()
        assertTrue(main.contains(player))

        // world change: the item was swapped in by something that fired no slot event
        player.inventory.setItem(0, null)
        server.pluginManager.callEvent(PlayerChangedWorldEvent(player, world))
        tick()
        assertFalse(main.contains(player))
    }

    // --- shared rules ---

    @Test
    fun `subscriptions are independent, close and owner disable remove everything`() {
        put(0, magic("wand"))
        player.inventory.heldItemSlot = 0
        val (a, callsA) = carrying("wand")
        val (b, callsB) = carrying("wand", interval = 2)
        val (h, _) = holding("wand", HeldSlot.MAIN_HAND)
        val other = MockBukkit.createMockPlugin("Other")
        val c = SneakyItemsAPI.whileCarrying(other, "wand", 1, { })
        assertTrue(a.contains(player) && b.contains(player) && c.contains(player))
        assertEquals(4, plugin.watches.size)

        a.close()
        assertFalse(a.isActive)
        assertEquals(0, a.size)
        assertEquals(3, plugin.watches.size, "closing drops the watch at once")
        a.close() // idempotent
        tick(4)
        assertEquals(0, callsA.of(player))
        assertEquals(2, callsB.of(player))
        assertTrue(b.contains(player))

        // a closed watch is never refilled
        put(0, null)
        tick()
        put(0, magic("wand"))
        tick()
        assertFalse(a.contains(player))
        assertTrue(b.contains(player))

        server.pluginManager.disablePlugin(owner)
        assertFalse(b.isActive)
        assertFalse(h.isActive)
        assertTrue(c.isActive, "another owner's watch stays")
        assertEquals(1, plugin.watches.size)
        assertFalse(plugin.watches.trackingHeld, "the last held watch went with the owner")
        assertFalse(plugin.held.active)
        assertTrue(plugin.watches.trackingCarried)
        put(0, null)
        tick()
        assertFalse(c.contains(player))
    }

    @Test
    fun `tracking runs only while watches of its kind exist and restarts from the live inventory`() {
        assertFalse(plugin.held.active)
        assertFalse(plugin.watches.trackingCarried)
        val (carry, _) = carrying("wand")
        assertTrue(plugin.watches.trackingCarried)
        assertFalse(plugin.held.active, "whileCarrying doesn't start held tracking")
        assertTrue(CarriedItemChangeEvent.getHandlerList().registeredListeners.isEmpty(), "no public listener is added")
        fun heldHandlers() = PlayerItemHeldEvent.getHandlerList().registeredListeners.count { it.plugin === plugin }
        val baseline = heldHandlers()
        val (hold, _) = holding("wand", HeldSlot.ANY)
        assertTrue(plugin.held.active)
        assertTrue(plugin.watches.trackingHeld)
        assertEquals(baseline + 1, heldHandlers())

        hold.close()
        assertFalse(plugin.held.active)
        assertFalse(plugin.watches.trackingHeld)
        assertEquals(baseline, heldHandlers(), "the held listener is unregistered")
        assertEquals(0, plugin.held.trackedPlayers)
        carry.close()
        assertFalse(plugin.watches.trackingCarried)
        assertEquals(0, plugin.watches.size)

        // a new watch after a pause sees changes made while nothing was tracked, without any event
        player.inventory.heldItemSlot = 0
        player.inventory.setItem(0, magic("wand"))
        val (again, _) = holding("wand", HeldSlot.MAIN_HAND)
        assertTrue(again.contains(player))
    }

    @Test
    fun `new watches read the live inventory, not a stale cache, and correct existing watches`() {
        player.inventory.heldItemSlot = 0
        val (carry1, _) = carrying("wand")
        val (hold1, _) = holding("wand", HeldSlot.MAIN_HAND)

        // added just before subscribing, no slot event yet
        player.inventory.setItem(0, magic("wand"))
        val (carry2, _) = carrying("wand")
        assertTrue(carry2.contains(player))
        assertTrue(carry1.contains(player), "the recount reached the existing watch too")
        val (hold2, _) = holding("wand", HeldSlot.MAIN_HAND)
        assertTrue(hold2.contains(player))
        assertTrue(hold1.contains(player))

        // removed just before subscribing, no slot event yet
        player.inventory.setItem(0, null)
        val (carry3, _) = carrying("wand")
        assertFalse(carry3.contains(player))
        assertFalse(carry1.contains(player))
        assertFalse(carry2.contains(player))
        val (hold3, _) = holding("wand", HeldSlot.MAIN_HAND)
        assertFalse(hold3.contains(player))
        assertFalse(hold1.contains(player))
        assertFalse(hold2.contains(player))
    }

    @Test
    fun `a forged CarriedItemChangeEvent changes nothing, and consumers still get the real ones`() {
        val seen = mutableListOf<CarriedItemChangeEvent>()
        val listener = object : Listener {
            @EventHandler
            fun on(event: CarriedItemChangeEvent) {
                seen.add(event)
            }
        }
        server.pluginManager.registerEvents(listener, owner)
        val (carry, calls) = carrying("wand")
        server.pluginManager.callEvent(CarriedItemChangeEvent(player, "wand", 0, 5))
        assertFalse(carry.contains(player))
        tick(3)
        assertEquals(0, calls.of(player))
        SneakyItemsAPI.refreshCarried(player)
        assertFalse(carry.contains(player))

        seen.clear()
        put(0, magic("wand", 2))
        tick()
        assertTrue(carry.contains(player))
        assertEquals(listOf(Triple("wand", 0, 2)), seen.map { Triple(it.itemId, it.oldCount, it.newCount) })
    }

    @Test
    fun `timers closed behind SneakyItems' back are dropped and the last held one stops tracking`() {
        // ItemWatch never hands out its timer; SneakyUtils itself can still close it (owner or SneakyUtils disable).
        val (carry, _) = carrying("wand")
        carry.playerTimer.close()
        assertFalse(carry.isActive)
        put(0, magic("wand"))
        tick()
        assertFalse(carry.contains(player))
        assertEquals(0, plugin.watches.size)
        assertFalse(plugin.watches.trackingCarried)

        player.inventory.heldItemSlot = 1
        val (hold, _) = holding("wand", HeldSlot.MAIN_HAND)
        hold.playerTimer.close()
        put(1, magic("wand")) // a change for its id prunes it
        assertEquals(0, plugin.watches.size)
        assertTrue(plugin.watches.trackingHeld, "not torn down inside the resync")
        tick()
        assertFalse(plugin.watches.trackingHeld)
        assertFalse(plugin.held.active)
    }

    @Test
    fun `subscribing inside a CarriedItemChangeEvent listener seeds from the live inventory`() {
        put(5, magic("trigger"))
        assertEquals(1, SneakyItemsAPI.carriedCount(player, "trigger")) // commit the count before listening
        val results = mutableListOf<Boolean>()
        var step = 0
        var created: ItemWatch? = null
        val listener = object : Listener {
            @EventHandler
            fun on(event: CarriedItemChangeEvent) {
                if (event.itemId != "trigger") return
                when (step++) {
                    0 -> { // add the wand, then subscribe: the index's recount is deferred until this batch ends
                        player.inventory.setItem(0, magic("wand"))
                        created = SneakyItemsAPI.whileCarrying(owner, "wand", 1, { })
                        results.add(created!!.contains(player))
                    }
                    1 -> { // remove the wand, then subscribe
                        player.inventory.setItem(0, null)
                        created = SneakyItemsAPI.whileCarrying(owner, "wand", 1, { })
                        results.add(created!!.contains(player))
                    }
                }
            }
        }
        server.pluginManager.registerEvents(listener, owner)

        put(5, null) // trigger 1 -> 0: step 0
        tick()
        assertEquals(listOf(true), results, "added at once, from the live read")
        val first = created!!
        assertTrue(first.contains(player), "still right after the deferred recount")
        assertEquals(1, SneakyItemsAPI.carriedCount(player, "wand"))

        put(5, magic("trigger")) // 0 -> 1: step 1
        tick()
        assertEquals(listOf(true, false), results, "not added: the live read says it is gone")
        assertFalse(created!!.contains(player))
        assertFalse(first.contains(player), "the deferred recount removed it from the earlier watch")
    }

    @Test
    fun `a change made after subscribing inside the same batch is reconciled when the batch ends`() {
        put(5, magic("trigger"))
        assertEquals(1, SneakyItemsAPI.carriedCount(player, "trigger")) // commit the count before listening
        var created: ItemWatch? = null
        val listener = object : Listener {
            @EventHandler
            fun on(event: CarriedItemChangeEvent) {
                if (event.itemId != "trigger" || created != null) return
                player.inventory.setItem(0, magic("wand"))
                created = SneakyItemsAPI.whileCarrying(owner, "wand", 1, { })
                assertTrue(created!!.contains(player))
                player.inventory.setItem(0, null) // committed count stays 0: no notification would fix this
            }
        }
        server.pluginManager.registerEvents(listener, owner)
        put(5, null)
        tick()
        assertFalse(created!!.contains(player))
    }

    /** A consumer that takes the wand away as soon as it hears the wand arrived (no slot event follows). */
    private fun confiscateWandOnArrival() {
        val listener = object : Listener {
            @EventHandler
            fun on(event: CarriedItemChangeEvent) {
                if (event.itemId == "wand" && event.newCount > 0) {
                    for (slot in 0..40) if (SneakyItemsAPI.isItem(player.inventory.getItem(slot), "wand")) player.inventory.setItem(slot, null)
                }
            }
        }
        server.pluginManager.registerEvents(listener, owner)
    }

    @Test
    fun `a listener removing the item in response to the seed recount's own event leaves nobody enrolled`() {
        val (existing, _) = carrying("wand")
        confiscateWandOnArrival()
        player.inventory.setItem(0, magic("wand")) // no slot event: Paper hasn't synced yet
        val (watch, calls) = carrying("wand")
        assertFalse(watch.contains(player))
        assertFalse(existing.contains(player))
        assertEquals(0, SneakyItemsAPI.carriedCount(player, "wand"))
        tick(3)
        assertEquals(0, calls.of(player))
    }

    @Test
    fun `the same after a deferred recount following registration inside a listener`() {
        put(5, magic("trigger"))
        assertEquals(1, SneakyItemsAPI.carriedCount(player, "trigger"))
        var created: ItemWatch? = null
        val listener = object : Listener {
            @EventHandler
            fun on(event: CarriedItemChangeEvent) {
                if (event.itemId != "trigger" || created != null) return
                player.inventory.setItem(0, magic("wand"))
                created = SneakyItemsAPI.whileCarrying(owner, "wand", 1, { })
            }
        }
        server.pluginManager.registerEvents(listener, owner)
        confiscateWandOnArrival()
        put(5, null)
        tick()
        assertFalse(created!!.contains(player))
        assertEquals(0, SneakyItemsAPI.carriedCount(player, "wand"))
    }

    @Test
    fun `registering one watch settles every watched id, not just its own`() {
        put(0, magic("wand"))
        val (wandWatch, wandCalls) = carrying("wand")
        assertTrue(wandWatch.contains(player))
        val listener = object : Listener {
            @EventHandler
            fun on(event: CarriedItemChangeEvent) {
                // the lantern's arrival makes a consumer confiscate the wand (no slot event follows)
                if (event.itemId == "lantern" && event.newCount > 0) player.inventory.setItem(0, null)
            }
        }
        server.pluginManager.registerEvents(listener, owner)
        player.inventory.setItem(1, magic("lantern")) // no slot event yet
        val (lanternWatch, _) = carrying("lantern")
        assertTrue(lanternWatch.contains(player))
        assertFalse(wandWatch.contains(player), "the wand watch was corrected by the settle step")
        assertEquals(0, SneakyItemsAPI.carriedCount(player, "wand"))
        val before = wandCalls.of(player)
        tick(3)
        assertEquals(before, wandCalls.of(player))
    }

    @Test
    fun `watches registered from a quit handler after SneakyUtils' cleanup never enroll the departing player`() {
        for (heldAlreadyTracked in listOf(false, true)) {
            if (heldAlreadyTracked) holding("other", HeldSlot.ANY)
            val p = addPlayer("leaver$heldAlreadyTracked")
            p.inventory.heldItemSlot = 0
            put(0, magic("wand"), p)
            val calls = Calls()
            val made = mutableListOf<ItemWatch>()
            val seenOnline = mutableListOf<Boolean>()
            // Registered after SneakyUtils (loaded first), so at MONITOR it runs after SneakyUtils' quit cleanup.
            val late = object : Listener {
                @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
                fun on(event: org.bukkit.event.player.PlayerQuitEvent) {
                    if (event.player !== p) return
                    seenOnline.add(p.isOnline)
                    made.add(carrying("wand", calls).first)
                    made.add(holding("wand", HeldSlot.MAIN_HAND, calls).first)
                    made.add(holding("wand", HeldSlot.ANY, calls).first)
                    // a recount during the quit must not enroll them through the observer either
                    SneakyItemsAPI.refreshCarried(p)
                }
            }
            server.pluginManager.registerEvents(late, owner)
            p.disconnect()
            org.bukkit.event.HandlerList.unregisterAll(late)
            assertEquals(listOf(true), seenOnline, "the quit handler ran while the player still counted as online")
            assertEquals(3, made.size)
            for (w in made) assertFalse(w.contains(p), "$w")
            tick(5)
            for (w in made) assertFalse(w.contains(p), "$w after the quit")
            assertEquals(0, calls.of(p))

            // rejoining enrolls them again
            p.reconnect()
            tick()
            assertTrue(made[0].contains(p))
            assertTrue(made[1].contains(p))
            made.forEach { it.close() }
            p.disconnect()
        }
    }

    @Test
    fun `the index callbacks the watches rely on cannot be replaced`() {
        assertThrows(IllegalStateException::class.java) {
            plugin.carried.bindObserver(object : CarriedItemIndex.Observer {
                override val wanted = true
                override fun changed(player: org.bukkit.entity.Player, itemId: String, oldCount: Int, newCount: Int) {}
            })
        }
        assertThrows(IllegalStateException::class.java) { plugin.held.bindOnChange({ _, _ -> }, { false }) }
        put(0, magic("wand"))
        val (carry, _) = carrying("wand")
        assertTrue(carry.contains(player))
    }

    @Test
    fun `invalid arguments are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SneakyItemsAPI.whileCarrying(owner, "wand", 1, { }, PlayerTimerOptions.builder().scope(PlayerScope.ALL_ONLINE).build())
        }
        assertThrows(IllegalArgumentException::class.java) { SneakyItemsAPI.whileCarrying(owner, " ", 1, { }) }
        assertThrows(IllegalArgumentException::class.java) { SneakyItemsAPI.whileHolding(owner, "wand", HeldSlot.ANY, 0, { }) }
        server.pluginManager.disablePlugin(owner)
        assertThrows(IllegalArgumentException::class.java) { SneakyItemsAPI.whileCarrying(owner, "wand", 1, { }) }
        assertEquals(0, plugin.watches.size)
    }

    @Test
    fun `SneakyItems disabling closes every subscription`() {
        val (a, _) = carrying("wand")
        val (b, _) = holding("wand", HeldSlot.ANY)
        server.pluginManager.disablePlugin(plugin)
        assertFalse(a.isActive)
        assertFalse(b.isActive)
        assertFalse(plugin.held.active)
    }
}
