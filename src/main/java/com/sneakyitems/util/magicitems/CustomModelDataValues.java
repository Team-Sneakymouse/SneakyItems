package com.sneakyitems.util.magicitems;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.bukkit.Color;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;

import com.sneakyitems.util.TxtUtil;

/**
 * Immutable snapshot of a {@code minecraft:custom_model_data} component.
 * Legacy integer CMD is represented as a single entry in {@link #getFloats()}.
 */
public final class CustomModelDataValues {

	private final List<Float> floats;
	private final List<String> strings;
	private final List<Boolean> flags;
	private final List<Color> colors;

	public CustomModelDataValues(List<Float> floats, List<String> strings, List<Boolean> flags, List<Color> colors) {
		this.floats = copyFloats(floats);
		this.strings = copyStrings(strings);
		this.flags = copyFlags(flags);
		this.colors = copyColors(colors);
	}

	public static CustomModelDataValues ofLegacyInt(int value) {
		return new CustomModelDataValues(List.of((float) value), List.of(), List.of(), List.of());
	}

	public static CustomModelDataValues ofString(String value) {
		return new CustomModelDataValues(List.of(), List.of(value), List.of(), List.of());
	}

	public static CustomModelDataValues ofStrings(List<String> values) {
		return new CustomModelDataValues(List.of(), values, List.of(), List.of());
	}

	public static CustomModelDataValues fromMeta(ItemMeta meta) {
		if (meta == null) return null;
		return fromComponent(meta.getCustomModelDataComponent());
	}

	public static CustomModelDataValues fromComponent(CustomModelDataComponent component) {
		if (component == null) return null;
		CustomModelDataValues values = new CustomModelDataValues(
			component.getFloats(),
			component.getStrings(),
			component.getFlags(),
			component.getColors()
		);
		return values.isEmpty() ? null : values;
	}

	public void applyTo(ItemMeta meta) {
		if (meta == null || isEmpty()) return;
		CustomModelDataComponent component = meta.getCustomModelDataComponent();
		component.setFloats(new ArrayList<>(floats));
		component.setStrings(new ArrayList<>(strings));
		component.setFlags(new ArrayList<>(flags));
		component.setColors(new ArrayList<>(colors));
		meta.setCustomModelDataComponent(component);
	}

	public boolean isEmpty() {
		return floats.isEmpty() && strings.isEmpty() && flags.isEmpty() && colors.isEmpty();
	}

	/** First float as int, or null if there are no floats. */
	public Integer getLegacyIntOrNull() {
		if (floats.isEmpty()) return null;
		return floats.get(0).intValue();
	}

	public List<Float> getFloats() {
		return floats;
	}

	public List<String> getStrings() {
		return strings;
	}

	public List<Boolean> getFlags() {
		return flags;
	}

	public List<Color> getColors() {
		return colors;
	}

	/**
	 * JSON fragment suitable for cast-item / MagicItemData string forms
	 * (no surrounding key). Prefers compact shorthands when possible.
	 */
	public String toJsonValue() {
		if (isLegacyIntOnly()) {
			float f = floats.get(0);
			if (f == Math.rint(f)) return Integer.toString((int) f);
			return Float.toString(f);
		}
		if (strings.size() == 1 && floats.isEmpty() && flags.isEmpty() && colors.isEmpty()) {
			return '"' + TxtUtil.escapeJSON(strings.get(0)) + '"';
		}
		if (!strings.isEmpty() && floats.isEmpty() && flags.isEmpty() && colors.isEmpty()) {
			StringBuilder out = new StringBuilder("[");
			for (int i = 0; i < strings.size(); i++) {
				if (i > 0) out.append(',');
				out.append('"').append(TxtUtil.escapeJSON(strings.get(i))).append('"');
			}
			return out.append(']').toString();
		}

		StringBuilder out = new StringBuilder("{");
		boolean previous = false;
		if (!floats.isEmpty()) {
			out.append("\"floats\":[");
			for (int i = 0; i < floats.size(); i++) {
				if (i > 0) out.append(',');
				float f = floats.get(i);
				if (f == Math.rint(f)) out.append((int) f);
				else out.append(f);
			}
			out.append(']');
			previous = true;
		}
		if (!strings.isEmpty()) {
			if (previous) out.append(',');
			out.append("\"strings\":[");
			for (int i = 0; i < strings.size(); i++) {
				if (i > 0) out.append(',');
				out.append('"').append(TxtUtil.escapeJSON(strings.get(i))).append('"');
			}
			out.append(']');
			previous = true;
		}
		if (!flags.isEmpty()) {
			if (previous) out.append(',');
			out.append("\"flags\":[");
			for (int i = 0; i < flags.size(); i++) {
				if (i > 0) out.append(',');
				out.append(flags.get(i));
			}
			out.append(']');
			previous = true;
		}
		if (!colors.isEmpty()) {
			if (previous) out.append(',');
			out.append("\"colors\":[");
			for (int i = 0; i < colors.size(); i++) {
				if (i > 0) out.append(',');
				Color color = colors.get(i);
				out.append('"')
					.append(String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue()))
					.append('"');
			}
			out.append(']');
		}
		return out.append('}').toString();
	}

	private boolean isLegacyIntOnly() {
		return floats.size() == 1 && strings.isEmpty() && flags.isEmpty() && colors.isEmpty();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof CustomModelDataValues other)) return false;
		return floats.equals(other.floats)
			&& strings.equals(other.strings)
			&& flags.equals(other.flags)
			&& colors.equals(other.colors);
	}

	@Override
	public int hashCode() {
		return Objects.hash(floats, strings, flags, colors);
	}

	@Override
	public String toString() {
		return "CustomModelDataValues" + toJsonValue();
	}

	private static List<Float> copyFloats(List<Float> source) {
		if (source == null || source.isEmpty()) return List.of();
		List<Float> copy = new ArrayList<>(source.size());
		for (Float value : source) {
			if (value != null) copy.add(value);
		}
		return Collections.unmodifiableList(copy);
	}

	private static List<String> copyStrings(List<String> source) {
		if (source == null || source.isEmpty()) return List.of();
		List<String> copy = new ArrayList<>(source.size());
		for (String value : source) {
			if (value != null) copy.add(value);
		}
		return Collections.unmodifiableList(copy);
	}

	private static List<Boolean> copyFlags(List<Boolean> source) {
		if (source == null || source.isEmpty()) return List.of();
		List<Boolean> copy = new ArrayList<>(source.size());
		for (Boolean value : source) {
			if (value != null) copy.add(value);
		}
		return Collections.unmodifiableList(copy);
	}

	private static List<Color> copyColors(List<Color> source) {
		if (source == null || source.isEmpty()) return List.of();
		List<Color> copy = new ArrayList<>(source.size());
		for (Color value : source) {
			if (value != null) copy.add(value);
		}
		return Collections.unmodifiableList(copy);
	}

}
