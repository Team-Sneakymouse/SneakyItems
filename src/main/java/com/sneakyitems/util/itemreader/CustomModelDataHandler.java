package com.sneakyitems.util.itemreader;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Color;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.meta.ItemMeta;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.sneakyutils.util.ColorUtil;
import com.sneakyitems.util.ItemUtil;
import com.sneakyitems.util.magicitems.CustomModelDataValues;
import com.sneakyitems.util.magicitems.MagicItemData;
import static com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute.CUSTOM_MODEL_DATA;

public class CustomModelDataHandler {

	private static final String CONFIG_NAME = CUSTOM_MODEL_DATA.toString();

	public static void process(ConfigurationSection config, ItemMeta meta, MagicItemData data) {
		if (!config.contains(CONFIG_NAME)) return;

		CustomModelDataValues values = parseFromConfig(config);
		if (values == null || values.isEmpty()) return;

		values.applyTo(meta);
		data.setAttribute(CUSTOM_MODEL_DATA, values);
	}

	public static void processItemMeta(ItemMeta meta, MagicItemData data) {
		if (!data.hasAttribute(CUSTOM_MODEL_DATA)) return;
		((CustomModelDataValues) data.getAttribute(CUSTOM_MODEL_DATA)).applyTo(meta);
	}

	public static void processMagicItemData(ItemMeta meta, MagicItemData data) {
		CustomModelDataValues values = ItemUtil.getCustomModelDataValues(meta);
		if (values != null) data.setAttribute(CUSTOM_MODEL_DATA, values);
	}

	public static CustomModelDataValues parseFromJson(JsonElement value) {
		if (value == null || value.isJsonNull()) return null;

		if (value.isJsonPrimitive()) {
			var primitive = value.getAsJsonPrimitive();
			if (primitive.isNumber()) {
				float number = primitive.getAsFloat();
				if (number == Math.rint(number)) return CustomModelDataValues.ofLegacyInt((int) number);
				return new CustomModelDataValues(List.of(number), List.of(), List.of(), List.of());
			}
			if (primitive.isString()) {
				String raw = primitive.getAsString();
				if (raw == null || raw.isEmpty()) return null;
				try {
					return CustomModelDataValues.ofLegacyInt(Integer.parseInt(raw.trim()));
				} catch (NumberFormatException ignored) {
					return CustomModelDataValues.ofString(raw);
				}
			}
			return null;
		}

		if (value.isJsonArray()) {
			List<String> strings = new ArrayList<>();
			for (JsonElement entry : value.getAsJsonArray()) {
				if (entry != null && !entry.isJsonNull()) strings.add(entry.getAsString());
			}
			return strings.isEmpty() ? null : CustomModelDataValues.ofStrings(strings);
		}

		if (!value.isJsonObject()) return null;
		JsonObject object = value.getAsJsonObject();

		List<Float> floats = new ArrayList<>();
		List<String> strings = new ArrayList<>();
		List<Boolean> flags = new ArrayList<>();
		List<Color> colors = new ArrayList<>();

		if (object.has("floats")) readFloats(object.get("floats"), floats);
		if (object.has("strings")) readStrings(object.get("strings"), strings);
		if (object.has("flags")) readFlags(object.get("flags"), flags);
		if (object.has("colors")) readColors(object.get("colors"), colors);

		CustomModelDataValues values = new CustomModelDataValues(floats, strings, flags, colors);
		return values.isEmpty() ? null : values;
	}

	public static CustomModelDataValues parseFromConfig(ConfigurationSection config) {
		if (config.isConfigurationSection(CONFIG_NAME)) {
			return parseSection(config.getConfigurationSection(CONFIG_NAME));
		}

		if (config.isList(CONFIG_NAME)) {
			List<String> strings = new ArrayList<>();
			List<?> list = config.getList(CONFIG_NAME);
			if (list != null) {
				for (Object entry : list) {
					if (entry != null) strings.add(String.valueOf(entry));
				}
			}
			return strings.isEmpty() ? null : CustomModelDataValues.ofStrings(strings);
		}

		if (config.isInt(CONFIG_NAME)) {
			return CustomModelDataValues.ofLegacyInt(config.getInt(CONFIG_NAME));
		}

		if (config.isDouble(CONFIG_NAME)) {
			return new CustomModelDataValues(
				List.of((float) config.getDouble(CONFIG_NAME)),
				List.of(),
				List.of(),
				List.of()
			);
		}

		if (config.isString(CONFIG_NAME)) {
			String raw = config.getString(CONFIG_NAME);
			if (raw == null || raw.isEmpty()) return null;
			// Quoted numeric strings keep legacy int behavior for older configs.
			try {
				return CustomModelDataValues.ofLegacyInt(Integer.parseInt(raw.trim()));
			} catch (NumberFormatException ignored) {
				return CustomModelDataValues.ofString(raw);
			}
		}

		return null;
	}

