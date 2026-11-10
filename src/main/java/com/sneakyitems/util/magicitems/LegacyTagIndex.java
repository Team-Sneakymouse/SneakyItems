package com.sneakyitems.util.magicitems;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;

import org.jetbrains.annotations.Nullable;

import com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute;
import com.sneakyitems.util.magicitems.MagicItemTagging.TagMapping;

/**
 * Narrows the legacy mappings a stack can match, in two stages: item type, then model
 * (custom model data, or item model when the pattern has no custom model data).
 *
 * <p>Both values are compared by plain equality in {@link MagicItemData#matches}, so a pattern that
 * sets one can only match stacks with the same value. Patterns that leave a value unset or ignored
 * go in an "any" list. Candidates keep the original mapping order, so the first match wins as before.
 */
final class LegacyTagIndex {

	static final LegacyTagIndex EMPTY = new LegacyTagIndex(List.of());

	private record Entry(int order, TagMapping mapping) {
	}

	private record CmdKey(CustomModelDataValues values) {
	}

	private record ModelKey(NamespacedKey key) {
	}

	/** Mappings of one item type (or of no type), split by model. */
	private static final class Bucket {
		final Map<Object, List<Entry>> byModel = new HashMap<>();
		final List<Entry> anyModel = new ArrayList<>();

		void add(Entry entry) {
			Object key = modelKey(entry.mapping().patternData());
			if (key == null)
				anyModel.add(entry);
			else
				byModel.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
		}

		void collect(@Nullable Object cmdKey, @Nullable Object itemModelKey, List<Entry> out) {
			if (cmdKey != null)
				out.addAll(byModel.getOrDefault(cmdKey, List.of()));
			if (itemModelKey != null)
				out.addAll(byModel.getOrDefault(itemModelKey, List.of()));
			out.addAll(anyModel);
		}
	}

	private final List<TagMapping> all;
	private final Map<Material, Bucket> byType = new HashMap<>();
	private final Bucket anyType = new Bucket();

	LegacyTagIndex(Collection<TagMapping> mappings) {
		List<TagMapping> ordered = new ArrayList<>(mappings);
		this.all = Collections.unmodifiableList(ordered);
		for (int i = 0; i < ordered.size(); i++) {
			Entry entry = new Entry(i, ordered.get(i));
			Material type = constrained(entry.mapping().patternData(), MagicItemAttribute.TYPE);
			if (type == null)
				anyType.add(entry);
			else
				byType.computeIfAbsent(type, t -> new Bucket()).add(entry);
		}
	}

	int size() {
		return all.size();
	}

	/** Every mapping, in order. For stacks that ignore type or model, where the index can't narrow. */
	List<TagMapping> all() {
		return all;
	}

	/** Stage 1, before reading the stack's data: false means no mapping can match this type. */
	boolean mayMatchType(Material type) {
		return byType.containsKey(type) || !anyType.anyModel.isEmpty() || !anyType.byModel.isEmpty();
	}

	/** Stage 2: mappings whose type and model fit the stack's data, in original order. */
	List<TagMapping> candidates(MagicItemData stackData) {
		Material type = stackData.hasAttribute(MagicItemAttribute.TYPE)
				? (Material) stackData.getAttribute(MagicItemAttribute.TYPE)
				: null;
		CustomModelDataValues cmd = stackData.hasAttribute(MagicItemAttribute.CUSTOM_MODEL_DATA)
				? (CustomModelDataValues) stackData.getAttribute(MagicItemAttribute.CUSTOM_MODEL_DATA)
				: null;
		NamespacedKey itemModel = stackData.hasAttribute(MagicItemAttribute.ITEM_MODEL)
				? (NamespacedKey) stackData.getAttribute(MagicItemAttribute.ITEM_MODEL)
				: null;
		Object cmdKey = cmd == null ? null : new CmdKey(cmd);
		Object itemModelKey = itemModel == null ? null : new ModelKey(itemModel);

		List<Entry> found = new ArrayList<>();
		Bucket typed = type == null ? null : byType.get(type);
		if (typed != null)
			typed.collect(cmdKey, itemModelKey, found);
		anyType.collect(cmdKey, itemModelKey, found);
		if (found.isEmpty())
			return List.of();
		found.sort((a, b) -> Integer.compare(a.order(), b.order()));
		List<TagMapping> result = new ArrayList<>(found.size());
		for (Entry entry : found)
			result.add(entry.mapping());
		return result;
	}

	/** Index key for the pattern's model: custom model data first, else item model, else null (any). */
	private static @Nullable Object modelKey(MagicItemData pattern) {
		CustomModelDataValues cmd = constrained(pattern, MagicItemAttribute.CUSTOM_MODEL_DATA);
		if (cmd != null)
			return new CmdKey(cmd);
		NamespacedKey itemModel = constrained(pattern, MagicItemAttribute.ITEM_MODEL);
		return itemModel == null ? null : new ModelKey(itemModel);
	}

	/** The pattern's value for an attribute it actually compares, or null when unset or ignored. */
	private static <T> @Nullable T constrained(MagicItemData pattern, MagicItemAttribute attr) {
		if (!pattern.hasAttribute(attr) || pattern.getIgnoredAttributes().contains(attr))
			return null;
		return pattern.getAttribute(attr);
	}
}
