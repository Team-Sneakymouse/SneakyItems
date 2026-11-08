package com.sneakyitems.util.items;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import com.sneakyitems.platform.SneakyItemsPlatform;

/**
 * Schedules one delayed task per (player, expiresAt) pair so multiple stacks or
 * magic-item updates sharing the same expiry time do not register duplicate tasks.
 */
public final class ItemExpirationScheduler {

	private record ExpirationKey(UUID playerId, long expiresAt) {
	}

	private static final Map<ExpirationKey, Integer> scheduledTasks = new ConcurrentHashMap<>();

	private ItemExpirationScheduler() {
	}

	public static void shutdown() {
		for (int task : scheduledTasks.values()) Bukkit.getScheduler().cancelTask(task);
		scheduledTasks.clear();
	}

	public static void scheduleFromItem(@Nullable Player player, @Nullable ItemStack item) {
		if (player == null || item == null)
			return;

		Long expiresAt = getExpiresAt(item);
		if (expiresAt == null)
			return;

		scheduleForPlayer(player, expiresAt);
	}

	public static void scheduleForPlayer(Player player, long expiresAt) {
		if (player == null)
			return;

		long now = System.currentTimeMillis();
		if (expiresAt <= now) {
			purgeExpiredForPlayer(player);
			return;
		}

		ExpirationKey key = new ExpirationKey(player.getUniqueId(), expiresAt);
		scheduledTasks.computeIfAbsent(key, ignored -> {
			long delayTicks = Math.max(1L, (expiresAt - now + 49) / 50);
			if (delayTicks > Integer.MAX_VALUE)
				delayTicks = Integer.MAX_VALUE;

			return Bukkit.getScheduler().scheduleSyncDelayedTask(SneakyItemsPlatform.getInstance(), () -> {
				scheduledTasks.remove(key);
				Player online = Bukkit.getPlayer(key.playerId());
				if (online != null && online.isOnline())
					purgeExpiredForPlayer(online);
			}, delayTicks);
		});
	}

	public static void scheduleAllForPlayer(Player player) {
		if (player == null)
			return;

		PlayerInventory inv = player.getInventory();
		scheduleFromContents(player, inv.getStorageContents());
		scheduleFromContents(player, inv.getArmorContents());
		scheduleFromContents(player, inv.getExtraContents());
		scheduleFromContents(player, player.getEnderChest().getContents());
	}

	private static void scheduleFromContents(Player player, ItemStack[] contents) {
		if (contents == null)
			return;
		for (ItemStack item : contents)
			scheduleFromItem(player, item);
	}

	public static boolean purgeExpiredForPlayer(Player player) {
		if (player == null)
			return false;

		boolean changed = false;
		changed |= purgeInventory(player.getInventory());
		changed |= purgeInventory(player.getEnderChest());
		if (changed)
			player.updateInventory();
		return changed;
	}

	private static boolean purgeInventory(Inventory inv) {
		if (inv == null)
			return false;

		Player owner = inv.getHolder() instanceof Player player ? player : null;
		ItemStack[] contents = inv.getContents();
		boolean changed = false;
		for (int i = 0; i < contents.length; i++) {
			if (ItemBehaviors.updateExpiresLineIfNeeded(contents[i], owner)
					== ItemBehaviors.ExpirationResult.EXPIRED) {
				contents[i] = null;
				changed = true;
			}
		}
		if (changed)
			inv.setContents(contents);
		return changed;
	}

	@Nullable
	private static Long getExpiresAt(ItemStack item) {
		if (item == null || !item.hasItemMeta())
			return null;
		return item.getItemMeta().getPersistentDataContainer()
				.get(ItemBehaviors.EXPIRES_AT, PersistentDataType.LONG);
	}

}
