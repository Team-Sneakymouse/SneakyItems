package com.sneakyitems.util.itemreader;

import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.configuration.ConfigurationSection;

import com.sneakyitems.util.magicitems.MagicItemData;
import static com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute.REPAIR_COST;

public class RepairableHandler {

	private static final String CONFIG_NAME = REPAIR_COST.toString();

	public static void process(ConfigurationSection config, ItemMeta meta, MagicItemData data) {
		if (!(meta instanceof Repairable)) return;
		if (!config.isInt(CONFIG_NAME)) return;

		int repairCost = config.getInt(CONFIG_NAME);
		((Repairable) meta).setRepairCost(repairCost);
		data.setAttribute(REPAIR_COST, repairCost);
	}

	public static void processItemMeta(ItemMeta meta, MagicItemData data) {
		if (!(meta instanceof Repairable repairable)) return;
		if (!data.hasAttribute(REPAIR_COST)) return;
		repairable.setRepairCost((int) data.getAttribute(REPAIR_COST));
	}

	public static void processMagicItemData(ItemMeta meta, MagicItemData data) {
		if (!(meta instanceof Repairable repairable)) return;
		if (!repairable.hasRepairCost()) return;
		data.setAttribute(REPAIR_COST, repairable.getRepairCost());
	}
	
}
