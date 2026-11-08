package com.sneakyitems.util.items;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;



public class ItemSoulboundListener implements Listener {

	@EventHandler(priority = EventPriority.LOWEST)
	private void onJoin(PlayerJoinEvent event) {
		processInventory(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.LOWEST)
	private void onInvOpen(InventoryOpenEvent event) {
		if (event.getPlayer() instanceof Player player) {
			Inventory inv = event.getInventory();
			if (inv instanceof org.bukkit.inventory.PlayerInventory || inv.getType() == InventoryType.ENDER_CHEST) {
				processInventory(player, inv);
			}
		}
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	private void onClick(InventoryClickEvent event) {
		if (!(event.getWhoClicked() instanceof Player player))
			return;
		if (isInvalidSoulbound(event.getCurrentItem(), player) || isInvalidSoulbound(event.getCursor(), player)) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	private void onDrag(InventoryDragEvent event) {
		if (!(event.getWhoClicked() instanceof Player player))
			return;
		if (isInvalidSoulbound(event.getOldCursor(), player)) {
			event.setCancelled(true);
			return;
		}
		for (ItemStack item : event.getNewItems().values()) {
			if (isSoulbound(item) && isInvalidSoulbound(item, player)) {
				event.setCancelled(true);
				return;
			}
		}
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	private void onPickup(EntityPickupItemEvent event) {
		ItemStack item = event.getItem().getItemStack();
		if (!isSoulbound(item))
			return;

		if (!(event.getEntity() instanceof Player player) || isInvalidSoulbound(item, player)) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.LOWEST)
	private void onInteract(PlayerInteractEvent event) {
		if (!event.hasItem())
			return;
		if (isInvalidSoulbound(event.getItem(), event.getPlayer())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.LOWEST)
	private void onItemSwap(PlayerSwapHandItemsEvent event) {
		if (isInvalidSoulbound(event.getOffHandItem(), event.getPlayer())
				|| isInvalidSoulbound(event.getMainHandItem(), event.getPlayer())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.LOWEST)
	private void onHotbarScroll(PlayerItemHeldEvent event) {
		ItemStack item = event.getPlayer().getInventory().getItem(event.getNewSlot());
		if (isInvalidSoulbound(item, event.getPlayer())) {
			event.setCancelled(true);
		}
	}

	private void processInventory(Player player) {
		processInventory(player, player.getInventory());
		processInventory(player, player.getEnderChest());
	}

	private void processInventory(Player player, Inventory inv) {
		ItemStack[] contents = inv.getContents();
		boolean changed = false;
		for (int i = 0; i < contents.length; i++) {
			if (isInvalidSoulbound(contents[i], player)) {
				contents[i] = null;
				changed = true;
			}
		}
		if (changed)
			inv.setContents(contents);
	}

	private boolean isSoulbound(ItemStack item) {
		if (item == null || !item.hasItemMeta())
			return false;
		return item.getItemMeta().getPersistentDataContainer()
				.has(ItemBehaviors.SOULBOUND_OWNER, PersistentDataType.STRING);
	}

	private boolean isInvalidSoulbound(ItemStack item, Player player) {
		if (item == null || !item.hasItemMeta())
			return false;
		if (player.hasPermission("sneakyitems.nosoulbound") && player.getGameMode() == GameMode.CREATIVE)
			return false;
		String ownerUuid = item.getItemMeta().getPersistentDataContainer()
				.get(ItemBehaviors.SOULBOUND_OWNER, PersistentDataType.STRING);
		if (ownerUuid == null)
			return false;
		return !ownerUuid.equals(player.getUniqueId().toString());
	}

}
