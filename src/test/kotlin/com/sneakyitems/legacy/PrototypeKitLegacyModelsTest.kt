package com.sneakyitems.legacy

import com.sneakyitems.api.SneakyItemsAPI
import com.sneakyitems.platform.SneakyItemsPlatform
import com.sneakyitems.util.magicitems.MagicItemBehaviorKeys
import com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute
import com.sneakyitems.util.magicitems.MagicItemUpdater
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit

/** TEMPORARY: remove with [PrototypeKitLegacyModels]. */
class PrototypeKitLegacyModelsTest {
    @BeforeEach fun setup() { MockBukkit.mock() }
    @AfterEach fun cleanup() { MockBukkit.unmock() }

    private fun legacy(material: Material, cmd: Float, name: String?): ItemStack =
        ItemStack(material).apply {
            editMeta { m ->
                m.setCustomModelDataComponent(m.customModelDataComponent.also { it.floats = listOf(cmd) })
                if (name != null) {
                    m.persistentDataContainer.set(MagicItemBehaviorKeys.magicItem(), PersistentDataType.STRING, name)
                }
            }
        }

    @Test fun `exact and ranged routes resolve like the old pack`() {
        assertEquals(NamespacedKey.fromString("lom:tools/manastone1"), PrototypeKitLegacyModels.lookup(Material.AMETHYST_CLUSTER, 1))
        // apple 34..36 dispatched to pinkmilk; 5+ on amethyst_cluster is open-ended
        assertEquals(NamespacedKey.fromString("lom:items/pinkmilk"), PrototypeKitLegacyModels.lookup(Material.APPLE, 36))
        assertEquals(NamespacedKey.fromString("lom:tools/manastone5"), PrototypeKitLegacyModels.lookup(Material.AMETHYST_CLUSTER, 99))
        assertNull(PrototypeKitLegacyModels.lookup(Material.AMETHYST_CLUSTER, 0))
        assertNull(PrototypeKitLegacyModels.lookup(Material.DIRT, 1))
    }

    @Test fun `prototype-kit item swaps integer for item model`() {
        val stack = legacy(Material.AMETHYST_CLUSTER, 2f, "item-prototypekit-created-amethyst_cluster")
        val out = PrototypeKitLegacyModels.migrate(stack, "item-prototypekit-created-amethyst_cluster")
        assertNotSame(stack, out)
        assertEquals(NamespacedKey.fromString("lom:tools/manastone2"), out.itemMeta.itemModel)
        assertFalse(out.itemMeta.hasCustomModelDataComponent())
        assertTrue(stack.itemMeta.hasCustomModelDataComponent(), "input stack is left untouched")
    }

    @Test fun `other items, unmapped ints and items with a model are left alone`() {
        val other = legacy(Material.AMETHYST_CLUSTER, 2f, "item-something")
        assertSame(other, PrototypeKitLegacyModels.migrate(other, "item-something"))

        val unmapped = legacy(Material.DIRT, 2f, null)
        assertSame(unmapped, PrototypeKitLegacyModels.migrate(unmapped, "item-prototypekit-created-dirt"))

        val fractional = legacy(Material.AMETHYST_CLUSTER, 2.5f, null)
        assertSame(fractional, PrototypeKitLegacyModels.migrate(fractional, "item-prototypekit-created-amethyst_cluster"))

        val modelled = legacy(Material.AMETHYST_CLUSTER, 2f, null).apply {
            editMeta { it.itemModel = NamespacedKey.fromString("lom:tools/manastone4") }
        }
        assertSame(modelled, PrototypeKitLegacyModels.migrate(modelled, "item-prototypekit-created-amethyst_cluster"))
    }

    @Test fun `updater migrates a prototype-kit def that ignores its look`() {
        val plugin = MockBukkit.load(com.sneakyitems.SneakyItems::class.java)
        val name = "item-prototypekit-created-amethyst_cluster"
        val magic = SneakyItemsAPI.registerFromStack(plugin, "magicspells", name, ItemStack(Material.AMETHYST_CLUSTER))!!
        magic.magicItemData.ignoredAttributes.add(MagicItemAttribute.ITEM_MODEL)
        magic.magicItemData.ignoredAttributes.add(MagicItemAttribute.CUSTOM_MODEL_DATA)

        val previous = SneakyItemsPlatform.enableUpdateItemData()
        SneakyItemsPlatform.setEnableUpdateItemData(true)
        try {
            val out = MagicItemUpdater.processItemStack(legacy(Material.AMETHYST_CLUSTER, 3f, name), null)!!
            assertEquals(NamespacedKey.fromString("lom:tools/manastone3"), out.itemMeta.itemModel)
            assertFalse(out.itemMeta.hasCustomModelDataComponent())
        } finally {
            SneakyItemsPlatform.setEnableUpdateItemData(previous)
        }
    }
}
