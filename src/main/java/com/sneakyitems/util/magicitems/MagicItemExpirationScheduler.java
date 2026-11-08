package com.sneakyitems.util.magicitems;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import com.sneakyitems.util.items.ItemExpirationScheduler;

/** Compatibility facade over SneakyItems {@link ItemExpirationScheduler}. */
public final class MagicItemExpirationScheduler {
    private MagicItemExpirationScheduler() {}
    public static void scheduleFromItem(Player player, ItemStack item) { ItemExpirationScheduler.scheduleFromItem(player, item); }
    public static void scheduleForPlayer(Player player, long deadline) { ItemExpirationScheduler.scheduleForPlayer(player, deadline); }
    public static void scheduleAllForPlayer(Player player) { ItemExpirationScheduler.scheduleAllForPlayer(player); }
    public static boolean purgeExpiredForPlayer(Player player) { return ItemExpirationScheduler.purgeExpiredForPlayer(player); }
}
