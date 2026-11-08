package com.sneakyitems.util;

/**
 * Bukkit-free key / slot parsing for magic-item PDC modifiers.
 */
public final class ItemPdcKeys {

	public static final String REGULAR_PREFIX = "magicspelldata_";
	public static final String PERMANENT_PREFIX = "magicspellpermanentdata_";

	public enum Slot {
		CAST,
		MAINHAND,
		OFFHAND;

		public static Slot parse(String raw) {
			if (raw == null) return null;
			return switch (raw.trim().toLowerCase()) {
				case "cast" -> CAST;
				case "mainhand", "main-hand", "hand" -> MAINHAND;
				case "offhand", "off-hand" -> OFFHAND;
				default -> null;
			};
		}
	}

	private ItemPdcKeys() {
	}

	public static String toPdcKeyName(String key) {
		if (key == null || key.isEmpty()) return null;
		String trimmed = key.trim();
		if (trimmed.regionMatches(true, 0, "permanent:", 0, "permanent:".length())) {
			return PERMANENT_PREFIX + trimmed.substring("permanent:".length()).trim();
		}
		if (trimmed.regionMatches(true, 0, "permanent-data:", 0, "permanent-data:".length())) {
			return PERMANENT_PREFIX + trimmed.substring("permanent-data:".length()).trim();
		}
		if (trimmed.regionMatches(true, 0, "pdc-permanent:", 0, "pdc-permanent:".length())) {
			return PERMANENT_PREFIX + trimmed.substring("pdc-permanent:".length()).trim();
		}
		return REGULAR_PREFIX + trimmed;
	}

	public static String stripQuotes(String value) {
		if (value == null) return null;
		String trimmed = value.trim();
		if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
			return trimmed.substring(1, trimmed.length() - 1);
		}
		return trimmed;
	}

}