	private static CustomModelDataValues parseSection(ConfigurationSection section) {
		if (section == null) return null;

		List<Float> floats = new ArrayList<>();
		List<String> strings = new ArrayList<>();
		List<Boolean> flags = new ArrayList<>();
		List<Color> colors = new ArrayList<>();

		if (section.isList("floats")) {
			List<?> list = section.getList("floats");
			if (list != null) {
				for (Object entry : list) {
					Float value = toFloat(entry);
					if (value != null) floats.add(value);
				}
			}
		} else if (section.contains("floats")) {
			Float value = toFloat(section.get("floats"));
			if (value != null) floats.add(value);
		}

		if (section.isList("strings")) {
			List<?> list = section.getList("strings");
			if (list != null) {
				for (Object entry : list) {
					if (entry != null) strings.add(String.valueOf(entry));
				}
			}
		} else if (section.isString("strings")) {
			strings.add(section.getString("strings"));
		}

		if (section.isList("flags")) {
			List<?> list = section.getList("flags");
			if (list != null) {
				for (Object entry : list) {
					Boolean value = toBoolean(entry);
					if (value != null) flags.add(value);
				}
			}
		} else if (section.contains("flags")) {
			Boolean value = toBoolean(section.get("flags"));
			if (value != null) flags.add(value);
		}

		if (section.isList("colors")) {
			List<?> list = section.getList("colors");
			if (list != null) {
				for (Object entry : list) {
					Color color = toColor(entry);
					if (color != null) colors.add(color);
				}
			}
		} else if (section.contains("colors")) {
			Color color = toColor(section.get("colors"));
			if (color != null) colors.add(color);
		}

		CustomModelDataValues values = new CustomModelDataValues(floats, strings, flags, colors);
		return values.isEmpty() ? null : values;
	}

	private static void readFloats(JsonElement element, List<Float> floats) {
		if (element.isJsonArray()) {
			for (JsonElement entry : element.getAsJsonArray()) {
				Float value = toFloat(jsonToObject(entry));
				if (value != null) floats.add(value);
			}
			return;
		}
		Float value = toFloat(jsonToObject(element));
		if (value != null) floats.add(value);
	}

	private static void readStrings(JsonElement element, List<String> strings) {
		if (element.isJsonArray()) {
			for (JsonElement entry : element.getAsJsonArray()) {
				if (entry != null && !entry.isJsonNull()) strings.add(entry.getAsString());
			}
			return;
		}
		if (!element.isJsonNull()) strings.add(element.getAsString());
	}

	private static void readFlags(JsonElement element, List<Boolean> flags) {
		if (element.isJsonArray()) {
			for (JsonElement entry : element.getAsJsonArray()) {
				Boolean value = toBoolean(jsonToObject(entry));
				if (value != null) flags.add(value);
			}
			return;
		}
		Boolean value = toBoolean(jsonToObject(element));
		if (value != null) flags.add(value);
	}

	private static void readColors(JsonElement element, List<Color> colors) {
		if (element.isJsonArray()) {
			for (JsonElement entry : element.getAsJsonArray()) {
				Color color = toColor(jsonToObject(entry));
				if (color != null) colors.add(color);
			}
			return;
		}
		Color color = toColor(jsonToObject(element));
		if (color != null) colors.add(color);
	}

	private static Object jsonToObject(JsonElement element) {
		if (element == null || element.isJsonNull()) return null;
		if (!element.isJsonPrimitive()) return null;
		var primitive = element.getAsJsonPrimitive();
		if (primitive.isBoolean()) return primitive.getAsBoolean();
		if (primitive.isNumber()) return primitive.getAsNumber();
		if (primitive.isString()) return primitive.getAsString();
		return null;
	}

	private static Float toFloat(Object value) {
		if (value instanceof Number number) return number.floatValue();
		if (value instanceof String string) {
			try {
				return Float.parseFloat(string.trim());
			} catch (NumberFormatException ignored) {
				return null;
			}
		}
		return null;
	}

	private static Boolean toBoolean(Object value) {
		if (value instanceof Boolean bool) return bool;
		if (value instanceof String string) {
			if (string.equalsIgnoreCase("true")) return true;
			if (string.equalsIgnoreCase("false")) return false;
		}
		return null;
	}

	private static Color toColor(Object value) {
		if (value instanceof Color color) return color;
		if (value instanceof Number number) {
			try {
				return Color.fromRGB(number.intValue());
			} catch (IllegalArgumentException ignored) {
				return null;
			}
		}
		if (value instanceof String string) {
			String trimmed = string.trim();
			if (trimmed.startsWith("#") || trimmed.chars().allMatch(c ->
				(c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
				return ColorUtil.getColorFromHexString(trimmed, false);
			}
			try {
				return Color.fromRGB(Integer.parseInt(trimmed));
			} catch (IllegalArgumentException ignored) {
				return null;
			}
		}
		return null;
	}

}
