package com.sneakyitems.util.magicitems;

import java.util.Set;
import java.util.List;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;

/**
 * Bukkit-free tag string normalize / serialize helpers for magic-item tags.
 */
public final class MagicItemTagStrings {

	private MagicItemTagStrings() {
	}

	public static Set<String> normalize(Collection<String> tags) {
		if (tags == null || tags.isEmpty()) return Collections.emptySet();
		Set<String> out = new LinkedHashSet<>();
		for (String tag : tags) {
			if (tag == null) continue;
			String trimmed = tag.trim();
			if (!trimmed.isEmpty()) out.add(trimmed);
		}
		return out.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(out);
	}

	public static String serialize(Collection<String> tags) {
		Set<String> normalized = normalize(tags);
		if (normalized.isEmpty()) return "";
		return String.join(",", normalized);
	}

	public static Set<String> deserialize(String raw) {
		if (raw == null || raw.isEmpty()) return Collections.emptySet();
		Set<String> out = new LinkedHashSet<>();
		for (String part : raw.split(",")) {
			String trimmed = part.trim();
			if (!trimmed.isEmpty()) out.add(trimmed);
		}
		return out.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(out);
	}

	public static Set<String> fromConfigList(List<String> list) {
		return normalize(list);
	}

}
