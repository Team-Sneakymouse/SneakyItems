package com.sneakyitems.util.magicitems;

import java.util.Set;
import java.util.List;
import java.util.Collection;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.persistence.PersistentDataContainer;

import static com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute.TAGS;

/**
 * Magic-item tags stored in config ({@code tags:}) and written to the ItemStack PDC
 * under the SneakyItemsPlatform namespace key {@code tags} as a comma-separated STRING.
 */
public final class MagicItemTags {

	private static NamespacedKey tagsKey;

	private MagicItemTags() {
	}

	public static NamespacedKey tagsKey() {
		if (tagsKey == null) tagsKey = MagicItemBehaviorKeys.key("tags");
		return tagsKey;
	}

	public static Set<String> normalize(Collection<String> tags) {
		return MagicItemTagStrings.normalize(tags);
	}

	public static String serialize(Collection<String> tags) {
		return MagicItemTagStrings.serialize(tags);
	}

	public static Set<String> deserialize(String raw) {
		return MagicItemTagStrings.deserialize(raw);
	}

	public static void write(ItemMeta meta, Collection<String> tags) {
		if (meta == null) return;
		Set<String> normalized = normalize(tags);
		PersistentDataContainer container = meta.getPersistentDataContainer();
		if (normalized.isEmpty()) {
			container.remove(tagsKey());
			return;
		}
		container.set(tagsKey(), PersistentDataType.STRING, serialize(normalized));
	}

	public static Set<String> readFromMeta(ItemMeta meta) {
		if (meta == null) return Set.of();
		PersistentDataContainer container = meta.getPersistentDataContainer();
		if (!container.has(tagsKey(), PersistentDataType.STRING)) return Set.of();
		return deserialize(container.get(tagsKey(), PersistentDataType.STRING));
	}

	public static Set<String> read(ItemStack stack) {
		if (stack == null || !stack.hasItemMeta()) return Set.of();
		Set<String> fromPdc = readFromMeta(stack.getItemMeta());
		if (!fromPdc.isEmpty()) return fromPdc;
		return readFromMagicItemName(stack);
	}

	public static Set<String> readFromMagicItemName(ItemStack stack) {
		if (stack == null || !stack.hasItemMeta()) return Set.of();
		PersistentDataContainer container = stack.getItemMeta().getPersistentDataContainer();
		NamespacedKey nameKey = MagicItemBehaviorKeys.magicItem();
		if (!container.has(nameKey, PersistentDataType.STRING)) return Set.of();
		String name = container.get(nameKey, PersistentDataType.STRING);
		if (name == null || name.isEmpty()) return Set.of();
		MagicItem magicItem = MagicItems.getMagicItems().get(name);
		if (magicItem == null) return Set.of();
		MagicItemData data = magicItem.getMagicItemData();
		if (data == null || !data.hasAttribute(TAGS)) return Set.of();
		return normalize((Collection<String>) data.getAttribute(TAGS));
	}

	public static boolean has(ItemStack stack, String tag) {
		if (tag == null || tag.isEmpty() || stack == null) return false;
		String needle = tag.startsWith("#") ? tag.substring(1) : tag;
		needle = needle.trim();
		if (needle.isEmpty()) return false;
		return read(stack).contains(needle);
	}

	public static Set<String> fromConfigList(List<String> list) {
		return MagicItemTagStrings.fromConfigList(list);
	}

	public static Set<String> fromMagicItemData(MagicItemData data) {
		if (data == null || !data.hasAttribute(TAGS)) return Set.of();
		return normalize((Collection<String>) data.getAttribute(TAGS));
	}

	public static String magicItemName(ItemStack stack) {
		if (stack == null || !stack.hasItemMeta()) return null;
		PersistentDataContainer container = stack.getItemMeta().getPersistentDataContainer();
		NamespacedKey nameKey = MagicItemBehaviorKeys.magicItem();
		if (!container.has(nameKey, PersistentDataType.STRING)) return null;
		return container.get(nameKey, PersistentDataType.STRING);
	}

}
