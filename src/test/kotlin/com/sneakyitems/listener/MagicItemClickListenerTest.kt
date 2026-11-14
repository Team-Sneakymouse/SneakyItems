package com.sneakyitems.listener

import com.sneakyitems.SneakyItems
import com.sneakyitems.events.MagicItemClickEvent
import com.sneakyitems.events.MagicItemClickEvent.Click
import com.sneakyitems.events.MagicItemClickEvent.Trigger
import com.sneakyitems.events.MagicItemSwingEvent
import com.sneakyitems.util.magicitems.MagicItemBehaviorKeys
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.EntityType
import org.bukkit.entity.Item
import org.bukkit.event.Event.Result
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerAnimationType
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock

class MagicItemClickListenerTest {

    private lateinit var server: ServerMock
    private lateinit var plugin: SneakyItems
    private lateinit var player: PlayerMock
    private lateinit var listener: MagicItemClickListener
    private var now = 1_000L
    private val fired = mutableListOf<MagicItemClickEvent>()
    private val swings = mutableListOf<MagicItemSwingEvent>()
    private var onClick: (MagicItemClickEvent) -> Unit = {}

    @BeforeEach
    fun setup() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(SneakyItems::class.java)
        player = server.addPlayer()
        // swap the plugin's listener for one with a controllable clock
        HandlerList.unregisterAll(plugin.clicks)
        listener = MagicItemClickListener(plugin) { now }
        server.pluginManager.registerEvents(listener, plugin)
        server.pluginManager.registerEvents(object : Listener {
            @EventHandler
            fun on(e: MagicItemClickEvent) {
                fired += e
                onClick(e)
            }

            @EventHandler
            fun on(e: MagicItemSwingEvent) {
                swings += e
            }
        }, plugin)
    }

    @AfterEach
    fun cleanup() {
        MockBukkit.unmock()
    }

    private fun magic(key: String, type: Material = Material.STICK): ItemStack {
        val stack = ItemStack(type)
        stack.editPersistentDataContainer { it.set(MagicItemBehaviorKeys.magicItem(), PersistentDataType.STRING, key) }
        return stack
    }

    private fun interact(action: Action, hand: EquipmentSlot = EquipmentSlot.HAND, blockType: Material? = null): PlayerInteractEvent {
        val block = blockType?.let { player.location.block.apply { type = it } }
        val item = player.inventory.getItem(hand).takeUnless { it.isEmpty }
        val event = PlayerInteractEvent(player, action, item, block, if (block != null) BlockFace.UP else BlockFace.SELF, hand)
        server.pluginManager.callEvent(event)
        return event
    }

    private fun swing() = server.pluginManager.callEvent(PlayerAnimationEvent(player, PlayerAnimationType.ARM_SWING))

    private fun drop() {
        val item = server.getWorld(player.world.name)!!.dropItem(player.location, ItemStack(Material.DIRT)) as Item
        server.pluginManager.callEvent(PlayerDropItemEvent(player, item))
    }

    private fun pig() = player.world.spawnEntity(player.location, EntityType.PIG)

    // --- left clicks ---

    @Test
    fun `left click air fires one tick later with the main-hand key`() {
        player.inventory.setItemInMainHand(magic("wand"))
        interact(Action.LEFT_CLICK_AIR)
        assertTrue(fired.isEmpty())
        server.scheduler.performTicks(1)
        val e = fired.single()
        assertEquals("wand", e.itemKey)
        assertEquals(Click.LEFT, e.click)
        assertEquals(Trigger.AIR, e.trigger)
        assertEquals(EquipmentSlot.HAND, e.hand)
        assertFalse(e.hasInteractResults())
        assertThrows(IllegalStateException::class.java) { e.setUseItemInHand(Result.DENY) }
    }

    @Test
    fun `non-magic items and empty hands fire nothing`() {
        player.inventory.setItemInMainHand(ItemStack(Material.STICK))
        interact(Action.LEFT_CLICK_AIR)
        interact(Action.LEFT_CLICK_BLOCK, blockType = Material.STONE)
        interact(Action.RIGHT_CLICK_AIR)
        swing()
        server.scheduler.performTicks(2)
        assertTrue(fired.isEmpty())
        assertTrue(swings.isEmpty())
    }

    @Test
    fun `left click block fires at once and cancelling cancels the interact`() {
        player.inventory.setItemInMainHand(magic("wand"))
        onClick = { it.isCancelled = true }
        val event = interact(Action.LEFT_CLICK_BLOCK, blockType = Material.STONE)
        val e = fired.single()
        assertEquals(Trigger.BLOCK, e.trigger)
        assertEquals(Material.STONE, e.clickedBlock!!.type)
        assertTrue(event.isCancelled)
    }

    @Test
    fun `dropping suppresses the air left click and the swing for 150 ms`() {
        player.inventory.setItemInMainHand(magic("wand"))
        drop()
        interact(Action.LEFT_CLICK_AIR)
        swing()
        server.scheduler.performTicks(1)
        assertTrue(fired.isEmpty())
        assertTrue(swings.isEmpty())

        now += MagicItemClickListener.NO_CLICK_MILLIS
        interact(Action.LEFT_CLICK_AIR)
        swing()
        server.scheduler.performTicks(1)
        assertEquals(1, fired.size)
        assertEquals(1, swings.size)
    }

    @Test
    fun `right click on a door suppresses the following air left click`() {
        player.inventory.setItemInMainHand(magic("wand"))
        interact(Action.RIGHT_CLICK_BLOCK, blockType = Material.OAK_DOOR)
        fired.clear() // the right click itself is reported
        interact(Action.LEFT_CLICK_AIR)
        server.scheduler.performTicks(1)
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `an attack fires at once, cancels the attack when cancelled, and swallows its swing click`() {
        player.inventory.setItemInMainHand(magic("sword"))
        onClick = { it.isCancelled = true }
        val target = pig()
        val attack = PrePlayerAttackEntityEvent(player, target, true)
        server.pluginManager.callEvent(attack)
        val e = fired.single()
        assertEquals(Trigger.ENTITY, e.trigger)
        assertEquals(target, e.clickedEntity)
        assertTrue(attack.isCancelled)

        interact(Action.LEFT_CLICK_AIR)
        server.scheduler.performTicks(1)
        assertEquals(1, fired.size)

        // a separate click a few ticks later still counts
        server.scheduler.performTicks(3)
        interact(Action.LEFT_CLICK_AIR)
        server.scheduler.performTicks(1)
        assertEquals(2, fired.size)
    }

    @Test
    fun `cast-on-animate turns swings into left clicks but block and entity hits stay single`() {
        listener.castOnAnimate = true
        player.inventory.setItemInMainHand(magic("wand"))
        interact(Action.LEFT_CLICK_AIR) // ignored in this mode: the swing reports it
        server.scheduler.performTicks(1)
        assertTrue(fired.isEmpty())

        swing()
        server.scheduler.performTicks(1)
        assertEquals(Trigger.ARM_SWING, fired.single().trigger)

        fired.clear()
        server.scheduler.performTicks(3)
        swing()
        interact(Action.LEFT_CLICK_BLOCK, blockType = Material.STONE)
        server.scheduler.performTicks(1)
        assertEquals(listOf(Trigger.BLOCK), fired.map { it.trigger })

        fired.clear()
        server.scheduler.performTicks(3)
        server.pluginManager.callEvent(PrePlayerAttackEntityEvent(player, pig(), true))
        swing()
        server.scheduler.performTicks(1)
        assertEquals(listOf(Trigger.ENTITY), fired.map { it.trigger })
    }

    // --- right clicks ---

    @Test
    fun `right click fires per hand and cancelling cancels the interact`() {
        player.inventory.setItemInOffHand(magic("tome"))
        onClick = { it.isCancelled = true }
        interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND) // empty main hand
        assertTrue(fired.isEmpty())
        val off = interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.OFF_HAND)
        val e = fired.single()
        assertEquals("tome", e.itemKey)
        assertEquals(Click.RIGHT, e.click)
        assertEquals(EquipmentSlot.OFF_HAND, e.hand)
        assertEquals(Result.DENY, off.useItemInHand())
    }

    @Test
    fun `entity right click counts once across interact-at, interact and the follow-up air click`() {
        player.inventory.setItemInMainHand(magic("remote"))
        onClick = { it.isCancelled = true }
        val target = pig()
        val at = PlayerInteractAtEntityEvent(player, target, Vector(), EquipmentSlot.HAND)
        server.pluginManager.callEvent(at)
        val plain = PlayerInteractEntityEvent(player, target, EquipmentSlot.HAND)
        server.pluginManager.callEvent(plain)
        val air = interact(Action.RIGHT_CLICK_AIR)
        val e = fired.single()
        assertEquals(Trigger.ENTITY, e.trigger)
        assertEquals(target, e.clickedEntity)
        assertTrue(at.isCancelled)
        assertTrue(plain.isCancelled)
        assertEquals(Result.DENY, air.useItemInHand()) // the consumed click's follow-up is cancelled too

        // the next click is its own
        server.scheduler.performTicks(4)
        interact(Action.RIGHT_CLICK_AIR)
        assertEquals(2, fired.size)
    }

    // --- denial and fine-grained results ---

    @Test
    fun `isAlreadyDenied reports an earlier deny, not the default-cancelled air click`() {
        player.inventory.setItemInMainHand(magic("crystal"))
        interact(Action.RIGHT_CLICK_AIR)
        assertFalse(fired.single().isAlreadyDenied)

        fired.clear()
        server.scheduler.performTicks(4)
        server.pluginManager.registerEvents(object : Listener {
            @EventHandler(priority = EventPriority.LOW)
            fun protect(e: PlayerInteractEvent) {
                e.setUseItemInHand(Result.DENY)
            }

            @EventHandler(priority = EventPriority.LOW)
            fun protect(e: PlayerInteractEntityEvent) {
                e.isCancelled = true
            }
        }, plugin)
        interact(Action.RIGHT_CLICK_AIR)
        assertTrue(fired.single().isAlreadyDenied)

        fired.clear()
        server.scheduler.performTicks(4)
        server.pluginManager.callEvent(PlayerInteractEntityEvent(player, pig(), EquipmentSlot.HAND))
        assertTrue(fired.single().isAlreadyDenied)
    }

    @Test
    fun `interact results can be set separately`() {
        player.inventory.setItemInMainHand(magic("roll"))
        onClick = { it.setUseInteractedBlock(Result.DENY) }
        val event = interact(Action.RIGHT_CLICK_BLOCK, blockType = Material.STONE)
        val e = fired.single()
        assertTrue(e.hasInteractResults())
        assertFalse(e.isCancelled)
        assertEquals(Result.DENY, event.useInteractedBlock())
        assertEquals(Result.DEFAULT, event.useItemInHand())
    }

    @Test
    fun `interaction point is taken from the vanilla interact when present`() {
        player.inventory.setItemInMainHand(magic("switch"))
        val block = player.location.block.apply { type = Material.STONE }
        val item = player.inventory.itemInMainHand
        val vanilla = PlayerInteractEvent(
            player, Action.RIGHT_CLICK_BLOCK, item, block, BlockFace.NORTH, EquipmentSlot.HAND,
        )
        // MockBukkit may leave interactionPoint null; assert the event mirrors whatever the interact has.
        server.pluginManager.callEvent(vanilla)
        val e = fired.single()
        assertEquals(vanilla.interactionPoint, e.interactionPoint)
    }

    // --- swing event ---

    @Test
    fun `swing event fires for every main-hand swing whatever cast-on-animate says`() {
        player.inventory.setItemInMainHand(magic("sword"))
        swing()
        assertTrue(swings.isEmpty())
        server.scheduler.performTicks(1)
        assertEquals("sword", swings.single().itemKey)
        assertTrue(fired.isEmpty()) // not a click without cast-on-animate

        // raw: the swing of a block hit still counts as a swing
        interact(Action.LEFT_CLICK_BLOCK, blockType = Material.STONE)
        swing()
        server.scheduler.performTicks(1)
        assertEquals(2, swings.size)
    }

    @Test
    fun `cast-on-animate is read from config on reload`() {
        plugin.config.set("click-events.cast-on-animate", true)
        plugin.saveConfig()
        plugin.reloadPlugin()
        assertTrue(plugin.clicks.castOnAnimate)
    }
}
