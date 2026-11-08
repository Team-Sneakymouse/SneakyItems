package com.sneakyitems.util

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import java.util.UUID

class ItemBehaviorsTest {
    @BeforeEach fun setup() { MockBukkit.mock() }
    @AfterEach fun cleanup() { MockBukkit.unmock() }

    @Test fun `expiration uses literal long milliseconds and preserves existing deadline`() {
        val item = ItemStack(Material.STONE)
        val api = Class.forName("com.sneakyitems.util.items.ItemBehaviors")
        val apply = api.getMethod("applyExpiration", ItemStack::class.java, Double::class.javaPrimitiveType)
        val before = System.currentTimeMillis()
        apply.invoke(null, item, 60000.0)
        val key = NamespacedKey.fromString("magicspells:expires_at")!!
        val deadline = item.itemMeta.persistentDataContainer.get(key, PersistentDataType.LONG)!!
        assertTrue(deadline >= before + 60000)
        assertTrue(deadline <= System.currentTimeMillis() + 60000)
        apply.invoke(null, item, 900000.0)
        assertEquals(deadline, item.itemMeta.persistentDataContainer.get(key, PersistentDataType.LONG))
        assertEquals(1, item.lore()!!.size)
    }

    @Test fun `standalone plugin enforces literal foreign soulbound pickup`() {
        val server = MockBukkit.getMock()!!
        MockBukkit.load(com.sneakyitems.SneakyItems::class.java)
        val player = server.addPlayer()
        val item = ItemStack(Material.STONE)
        val meta = item.itemMeta
        meta.persistentDataContainer.set(NamespacedKey.fromString("magicspells:soulbound_owner")!!,
            PersistentDataType.STRING, UUID.randomUUID().toString())
        item.itemMeta = meta
        val drop = player.world.dropItem(player.location, item)
        val event = org.bukkit.event.entity.EntityPickupItemEvent(player, drop, 0)
        server.pluginManager.callEvent(event)
        assertTrue(event.isCancelled)
    }

    @Test fun `initial online scan waits for consumer listener then expires exactly once`() {
        val server = MockBukkit.getMock()!!
        val player = server.addPlayer()
        val item = ItemStack(Material.STONE)
        val meta = item.itemMeta
        meta.persistentDataContainer.set(NamespacedKey.fromString("magicspells:expires_at")!!,
            PersistentDataType.LONG, 1L)
        item.itemMeta = meta
        player.inventory.setItem(0, item)

        val provider = MockBukkit.load(com.sneakyitems.SneakyItems::class.java)
        assertEquals(item, player.inventory.getItem(0), "provider enable must wait for consumers")
        val events = mutableListOf<com.sneakyitems.events.ItemExpireEvent>()
        server.pluginManager.registerEvent(com.sneakyitems.events.ItemExpireEvent::class.java,
            object : org.bukkit.event.Listener {}, org.bukkit.event.EventPriority.NORMAL,
            org.bukkit.plugin.EventExecutor { _, event -> events.add(event as com.sneakyitems.events.ItemExpireEvent) },
            provider)
        server.scheduler.performOneTick()
        assertNull(player.inventory.getItem(0))
        assertEquals(1, events.size)
        assertEquals(player, events.single().player)
        assertEquals(item, events.single().itemStack)
        server.scheduler.performTicks(2)
        assertEquals(1, events.size)
    }

    @Test fun `standalone join purges literal expired stacks and fires utility event`() {
        val server = MockBukkit.getMock()!!
        MockBukkit.load(com.sneakyitems.SneakyItems::class.java)
        val player = server.addPlayer()
        val item = ItemStack(Material.STONE)
        val meta = item.itemMeta
        meta.persistentDataContainer.set(NamespacedKey.fromString("magicspells:expires_at")!!,
            PersistentDataType.LONG, 1L)
        item.itemMeta = meta
        player.inventory.setItem(0, item)
        server.pluginManager.callEvent(org.bukkit.event.player.PlayerJoinEvent(player, net.kyori.adventure.text.Component.empty()))
        assertNull(player.inventory.getItem(0))
        @Suppress("UNCHECKED_CAST")
        server.pluginManager.assertEventFired(Class.forName("com.sneakyitems.events.ItemExpireEvent") as Class<org.bukkit.event.Event>)
    }

    @Test fun `scheduled expiration removes inventory and ender chest without interaction`() {
        val server = MockBukkit.getMock()!!
        val plugin = MockBukkit.load(com.sneakyitems.SneakyItems::class.java)
        val player = server.addPlayer()
        val item = ItemStack(Material.STONE)
        val key = NamespacedKey.fromString("magicspells:expires_at")!!
        var meta = item.itemMeta
        meta.persistentDataContainer.set(key, PersistentDataType.LONG, System.currentTimeMillis() + 1000)
        item.itemMeta = meta
        player.inventory.setItem(0, item)
        player.enderChest.setItem(0, item.clone())
        val scheduler = Class.forName("com.sneakyitems.util.items.ItemExpirationScheduler")
        scheduler.getMethod("scheduleAllForPlayer", org.bukkit.entity.Player::class.java).invoke(null, player)
        for (inventory in listOf(player.inventory, player.enderChest)) {
            val stored = inventory.getItem(0)!!
            meta = stored.itemMeta
            meta.persistentDataContainer.set(key, PersistentDataType.LONG, 1L)
            stored.itemMeta = meta
        }
        server.scheduler.performTicks(21)
        assertNull(player.inventory.getItem(0))
        assertNull(player.enderChest.getItem(0))
        val pending = ItemStack(Material.STONE)
        meta = pending.itemMeta
        meta.persistentDataContainer.set(key, PersistentDataType.LONG, System.currentTimeMillis() + 60000)
        pending.itemMeta = meta
        player.inventory.setItem(1, pending)
        scheduler.getMethod("scheduleAllForPlayer", org.bukkit.entity.Player::class.java).invoke(null, player)
        server.pluginManager.disablePlugin(plugin)
        val tasks = scheduler.getDeclaredField("scheduledTasks").apply { isAccessible = true }.get(null) as Map<*, *>
        assertTrue(tasks.isEmpty(), "disable must release scheduler state")
    }

