package com.sneakyitems.util.itemreader;

import java.util.List;
import java.util.ArrayList;

import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.configuration.ConfigurationSection;

import com.sneakyitems.util.ItemParseUtil;
import com.sneakyitems.handlers.DebugHandler;
import com.sneakyitems.util.magicitems.MagicItemData;
import static com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute.POWER;
import static com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute.FIREWORK_EFFECTS;

public class FireworkHandler {

	private static final String FIREWORK_EFFECTS_CONFIG_NAME = FIREWORK_EFFECTS.toString();
	private static final String POWER_CONFIG_NAME = POWER.toString();

	public static void process(ConfigurationSection config, ItemMeta meta, MagicItemData data) {
		if (!(meta instanceof FireworkMeta fireworkMeta)) return;
		if (!config.isList(FIREWORK_EFFECTS_CONFIG_NAME)) return;

		int power = 0;
		if (config.isInt(POWER_CONFIG_NAME)) power = config.getInt(POWER_CONFIG_NAME);

		if (config.isList(FIREWORK_EFFECTS_CONFIG_NAME)) {
			List<String> argList = config.getStringList(FIREWORK_EFFECTS_CONFIG_NAME);

			List<FireworkEffect> fireworkEffects = new ArrayList<>();

			// <type> <trail> <flicker> <colors>(,) <fadeColors>(,)
			for (String str : argList) {
				String[] args = str.split(" ");
				if (args.length != 4 && args.length != 5) continue;

				String type = args[0];
				FireworkEffect.Type fireworkType = null;
				try {
					fireworkType = FireworkEffect.Type.valueOf(type.toUpperCase());
				} catch (IllegalArgumentException e) {
					DebugHandler.debugBadEnumValue(FireworkEffect.Type.class, type.toUpperCase());
				}
				if (fireworkType == null) continue;

				boolean trail = Boolean.parseBoolean(args[1]);
				boolean flicker = Boolean.parseBoolean(args[2]);

				Color[] colors = ItemParseUtil.getColorsFromString(args[3]);

				Color[] fadeColors;
				if (args.length == 5) fadeColors = ItemParseUtil.getColorsFromString(args[4]);
				else fadeColors = new Color[0];

				FireworkEffect effect = FireworkEffect.builder()
						.flicker(flicker)
						.trail(trail)
						.with(fireworkType)
						.withColor(colors)
						.withFade(fadeColors)
						.build();

				fireworkEffects.add(effect);
			}

			if (!fireworkEffects.isEmpty()) {
				fireworkMeta.addEffects(fireworkEffects);
				data.setAttribute(FIREWORK_EFFECTS, fireworkEffects);
			}
		}

		fireworkMeta.setPower(power);
		data.setAttribute(POWER, power);
	}

	public static void processItemMeta(ItemMeta meta, MagicItemData data) {
		if (!(meta instanceof FireworkMeta fireworkMeta)) return;

		if (data.hasAttribute(POWER)) fireworkMeta.setPower((int) data.getAttribute(POWER));
		if (data.hasAttribute(FIREWORK_EFFECTS)) {
			List<FireworkEffect> effects = data.getAttribute(FIREWORK_EFFECTS);
			fireworkMeta.addEffects(effects);
		}
	}

	public static void processMagicItemData(ItemMeta meta, MagicItemData data) {
		if (!(meta instanceof FireworkMeta fireworkMeta)) return;
		data.setAttribute(POWER, fireworkMeta.getPower());
		if (!fireworkMeta.hasEffects()) return;
		List<FireworkEffect> effects = fireworkMeta.getEffects();
		if (!effects.isEmpty()) data.setAttribute(FIREWORK_EFFECTS, fireworkMeta.getEffects());
	}

}
