package com.sneakyitems.util.itemreader;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.inventory.meta.components.FoodComponent;
import org.bukkit.inventory.meta.components.JukeboxPlayableComponent;
import org.bukkit.inventory.meta.components.UseCooldownComponent;

import com.sneakyitems.platform.SneakyItemsPlatform;
import com.sneakyitems.handlers.DebugHandler;
import com.sneakyutils.util.SoundUtil;
import com.sneakyitems.util.magicitems.ItemComponentValues;
import com.sneakyitems.util.magicitems.MagicItemData;

import static com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute.*;

/**
 * First-class modern item data components (item_model, rarity, food, …)
 * plus a raw {@code components} string using Minecraft item argument syntax.
 */
public class DataComponentsHandler {

	public static void process(ConfigurationSection config, ItemMeta meta, MagicItemData data) {
		processItemModel(config, meta, data);
		processTooltipStyle(config, meta, data);
		processRarity(config, meta, data);
		processEnchantable(config, meta, data);
		processGlider(config, meta, data);
		processMaxDamage(config, meta, data);
		processFood(config, meta, data);
		processUseCooldown(config, meta, data);
		processEquippable(config, meta, data);
		processJukeboxPlayable(config, meta, data);

		if (config.isString("components")) {
			String components = config.getString("components");
			if (components != null && !components.isBlank()) data.setAttribute(COMPONENTS, components.trim());
		}
	}

	public static void processItemMeta(ItemMeta meta, MagicItemData data) {
		if (data.hasAttribute(ITEM_MODEL)) meta.setItemModel((NamespacedKey) data.getAttribute(ITEM_MODEL));
		if (data.hasAttribute(TOOLTIP_STYLE)) meta.setTooltipStyle((NamespacedKey) data.getAttribute(TOOLTIP_STYLE));
		if (data.hasAttribute(RARITY)) meta.setRarity((ItemRarity) data.getAttribute(RARITY));
		if (data.hasAttribute(ENCHANTABLE)) meta.setEnchantable((Integer) data.getAttribute(ENCHANTABLE));
		if (data.hasAttribute(GLIDER)) meta.setGlider((Boolean) data.getAttribute(GLIDER));
		if (data.hasAttribute(MAX_DAMAGE) && meta instanceof Damageable damageable) {
			damageable.setMaxDamage((Integer) data.getAttribute(MAX_DAMAGE));
		}

		if (data.hasAttribute(FOOD)) {
			ItemComponentValues.Food food = (ItemComponentValues.Food) data.getAttribute(FOOD);
			FoodComponent component = meta.getFood();
			component.setNutrition(food.nutrition());
			component.setSaturation(food.saturation());
			component.setCanAlwaysEat(food.canAlwaysEat());
			meta.setFood(component);
		}

		if (data.hasAttribute(USE_COOLDOWN)) {
			ItemComponentValues.UseCooldown cooldown = (ItemComponentValues.UseCooldown) data.getAttribute(USE_COOLDOWN);
			UseCooldownComponent component = meta.getUseCooldown();
			component.setCooldownSeconds(cooldown.seconds());
			component.setCooldownGroup(cooldown.cooldownGroup());
			meta.setUseCooldown(component);
		}

		if (data.hasAttribute(EQUIPPABLE)) {
			ItemComponentValues.Equippable equippable = (ItemComponentValues.Equippable) data.getAttribute(EQUIPPABLE);
			EquippableComponent component = meta.getEquippable();
			component.setSlot(equippable.slot());
			component.setEquipSound(equippable.equipSound());
			component.setModel(equippable.model());
			component.setCameraOverlay(equippable.cameraOverlay());
			component.setDispensable(equippable.dispensable());
			component.setSwappable(equippable.swappable());
			component.setDamageOnHurt(equippable.damageOnHurt());
			meta.setEquippable(component);
		}

		if (data.hasAttribute(JUKEBOX_PLAYABLE)) {
			ItemComponentValues.JukeboxPlayable playable = (ItemComponentValues.JukeboxPlayable) data.getAttribute(JUKEBOX_PLAYABLE);
			JukeboxPlayableComponent component = meta.getJukeboxPlayable();
			component.setSongKey(playable.songKey());
			// show-in-tooltip is no longer on the Paper component; retained on MagicItemData for serialize compat only
			meta.setJukeboxPlayable(component);
		}
	}

