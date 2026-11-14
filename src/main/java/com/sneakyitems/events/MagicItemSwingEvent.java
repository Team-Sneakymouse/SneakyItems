package com.sneakyitems.events;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * A player swung their main arm while holding a registered magic item.
 *
 * <p>Raw: every main-hand arm swing counts, whatever {@code click-events.cast-on-animate} says, except the swing that
 * comes with dropping an item. A swing is not necessarily a left click (right-clicking a block or using an item swings
 * the arm too); use {@link MagicItemClickEvent} for clicks. Fired one tick after the swing (to recognise drops), so it
 * can't be cancelled.
 */
public class MagicItemSwingEvent extends PlayerEvent {

	private static final HandlerList handlers = new HandlerList();

	private final String itemKey;
	private final ItemStack item;

	public MagicItemSwingEvent(@NotNull Player player, @NotNull String itemKey, @NotNull ItemStack item) {
		super(player);
		this.itemKey = itemKey;
		this.item = item;
	}

	/** Internal name of the magic item (its stamped {@code magicitem} PDC value). */
	@NotNull
	public String getItemKey() {
		return itemKey;
	}

	/** The main-hand stack when the event fired (the live inventory stack, not a copy). */
	@NotNull
	public ItemStack getItem() {
		return item;
	}

	@Override
	public @NotNull HandlerList getHandlers() {
		return handlers;
	}

	public static HandlerList getHandlerList() {
		return handlers;
	}

}
