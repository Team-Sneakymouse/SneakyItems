package com.sneakyitems.api

import com.sneakyitems.SneakyItems
import com.sneakyitems.util.magicitems.MagicItemBehaviorKeys
import com.sneakyitems.util.magicitems.MagicItemTagging
import com.sneakyitems.util.magicitems.MagicItemUpdater
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit

class LegacyMappingsApiTest {

    private lateinit var plugin: SneakyItems
    private val awardKey = NamespacedKey("sneakyoffice", "award")
    private val awardIdKey = NamespacedKey("sneakyoffice", "award_id")

    @BeforeEach
    fun setup() {
        MockBukkit.mock()
        plugin = MockBukkit.load(SneakyItems::class.java)
    }

    @AfterEach
    fun cleanup() {
        SneakyItemsAPI.clearOwner(plugin)
        MockBukkit.unmock()
    }

    private fun paper(name: String, model: String? = null): ItemStack {
        val it = ItemStack(Material.PAPER)
        it.editMeta { m ->
            m.itemName(Component.text(name))
            if (model != null) m.itemModel = NamespacedKey.fromString(model)
        }
        return it
    }

    private fun oldAward(id: String): ItemStack = paper("Employee of the Month").apply {
        editPersistentDataContainer {
            it.set(awardKey, PersistentDataType.BYTE, 1)
            it.set(awardIdKey, PersistentDataType.STRING, id)
        }
    }

    @Test
    fun `a key mapping stamps old copies and the rebuild keeps per-copy keys`() {
        SneakyItemsAPI.registerFromStack(plugin, "magicspells", "test-award", paper("Award"))
        SneakyItemsAPI.registerLegacyKey(plugin, awardKey, "test-award")

        val result = MagicItemTagging.applyTagIfNeeded(oldAward("abc"))
        assertTrue(result.tagged)
        assertEquals("test-award", SneakyItemsAPI.keyOf(result.stack))

        val rebuilt = MagicItemUpdater.updateItem(result.stack, SneakyItemsAPI.getByInternalName("test-award")!!)
        assertEquals("test-award", SneakyItemsAPI.keyOf(rebuilt))
        assertEquals("abc", rebuilt.persistentDataContainer.get(awardIdKey, PersistentDataType.STRING))
    }

    @Test
    fun `a key mapping waits until its definition is registered`() {
        SneakyItemsAPI.registerLegacyKey(plugin, awardKey, "test-award-later")
        assertFalse(MagicItemTagging.applyTagIfNeeded(oldAward("abc")).tagged)

        SneakyItemsAPI.registerFromStack(plugin, "magicspells", "test-award-later", paper("Award"))
        assertTrue(MagicItemTagging.applyTagIfNeeded(oldAward("abc")).tagged)
    }

    @Test
    fun `a pattern mapping stamps look-only items with that model`() {
        SneakyItemsAPI.registerFromStack(plugin, "magicspells", "test-whistle", paper("Whistle", "lom:coach_whistle"))
        assertTrue(SneakyItemsAPI.registerLegacyPattern(plugin, "test-whistle", "paper{\"item-model\":\"lom:coach_whistle\"}"))

        val old = MagicItemTagging.applyTagIfNeeded(paper("Coach's Whistle", "lom:coach_whistle"))
        assertTrue(old.tagged)
        assertEquals("test-whistle", SneakyItemsAPI.keyOf(old.stack))
        assertFalse(MagicItemTagging.applyTagIfNeeded(paper("Something else", "lom:other")).tagged)
    }

    @Test
    fun `a pattern that doesn't parse is refused`() {
        assertFalse(SneakyItemsAPI.registerLegacyPattern(plugin, "x", "not_a_material{"))
    }

    @Test
    fun `clearOwner drops the owner's mappings but not the YAML file's`() {
        val before = MagicItemTagging.mappingCount()
        SneakyItemsAPI.registerFromStack(plugin, "magicspells", "test-award-clear", paper("Award"))
        SneakyItemsAPI.registerLegacyKey(plugin, awardKey, "test-award-clear")
        SneakyItemsAPI.registerLegacyPattern(plugin, "test-award-clear", "paper{\"item-model\":\"lom:x\"}")
        assertEquals(before + 2, MagicItemTagging.mappingCount())

        SneakyItemsAPI.clearOwner(plugin)
        assertEquals(before, MagicItemTagging.mappingCount())
        assertFalse(MagicItemTagging.applyTagIfNeeded(oldAward("abc")).tagged)
    }

    @Test
    fun `keyOf stays the identity after tagging`() {
        // sanity: the stamped key is the one keyOf reads
        SneakyItemsAPI.registerFromStack(plugin, "magicspells", "test-award-key", paper("Award"))
        SneakyItemsAPI.registerLegacyKey(plugin, awardKey, "test-award-key")
        val stamped = MagicItemTagging.applyTagIfNeeded(oldAward("z")).stack!!
        assertEquals("test-award-key", stamped.persistentDataContainer.get(MagicItemBehaviorKeys.magicItem(), PersistentDataType.STRING))
    }
}
