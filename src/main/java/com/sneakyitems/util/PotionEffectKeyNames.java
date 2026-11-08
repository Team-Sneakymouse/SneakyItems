package com.sneakyitems.util;

import java.util.Locale;
import java.util.Map;

/**
 * Bukkit-free potion-effect name → {@code namespace:path} shaping for registry lookup.
 * Includes legacy Bukkit {@code PotionEffectType} names that differ from modern registry keys.
 */
public final class PotionEffectKeyNames {

	/**
	 * Legacy Bukkit {@code getName()} tokens → modern {@code Registry.MOB_EFFECT} path keys.
	 * Names that already match the registry (e.g. {@code speed}) are omitted.
	 */
	public static final Map<String, String> LEGACY_TO_MODERN = Map.ofEntries(
			Map.entry("slow", "slowness"),
			Map.entry("fast_digging", "haste"),
			Map.entry("slow_digging", "mining_fatigue"),
			Map.entry("increase_damage", "strength"),
			Map.entry("heal", "instant_health"),
			Map.entry("harm", "instant_damage"),
			Map.entry("jump", "jump_boost"),
			Map.entry("confusion", "nausea"),
			Map.entry("damage_resistance", "resistance")
	);

	private PotionEffectKeyNames() {
	}

	/**
	 * Canonicalizes a bare effect path (no namespace): lowercases, spaces→underscores,
	 * and remaps known legacy Bukkit names.
	 *
	 * @return modern registry path, or {@code null} if blank
	 */
	public static String canonicalizePath(String name) {
		if (name == null || name.isBlank()) return null;
		String path = name.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
		return LEGACY_TO_MODERN.getOrDefault(path, path);
	}

	/**
	 * @return namespaced key string, or {@code null} if blank
	 */
	public static String toNamespacedKeyString(String name) {
		if (name == null || name.isBlank()) return null;
		String trimmed = name.trim().toLowerCase(Locale.ROOT);
		if (trimmed.contains(":")) {
			int colon = trimmed.indexOf(':');
			String namespace = trimmed.substring(0, colon);
			String path = canonicalizePath(trimmed.substring(colon + 1));
			if (path == null) return null;
			return namespace + ':' + path;
		}
		String path = canonicalizePath(trimmed);
		if (path == null) return null;
		return "minecraft:" + path;
	}

}