	public static void processMagicItemData(ItemMeta meta, MagicItemData data) {
		if (meta.hasItemModel()) data.setAttribute(ITEM_MODEL, meta.getItemModel());
		if (meta.hasTooltipStyle()) data.setAttribute(TOOLTIP_STYLE, meta.getTooltipStyle());
		if (meta.hasRarity()) data.setAttribute(RARITY, meta.getRarity());
		if (meta.hasEnchantable()) data.setAttribute(ENCHANTABLE, meta.getEnchantable());
		if (meta.isGlider()) data.setAttribute(GLIDER, true);
		if (meta instanceof Damageable damageable && damageable.hasMaxDamage()) {
			data.setAttribute(MAX_DAMAGE, damageable.getMaxDamage());
		}

		if (meta.hasFood()) {
			FoodComponent food = meta.getFood();
			data.setAttribute(FOOD, new ItemComponentValues.Food(food.getNutrition(), food.getSaturation(), food.canAlwaysEat()));
		}

		if (meta.hasUseCooldown()) {
			UseCooldownComponent cooldown = meta.getUseCooldown();
			data.setAttribute(USE_COOLDOWN, new ItemComponentValues.UseCooldown(cooldown.getCooldownSeconds(), cooldown.getCooldownGroup()));
		}

		if (meta.hasEquippable()) {
			EquippableComponent equippable = meta.getEquippable();
			data.setAttribute(EQUIPPABLE, new ItemComponentValues.Equippable(
				equippable.getSlot(),
				equippable.getEquipSound(),
				equippable.getModel(),
				equippable.getCameraOverlay(),
				equippable.isDispensable(),
				equippable.isSwappable(),
				equippable.isDamageOnHurt()
			));
		}

		if (meta.hasJukeboxPlayable()) {
			JukeboxPlayableComponent playable = meta.getJukeboxPlayable();
			NamespacedKey songKey = playable.getSongKey();
			if (songKey != null) {
				data.setAttribute(JUKEBOX_PLAYABLE, new ItemComponentValues.JukeboxPlayable(songKey, true));
			}
		}
	}

	/**
	 * Applies a raw Minecraft item-components string (give-argument syntax) onto the stack.
	 * Example: {@code [item_model="foo:bar",rarity=epic]} or {@code item_model="foo:bar"}.
	 */
	public static void applyComponentsString(ItemStack item, MagicItemData data) {
		if (item == null || data == null || !data.hasAttribute(COMPONENTS)) return;

		String components = (String) data.getAttribute(COMPONENTS);
		if (components == null || components.isBlank()) return;

		String trimmed = components.trim();
		if (!trimmed.startsWith("[")) trimmed = '[' + trimmed;
		if (!trimmed.endsWith("]")) trimmed = trimmed + ']';

		String itemAsString = item.getType().getKey() + trimmed;
		try {
			ItemStack parsed = Bukkit.getItemFactory().createItemStack(itemAsString);
			item.copyDataFrom(parsed, type -> true);
		} catch (IllegalArgumentException e) {
			SneakyItemsPlatform.error("Invalid magic item components '" + components + "': " + e.getMessage());
			DebugHandler.debugIllegalArgumentException(e);
		}
	}

	private static void processItemModel(ConfigurationSection config, ItemMeta meta, MagicItemData data) {
		NamespacedKey key = readKey(config, "item-model");
		if (key == null) return;
		meta.setItemModel(key);
		data.setAttribute(ITEM_MODEL, key);
	}

	private static void processTooltipStyle(ConfigurationSection config, ItemMeta meta, MagicItemData data) {
		NamespacedKey key = readKey(config, "tooltip-style");
		if (key == null) return;
		meta.setTooltipStyle(key);
		data.setAttribute(TOOLTIP_STYLE, key);
	}

	private static void processRarity(ConfigurationSection config, ItemMeta meta, MagicItemData data) {
		if (!config.isString("rarity")) return;
		String raw = config.getString("rarity");
		if (raw == null) return;
		try {
			ItemRarity rarity = ItemRarity.valueOf(raw.trim().toUpperCase());
			meta.setRarity(rarity);
			data.setAttribute(RARITY, rarity);
		} catch (IllegalArgumentException e) {
			DebugHandler.debugBadEnumValue(ItemRarity.class, raw);
		}
	}

	private static void processEnchantable(ConfigurationSection config, ItemMeta meta, MagicItemData data) {
		Integer value = readInt(config, "enchantable");
		if (value == null) return;
		meta.setEnchantable(value);
		data.setAttribute(ENCHANTABLE, value);
	}

	private static void processGlider(ConfigurationSection config, ItemMeta meta, MagicItemData data) {
		if (!config.isBoolean("glider")) return;
		boolean glider = config.getBoolean("glider");
		meta.setGlider(glider);
		data.setAttribute(GLIDER, glider);
	}

	private static void processMaxDamage(ConfigurationSection config, ItemMeta meta, MagicItemData data) {
		Integer value = readInt(config, "max-damage");
		if (value == null || !(meta instanceof Damageable damageable)) return;
		damageable.setMaxDamage(value);
		data.setAttribute(MAX_DAMAGE, value);
	}

