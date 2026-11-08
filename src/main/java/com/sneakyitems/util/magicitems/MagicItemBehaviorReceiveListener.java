package com.sneakyitems.util.magicitems;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.inventory.ItemStack;

public class MagicItemBehaviorReceiveListener implements Listener {

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	private void onCraft(CraftItemEvent event) {
		if (!(event.getWhoClicked() instanceof Player player))
			return;

		ItemStack result = event.getCurrentItem();
		if (result == null)
			return;

		ItemStack copy = result.clone();
		MagicItemBehaviors.applyMissingFromRegistry(copy, player);
		event.setCurrentItem(copy);
		MagicItemExpirationScheduler.scheduleFromItem(player, copy);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	private void onPickup(EntityPickupItemEvent event) {
		if (!(event.getEntity() instanceof Player player))
			return;

		ItemStack item = event.getItem().getItemStack();
		MagicItemBehaviors.applyMissingFromRegistry(item, player);
		event.getItem().setItemStack(item);
	}

}
