package com.sneakyitems.util.magicitems;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import org.jetbrains.annotations.Nullable;

/**
 * Definition fingerprints for magic items and PDC stamping of {@code magicitem_version}.
 */
public final class MagicItemDefinitionVersions {

	private MagicItemDefinitionVersions() {
	}

	public static long fingerprintSection(ConfigurationSection section) {
		if (section == null)
			return DefinitionFingerprinter.fingerprintValue(null);
		return DefinitionFingerprinter.fingerprintValue(sectionToMap(section));
	}

	public static long fingerprintString(String value) {
		return DefinitionFingerprinter.fingerprintString(value);
	}

	public static void applyToMagicItem(MagicItem magicItem, long version) {
		if (magicItem == null)
			return;
		magicItem.setDefinitionVersion(version);
		stamp(magicItem.getItemStack(), version);
	}

	public static void stamp(@Nullable ItemStack stack, long version) {
		if (stack == null)
			return;
		ItemMeta meta = stack.getItemMeta();
		if (meta == null)
			return;
		meta.getPersistentDataContainer().set(MagicItemBehaviorKeys.magicItemVersion(), PersistentDataType.LONG,
				version);
		stack.setItemMeta(meta);
	}

	/**
	 * Returns {@code stack} unchanged if it already has {@code version}; otherwise clones and stamps.
	 */
	public static ItemStack ensureStamped(ItemStack stack, long version) {
		if (stack == null)
			return null;
		ItemMeta meta = stack.getItemMeta();
		if (meta == null)
			return stack;
		Long stamped = meta.getPersistentDataContainer().get(MagicItemBehaviorKeys.magicItemVersion(),
				PersistentDataType.LONG);
		if (stamped != null && stamped == version)
			return stack;
		ItemStack copy = stack.clone();
		stamp(copy, version);
		return copy;
	}

	private static Map<String, Object> sectionToMap(ConfigurationSection section) {
		Map<String, Object> map = new TreeMap<>();
		for (String key : section.getKeys(false)) {
			if (section.isConfigurationSection(key)) {
				ConfigurationSection child = section.getConfigurationSection(key);
				map.put(key, child == null ? null : sectionToMap(child));
			} else if (section.isList(key)) {
				List<?> list = section.getList(key);
				map.put(key, list == null ? new ArrayList<>() : new ArrayList<>(list));
			} else {
				map.put(key, section.get(key));
			}
		}
		return map;
	}

}
