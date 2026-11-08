package com.sneakyitems.util.itemreader;

import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.FireworkEffectMeta;
import org.bukkit.configuration.ConfigurationSection;

import com.sneakyitems.util.ItemParseUtil;
import com.sneakyitems.handlers.DebugHandler;
import com.sneakyitems.util.magicitems.MagicItemData;
import static com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute.FIREWORK_EFFECT;

public class FireworkEffectHandler {

	private static final String TYPE_CONFIG_NAME = "firework-type";
	private static final String TRAIL_CONFIG_NAME = "trail";
	private static final String FLICKER_CONFIG_NAME = "flicker";
	private static final String COLORS_CONFIG_NAME = "colors";
	private static final String FADE_COLORS_CONFIG_NAME = "fade-colors";

	public static void process(ConfigurationSection config, ItemMeta meta, MagicItemData data) {
		if (!(meta instanceof FireworkEffectMeta fireworkMeta)) return;

		String type = "BALL";

		boolean trail = false;
		boolean flicker = false;

		Color[] colors = null;
		Color[] fadeColors = null;

		if (config.isString(TYPE_CONFIG_NAME)) {
			type = config.getString(TYPE_CONFIG_NAME, "").trim().toUpperCase();
		}

		if (config.isBoolean(TRAIL_CONFIG_NAME)) {
			trail = config.getBoolean(TRAIL_CONFIG_NAME);
		}

		if (config.isBoolean(FLICKER_CONFIG_NAME)) {
			flicker = config.getBoolean(FLICKER_CONFIG_NAME);
		}

		if (config.isString(COLORS_CONFIG_NAME)) {
			colors = ItemParseUtil.getColorsFromString(config.getString(COLORS_CONFIG_NAME, "FF0000"));
		}

		if (config.isString(FADE_COLORS_CONFIG_NAME)) {
			fadeColors = ItemParseUtil.getColorsFromString(config.getString(FADE_COLORS_CONFIG_NAME, "FF0000"));
		}

		// colors cant be null
		if (colors == null) return;
		if (fadeColors == null) fadeColors = new Color[0];

		FireworkEffect.Type fireworkType;
		try {
			fireworkType = FireworkEffect.Type.valueOf(type);
		} catch (IllegalArgumentException e) {
			DebugHandler.debugBadEnumValue(FireworkEffect.Type.class, type);
			return;
		}

		FireworkEffect effect = FireworkEffect.builder()
				.flicker(flicker)
				.trail(trail)
				.with(fireworkType)
				.withColor(colors)
				.withFade(fadeColors)
				.build();

		fireworkMeta.setEffect(effect);
		data.setAttribute(FIREWORK_EFFECT, effect);
	}

	public static void processItemMeta(ItemMeta meta, MagicItemData data) {
		if (!(meta instanceof FireworkEffectMeta fireworkMeta)) return;
		if (!data.hasAttribute(FIREWORK_EFFECT)) return;
		fireworkMeta.setEffect((FireworkEffect) data.getAttribute(FIREWORK_EFFECT));
	}

	public static void processMagicItemData(ItemMeta meta, MagicItemData data) {
		if (!(meta instanceof FireworkEffectMeta effectMeta)) return;
		if (!effectMeta.hasEffect()) return;
		data.setAttribute(FIREWORK_EFFECT, effectMeta.getEffect());
	}

}
