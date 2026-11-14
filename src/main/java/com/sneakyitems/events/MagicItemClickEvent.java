package com.sneakyitems.events;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A player left- or right-clicked while holding a registered magic item.
 *
 * <p>Fired by SneakyItems so plugins share one click implementation instead of each
 * listening to {@code PlayerInteractEvent} and friends. The timing follows MagicSpells'
 * cast listeners (see the README, "Magic item clicks"):
 * <ul>
 *   <li><b>Right</b>: fired synchronously for each hand that holds a magic item, from block/air
 *       interacts and entity interacts.</li>
 *   <li><b>Left</b>: always the main hand. Fired one tick after the click (so drops and
 *       container/door clicks can suppress it). With {@code click-events.cast-on-animate} the
 *       trigger is the arm-swing animation; otherwise it is a left-click interact, or a melee hit
 *       (synchronous).</li>
 * </ul>
 *
 * <p>Cancelling cancels the vanilla action where it is still pending: right-click interacts,
 * right-click entity interacts, and melee hits (the damage is cancelled). Left clicks fired a tick
 * later have already been processed by vanilla; cancelling those only marks the click as consumed
 * so later listeners using {@code ignoreCancelled = true} skip it.
 */
public class MagicItemClickEvent extends PlayerEvent implements Cancellable {

	public enum Click { LEFT, RIGHT }

	public enum Trigger {
		/** Clicked air. */
		AIR,
		/** Clicked a block; {@link #getClickedBlock()} and {@link #getBlockFace()} are set. */
		BLOCK,
		/** Right-clicked or hit an entity; {@link #getClickedEntity()} is set. */
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
	private boolean cancelled;

	public MagicItemClickEvent(
			@NotNull Player player,
			@NotNull String itemKey,
			@NotNull ItemStack item,
			@NotNull EquipmentSlot hand,
			@NotNull Click click,
			@NotNull Trigger trigger,
			@Nullable Block clickedBlock,
			@Nullable BlockFace blockFace,
			@Nullable Entity clickedEntity) {
		super(player);
		this.itemKey = itemKey;
		this.item = item;
		this.hand = hand;
		this.click = click;
		this.trigger = trigger;
		this.clickedBlock = clickedBlock;
		this.blockFace = blockFace;
		this.clickedEntity = clickedEntity;
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
