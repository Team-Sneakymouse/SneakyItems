package com.sneakyitems.events;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Fired (synchronously, on the main thread) when the number of one magic item a player carries changes.
 *
 * <p>"Carried" means the player's own inventory: storage, hotbar, armor and offhand. The cursor, open containers and
 * bags are not counted. An item is counted when SneakyItems says it is {@code itemId} (the stamped {@code magicitem}
 * PDC value, see {@code SneakyItemsAPI.keyOf}).
 *
 * <p>Counts are updated from {@code PlayerInventorySlotChangeEvent}, which Paper fires when it next syncs the
 * player's menu (the same tick or the next one), not at the moment a plugin calls {@code setItem}. This event fires
 * when the change is noticed: on the next scheduler pass after that sync, or earlier if something queries the count
 * first. One event per item id whose count changed, so several may fire for one inventory change. A newly tracked
 * player's first count is reported as a change from 0. Nothing fires when a player quits; their counts are dropped.
 *
 * <p>SneakyItems builds the event only when something listens, so keep handlers cheap.
 */
public class CarriedItemChangeEvent extends PlayerEvent {

	private static final HandlerList HANDLERS = new HandlerList();

	private final String itemId;
	private final int oldCount;
	private final int newCount;

	public CarriedItemChangeEvent(@NotNull Player player, @NotNull String itemId, int oldCount, int newCount) {
		super(player);
		this.itemId = itemId;
		this.oldCount = oldCount;
		this.newCount = newCount;
	}

	/** Internal name of the magic item whose count changed. */
	@NotNull
	public String getItemId() {
		return itemId;
	}

	/** Count carried before the change (0 when the player did not carry the item). */
	public int getOldCount() {
		return oldCount;
	}

	/** Count carried after the change (0 when the player no longer carries the item). */
	public int getNewCount() {
		return newCount;
	}

	@Override
	public @NotNull HandlerList getHandlers() {
		return HANDLERS;
	}

	public static HandlerList getHandlerList() {
		return HANDLERS;
	}

}
