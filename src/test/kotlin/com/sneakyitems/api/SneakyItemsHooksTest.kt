package com.sneakyitems.api

import com.sneakyutils.util.DisplayOnlyInventoryHolder
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class SneakyItemsHooksTest {
    private class MenuHolder : DisplayOnlyInventoryHolder {
        override fun getInventory(): Inventory = throw UnsupportedOperationException()
    }

    private class StorageHolder : InventoryHolder {
        override fun getInventory(): Inventory = throw UnsupportedOperationException()
    }

    /** MockBukkit does not implement `getHolder(false)`; answer holder lookups only. */
    private fun inventoryHeldBy(holder: InventoryHolder): Inventory =
        Proxy.newProxyInstance(Inventory::class.java.classLoader, arrayOf(Inventory::class.java)) { _, method, _ ->
            if (method.name == "getHolder") holder else throw UnsupportedOperationException(method.name)
        } as Inventory

    @Test
    fun `display-only GUI holders count as menu inventories without a registered predicate`() {
        assertTrue(SneakyItemsHooks.isMenuInventory(inventoryHeldBy(MenuHolder())))
    }

    @Test
    fun `storage holders are still scanned`() {
        assertFalse(SneakyItemsHooks.isMenuInventory(inventoryHeldBy(StorageHolder())))
    }
}
