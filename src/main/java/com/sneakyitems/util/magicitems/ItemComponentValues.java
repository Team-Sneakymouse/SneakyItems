package com.sneakyitems.util.magicitems;

import java.util.Objects;

import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.inventory.EquipmentSlot;

/** Value types for modern item data components stored on {@link MagicItemData}. */
public final class ItemComponentValues {

	private ItemComponentValues() {
	}

	public record Food(int nutrition, float saturation, boolean canAlwaysEat) {
	}

	public record UseCooldown(float seconds, NamespacedKey cooldownGroup) {
	}

	public record Equippable(
		EquipmentSlot slot,
		Sound equipSound,
		NamespacedKey model,
		NamespacedKey cameraOverlay,
		boolean dispensable,
		boolean swappable,
		boolean damageOnHurt
	) {
	}

	public record JukeboxPlayable(NamespacedKey songKey, boolean showInTooltip) {
		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof JukeboxPlayable other)) return false;
			return showInTooltip == other.showInTooltip && Objects.equals(songKey, other.songKey);
		}

		@Override
		public int hashCode() {
			return Objects.hash(songKey, showInTooltip);
		}
	}

}
