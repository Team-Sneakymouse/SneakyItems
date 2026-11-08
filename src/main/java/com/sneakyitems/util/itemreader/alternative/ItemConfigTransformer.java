package com.sneakyitems.util.itemreader.alternative;

import org.bukkit.inventory.ItemStack;
import org.bukkit.configuration.ConfigurationSection;

public interface ItemConfigTransformer {

	// Deserialize this section
	ItemStack deserialize(ConfigurationSection section);
	
	ConfigurationSection serialize(ItemStack itemStack);
	
	String getReaderKey();
	
}
