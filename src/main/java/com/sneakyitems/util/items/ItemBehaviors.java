package com.sneakyitems.util.items;

import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** Shared ItemStack behaviors. Call on the server thread; legacy persisted keys are immutable. */
public final class ItemBehaviors {
    public static final NamespacedKey SOULBOUND_OWNER = NamespacedKey.fromString("magicspells:soulbound_owner");
    public static final NamespacedKey EXPIRES_AT = NamespacedKey.fromString("magicspells:expires_at");
    private ItemBehaviors() {}

    public static boolean hasExpiration(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer()
                .has(EXPIRES_AT, PersistentDataType.LONG);
    }
    public static void applyExpiration(ItemStack item, double durationMs) {
        if (!hasExpiration(item)) applyExpirationOverlay(item, durationMs);
    }
    public static void applyExpirationOverlay(ItemStack item, double durationMs) {
        if (item == null || item.getType().isAir() || durationMs <= 0) return;
        ItemMeta meta = item.getItemMeta();
        long deadline = System.currentTimeMillis() + (long) durationMs;
        meta.getPersistentDataContainer().set(EXPIRES_AT, PersistentDataType.LONG, deadline);
        java.util.List<net.kyori.adventure.text.Component> lore = meta.lore();
        if (lore == null) lore = new java.util.ArrayList<>();
        lore.add(com.sneakyutils.util.TextUtility.INSTANCE.convertToComponent(getExpiresText(deadline)));
        meta.lore(lore);
        item.setItemMeta(meta);
    }
    public static String getExpiresText(long expiresAt) {
        long remaining = expiresAt - System.currentTimeMillis();
        if (remaining < 0) return "<gray>Expired";
        double hours = remaining / 3600000.0;
        String value;
        if (hours / 24 >= 15) value = (long) hours / 168 + "<gray> weeks";
        else if (hours / 24 >= 3) value = (long) hours / 24 + "<gray> days";
        else if (hours >= 2) value = (long) hours + "<gray> hours";
        else if (hours >= 1) value = "1<gray> hour";
        else if (remaining >= 120000) value = remaining / 60000 + "<gray> minutes";
        else if (remaining >= 60000) value = "1<gray> minute";
        else if (remaining >= 2000) value = remaining / 1000 + "<gray> seconds";
        else value = "1<gray> second";
        return "<gray>Expires in <white>" + value;
    }

    public enum ExpirationResult { NO_UPDATE, UPDATE, EXPIRED }

    public static ExpirationResult updateExpiresLineIfNeeded(ItemStack item) {
        return updateExpiresLineIfNeeded(item, null);
    }
    public static ExpirationResult updateExpiresLineIfNeeded(ItemStack item, Player owner) {
        if (!hasExpiration(item)) return ExpirationResult.NO_UPDATE;
        ItemMeta meta = item.getItemMeta();
        long deadline = meta.getPersistentDataContainer().get(EXPIRES_AT, PersistentDataType.LONG);
        if (deadline < System.currentTimeMillis()) {
            if (owner != null) org.bukkit.Bukkit.getPluginManager().callEvent(
                    new com.sneakyitems.events.ItemExpireEvent(owner, item));
            return ExpirationResult.EXPIRED;
        }
        java.util.List<net.kyori.adventure.text.Component> lore = meta.lore();
        if (lore == null) lore = new java.util.ArrayList<>();
        var line = com.sneakyutils.util.TextUtility.INSTANCE.convertToComponent(getExpiresText(deadline));
        if (!lore.isEmpty() && lore.getLast() instanceof net.kyori.adventure.text.TextComponent text
                && text.content().contains("Expires in ")) lore.set(lore.size() - 1, line);
        else lore.add(line);
        meta.lore(lore);
        item.setItemMeta(meta);
        if (owner != null) ItemExpirationScheduler.scheduleFromItem(owner, item);
        return ExpirationResult.UPDATE;
    }

    public static boolean hasSoulbound(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer()
                .has(SOULBOUND_OWNER, PersistentDataType.STRING);
    }
    public static void applySoulbound(ItemStack item, Player owner) {
        if (owner != null) applySoulbound(item, owner.getUniqueId());
    }
    public static void applySoulbound(ItemStack item, UUID owner) {
        if (!hasSoulbound(item)) applySoulboundOverlay(item, owner);
    }
    public static void applySoulboundOverlay(ItemStack item, Player owner) {
        if (owner != null) applySoulboundOverlay(item, owner.getUniqueId());
    }
    public static void applySoulboundOverlay(ItemStack item, UUID owner) {
        if (item == null || item.getType().isAir() || owner == null) return;
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(SOULBOUND_OWNER, PersistentDataType.STRING, owner.toString());
        item.setItemMeta(meta);
    }
}
