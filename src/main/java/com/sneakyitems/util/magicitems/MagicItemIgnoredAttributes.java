package com.sneakyitems.util.magicitems;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import com.sneakyitems.handlers.DebugHandler;
import com.sneakyitems.util.DataUtil;
import com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute;

public final class MagicItemIgnoredAttributes {

	private static final Set<MagicItemAttribute> BLOCKED = Set.of(
			MagicItemAttribute.AMOUNT,
			MagicItemAttribute.TYPE,
			MagicItemAttribute.MAGIC_ITEM_NAME,
			MagicItemAttribute.PERSISTENT_DATA);

	private MagicItemIgnoredAttributes() {
	}

	public static EnumSet<MagicItemAttribute> fromItemStack(ItemStack stack) {
		EnumSet<MagicItemAttribute> result = EnumSet.noneOf(MagicItemAttribute.class);
		if (stack == null || !stack.hasItemMeta())
			return result;

		String raw = stack.getItemMeta().getPersistentDataContainer()
				.get(MagicItemBehaviorKeys.ignoredAttributes(), PersistentDataType.STRING);
		if (raw == null || raw.isBlank())
			return result;

		parseInto(raw, result);
		return result;
	}

	public static EnumSet<MagicItemAttribute> getEffectiveIgnored(MagicItemData definition, ItemStack stack) {
		EnumSet<MagicItemAttribute> effective = definition != null
				? EnumSet.copyOf(definition.getIgnoredAttributes())
				: EnumSet.noneOf(MagicItemAttribute.class);
		effective.addAll(fromItemStack(stack));

		if (stack != null && DataUtil.getString(stack, "transmogrified") != null)
			effective.add(MagicItemAttribute.ITEM_MODEL);

		effective.removeAll(BLOCKED);
		return effective;
	}

	public static void add(ItemStack stack, MagicItemAttribute... attributes) {
		if (stack == null || attributes == null || attributes.length == 0)
			return;

		EnumSet<MagicItemAttribute> current = fromItemStack(stack);
		for (MagicItemAttribute attr : attributes) {
			if (attr != null && !BLOCKED.contains(attr))
				current.add(attr);
		}
		write(stack, current);
	}

	public static void remove(ItemStack stack, MagicItemAttribute... attributes) {
		if (stack == null || attributes == null || attributes.length == 0)
			return;

		EnumSet<MagicItemAttribute> current = fromItemStack(stack);
		for (MagicItemAttribute attr : attributes) {
			if (attr != null)
				current.remove(attr);
		}
		write(stack, current);
	}

	public static void copyPdc(ItemMeta source, ItemMeta dest) {
		if (source == null || dest == null)
			return;

		PersistentDataContainer sourceContainer = source.getPersistentDataContainer();
		if (!sourceContainer.has(MagicItemBehaviorKeys.ignoredAttributes(), PersistentDataType.STRING))
			return;

		String value = sourceContainer.get(MagicItemBehaviorKeys.ignoredAttributes(), PersistentDataType.STRING);
		if (value != null)
			dest.getPersistentDataContainer().set(MagicItemBehaviorKeys.ignoredAttributes(),
					PersistentDataType.STRING, value);
	}

	public static boolean isTaggedMagicItem(ItemStack stack) {
		if (stack == null || !stack.hasItemMeta())
			return false;
		return stack.getItemMeta().getPersistentDataContainer()
				.has(MagicItemBehaviorKeys.magicItem(), PersistentDataType.STRING);
	}

	private static void write(ItemStack stack, EnumSet<MagicItemAttribute> attributes) {
		if (attributes.isEmpty()) {
			DataUtil.remove(stack, "ignored_attributes");
			return;
		}

		String value = attributes.stream()
				.map(MagicItemAttribute::toString)
				.collect(Collectors.joining(","));
		DataUtil.setString(stack, "ignored_attributes", value);
	}

	private static void parseInto(String raw, EnumSet<MagicItemAttribute> into) {
		for (String part : raw.split(",")) {
			String normalized = part.trim();
			if (normalized.isEmpty())
				continue;

			try {
				MagicItemAttribute attr = MagicItemAttribute.valueOf(normalized.toUpperCase().replace("-", "_"));
				if (!BLOCKED.contains(attr))
					into.add(attr);
			} catch (IllegalArgumentException e) {
				DebugHandler.debugBadEnumValue(MagicItemAttribute.class, normalized);
			}
		}
	}

}
