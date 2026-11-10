package com.sneakyitems.util.itemreader;

import net.kyori.adventure.text.Component;

import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.configuration.ConfigurationSection;

import com.sneakyitems.util.ItemParseUtil;
import com.sneakyitems.util.magicitems.MagicItemData;
import static com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute.ITEM_NAME;
import static com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute.NAME;

public class NameHandler {

	private static final String CONFIG_NAME = NAME.toString();

	public static void process(ConfigurationSection config, ItemMeta meta, MagicItemData data) {
		if (!config.isString(CONFIG_NAME)) return;
		meta.displayName(ItemParseUtil.getMiniMessage(config.getString(CONFIG_NAME)));
		data.setAttribute(NAME, meta.displayName());
	}

	/** Writes {@code custom_name} (NAME) and {@code item_name} (ITEM_NAME), each when present. */
	public static void processItemMeta(ItemMeta meta, MagicItemData data) {
		if (data.hasAttribute(NAME)) meta.displayName((Component) data.getAttribute(NAME));
		if (data.hasAttribute(ITEM_NAME)) meta.itemName((Component) data.getAttribute(ITEM_NAME));
	}

	/** Reads {@code custom_name} into NAME and {@code item_name} into ITEM_NAME (stacks built by plugins often set only item_name). */
	public static void processMagicItemData(ItemMeta meta, MagicItemData data) {
		if (meta.hasDisplayName()) data.setAttribute(NAME, meta.displayName());
		if (meta.hasItemName()) data.setAttribute(ITEM_NAME, meta.itemName());
	}
	
}
