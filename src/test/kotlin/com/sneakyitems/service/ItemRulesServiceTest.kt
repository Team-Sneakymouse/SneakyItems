package com.sneakyitems.service

import com.sneakyitems.SneakyItems
import com.sneakyitems.api.HeldSlot
import com.sneakyitems.api.ItemRuleGroup
import com.sneakyitems.api.ItemRuleHooks
import com.sneakyitems.api.ItemRules
import com.sneakyitems.api.RuleRefusal
import com.sneakyitems.api.SneakyItemsAPI
import com.sneakyitems.api.UniqueWhere
import com.sneakyitems.api.WatchMembership
import com.sneakyutils.SneakyUtils
import com.sneakyutils.triggers.PlayerTimerOptions
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.ItemDespawnEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.inventory.SimpleInventoryViewMock
import org.mockbukkit.mockbukkit.world.WorldMock

/** `SneakyItemsAPI.itemRules` in MockBukkit; events are fired by hand (MockBukkit fires none of them itself). */
class ItemRulesServiceTest {

  private lateinit var server: ServerMock
  private lateinit var world: WorldMock
  private lateinit var plugin: SneakyItems
  private lateinit var owner: Plugin
  private lateinit var player: PlayerMock

  private val refusals = ArrayList<RuleRefusal>()
  private val hooks = object : ItemRuleHooks {
    override fun refused(player: Player, itemId: String, reason: RuleRefusal) { refusals += reason }
  }

  @BeforeEach
  fun setup() {
    server = MockBukkit.mock()
    world = server.addSimpleWorld("world")
    MockBukkit.load(SneakyUtils::class.java)
    plugin = MockBukkit.load(SneakyItems::class.java)
    owner = MockBukkit.createMockPlugin("Consumer")
    for (id in listOf("orb_1", "orb_2", "other")) SneakyItemsAPI.registerFromStack(owner, "magicspells", id, ItemStack(Material.PAPER))
    player = server.addPlayer("alice")
  }

  @AfterEach
  fun cleanup() = MockBukkit.unmock()

  private fun group(rules: ItemRules.Builder): ItemRuleGroup =
      SneakyItemsAPI.itemRules(owner, "orbs", listOf("orb_1", "orb_2"), rules.hooks(hooks).build())

  private fun pickup(p: PlayerMock, stack: ItemStack): EntityPickupItemEvent {
    val item = world.dropItem(p.location, stack)
    val e = EntityPickupItemEvent(p, item, 0)
    server.pluginManager.callEvent(e)
    return e
  }

  /** MockBukkit's view throws from `convertSlot` and `getInventory(rawSlot)`, which the click event calls. */
  private fun chestView(chest: org.bukkit.inventory.Inventory) =
      object : SimpleInventoryViewMock(player, chest, player.inventory, InventoryType.CHEST) {
        override fun convertSlot(rawSlot: Int): Int = rawSlot
        override fun getInventory(rawSlot: Int): org.bukkit.inventory.Inventory? =
            if (rawSlot < 0) null else if (rawSlot < chest.size) chest else player.inventory
      }

  @Test
  fun `stacks are matched by magic item id`() {
    val g = group(ItemRules.builder())
    assertEquals("orb_1", g.idOf(SneakyItemsAPI.getItemStack("orb_1")))
    assertFalse(g.isMember(SneakyItemsAPI.getItemStack("other")))
    assertFalse(g.isMember(ItemStack(Material.PAPER)))
    assertEquals(g, SneakyItemsAPI.ruleGroupOf(SneakyItemsAPI.getItemStack("orb_2")))
  }

  @Test
  fun `an id belongs to one group, registering the same group again replaces it`() {
    val first = group(ItemRules.builder())
    assertThrows(IllegalArgumentException::class.java) {
      SneakyItemsAPI.itemRules(owner, "more", listOf("orb_1"), ItemRules.builder().build())
    }
    val second = group(ItemRules.builder())
    assertFalse(first.isActive)
    assertTrue(second.isActive)
  }

  @Test
  fun `carry limit refuses a second pickup, bypass and permission exempt`() {
    var exempt = false
    group(ItemRules.builder().carryLimit(1).bypass { exempt })
    player.inventory.addItem(SneakyItemsAPI.getItemStack("orb_1")!!)
    assertTrue(pickup(player, SneakyItemsAPI.getItemStack("orb_2")!!).isCancelled)
    assertEquals(listOf(RuleRefusal.CARRY_LIMIT), refusals)
    exempt = true
    assertFalse(pickup(player, SneakyItemsAPI.getItemStack("orb_2")!!).isCancelled)
  }

  @Test
  fun `no containers - placing into a chest and moving by hopper are cancelled`() {
    group(ItemRules.builder().noContainers(true))
    val chest = server.createInventory(null, InventoryType.CHEST)
    val view = chestView(chest)
    player.setItemOnCursor(SneakyItemsAPI.getItemStack("orb_1"))
    val click = InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PLACE_ALL)
    server.pluginManager.callEvent(click)
    assertTrue(click.isCancelled)
    assertEquals(listOf(RuleRefusal.CONTAINER), refusals)

