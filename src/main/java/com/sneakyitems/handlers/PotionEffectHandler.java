package com.sneakyitems.handlers;

import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.potion.PotionType;
import org.bukkit.potion.PotionEffectType;

import com.sneakyitems.util.PotionEffectKeyNames;

public class PotionEffectHandler {

	private static final Map<String, PotionEffectType> potionEffects = new HashMap<>();

	static {
		for (PotionEffectType potionEffect : Registry.MOB_EFFECT) {
			NamespacedKey key = potionEffect.getKey();
			if (key == null) continue;
			potionEffects.put(key.getKey().toLowerCase(Locale.ROOT), potionEffect);
			potionEffects.put(key.asString().toLowerCase(Locale.ROOT), potionEffect);
		}

		// Legacy Bukkit PotionEffectType.getName() tokens (slow → slowness, etc.)
		for (Map.Entry<String, String> entry : PotionEffectKeyNames.LEGACY_TO_MODERN.entrySet()) {
			PotionEffectType type = potionEffects.get(entry.getValue());
			if (type != null) potionEffects.put(entry.getKey(), type);
		}
	}

	public static PotionEffectType getPotionEffectType(String identification) {
		if (identification == null || identification.isBlank()) return null;
		String id = identification.trim().toLowerCase(Locale.ROOT);
		PotionEffectType mapped = potionEffects.get(id);
		if (mapped != null) return mapped;

		String namespaced = PotionEffectKeyNames.toNamespacedKeyString(identification);
		if (namespaced == null) return null;
		mapped = potionEffects.get(namespaced);
		if (mapped != null) return mapped;

		NamespacedKey key = NamespacedKey.fromString(namespaced);
		if (key == null) return null;
		return Registry.MOB_EFFECT.get(key);
	}

	public static PotionType getPotionType(String name) {
		try {
			return PotionType.valueOf(name.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			DebugHandler.debugIllegalArgumentException(e);
		}
		return null;
	}

}
