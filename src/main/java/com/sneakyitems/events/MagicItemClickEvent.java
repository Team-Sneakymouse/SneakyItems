package com.sneakyitems.events;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A player left- or right-clicked while holding a registered magic item.
 *
 * <p>Fired by SneakyItems so plugins share one click implementation instead of each listening to
 * {@code PlayerInteractEvent} and friends. Listeners pick their own priority on this event; SneakyItems
 * fires it while handling the vanilla event at {@code NORMAL}, so protection plugins on {@code LOWEST}/{@code LOW}
 * have already run (see {@link #isAlreadyDenied()}).
 *
 * <ul>
 *   <li><b>Right</b>: at once, for each hand holding a magic item, from block/air and entity interacts. One physical
 *       click is reported once (an entity click plus the client's follow-up air/block click, or the
 *       interact-at plus interact pair, count as one).</li>
 *   <li><b>Left</b>: always the main hand. Block hits ({@link Trigger#BLOCK}) and entity hits
 *       ({@link Trigger#ENTITY}) fire at once and can be cancelled. Clicks only known from the arm swing
 *       ({@link Trigger#AIR}, or {@link Trigger#ARM_SWING} with {@code click-events.cast-on-animate}) fire a tick
 *       later, so drops, container/door clicks and the swing of a block/entity hit can be told apart.</li>
 *   <li><b>Hit point</b>: {@link #getInteractionPoint()} is the exact client hit on a block (from
 *       {@link PlayerInteractEvent#getInteractionPoint()}) when that is available; otherwise null.</li>
 * </ul>
 *
 * <p>Cancelling cancels the vanilla action where it is still pending (every trigger except the late
 * {@code AIR}/{@code ARM_SWING} left clicks, which only get marked consumed for later listeners using
 * {@code ignoreCancelled = true}). Interact triggers also offer {@link #setUseItemInHand} and
 * {@link #setUseInteractedBlock} for finer control. See {@link MagicItemSwingEvent} for raw swings.
 */
public class MagicItemClickEvent extends PlayerEvent implements Cancellable {

	public enum Click { LEFT, RIGHT }

	public enum Trigger {
		/** Clicked air (left: known from the arm swing, fired a tick late). */
		AIR,
		/** Clicked a block; {@link #getClickedBlock()} and {@link #getBlockFace()} are set. */
		BLOCK,
		/** Right-clicked or attacked an entity (any entity, hitboxes included); {@link #getClickedEntity()} is set. */
		ENTITY,
		/** Main-hand arm swing ({@code cast-on-animate}); no target information. */
		ARM_SWING
	}

	private static final HandlerList handlers = new HandlerList();

	private final String itemKey;
	private final ItemStack item;
	private final EquipmentSlot hand;
	private final Click click;
	private final Trigger trigger;
	private final Block clickedBlock;
	private final BlockFace blockFace;
	private final Entity clickedEntity;
	private final Location interactionPoint;
	private final PlayerInteractEvent interact;
	private final boolean alreadyDenied;
	private boolean cancelled;

	/**
	 * @param interactionPoint exact block hit when known; use {@code null} when the client did not send one
	 */
	public MagicItemClickEvent(
			@NotNull Player player,
			@NotNull String itemKey,
			@NotNull ItemStack item,
			@NotNull EquipmentSlot hand,
			@NotNull Click click,
			@NotNull Trigger trigger,
			@Nullable Block clickedBlock,
			@Nullable BlockFace blockFace,
			@Nullable Entity clickedEntity,
			@Nullable Location interactionPoint,
			@Nullable PlayerInteractEvent interact,
			boolean alreadyDenied) {
		super(player);
		this.itemKey = itemKey;
		this.item = item;
		this.hand = hand;
		this.click = click;
		this.trigger = trigger;
		this.clickedBlock = clickedBlock;
		this.blockFace = blockFace;
		this.clickedEntity = clickedEntity;
		this.interactionPoint = interactionPoint;
		this.interact = interact;
		this.alreadyDenied = alreadyDenied;
	}

	/**
	 * Convenience for callers without an explicit hit point: uses
	 * {@link PlayerInteractEvent#getInteractionPoint()} when {@code interact} is set.
	 */
	public MagicItemClickEvent(
			@NotNull Player player,
			@NotNull String itemKey,
			@NotNull ItemStack item,
			@NotNull EquipmentSlot hand,
			@NotNull Click click,
			@NotNull Trigger trigger,
			@Nullable Block clickedBlock,
			@Nullable BlockFace blockFace,
			@Nullable Entity clickedEntity,
			@Nullable PlayerInteractEvent interact,
			boolean alreadyDenied) {
		this(
				player, itemKey, item, hand, click, trigger, clickedBlock, blockFace, clickedEntity,
				interact != null ? interact.getInteractionPoint() : null, interact, alreadyDenied);
	}

	/** Internal name of the magic item (its stamped {@code magicitem} PDC value). */
	@NotNull
	public String getItemKey() {
		return itemKey;
	}

	/** The stack in {@link #getHand()} when the event fired (the live inventory stack, not a copy). */
	@NotNull
	public ItemStack getItem() {
		return item;
	}

	/** {@link EquipmentSlot#HAND} or {@link EquipmentSlot#OFF_HAND}. Left clicks are always {@code HAND}. */
	@NotNull
	public EquipmentSlot getHand() {
		return hand;
	}

	@NotNull
	public Click getClick() {
		return click;
	}

	public boolean isLeftClick() {
		return click == Click.LEFT;
	}

	public boolean isRightClick() {
		return click == Click.RIGHT;
	}

	@NotNull
	public Trigger getTrigger() {
		return trigger;
	}

	@Nullable
	public Block getClickedBlock() {
		return clickedBlock;
	}

	@Nullable
	public BlockFace getBlockFace() {
		return blockFace;
	}

	@Nullable
	public Entity getClickedEntity() {
		return clickedEntity;
	}

	/**
	 * Exact hit location on the clicked block when the client sent one (typically
	 * {@link PlayerInteractEvent#getInteractionPoint()} for {@link Trigger#BLOCK} / {@link Trigger#AIR} interacts
	 * fired at once). Null for late left clicks, entity triggers, arm swings, or when the vanilla event had no point.
	 */
	@Nullable
	public Location getInteractionPoint() {
		return interactionPoint;
	}

	/**
	 * True when the vanilla action was already denied before this event fired: for interacts, another plugin set
	 * {@code useItemInHand} to {@code DENY} (an air click arriving "cancelled" does not count); for entity clicks and
	 * attacks, the vanilla event was already cancelled. Region/protection plugins do this. Always false for the late
	 * {@code AIR}/{@code ARM_SWING} left clicks.
	 */
	public boolean isAlreadyDenied() {
		return alreadyDenied;
	}

	/** Whether {@link #setUseItemInHand}/{@link #setUseInteractedBlock} apply (block/air interact triggers fired at once). */
	public boolean hasInteractResults() {
		return interact != null;
	}

	/** The vanilla interact's item-use result, or null when this click did not come from an interact fired at once. */
	@Nullable
	public Event.Result getUseItemInHand() {
		return interact == null ? null : interact.useItemInHand();
	}

	/** Allow/deny using the item (eating, throwing, placing). Only for {@link #hasInteractResults()} clicks. */
	public void setUseItemInHand(@NotNull Event.Result result) {
		requireInteract().setUseItemInHand(result);
	}

	/** The vanilla interact's clicked-block result, or null when this click did not come from an interact fired at once. */
	@Nullable
	public Event.Result getUseInteractedBlock() {
		return interact == null ? null : interact.useInteractedBlock();
	}

	/** Allow/deny the clicked block's own use (doors, containers). Only for {@link #hasInteractResults()} clicks. */
	public void setUseInteractedBlock(@NotNull Event.Result result) {
		requireInteract().setUseInteractedBlock(result);
	}

	private PlayerInteractEvent requireInteract() {
		if (interact == null) throw new IllegalStateException("No interact results for a " + trigger + " " + click + " click");
		return interact;
	}

	@Override
	public boolean isCancelled() {
		return cancelled;
	}

	@Override
	public void setCancelled(boolean cancel) {
		this.cancelled = cancel;
	}

	@Override
	public @NotNull HandlerList getHandlers() {
		return handlers;
	}

	public static HandlerList getHandlerList() {
		return handlers;
	}

}