    val move = InventoryMoveItemEvent(chest, SneakyItemsAPI.getItemStack("orb_2")!!, server.createInventory(null, InventoryType.HOPPER), true)
    server.pluginManager.callEvent(move)
    assertTrue(move.isCancelled)
  }

  @Test
  fun `other items are left alone`() {
    group(ItemRules.builder().noContainers(true).carryLimit(1))
    val chest = server.createInventory(null, InventoryType.CHEST)
    val view = chestView(chest)
    player.setItemOnCursor(SneakyItemsAPI.getItemStack("other"))
    val click = InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PLACE_ALL)
    server.pluginManager.callEvent(click)
    assertFalse(click.isCancelled)
  }

  @Test
  fun `protected on the ground - no despawn, no hopper pickup, SneakyGroundItems opt-out`() {
    // MockBukkit's ItemMock doesn't implement setUnlimitedLifetime, so the item is dropped before the group exists
    // and the invulnerable / no-mob-pickup flags are only checked in game.
    val item = world.dropItem(Location(world, 0.0, 70.0, 0.0), SneakyItemsAPI.getItemStack("orb_1")!!)
    val g = group(ItemRules.builder().protectOnGround(true))
    val despawn = ItemDespawnEvent(item, item.location)
    server.pluginManager.callEvent(despawn)
    assertTrue(despawn.isCancelled)
    val hopper = org.bukkit.event.inventory.InventoryPickupItemEvent(server.createInventory(null, InventoryType.HOPPER), item)
    server.pluginManager.callEvent(hopper)
    assertTrue(hopper.isCancelled)
    val made = g.create("orb_2")!!
    assertEquals("false", made.persistentDataContainer.get(ItemRulesService.GROUND_PLACEABLE, PersistentDataType.STRING))
  }

  @Test
  fun `unique - each give makes a new live serial, older copies go stale and vanish on join`() {
    val g = group(ItemRules.builder().unique(true))
    assertTrue(g.give("orb_1", player))
    val first = player.inventory.contents.first { g.isMember(it) }!!.clone()
    assertTrue(g.isLive(first))
    assertEquals(UniqueWhere.HELD, g.state("orb_1").where)

    val bob = server.addPlayer("bob")
    g.give("orb_1", bob)
    assertFalse(g.isLive(first))
    assertNotEquals(g.serial(first), g.state("orb_1").serial)
    assertEquals(bob.uniqueId, g.state("orb_1").holder)

    server.pluginManager.callEvent(PlayerJoinEvent(player, null as net.kyori.adventure.text.Component?))
    assertTrue(player.inventory.contents.none { g.isMember(it) }, "the stale copy is gone")
    assertFalse(g.isLive(SneakyItemsAPI.getItemStack("orb_1")), "a plain definition stack has no serial")
  }

  @Test
  fun `unique - stale pickups are removed`() {
    val g = group(ItemRules.builder().unique(true))
    g.give("orb_1", player)
    val stale = player.inventory.contents.first { g.isMember(it) }!!.clone()
    g.give("orb_1", player) // new serial
    val e = pickup(server.addPlayer("bob"), stale)
    assertTrue(e.isCancelled)
    assertTrue(e.item.isDead || !e.item.isValid)
  }

  @Test
  fun `unique - state survives a reload of the group`() {
    val g = group(ItemRules.builder().unique(true))
    g.respawn("orb_2", Location(world, 5.0, 70.0, 5.0))
    val serial = g.state("orb_2").serial
    g.close()
    val again = group(ItemRules.builder().unique(true))
    assertEquals(serial, again.state("orb_2").serial)
    assertEquals(UniqueWhere.GROUND, again.state("orb_2").where)
    assertEquals(5.0, again.state("orb_2").location!!.x, 0.01)
  }

  @Test
  fun `unique - adopt keeps a migrated serial only when there is none`() {
    val g = group(ItemRules.builder().unique(true))
    assertTrue(g.adopt("orb_1", "old-serial"))
    assertEquals("old-serial", g.state("orb_1").serial)
    assertFalse(g.adopt("orb_1", "other"))
  }

  @Test
  fun `drop on leave - death and quit put the items on the ground`() {
    val g = group(ItemRules.builder().dropOnLeave(true).unique(true))
    g.give("orb_1", player)
    val drops = player.inventory.contents.filterNotNull().map { it.clone() }.toMutableList()
    val death = PlayerDeathEvent(player, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC).build(), drops, 0, null as String?)
    server.pluginManager.callEvent(death)
    assertTrue(death.drops.none { g.isMember(it) })
    assertTrue(player.inventory.contents.none { g.isMember(it) })
    assertEquals(UniqueWhere.GROUND, g.state("orb_1").where)

    val bob = server.addPlayer("bob")
    g.give("orb_2", bob)
    server.pluginManager.callEvent(PlayerQuitEvent(bob, null as net.kyori.adventure.text.Component?, PlayerQuitEvent.QuitReason.DISCONNECTED))
    assertEquals(UniqueWhere.GROUND, g.state("orb_2").where)
  }

  @Test
  fun `placing and the group close when the owner disables`() {
    val g = group(ItemRules.builder().unique(true).placement(com.sneakyitems.api.Placement()))
    assertTrue(g.place("orb_1", Location(world, 1.5, 65.0, 1.5)))
    assertEquals(UniqueWhere.PLACED, g.state("orb_1").where)
    assertNotNull(g.body("orb_1"))
    server.pluginManager.disablePlugin(owner)
    assertFalse(g.isActive)
    assertEquals(0, plugin.rules.size)
  }

  @Test
  fun `watch membership - entered on arrival, left on close`() {
    val entered = ArrayList<String>()
    val left = ArrayList<String>()
    player.inventory.setItemInMainHand(SneakyItemsAPI.getItemStack("orb_1"))
    val watch = SneakyItemsAPI.whileHolding(owner, "orb_1", HeldSlot.EITHER_HAND, 2, { }, PlayerTimerOptions.DEFAULT,
        object : WatchMembership {
          override fun entered(player: Player) { entered += player.name }
          override fun left(player: Player) { left += player.name }
        })
    assertEquals(listOf("alice"), entered)
    watch.close()
    assertEquals(listOf("alice"), left)
  }
}
