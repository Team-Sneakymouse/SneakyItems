package com.sneakyitems.util.magicitems;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import org.jetbrains.annotations.Nullable;

import com.sneakyitems.util.DataUtil;
import com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute;
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
	/** Rebuilt whenever the registry changes; read without locking. */
	private static volatile LegacyTagIndex index = LegacyTagIndex.EMPTY;

	/** Stack-side ignores that stop the index from narrowing (the pattern value is no longer compared). */
	private static final EnumSet<MagicItemAttribute> INDEX_ATTRIBUTES = EnumSet.of(
			MagicItemAttribute.TYPE, MagicItemAttribute.CUSTOM_MODEL_DATA, MagicItemAttribute.ITEM_MODEL);

	private MagicItemTagging() {
	}

	public static void register(Object owner, Collection<TagMapping> mappings) {
		if (owner == null || mappings == null || mappings.isEmpty())
			return;
		REGISTRY.put(owner, mappings);
		rebuildIndex();
	}

	public static void unregister(Object owner) {
		if (owner == null)
			return;
		REGISTRY.remove(owner);
		rebuildIndex();
	}

	private static synchronized void rebuildIndex() {
		List<TagMapping> all = new ArrayList<>();
		for (Collection<TagMapping> mappings : REGISTRY.values())
			all.addAll(mappings);
		index = all.isEmpty() ? LegacyTagIndex.EMPTY : new LegacyTagIndex(all);
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
	 *
	 * <p>A stack already tagged with a registered item name is never a legacy item and is skipped. A stack
	 * tagged with an unknown name (for example a variant since merged into another item) is still checked.
	 */
	public static TagResult applyTagIfNeeded(@Nullable ItemStack item) {
		LegacyTagIndex current = index;
		if (item == null || item.getType().isAir() || current.size() == 0)
			return TagResult.unchanged(item);

		ItemMeta meta = item.getItemMeta();
		if (meta == null)
			return TagResult.unchanged(item);

		if (meta.getPersistentDataContainer().has(
				new NamespacedKey("sneakybagofholding", "gui_display"),
				PersistentDataType.BYTE)) {
			return TagResult.unchanged(item);
		}

		String currentName = meta.getPersistentDataContainer().get(nameKey(), PersistentDataType.STRING);
		if (currentName != null && MagicItems.getMagicItems().containsKey(currentName)) {
			PerformanceDiagnostics.count("tag.known-name-skips");
			return TagResult.unchanged(item);
		}

		// Type check before reading the stack's data. The stack's own ignores only matter when it misses.
		if (!current.mayMatchType(item.getType()) && !ignoresIndexedAttribute(item)) {
			PerformanceDiagnostics.count("tag.type-skips");
			return TagResult.unchanged(item);
		}

		// Read the stack once; every mapping below matches against this instead of re-hashing the stack.
		MagicItems.PreparedStack prepared = MagicItems.prepare(item);
		if (prepared == null)
			return TagResult.unchanged(item);

		boolean narrow = !overlapsIndex(prepared.ignored());
		List<TagMapping> candidates = narrow ? current.candidates(prepared.data()) : current.all();
		int checked = 0;
		try {
			for (TagMapping tagMapping : candidates) {
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
		} finally {
			PerformanceDiagnostics.count("tag.mapping-checks", checked);
		}

		return TagResult.unchanged(item);
	}

	/** True when the stack itself ignores type or model, so any pattern value could still match. */
	private static boolean ignoresIndexedAttribute(ItemStack item) {
		return DataUtil.getString(item, "transmogrified") != null
				|| overlapsIndex(MagicItemIgnoredAttributes.fromItemStack(item));
	}

	private static boolean overlapsIndex(EnumSet<MagicItemAttribute> ignored) {
		for (MagicItemAttribute attr : ignored)
			if (INDEX_ATTRIBUTES.contains(attr))
				return true;
		return false;
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
