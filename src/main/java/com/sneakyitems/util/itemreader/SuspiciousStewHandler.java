package com.sneakyitems.util.itemreader;

import java.util.List;
import java.util.ArrayList;

import org.bukkit.potion.PotionEffect;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SuspiciousStewMeta;
import org.bukkit.configuration.ConfigurationSection;

import io.papermc.paper.potion.SuspiciousEffectEntry;

import com.sneakyitems.util.ItemParseUtil;
import com.sneakyitems.util.magicitems.MagicItemData;
import static com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute.POTION_EFFECTS;

public class SuspiciousStewHandler {

	private static final String CONFIG_NAME = POTION_EFFECTS.toString();

	public static void process(ConfigurationSection config, ItemMeta meta, MagicItemData data) {
		if (!(meta instanceof SuspiciousStewMeta stewMeta)) return;
		if (!config.isList(CONFIG_NAME)) return;

		stewMeta.clearCustomEffects();

		List<String> effects = config.getStringList(CONFIG_NAME);
		List<PotionEffect> potionEffects = new ArrayList<>();
		for (String str : effects) {
			PotionEffect potionEffect = ItemParseUtil.buildSuspiciousStewPotionEffect(str);
			if (potionEffect == null) continue;

			stewMeta.addCustomEffect(SuspiciousEffectEntry.create(potionEffect.getType(), potionEffect.getDuration()), true);
			potionEffects.add(potionEffect);
		}
		if (!potionEffects.isEmpty()) data.setAttribute(POTION_EFFECTS, potionEffects);
	}

	public static void processItemMeta(ItemMeta meta, MagicItemData data) {
		if (!(meta instanceof SuspiciousStewMeta stewMeta)) return;
		if (!data.hasAttribute(POTION_EFFECTS)) return;
		stewMeta.clearCustomEffects();
		List<PotionEffect> effects = data.getAttribute(POTION_EFFECTS);
		effects.forEach(potionEffect -> stewMeta.addCustomEffect(
				SuspiciousEffectEntry.create(potionEffect.getType(), potionEffect.getDuration()), true));
	}

	public static void processMagicItemData(ItemMeta meta, MagicItemData data) {
		if (!(meta instanceof SuspiciousStewMeta stewMeta)) return;
		if (!stewMeta.hasCustomEffects()) return;
		List<PotionEffect> effects = stewMeta.getCustomEffects();
		if (!effects.isEmpty()) data.setAttribute(POTION_EFFECTS, effects);
	}

}
