package com.sneakyitems.api

import com.sneakyitems.SneakyItems
import com.sneakyitems.util.magicitems.MagicItemBehaviorKeys
import org.bukkit.Material
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.entity.PlayerMock

class MagicItemLookupTest {

    private lateinit var player: PlayerMock

    @BeforeEach
    fun setup() {
        val server = MockBukkit.mock()
        MockBukkit.load(SneakyItems::class.java)
        player = server.addPlayer()
    }

    @AfterEach
    fun cleanup() {
        MockBukkit.unmock()
    }

    private fun magic(key: String, amount: Int = 1): ItemStack {
        val stack = ItemStack(Material.STICK, amount)
        stack.editPersistentDataContainer { it.set(MagicItemBehaviorKeys.magicItem(), PersistentDataType.STRING, key) }
        return stack
    }

    @Test
    fun `keyOf reads the stamped name and ignores plain or empty stacks`() {
        assertEquals("wand", SneakyItemsAPI.keyOf(magic("wand")))
        assertNull(SneakyItemsAPI.keyOf(ItemStack(Material.STICK)))
        assertNull(SneakyItemsAPI.keyOf(ItemStack.empty()))
        assertNull(SneakyItemsAPI.keyOf(null))
        assertTrue(SneakyItemsAPI.isItem(magic("wand"), "wand"))
        assertFalse(SneakyItemsAPI.isItem(magic("wand"), "tome"))
    }

    @Test
    fun `hand checks`() {
        player.inventory.setItemInMainHand(magic("wand"))
        player.inventory.setItemInOffHand(magic("tome"))
        assertTrue(SneakyItemsAPI.inMainHand(player, "wand"))
        assertFalse(SneakyItemsAPI.inMainHand(player, "tome"))
        assertTrue(SneakyItemsAPI.inOffHand(player, "tome"))
        assertTrue(SneakyItemsAPI.inEitherHand(player, "tome"))
        assertTrue(SneakyItemsAPI.inEitherHand(player, "wand"))
        assertFalse(SneakyItemsAPI.inEitherHand(player, "orb"))
    }

    @Test
    fun `hotbar is slots 0 to 8 only`() {
        player.inventory.setItem(8, magic("wand"))
        player.inventory.setItem(9, magic("tome"))
        assertTrue(SneakyItemsAPI.onHotbar(player, "wand"))
        assertFalse(SneakyItemsAPI.onHotbar(player, "tome"))
    }

    @Test
    fun `inventory includes storage and offhand but not armor`() {
        player.inventory.setItem(35, magic("tome", 3))
        player.inventory.setItem(2, magic("tome", 2))
        player.inventory.setItemInOffHand(magic("tome"))
        player.inventory.setHelmet(magic("crown"))
        assertTrue(SneakyItemsAPI.inInventory(player, "tome"))
        assertFalse(SneakyItemsAPI.inInventory(player, "crown"))
        assertEquals(listOf(2, 35, 40), SneakyItemsAPI.findSlots(player, "tome"))
        assertEquals(6, SneakyItemsAPI.count(player, "tome"))
    }

    @Test
    fun `wearing checks any armor slot or the given one`() {
        player.inventory.setHelmet(magic("crown"))
        assertTrue(SneakyItemsAPI.wearing(player, "crown"))
        assertTrue(SneakyItemsAPI.wearing(player, "crown", EquipmentSlot.HEAD))
        assertFalse(SneakyItemsAPI.wearing(player, "crown", EquipmentSlot.FEET))
        assertThrows(IllegalArgumentException::class.java) { SneakyItemsAPI.wearing(player, "crown", EquipmentSlot.HAND) }
    }
}
