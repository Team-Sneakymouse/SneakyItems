package com.sneakyitems.util;

import org.bukkit.Material;
import org.bukkit.inventory.*;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Damageable;

import com.sneakyitems.util.magicitems.CustomModelDataValues;

public class ItemUtil {

	public static void addFakeEnchantment(ItemMeta meta) {
		if (meta == null) return;
		meta.setEnchantmentGlintOverride(true);
	}

	public static boolean hasFakeEnchantment(ItemMeta meta) {
		return meta.hasEnchantmentGlintOverride();
	}

	public static boolean hasDurability(Material type) {
		String name = type.name().toUpperCase();
		return
				name.contains("HELMET") ||
				name.contains("CHESTPLATE") ||
				name.contains("LEGGINGS") ||
				name.contains("BOOTS") ||
				name.contains("PICKAXE") ||
				name.contains("SHOVEL") ||
				name.contains("AXE") ||
				name.contains("HOE") ||
				name.contains("SWORD") ||
				name.contains("FISHING_ROD") ||
				name.contains("CARROT_ON_A_STICK") ||
				name.contains("FLINT_AND_STEEL") ||
				name.contains("BOW") ||
				name.contains("CROSSBOW") ||
				name.contains("TRIDENT") ||
				name.contains("ELYTRA") ||
				name.contains("SHIELD") ||
				name.contains("WARPED_FUNGUS_ON_A_STICK") ||
				name.contains("SHEARS");
	}

	public static int getDurability(ItemStack item) {
		ItemMeta meta = item.getItemMeta();
		if (!(meta instanceof Damageable)) return 0;
		return ((Damageable) meta).getDamage();
	}

	/** Legacy integer CMD, or 0. Ignores string/flag/color-only CMD (1.21.4+). */
	public static int getCustomModelData(ItemMeta meta) {
		Integer value = getCustomModelDataOrNull(meta);
		return value == null ? 0 : value;
	}

	/** Legacy integer CMD, or null when absent / non-numeric only. */
	public static Integer getCustomModelDataOrNull(ItemMeta meta) {
		CustomModelDataValues values = getCustomModelDataValues(meta);
		return values == null ? null : values.getLegacyIntOrNull();
	}

	/** Full custom model data component, or null when absent/empty. */
	public static CustomModelDataValues getCustomModelDataValues(ItemMeta meta) {
		return CustomModelDataValues.fromMeta(meta);
	}

	public static void setCustomModelDataValues(ItemMeta meta, CustomModelDataValues values) {
		if (meta == null || values == null) return;
		values.applyTo(meta);
	}

	public static Recipe createCookingRecipe(String type, NamespacedKey namespaceKey, String group, ItemStack result, Material ingredient, float experience, int cookingTime) {
		CookingRecipe<?> recipe = null;

		switch (type.toLowerCase()) {
			case "smoking" -> recipe = new SmokingRecipe(namespaceKey, result, ingredient, experience, cookingTime);
			case "campfire" -> recipe = new CampfireRecipe(namespaceKey, result, ingredient, experience, cookingTime);
			case "blasting" -> recipe = new BlastingRecipe(namespaceKey, result, ingredient, experience, cookingTime);
		}

		if (recipe != null) recipe.setGroup(group);
		return recipe;
	}

	public static Recipe createStonecutterRecipe(NamespacedKey namespaceKey, String group, ItemStack result, Material ingredient) {
		// Paper deprecated StonecuttingRecipe#setGroup as unused; keep the group param for call-site stability.
		return new StonecuttingRecipe(namespaceKey, result, ingredient);
	}

}
