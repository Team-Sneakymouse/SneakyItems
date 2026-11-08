package com.sneakyitems.util.itemreader;

import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.configuration.ConfigurationSection;

import com.sneakyitems.util.magicitems.MagicItemData;
import static com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute.DURABILITY;

public class DurabilityHandler {

	private static final String CONFIG_NAME = DURABILITY.toString();

	public static void process(ConfigurationSection config, ItemMeta meta, MagicItemData data) {
		if (!(meta instanceof Damageable)) return;
		if (!config.isInt(CONFIG_NAME)) return;

		int durability = config.getInt(CONFIG_NAME);
		((Damageable) meta).setDamage(durability);
		data.setAttribute(DURABILITY, durability);
	}

	public static void processItemMeta(ItemMeta meta, MagicItemData data) {
		if (!(meta instanceof Damageable)) return;
		if (!data.hasAttribute(DURABILITY)) return;
		((Damageable) meta).setDamage((int) data.getAttribute(DURABILITY));
	}

	public static void processMagicItemData(ItemMeta meta, MagicItemData data) {
		if (!(meta instanceof Damageable damageableMeta)) return;
		data.setAttribute(DURABILITY, damageableMeta.getDamage());
	}

	public static int getDurability(ItemMeta meta) {
		if (!(meta instanceof Damageable)) return -1;
		return ((Damageable) meta).getDamage();
	}

}
