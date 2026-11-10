package com.sneakyitems.api

import com.sneakyitems.util.magicitems.MagicItemData
import com.sneakyitems.util.magicitems.MagicItemDataParser
import com.sneakyitems.util.magicitems.MagicItemUpdater
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit

class RegisterFromStackTest {
    @BeforeEach fun setup() { MockBukkit.mock() }
    @AfterEach fun cleanup() { MockBukkit.unmock() }

    private fun template(name: String, lore: String): ItemStack {
        val it = ItemStack(Material.ENDER_EYE)
        val m = it.itemMeta
        m.itemName(Component.text(name, NamedTextColor.DARK_GREEN))
        m.lore(listOf(Component.text(lore)))
        it.itemMeta = m
        return it
    }

    @Test fun `registerFromStack keeps item_name and lore`() {
        val plugin = MockBukkit.load(com.sneakyitems.SneakyItems::class.java)
        SneakyItemsAPI.registerFromStack(plugin, "magicspells", "test-book", template("Rune Book", "line one"))
        val made = SneakyItemsAPI.getByInternalName("test-book")!!.itemStack
        assertEquals(Component.text("Rune Book", NamedTextColor.DARK_GREEN), made.itemMeta.itemName())
        assertEquals(listOf<Component>(Component.text("line one")), made.itemMeta.lore())
    }

    @Test fun `registerFromStack versions the def so changed templates reach held copies`() {
        val plugin = MockBukkit.load(com.sneakyitems.SneakyItems::class.java)
        SneakyItemsAPI.registerFromStack(plugin, "magicspells", "test-book-versioned", template("Old", "old lore"))
        val oldDef = SneakyItemsAPI.getByInternalName("test-book-versioned")!!
        assertNotEquals(0L, oldDef.definitionVersion)
        val held = oldDef.itemStack.clone()

        SneakyItemsAPI.clearOwner(plugin)
        SneakyItemsAPI.registerFromStack(plugin, "magicspells", "test-book-versioned", template("Rune Book", "new lore"))
        val newDef = SneakyItemsAPI.getByInternalName("test-book-versioned")!!
        assertNotEquals(oldDef.definitionVersion, newDef.definitionVersion)

        val updated = MagicItemUpdater.updateItem(held, newDef)
        assertEquals(Component.text("Rune Book", NamedTextColor.DARK_GREEN), updated.itemMeta.itemName())
        assertEquals(listOf<Component>(Component.text("new lore")), updated.itemMeta.lore())
    }

    @Test fun `item-name round-trips through the data string`() {
        val data = MagicItemData()
        data.setAttribute(MagicItemData.MagicItemAttribute.TYPE, Material.ENDER_EYE)
        data.setAttribute(MagicItemData.MagicItemAttribute.ITEM_NAME, Component.text("Rune Book"))
        val parsed = MagicItemDataParser.parseMagicItemData(data.toString())!!
        assertTrue(parsed.hasAttribute(MagicItemData.MagicItemAttribute.ITEM_NAME))
    }
}
