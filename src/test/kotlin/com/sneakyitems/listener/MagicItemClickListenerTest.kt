package com.sneakyitems.listener

import com.sneakyitems.SneakyItems
import com.sneakyitems.events.MagicItemClickEvent
import com.sneakyitems.events.MagicItemClickEvent.Click
import com.sneakyitems.events.MagicItemClickEvent.Trigger
import com.sneakyitems.util.magicitems.MagicItemBehaviorKeys
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.EntityType
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerAnimationType
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
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
import org.mockbukkit.mockbukkit.entity.PlayerMock

class MagicItemClickListenerTest {

    private lateinit var server: ServerMock
    private lateinit var plugin: SneakyItems
    private lateinit var player: PlayerMock
    private lateinit var listener: MagicItemClickListener
    private var now = 1_000L
    private val fired = mutableListOf<MagicItemClickEvent>()
    private var cancelFired = false

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
                if (cancelFired) e.isCancelled = true
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
    }

    @Test
    fun `non-magic items and empty hands fire nothing`() {
        player.inventory.setItemInMainHand(ItemStack(Material.STICK))
        interact(Action.LEFT_CLICK_AIR)
        interact(Action.RIGHT_CLICK_AIR)
        server.scheduler.performTicks(2)
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `dropping suppresses the left click for 150 ms`() {
        player.inventory.setItemInMainHand(magic("wand"))
        val drop = server.getWorld(player.world.name)!!.dropItem(player.location, ItemStack(Material.DIRT)) as Item
        server.pluginManager.callEvent(PlayerDropItemEvent(player, drop))
        interact(Action.LEFT_CLICK_AIR)
        server.scheduler.performTicks(1)
        assertTrue(fired.isEmpty())

        now += MagicItemClickListener.NO_CLICK_MILLIS
        interact(Action.LEFT_CLICK_AIR)
        server.scheduler.performTicks(1)
        assertEquals(1, fired.size)
    }

    @Test
    fun `right click on a door suppresses the following left click`() {
        player.inventory.setItemInMainHand(magic("wand"))
        interact(Action.RIGHT_CLICK_BLOCK, blockType = Material.OAK_DOOR)
        fired.clear() // the right click itself is reported
        interact(Action.LEFT_CLICK_BLOCK, blockType = Material.STONE)
        server.scheduler.performTicks(1)
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `right click fires per hand and cancelling cancels the interact`() {
        player.inventory.setItemInOffHand(magic("tome"))
        cancelFired = true
        interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND) // empty main hand
        assertTrue(fired.isEmpty())
        val off = interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.OFF_HAND)
        val e = fired.single()
        assertEquals("tome", e.itemKey)
        assertEquals(Click.RIGHT, e.click)
        assertEquals(EquipmentSlot.OFF_HAND, e.hand)
        assertEquals(org.bukkit.event.Event.Result.DENY, off.useItemInHand())
    }

    @Test
    fun `right click entity reports the entity`() {
        player.inventory.setItemInMainHand(magic("wand"))
        val target = player.world.spawnEntity(player.location, EntityType.PIG)
        val event = PlayerInteractEntityEvent(player, target, EquipmentSlot.HAND)
        server.pluginManager.callEvent(event)
        val e = fired.single()
        assertEquals(Trigger.ENTITY, e.trigger)
        assertEquals(target, e.clickedEntity)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `melee hit fires at once, cancels damage when cancelled, and swallows its interact`() {
        player.inventory.setItemInMainHand(magic("sword"))
        cancelFired = true
        val target = player.world.spawnEntity(player.location, EntityType.PIG)
        @Suppress("DEPRECATION")
        val damage = EntityDamageByEntityEvent(player, target, DamageCause.ENTITY_ATTACK, 1.0)
        server.pluginManager.callEvent(damage)
        assertEquals(Trigger.ENTITY, fired.single().trigger)
        assertTrue(damage.isCancelled)

        interact(Action.LEFT_CLICK_AIR)
        server.scheduler.performTicks(1)
        assertEquals(1, fired.size)
    }

    @Test
    fun `cast-on-animate uses the arm swing instead of interacts and melee`() {
        listener.castOnAnimate = true
        player.inventory.setItemInMainHand(magic("wand"))
        interact(Action.LEFT_CLICK_AIR)
        val target = player.world.spawnEntity(player.location, EntityType.PIG)
        @Suppress("DEPRECATION")
        server.pluginManager.callEvent(EntityDamageByEntityEvent(player, target, DamageCause.ENTITY_ATTACK, 1.0))
        server.scheduler.performTicks(1)
        assertTrue(fired.isEmpty())

        server.pluginManager.callEvent(PlayerAnimationEvent(player, PlayerAnimationType.ARM_SWING))
        server.scheduler.performTicks(1)
        assertEquals(Trigger.ARM_SWING, fired.single().trigger)
    }

    @Test
    fun `cast-on-animate is read from config on reload`() {
        plugin.config.set("click-events.cast-on-animate", true)
        plugin.saveConfig()
        plugin.reloadPlugin()
        assertTrue(plugin.clicks.castOnAnimate)
    }
}
