package com.sneakyitems.handlers;

import java.util.Locale;

import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;

public class EnchantmentHandler {

	public static Enchantment getEnchantment(String name) {
		return RegistryAccess.registryAccess()
				.getRegistry(RegistryKey.ENCHANTMENT)
				.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
	}

}
