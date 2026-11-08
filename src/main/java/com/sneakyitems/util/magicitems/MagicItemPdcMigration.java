package com.sneakyitems.util.magicitems;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;

import com.sneakyitems.util.ItemPdcKeys;

/** PDC migration policy for rebuilding a magic item from its definition. */
final class MagicItemPdcMigration {
    private MagicItemPdcMigration() {}

    static void copy(PersistentDataContainer source, PersistentDataContainer target, String namespace) {
        // Bukkit copies opaque primitive values, including custom adapters and nested containers.
        // Filter a detached copy so the original item is never modified.
        PersistentDataContainer carried = target.getAdapterContext().newPersistentDataContainer();
        source.copyTo(carried, true);
        for (NamespacedKey key : carried.getKeys()) {
            if (key.getNamespace().equals(namespace)
                    && (key.getKey().startsWith(ItemPdcKeys.PERMANENT_PREFIX)
                    || key.getKey().equals("tags")
                    || key.getKey().equals("magicitem") || key.getKey().equals("magicitem_version"))) {
                carried.remove(key);
            }
        }
        carried.copyTo(target, true);
    }
}
