package com.sneakyitems.api;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import org.bukkit.inventory.Inventory;

import com.sneakyutils.util.DisplayOnlyInventoryHolder;

/**
 * Optional integration hooks (e.g. MagicsSpells menu inventories).
 *
 * <p>GUIs whose holder implements {@link DisplayOnlyInventoryHolder} are skipped without
 * registering a predicate here; prefer that marker for new menus.
 */
public final class SneakyItemsHooks {

    private static final CopyOnWriteArrayList<Predicate<Inventory>> menuInventoryChecks = new CopyOnWriteArrayList<>();

    private SneakyItemsHooks() {}

    public static void registerMenuInventorySkip(Predicate<Inventory> predicate) {
        if (predicate != null) {
            menuInventoryChecks.add(predicate);
        }
    }

    public static void unregisterMenuInventorySkip(Predicate<Inventory> predicate) {
        menuInventoryChecks.remove(predicate);
    }

    public static boolean isMenuInventory(Inventory inventory) {
        if (inventory == null) return false;
        if (DisplayOnlyInventoryHolder.isDisplayOnly(inventory)) return true;
        for (Predicate<Inventory> check : menuInventoryChecks) {
            try {
                if (check.test(inventory)) return true;
            } catch (RuntimeException ignored) {
                // consumer plugins must not break item updates
            }
        }
        return false;
    }
}
