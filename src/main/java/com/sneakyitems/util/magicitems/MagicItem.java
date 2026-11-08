package com.sneakyitems.util.magicitems;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute;

public class MagicItem {

	private ItemStack itemStack;
	private MagicItemData magicItemData;
	private long definitionVersion;

	public MagicItem(ItemStack itemStack, MagicItemData magicItemData) {
		this.itemStack = itemStack;
		this.magicItemData = magicItemData;
	}

	public ItemStack getItemStack() {
		return itemStack;
	}

	public MagicItemData getMagicItemData() {
		return magicItemData;
	}

	public long getDefinitionVersion() {
		return definitionVersion;
	}

	public void setDefinitionVersion(long definitionVersion) {
		this.definitionVersion = definitionVersion;
	}

	public ItemStack createFor(Player player) {
		int amount = 1;
		if (magicItemData != null && magicItemData.hasAttribute(MagicItemAttribute.AMOUNT))
			amount = (int) magicItemData.getAttribute(MagicItemAttribute.AMOUNT);
		return createFor(player, amount);
	}

	public ItemStack createFor(Player player, int amount) {
		if (itemStack == null)
			return null;
		ItemStack stack = itemStack.clone();
		stack.setAmount(amount);
		MagicItemBehaviors.applyFromData(stack, magicItemData, player);
		MagicItemExpirationScheduler.scheduleFromItem(player, stack);
		return stack;
	}

	public void setItemStack(ItemStack itemStack) {
		this.itemStack = itemStack;
	}

	public void setMagicItemData(MagicItemData magicItemData) {
		this.magicItemData = magicItemData;
	}

	@Override
	public MagicItem clone() {
		MagicItem copy = new MagicItem(itemStack.clone(), magicItemData.clone());
		copy.definitionVersion = definitionVersion;
		return copy;
	}

}