    @Test fun `SCM enabled after utilities scans restored inventory next tick`() {
        val server = MockBukkit.getMock()!!
        MockBukkit.load(com.sneakyitems.SneakyItems::class.java)
        val scm = MockBukkit.createMockPlugin("SneakyCharacterManager")
        server.pluginManager.callEvent(org.bukkit.event.server.PluginEnableEvent(scm))
        val player = server.addPlayer()
        val event = net.sneakycharactermanager.paper.handlers.character.LoadCharacterEvent(
            player, false, "char-uuid", "Name", "skin", false, "",
        )
        server.pluginManager.callEvent(event)
        val item = ItemStack(Material.STONE)
        val meta = item.itemMeta
        meta.persistentDataContainer.set(NamespacedKey.fromString("magicspells:expires_at")!!, PersistentDataType.LONG, 1L)
        item.itemMeta = meta
        player.inventory.setItem(0, item)
        assertNotNull(player.inventory.getItem(0))
        server.scheduler.performOneTick()
        assertNull(player.inventory.getItem(0))
    }

    @Test fun `creative bypass requires new permission and creative mode`() {
        val server = MockBukkit.getMock()!!
        val plugin = MockBukkit.load(com.sneakyitems.SneakyItems::class.java)
        val player = server.addPlayer()
        val item = ItemStack(Material.STONE)
        com.sneakyitems.util.items.ItemBehaviors.applySoulbound(item, UUID.randomUUID())
        val drop = player.world.dropItem(player.location, item)
        fun cancelled(): Boolean {
            val event = org.bukkit.event.entity.EntityPickupItemEvent(player, drop, 0)
            server.pluginManager.callEvent(event)
            return event.isCancelled
        }
        player.gameMode = org.bukkit.GameMode.CREATIVE
        player.addAttachment(plugin, "sneakyutils.nosoulbound", true)
        assertTrue(cancelled())
        player.addAttachment(plugin, "sneakyitems.nosoulbound", true)
        assertFalse(cancelled())
        player.gameMode = org.bukkit.GameMode.SURVIVAL
        assertTrue(cancelled())
    }

    @Test fun `lore refresh preserves unrelated lore and exact deadline`() {
        val item = ItemStack(Material.STONE)
        item.lore(listOf(net.kyori.adventure.text.Component.text("Custom lore")))
        com.sneakyitems.util.items.ItemBehaviors.applyExpiration(item, 60000.0)
        val key = NamespacedKey.fromString("magicspells:expires_at")!!
        val deadline = item.itemMeta.persistentDataContainer.get(key, PersistentDataType.LONG)
        repeat(3) { com.sneakyitems.util.items.ItemBehaviors.updateExpiresLineIfNeeded(item) }
        assertEquals(2, item.lore()!!.size)
        assertEquals(net.kyori.adventure.text.Component.text("Custom lore"), item.lore()!![0])
        assertEquals(deadline, item.itemMeta.persistentDataContainer.get(key, PersistentDataType.LONG))
    }

    @Test fun `SCM loaded first is discovered and cancelled loads remain inert`() {
        val server = MockBukkit.getMock()!!
        MockBukkit.createMockPlugin("SneakyCharacterManager")
        MockBukkit.load(com.sneakyitems.SneakyItems::class.java)
        val player = server.addPlayer()
        val item = ItemStack(Material.STONE)
        val meta = item.itemMeta
        meta.persistentDataContainer.set(NamespacedKey.fromString("magicspells:expires_at")!!, PersistentDataType.LONG, 1L)
        item.itemMeta = meta
        player.inventory.setItem(0, item)
        fun loadEvent() = net.sneakycharactermanager.paper.handlers.character.LoadCharacterEvent(
            player, false, "char-uuid", "Name", "skin", false, "",
        )
        val cancelled = loadEvent().also { it.isCancelled = true }
        server.pluginManager.callEvent(cancelled)
        server.scheduler.performOneTick()
        assertNotNull(player.inventory.getItem(0))
        server.pluginManager.callEvent(loadEvent())
        server.scheduler.performOneTick()
        assertNull(player.inventory.getItem(0))
    }

    @Test fun `standalone soulbinding reads and preserves literal legacy owner`() {
        val item = ItemStack(Material.STONE)
        val owner = UUID.randomUUID()
        val key = NamespacedKey.fromString("magicspells:soulbound_owner")!!
        val meta = item.itemMeta
        meta.persistentDataContainer.set(key, PersistentDataType.STRING, owner.toString())
        item.itemMeta = meta
        val api = Class.forName("com.sneakyitems.util.items.ItemBehaviors")
        assertEquals(true, api.getMethod("hasSoulbound", ItemStack::class.java).invoke(null, item))
        api.getMethod("applySoulbound", ItemStack::class.java, UUID::class.java).invoke(null, item, UUID.randomUUID())
        assertEquals(owner.toString(), item.itemMeta.persistentDataContainer.get(key, PersistentDataType.STRING))
    }
}