	private static void processFood(ConfigurationSection config, ItemMeta meta, MagicItemData data) {
		if (!config.isConfigurationSection("food")) return;
		ConfigurationSection section = config.getConfigurationSection("food");
		if (section == null) return;

		int nutrition = section.getInt("nutrition", 0);
		float saturation = (float) section.getDouble("saturation", 0);
		boolean canAlwaysEat = section.getBoolean("can-always-eat", false);

		FoodComponent component = meta.getFood();
		component.setNutrition(nutrition);
		component.setSaturation(saturation);
		component.setCanAlwaysEat(canAlwaysEat);
		meta.setFood(component);

		data.setAttribute(FOOD, new ItemComponentValues.Food(nutrition, saturation, canAlwaysEat));
	}

	private static void processUseCooldown(ConfigurationSection config, ItemMeta meta, MagicItemData data) {
		if (!config.isConfigurationSection("use-cooldown")) return;
		ConfigurationSection section = config.getConfigurationSection("use-cooldown");
		if (section == null) return;

		float seconds = (float) section.getDouble("seconds", 0);
		if (seconds <= 0f) {
			// Paper requires a positive cooldown; 0 is used in configs to mean "no component".
			return;
		}

		NamespacedKey group = null;
		if (section.isString("cooldown-group")) {
			group = NamespacedKey.fromString(section.getString("cooldown-group"));
		}

		UseCooldownComponent component = meta.getUseCooldown();
		component.setCooldownSeconds(seconds);
		component.setCooldownGroup(group);
		meta.setUseCooldown(component);

		data.setAttribute(USE_COOLDOWN, new ItemComponentValues.UseCooldown(seconds, group));
	}

	private static void processEquippable(ConfigurationSection config, ItemMeta meta, MagicItemData data) {
		if (!config.isConfigurationSection("equippable")) return;
		ConfigurationSection section = config.getConfigurationSection("equippable");
		if (section == null || !section.isString("slot")) return;

		EquipmentSlot slot;
		try {
			slot = EquipmentSlot.valueOf(section.getString("slot").trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			DebugHandler.debugBadEnumValue(EquipmentSlot.class, section.getString("slot"));
			return;
		}

		Sound equipSound = null;
		if (section.isString("equip-sound")) {
			String soundName = section.getString("equip-sound").trim();
			equipSound = SoundUtil.resolve(soundName);
			if (equipSound == null) {
				SneakyItemsPlatform.error("Invalid equip-sound '" + soundName + "' for magic item equippable component.");
			}
		}

		NamespacedKey model = section.isString("model") ? NamespacedKey.fromString(section.getString("model")) : null;
		NamespacedKey cameraOverlay = section.isString("camera-overlay")
			? NamespacedKey.fromString(section.getString("camera-overlay")) : null;
		boolean dispensable = section.getBoolean("dispensable", true);
		boolean swappable = section.getBoolean("swappable", true);
		boolean damageOnHurt = section.getBoolean("damage-on-hurt", true);

		EquippableComponent component = meta.getEquippable();
		component.setSlot(slot);
		component.setEquipSound(equipSound);
		component.setModel(model);
		component.setCameraOverlay(cameraOverlay);
		component.setDispensable(dispensable);
		component.setSwappable(swappable);
		component.setDamageOnHurt(damageOnHurt);
		meta.setEquippable(component);

		data.setAttribute(EQUIPPABLE, new ItemComponentValues.Equippable(
			slot, equipSound, model, cameraOverlay, dispensable, swappable, damageOnHurt
		));
	}

	private static void processJukeboxPlayable(ConfigurationSection config, ItemMeta meta, MagicItemData data) {
		if (!config.isConfigurationSection("jukebox-playable") && !config.isString("jukebox-playable")) return;

		NamespacedKey songKey;
		boolean showInTooltip = true;

		if (config.isString("jukebox-playable")) {
			songKey = NamespacedKey.fromString(config.getString("jukebox-playable"));
		} else {
			ConfigurationSection section = config.getConfigurationSection("jukebox-playable");
			if (section == null || !section.isString("song")) return;
			songKey = NamespacedKey.fromString(section.getString("song"));
			showInTooltip = section.getBoolean("show-in-tooltip", true);
		}

		if (songKey == null) return;

		JukeboxPlayableComponent component = meta.getJukeboxPlayable();
		component.setSongKey(songKey);
		meta.setJukeboxPlayable(component);

		data.setAttribute(JUKEBOX_PLAYABLE, new ItemComponentValues.JukeboxPlayable(songKey, showInTooltip));
	}

	private static NamespacedKey readKey(ConfigurationSection config, String path) {
		if (!config.isString(path)) return null;
		String raw = config.getString(path);
		if (raw == null || raw.isBlank()) return null;
		NamespacedKey key = NamespacedKey.fromString(raw.trim());
		if (key == null) SneakyItemsPlatform.error("Invalid namespaced key '" + raw + "' for magic item option '" + path + "'.");
		return key;
	}

	private static Integer readInt(ConfigurationSection config, String path) {
		if (config.isInt(path)) return config.getInt(path);
		if (config.isString(path)) {
			try {
				return Integer.valueOf(config.getString(path).trim());
			} catch (NumberFormatException ignored) {
				return null;
			}
		}
		return null;
	}

}
