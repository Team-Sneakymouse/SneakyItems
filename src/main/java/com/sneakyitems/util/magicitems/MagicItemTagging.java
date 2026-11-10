package com.sneakyitems.util.magicitems;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import org.jetbrains.annotations.Nullable;

import com.sneakyitems.util.performance.PerformanceDiagnostics;

/**
 * Legacy ItemTag pattern mappings (pre-PDC stacks).
 * Applies the {@code magicitem} PDC name so the updater can rebuild pre-PDC stacks.
 */
public final class MagicItemTagging {

	private static NamespacedKey nameKey() {
		return MagicItemBehaviorKeys.magicItem();
	}
	private static final Map<Object, Collection<TagMapping>> REGISTRY = new ConcurrentHashMap<>();

	private MagicItemTagging() {
	}

	public static void register(Object owner, Collection<TagMapping> mappings) {
		if (owner == null || mappings == null || mappings.isEmpty())
			return;
		REGISTRY.put(owner, mappings);
	}

	public static void unregister(Object owner) {
		if (owner == null)
			return;
		REGISTRY.remove(owner);
	}

	public static boolean hasMappings() {
		return !REGISTRY.isEmpty();
	}

	/** Registered mappings across owners (a level for diagnostics). */
	public static int mappingCount() {
		int count = 0;
		for (Collection<TagMapping> mappings : REGISTRY.values()) count += mappings.size();
		return count;
	}

	/**
	 * If the stack matches a registered mapping but lacks the correct {@code magicitem} name,
	 * sets that PDC value and returns a new stack. Does not rebuild attributes — call the updater after.
	 */
	public static TagResult applyTagIfNeeded(@Nullable ItemStack item) {
		if (item == null || item.getType().isAir() || REGISTRY.isEmpty())
			return TagResult.unchanged(item);

		ItemMeta meta = item.getItemMeta();
		if (meta == null)
			return TagResult.unchanged(item);

		if (meta.getPersistentDataContainer().has(
				new NamespacedKey("sneakybagofholding", "gui_display"),
				PersistentDataType.BYTE)) {
			return TagResult.unchanged(item);
		}

		// Read the stack once; every mapping below matches against this instead of re-hashing the stack.
		MagicItems.PreparedStack prepared = MagicItems.prepare(item);
		if (prepared == null)
			return TagResult.unchanged(item);

		String currentName = meta.getPersistentDataContainer().get(nameKey(), PersistentDataType.STRING);

		int checked = 0;
		try {
			for (Collection<TagMapping> mappings : REGISTRY.values()) {
				for (TagMapping tagMapping : mappings) {
					checked++;
					if (!MagicItems.matches(tagMapping.patternData(), prepared, true))
						continue;
					if (tagMapping.internalName().equals(currentName))
						return TagResult.unchanged(item);

					ItemStack tagged = item.clone();
					ItemMeta taggedMeta = tagged.getItemMeta();
					if (taggedMeta == null)
						return TagResult.unchanged(item);
					taggedMeta.getPersistentDataContainer().set(nameKey(), PersistentDataType.STRING,
							tagMapping.internalName());
					tagged.setItemMeta(taggedMeta);
					PerformanceDiagnostics.count("items.tagged");
					return new TagResult(tagged, true, tagMapping.internalName(), tagMapping.magicItem());
				}
			}
		} finally {
			PerformanceDiagnostics.count("tag.mapping-checks", checked);
		}

		return TagResult.unchanged(item);
	}

	public record TagMapping(String internalName, MagicItemData patternData, MagicItem magicItem) {
	}

	public record TagResult(@Nullable ItemStack stack, boolean tagged, @Nullable String internalName,
			@Nullable MagicItem magicItem) {

		static TagResult unchanged(@Nullable ItemStack stack) {
			return new TagResult(stack, false, null, null);
		}
	}

}
