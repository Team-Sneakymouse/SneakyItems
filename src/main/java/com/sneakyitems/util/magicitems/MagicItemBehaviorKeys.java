package com.sneakyitems.util.magicitems;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import com.sneakyitems.util.items.ItemBehaviors;

public final class MagicItemBehaviorKeys {

    private static volatile String pdcNamespace = "magicspells";

    private static final NamespacedKey SOULBOUND_OWNER = ItemBehaviors.SOULBOUND_OWNER;
    private static final NamespacedKey EXPIRES_AT = ItemBehaviors.EXPIRES_AT;

    private MagicItemBehaviorKeys() {}

    public static void setPdcNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) return;
        pdcNamespace = namespace;
    }

    public static String getPdcNamespace() {
        return pdcNamespace;
    }

    public static NamespacedKey magicItem() {
        return key("magicitem");
    }

    public static NamespacedKey magicItemVersion() {
        return key("magicitem_version");
    }

    public static NamespacedKey soulboundOwner() {
        return SOULBOUND_OWNER;
    }

    public static NamespacedKey expiresAt() {
        return EXPIRES_AT;
    }

    public static NamespacedKey creatorName() {
        return key("creator_name");
    }

    public static NamespacedKey ignoredAttributes() {
        return key("ignored_attributes");
    }

    public static NamespacedKey key(String local) {
        return new NamespacedKey(pdcNamespace, local);
    }

    public static String getMagicItemName(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return null;

        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return null;
        PersistentDataContainer container = meta.getPersistentDataContainer();
        String fromActive = container.get(magicItem(), PersistentDataType.STRING);
        if (fromActive != null) return fromActive;

        if (!"magicspells".equals(pdcNamespace)) {
            NamespacedKey legacy = new NamespacedKey("magicspells", "magicitem");
            return container.get(legacy, PersistentDataType.STRING);
        }
        return null;
    }
}
