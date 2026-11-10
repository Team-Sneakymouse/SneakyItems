package com.sneakyitems.util.magicitems;

import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Objects;
import java.util.Collection;

import com.google.common.collect.Multimap;

import net.kyori.adventure.text.Component;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;

import org.bukkit.*;
import org.bukkit.potion.PotionType;
import org.bukkit.attribute.Attribute;
import org.bukkit.potion.PotionEffect;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.attribute.AttributeModifier;

import com.sneakyitems.util.ItemParseUtil;
import com.sneakyitems.util.TxtUtil;
import com.sneakyutils.util.PotionIdentity;

import static com.sneakyitems.platform.SneakyItemsPlatform.checkItemPersistentData;

public class MagicItemData {

	private final EnumMap<MagicItemAttribute, Object> itemAttributes = new EnumMap<>(MagicItemAttribute.class);
	private final EnumSet<MagicItemAttribute> blacklistedAttributes = EnumSet.noneOf(MagicItemAttribute.class);
	private final EnumSet<MagicItemAttribute> ignoredAttributes = EnumSet.noneOf(MagicItemAttribute.class);

	private boolean strictEnchantLevel = true;
	private boolean strictDurability = true;
	private boolean strictBlockData = true;
	private boolean strictEnchants = true;

	/**
	 * Typed attribute lookup. Heterogeneous {@link EnumMap} values are cast once here
	 * so call sites stay unchecked-warning free.
	 */
	@SuppressWarnings("unchecked")
	public <T> T getAttribute(MagicItemAttribute attr) {
		return (T) itemAttributes.get(attr);
	}

	public void setAttribute(MagicItemAttribute attr, Object obj) {
		if (obj == null) return;
		if (!attr.getDataType().isAssignableFrom(obj.getClass())) return;

		itemAttributes.put(attr, obj);
	}

	public void removeAttribute(MagicItemAttribute attr) {
		itemAttributes.remove(attr);
	}

	public boolean hasAttribute(MagicItemAttribute atr) {
		return itemAttributes.containsKey(atr);
	}

	public EnumSet<MagicItemAttribute> getBlacklistedAttributes() {
		return blacklistedAttributes;
	}

	public EnumSet<MagicItemAttribute> getIgnoredAttributes() {
		return ignoredAttributes;
	}

	public boolean isStrictEnchantLevel() {
		return strictEnchantLevel;
	}

	public void setStrictEnchantLevel(boolean strictEnchantLevel) {
		this.strictEnchantLevel = strictEnchantLevel;
	}

	public boolean isStrictDurability() {
		return strictDurability;
	}

	public void setStrictDurability(boolean strictDurability) {
		this.strictDurability = strictDurability;
	}

	public boolean isStrictBlockData() {
		return strictBlockData;
	}

	public void setStrictBlockData(boolean strictBlockData) {
		this.strictBlockData = strictBlockData;
	}

	public boolean isStrictEnchants() {
		return strictEnchants;
	}

	public void setStrictEnchants(boolean strictEnchants) {
		this.strictEnchants = strictEnchants;
	}

	private boolean hasEqualAttributes(MagicItemData other) {
		Multimap<Attribute, AttributeModifier> attrSelf = getAttribute(MagicItemAttribute.ATTRIBUTES);
		Multimap<Attribute, AttributeModifier> attrOther = other.getAttribute(MagicItemAttribute.ATTRIBUTES);
		if (attrSelf == null || attrOther == null) return attrSelf == attrOther;

		Set<Attribute> keysSelf = attrSelf.keySet();
		Set<Attribute> keysOther = attrOther.keySet();
		if (!keysSelf.equals(keysOther)) return false;

		record AttributeModifierData(String name, double amt, AttributeModifier.Operation op, EquipmentSlotGroup slot) {

			public AttributeModifierData(AttributeModifier mod) {
				this(mod.getName(), mod.getAmount(), mod.getOperation(), mod.getSlotGroup());
			}

		}

		for (Attribute attr : keysSelf) {
			Collection<AttributeModifier> modsSelf = attrSelf.get(attr);
			Collection<AttributeModifier> modsOther = attrOther.get(attr);
			if (modsSelf.size() != modsOther.size()) return false;

			HashMap<AttributeModifierData, Integer> freq = new HashMap<>();
			for (AttributeModifier mod : modsSelf) {
				AttributeModifierData data = new AttributeModifierData(mod);
				Integer count = freq.get(data);

				if (count == null) count = 0;
				freq.put(data, count + 1);
			}

			for (AttributeModifier mod : modsOther) {
				AttributeModifierData data = new AttributeModifierData(mod);
				Integer count = freq.get(data);

				if (count == null) return false;
				if (count == 1) freq.remove(data);
				else freq.put(data, count - 1);
			}
		}

		return true;
	}

	public boolean matches(MagicItemData data) {
		return matches(data, EnumSet.noneOf(MagicItemAttribute.class));
	}

	public boolean matches(MagicItemData data, EnumSet<MagicItemAttribute> additionalIgnored) {
		if (this == data) return true;

		Set<MagicItemAttribute> keysSelf = itemAttributes.keySet();
		Set<MagicItemAttribute> keysOther = data.itemAttributes.keySet();

		for (MagicItemAttribute attr : keysSelf) {
			if (ignoredAttributes.contains(attr) || additionalIgnored.contains(attr)) continue;
			if (attr == MagicItemAttribute.PERSISTENT_DATA) continue;
			if (attr == MagicItemAttribute.TAGS) continue;
			if (attr == MagicItemAttribute.SOULBOUND || attr == MagicItemAttribute.EXPIRATION) continue;
			if (attr == MagicItemAttribute.MAGIC_ITEM_NAME && !checkItemPersistentData()) continue;
			if (!keysOther.contains(attr)) return false;
		}

		for (MagicItemAttribute attr : blacklistedAttributes) {
			if (keysOther.contains(attr)) return false;
		}

		for (MagicItemAttribute attr : keysSelf) {
			if (ignoredAttributes.contains(attr) || additionalIgnored.contains(attr)) continue;
			if (attr == MagicItemAttribute.SOULBOUND || attr == MagicItemAttribute.EXPIRATION) continue;

			switch (attr) {
				case ATTRIBUTES -> {
					if (!hasEqualAttributes(data)) return false;
				}
				case BLOCK_DATA -> {
					BlockData blockDataSelf = (BlockData) itemAttributes.get(attr);
					BlockData blockDataOther = (BlockData) data.itemAttributes.get(attr);

					if (strictBlockData) {
						if (!blockDataSelf.equals(blockDataOther))
							return false;

						continue;
					}

					if (!blockDataOther.matches(blockDataSelf)) return false;
				}
				case DURABILITY -> {
					Integer durabilitySelf = (Integer) itemAttributes.get(attr);
					Integer durabilityOther = (Integer) data.itemAttributes.get(attr);

					int compare = durabilitySelf.compareTo(durabilityOther);
					if (strictDurability ? compare != 0 : compare < 0) return false;
				}
				case ENCHANTS -> {
					if (strictEnchants && strictEnchantLevel) {
						if (!itemAttributes.get(attr).equals(data.itemAttributes.get(attr)))
							return false;

						continue;
					}

					Map<Enchantment, Integer> enchantsSelf = getAttribute(attr);
					Map<Enchantment, Integer> enchantsOther = data.getAttribute(attr);

					if (strictEnchants && enchantsSelf.size() != enchantsOther.size()) return false;

					for (Enchantment enchant : enchantsSelf.keySet()) {
						if (!enchantsOther.containsKey(enchant)) return false;

						Integer levelSelf = enchantsSelf.get(enchant);
						Integer levelOther = enchantsOther.get(enchant);

						int compare = levelSelf.compareTo(levelOther);
						if (strictEnchantLevel ? compare != 0 : compare > 0) return false;
					}
				}
				case NAME, ITEM_NAME -> {
					Component nameSelf = (Component) itemAttributes.get(attr);
					Component nameOther = (Component) data.itemAttributes.get(attr);
					if (!ItemParseUtil.getLegacyFromComponent(nameSelf).equals(ItemParseUtil.getLegacyFromComponent(nameOther)))
						return false;
				}
				case LORE -> {
					List<Component> loreSelf = getAttribute(attr);
					List<Component> loreOther = data.getAttribute(attr);
					if (loreSelf == null || loreOther == null) return loreSelf == loreOther;
					if (loreSelf.size() != loreOther.size()) return false;

					for (int i = 0; i < loreSelf.size(); i++) {
						String self = ItemParseUtil.getLegacyFromComponent(loreSelf.get(i));
						String other = ItemParseUtil.getLegacyFromComponent(loreOther.get(i));
						if (!self.equals(other)) return false;
					}
				}
				case MAGIC_ITEM_NAME -> {
					if (checkItemPersistentData()) continue;
					if (!itemAttributes.get(attr).equals(data.itemAttributes.get(attr))) return false;
				}
				default -> {
					Object self = itemAttributes.get(attr);
					Object other = data.itemAttributes.get(attr);
					if (!Objects.equals(self, other)) return false;
				}
			}
		}

		return true;
	}

	public boolean fastMatches(MagicItemData data) {
		if (this == data) return true;

		if (!itemAttributes.get(MagicItemAttribute.MAGIC_ITEM_NAME).equals(data.itemAttributes.get(MagicItemAttribute.MAGIC_ITEM_NAME))) {
			return false;
		}

		return true;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof MagicItemData other)) return false;
		return itemAttributes.equals(other.itemAttributes)
			&& ignoredAttributes.equals(other.ignoredAttributes)
			&& blacklistedAttributes.equals(other.blacklistedAttributes);
	}

	@Override
	public int hashCode() {
		return Objects.hash(itemAttributes, ignoredAttributes, blacklistedAttributes);
	}

	@Override
	public MagicItemData clone() {
		MagicItemData data = new MagicItemData();

		if (!itemAttributes.isEmpty()) data.itemAttributes.putAll(itemAttributes);
		if (!ignoredAttributes.isEmpty()) data.ignoredAttributes.addAll(ignoredAttributes);
		if (!blacklistedAttributes.isEmpty()) data.blacklistedAttributes.addAll(blacklistedAttributes);

		return data;
	}

	public enum MagicItemAttribute {

		TYPE(Material.class),
		NAME(Component.class),
		ITEM_NAME(Component.class),
		AMOUNT(Integer.class),
		DURABILITY(Integer.class),
		REPAIR_COST(Integer.class),
		CUSTOM_MODEL_DATA(CustomModelDataValues.class),
		MAX_STACK_SIZE(Integer.class),
		ITEM_MODEL(NamespacedKey.class),
		TOOLTIP_STYLE(NamespacedKey.class),
		RARITY(ItemRarity.class),
		ENCHANTABLE(Integer.class),
		GLIDER(Boolean.class),
		MAX_DAMAGE(Integer.class),
		FOOD(ItemComponentValues.Food.class),
		USE_COOLDOWN(ItemComponentValues.UseCooldown.class),
		EQUIPPABLE(ItemComponentValues.Equippable.class),
		JUKEBOX_PLAYABLE(ItemComponentValues.JukeboxPlayable.class),
		COMPONENTS(String.class),
		POWER(Integer.class),
		UNBREAKABLE(Boolean.class),
		HIDE_TOOLTIP(Boolean.class),
		INVISIBLE_TOOLTIP(Boolean.class),
		FAKE_GLINT(Boolean.class),
		POTION_DATA(PotionType.class),
		COLOR(Color.class),
		FIREWORK_EFFECT(FireworkEffect.class),
		TITLE(String.class),
		AUTHOR(String.class),
		UUID(String.class),
		TEXTURE(String.class),
		SIGNATURE(String.class),
		SKULL_OWNER(String.class),
		BLOCK_DATA(BlockData.class),
		ENCHANTS(Map.class),
		LORE(List.class),
		PAGES(List.class),
		POTION_EFFECTS(List.class),
		PATTERNS(List.class),
		FIREWORK_EFFECTS(List.class),
		ATTRIBUTES(Multimap.class),
		PERSISTENT_DATA(Map.class),
		PERMANENT_DATA(Map.class),
		TAGS(Set.class),
		MAGIC_ITEM_NAME(String.class),
		SOULBOUND(Boolean.class),
		EXPIRATION(Double.class);

		private final Class<?> dataType;
		private final String asString;

		MagicItemAttribute(Class<?> dataType) {
			this.dataType = dataType;
			asString = name().toLowerCase().replace('_', '-');
		}

		public Class<?> getDataType() {
			return dataType;
		}

		@Override
		public String toString() {
			return asString;
		}

	}

	@Override
	public String toString() {
		StringBuilder output = new StringBuilder();
		boolean previous = false;

		if (hasAttribute(MagicItemAttribute.TYPE))
			output.append(((Material) getAttribute(MagicItemAttribute.TYPE)).name());

		if (hasAttribute(MagicItemAttribute.NAME)) {
			output.append('{');

			output
				.append("\"name\":\"")
				.append(TxtUtil.escapeJSON(ItemParseUtil.getStringFromComponent((Component) getAttribute(MagicItemAttribute.NAME))))
				.append('"');

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.ITEM_NAME)) {
			if (previous) output.append(',');
			else output.append('{');

			output
				.append("\"item-name\":\"")
				.append(TxtUtil.escapeJSON(ItemParseUtil.getStringFromComponent((Component) getAttribute(MagicItemAttribute.ITEM_NAME))))
				.append('"');

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.AMOUNT)) {
			if (previous) output.append(',');
			else output.append('{');

			output
				.append("\"amount\":")
				.append((int) getAttribute(MagicItemAttribute.AMOUNT));

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.DURABILITY)) {
			if (previous) output.append(',');
			else output.append('{');

			output
				.append("\"durability\":")
				.append((int) getAttribute(MagicItemAttribute.DURABILITY));

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.REPAIR_COST)) {
			if (previous) output.append(',');
			else output.append('{');

			output
				.append("\"repair-cost\":")
				.append((int) getAttribute(MagicItemAttribute.REPAIR_COST));

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.CUSTOM_MODEL_DATA)) {
			if (previous) output.append(',');
			else output.append('{');

			output
				.append("\"custom-model-data\":")
				.append(((CustomModelDataValues) getAttribute(MagicItemAttribute.CUSTOM_MODEL_DATA)).toJsonValue());

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.MAX_STACK_SIZE)) {
			if (previous) output.append(',');
			else output.append('{');

			output
					.append("\"max-stack-size\":")
					.append((int) getAttribute(MagicItemAttribute.MAX_STACK_SIZE));

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.ITEM_MODEL)) {
			if (previous) output.append(',');
			else output.append('{');

			output
				.append("\"item-model\":\"")
				.append(((NamespacedKey) getAttribute(MagicItemAttribute.ITEM_MODEL)).asString())
				.append('"');

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.TOOLTIP_STYLE)) {
			if (previous) output.append(',');
			else output.append('{');

			output
				.append("\"tooltip-style\":\"")
				.append(((NamespacedKey) getAttribute(MagicItemAttribute.TOOLTIP_STYLE)).asString())
				.append('"');

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.RARITY)) {
			if (previous) output.append(',');
			else output.append('{');

			output
				.append("\"rarity\":\"")
				.append(((ItemRarity) getAttribute(MagicItemAttribute.RARITY)).name().toLowerCase())
				.append('"');

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.ENCHANTABLE)) {
			if (previous) output.append(',');
			else output.append('{');

			output
				.append("\"enchantable\":")
				.append((int) getAttribute(MagicItemAttribute.ENCHANTABLE));

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.GLIDER)) {
			if (previous) output.append(',');
			else output.append('{');

			output
				.append("\"glider\":")
				.append((boolean) getAttribute(MagicItemAttribute.GLIDER));

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.MAX_DAMAGE)) {
			if (previous) output.append(',');
			else output.append('{');

			output
				.append("\"max-damage\":")
				.append((int) getAttribute(MagicItemAttribute.MAX_DAMAGE));

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.FOOD)) {
			if (previous) output.append(',');
			else output.append('{');

			ItemComponentValues.Food food = (ItemComponentValues.Food) getAttribute(MagicItemAttribute.FOOD);
			output
				.append("\"food\":{")
				.append("\"nutrition\":").append(food.nutrition()).append(',')
				.append("\"saturation\":").append(food.saturation()).append(',')
				.append("\"can-always-eat\":").append(food.canAlwaysEat())
				.append('}');

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.USE_COOLDOWN)) {
			if (previous) output.append(',');
			else output.append('{');

			ItemComponentValues.UseCooldown cooldown = (ItemComponentValues.UseCooldown) getAttribute(MagicItemAttribute.USE_COOLDOWN);
			output.append("\"use-cooldown\":{\"seconds\":").append(cooldown.seconds());
			if (cooldown.cooldownGroup() != null) {
				output.append(",\"cooldown-group\":\"").append(cooldown.cooldownGroup().asString()).append('"');
			}
			output.append('}');

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.EQUIPPABLE)) {
			if (previous) output.append(',');
			else output.append('{');

			ItemComponentValues.Equippable equippable = (ItemComponentValues.Equippable) getAttribute(MagicItemAttribute.EQUIPPABLE);
			output
				.append("\"equippable\":{")
				.append("\"slot\":\"").append(equippable.slot().name().toLowerCase()).append('"')
				.append(",\"dispensable\":").append(equippable.dispensable())
				.append(",\"swappable\":").append(equippable.swappable())
				.append(",\"damage-on-hurt\":").append(equippable.damageOnHurt());
			if (equippable.equipSound() != null) {
				NamespacedKey soundKey = Registry.SOUNDS.getKey(equippable.equipSound());
				if (soundKey != null) {
					output.append(",\"equip-sound\":\"").append(soundKey.asString()).append('"');
				}
			}
			if (equippable.model() != null) {
				output.append(",\"model\":\"").append(equippable.model().asString()).append('"');
			}
			if (equippable.cameraOverlay() != null) {
				output.append(",\"camera-overlay\":\"").append(equippable.cameraOverlay().asString()).append('"');
			}
			output.append('}');

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.JUKEBOX_PLAYABLE)) {
			if (previous) output.append(',');
			else output.append('{');

			ItemComponentValues.JukeboxPlayable playable = (ItemComponentValues.JukeboxPlayable) getAttribute(MagicItemAttribute.JUKEBOX_PLAYABLE);
			output
				.append("\"jukebox-playable\":{")
				.append("\"song\":\"").append(playable.songKey().asString()).append('"')
				.append(",\"show-in-tooltip\":").append(playable.showInTooltip())
				.append('}');

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.COMPONENTS)) {
			if (previous) output.append(',');
			else output.append('{');

			output
				.append("\"components\":\"")
				.append(TxtUtil.escapeJSON((String) getAttribute(MagicItemAttribute.COMPONENTS)))
				.append('"');

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.POWER)) {
			if (previous) output.append(',');
			else output.append('{');

			output
				.append("\"power\":")
				.append((int) getAttribute(MagicItemAttribute.POWER));

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.UNBREAKABLE)) {
			if (previous) output.append(',');
			else output.append('{');

			output
				.append("\"unbreakable\":")
				.append((boolean) getAttribute(MagicItemAttribute.UNBREAKABLE));

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.HIDE_TOOLTIP)) {
			if (previous) output.append(',');
			else output.append('{');

			output
				.append("\"hide-tooltip\":")
				.append((boolean) getAttribute(MagicItemAttribute.HIDE_TOOLTIP));

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.INVISIBLE_TOOLTIP)) {
			if (previous) output.append(',');
			else output.append('{');

			output
					.append("\"invisible-tooltip\":")
					.append((boolean) getAttribute(MagicItemAttribute.INVISIBLE_TOOLTIP));

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.COLOR)) {
			if (previous) output.append(',');
			else output.append('{');

			Color color = (Color) getAttribute(MagicItemAttribute.COLOR);
			String hex = String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());

			output
				.append("\"color\":\"")
				.append(hex)
				.append('"');

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.POTION_DATA)) {
			if (previous) output.append(',');
			else output.append('{');

			PotionType potionType = (PotionType) getAttribute(MagicItemAttribute.POTION_DATA);

			output
				.append("\"potion-data\":\"")
				.append(PotionIdentity.serialize(potionType.name()))
				.append('"');

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.FIREWORK_EFFECT)) {
			if (previous) output.append(',');
			else output.append('{');

			FireworkEffect effect = (FireworkEffect) getAttribute(MagicItemAttribute.FIREWORK_EFFECT);

			output
				.append("\"firework-effect\":\"")
				.append(effect.getType())
				.append(' ')
				.append(effect.hasTrail())
				.append(' ')
				.append(effect.hasFlicker());

			boolean previousColor = false;
			if (!effect.getColors().isEmpty()) {
				output.append(' ');
				for (Color color : effect.getColors()) {
					if (previousColor) output.append(',');

					String hex = String.format("%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
					output.append(hex);

					previousColor = true;
				}

				if (!effect.getFadeColors().isEmpty()) {
					output.append(' ');
					previousColor = false;
					for (Color color : effect.getFadeColors()) {
						if (previousColor) output.append(',');

						String hex = String.format("%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
						output.append(hex);

						previousColor = true;
					}
				}
			}

			output.append('"');
			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.SKULL_OWNER)) {
			if (previous) output.append(',');
			else output.append('{');

			output
				.append("\"skull-owner\":\"")
				.append((String) getAttribute(MagicItemAttribute.SKULL_OWNER))
				.append('"');

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.TITLE)) {
			if (previous) output.append(',');
			else output.append('{');

			output
				.append("\"title\":\"")
				.append(TxtUtil.escapeJSON((String) getAttribute(MagicItemAttribute.TITLE)))
				.append('"');

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.AUTHOR)) {
			if (previous) output.append(',');
			else output.append('{');

			output
				.append("\"author\":\"")
				.append(TxtUtil.escapeJSON((String) getAttribute(MagicItemAttribute.AUTHOR)))
				.append('"');

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.UUID)) {
			if (previous) output.append(',');
			else output.append('{');

			output
				.append("\"uuid\":\"")
				.append(((String) getAttribute(MagicItemAttribute.UUID)))
				.append('"');

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.TEXTURE)) {
			if (previous) output.append(',');
			else output.append('{');

			output
				.append("\"texture\":\"")
				.append(((String) getAttribute(MagicItemAttribute.TEXTURE)))
				.append('"');

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.SIGNATURE)) {
			if (previous) output.append(',');
			else output.append('{');

			output
				.append("\"signature\":\"")
				.append(((String) getAttribute(MagicItemAttribute.SIGNATURE)))
				.append('"');

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.ENCHANTS)) {
			if (previous) output.append(',');
			else output.append('{');

			Map<Enchantment, Integer> enchantments = getAttribute(MagicItemAttribute.ENCHANTS);
			boolean previousEnchantment = false;
			output.append("\"enchants\":{");
			for (Enchantment enchantment : enchantments.keySet()) {
				if (previousEnchantment) output.append(',');

				output
					.append('"')
					.append(enchantment.getKey().getKey())
					.append("\":")
					.append(enchantments.get(enchantment));

				previousEnchantment = true;
			}
			output.append('}');
			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.FAKE_GLINT)) {
			if (previous) output.append(',');
			else output.append('{');

			output
				.append("\"fake-glint\":")
				.append((boolean) getAttribute(MagicItemAttribute.FAKE_GLINT));

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.ATTRIBUTES)) {
			if (previous) output.append(',');
			else output.append('{');

			Multimap<Attribute, AttributeModifier> attributes = getAttribute(MagicItemAttribute.ATTRIBUTES);
			boolean previousAttribute = false;
			output.append("\"attributes\":[");
			for (Map.Entry<Attribute, AttributeModifier> entries : attributes.entries()) {
				if (previousAttribute) output.append(',');

				AttributeModifier modifier = entries.getValue();

				output
					.append('"')
					.append(modifier.getName())
					.append(' ')
					.append(modifier.getAmount())
					.append(' ')
					.append(modifier.getOperation().name().toLowerCase());

				EquipmentSlotGroup slot = modifier.getSlotGroup();
				if (slot != null && slot != EquipmentSlotGroup.ANY) {
					output
						.append(' ')
						.append(slot.toString());
				}

				output.append('"');
				previousAttribute = true;
			}
			output.append(']');

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.LORE)) {
			if (previous) output.append(',');
			else output.append('{');

			List<Component> lore = getAttribute(MagicItemAttribute.LORE);
			boolean previousLore = false;
			output.append("\"lore\":[");
			for (Component line : lore) {
				if (previousLore) output.append(',');

				output
					.append('"')
					.append(TxtUtil.escapeJSON(ItemParseUtil.getStringFromComponent(line)))
					.append('"');

				previousLore = true;
			}
			output.append(']');

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.PAGES)) {
			if (previous) output.append(',');
			else output.append('{');

			List<Component> pages = getAttribute(MagicItemAttribute.PAGES);
			boolean previousPages = false;
			output.append("\"pages\":[");
			for (Component page : pages) {
				if (previousPages) output.append(',');

				output
					.append('"')
					.append(TxtUtil.escapeJSON(ItemParseUtil.getStringFromComponent(page)))
					.append('"');

				previousPages = true;
			}
			output.append(']');

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.PATTERNS)) {
			if (previous) output.append(',');
			else output.append('{');

			output.append("\"patterns\":[");
			List<Pattern> patterns = getAttribute(MagicItemAttribute.PATTERNS);
			boolean previousPattern = false;
			for (Pattern pattern : patterns) {
				if (previousPattern) output.append(',');

				NamespacedKey patternKey = RegistryAccess.registryAccess()
					.getRegistry(RegistryKey.BANNER_PATTERN).getKey(pattern.getPattern());
				output
					.append('"')
					.append(patternKey != null ? patternKey.asString() : pattern.getPattern().toString())
					.append(' ')
					.append(pattern.getColor().name())
					.append('"');

				previousPattern = true;
			}
			output.append(']');

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.POTION_EFFECTS)) {
			if (previous) output.append(',');
			else output.append('{');

			output.append("\"potion-effects\":[");
			List<PotionEffect> effects = getAttribute(MagicItemAttribute.POTION_EFFECTS);
			boolean previousEffect = false;
			for (PotionEffect effect : effects) {
				if (previousEffect) output.append(',');

				output
					.append('"')
					.append(effect.getType().getKey().getKey())
					.append(' ')
					.append(effect.getAmplifier())
					.append(' ')
					.append(effect.getDuration())
					.append('"');

				previousEffect = true;
			}
			output.append(']');

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.FIREWORK_EFFECTS)) {
			List<FireworkEffect> effects = getAttribute(MagicItemAttribute.FIREWORK_EFFECTS);

			if (previous) output.append(',');
			else output.append('{');

			output.append("\"firework-effects\":[");
			boolean previousEffect = false;
			for (FireworkEffect effect : effects) {
				if (previousEffect) output.append(',');

				output
					.append('"')
					.append(effect.getType())
					.append(' ')
					.append(effect.hasTrail())
					.append(' ')
					.append(effect.hasFlicker());

				boolean previousColor = false;
				if (!effect.getColors().isEmpty()) {
					output.append(' ');
					for (Color color : effect.getColors()) {
						if (previousColor) output.append(',');
						String hex = String.format("%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
						output.append(hex);
						previousColor = true;
					}

					if (!effect.getFadeColors().isEmpty()) {
						output.append(' ');
						previousColor = false;
						for (Color color : effect.getFadeColors()) {
							if (previousColor) output.append(',');
							String hex = String.format("%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
							output.append(hex);
							previousColor = true;
						}
					}
				}

				output.append('"');
				previousEffect = true;
			}

			output.append(']');
			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.PERSISTENT_DATA)) {
			if (previous) output.append(',');
			else output.append('{');

			Map<String, String> pdcMap = getAttribute(MagicItemAttribute.PERSISTENT_DATA);
			boolean previousLine = false;
			output.append("\"data\":[");
			if (pdcMap != null) {
				for (Map.Entry<String, String> entry : pdcMap.entrySet()) {
					if (previousLine) output.append(',');

					output
							.append('"')
							.append(TxtUtil.escapeJSON(entry.getKey() + ' ' + entry.getValue()))
							.append('"');

					previousLine = true;
				}
			}
			output.append(']');

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.TAGS)) {
			if (previous) output.append(',');
			else output.append('{');

			Set<String> tags = getAttribute(MagicItemAttribute.TAGS);
			boolean previousTag = false;
			output.append("\"tags\":[");
			if (tags != null) {
				for (String tag : tags) {
					if (previousTag) output.append(',');
					output.append('"').append(TxtUtil.escapeJSON(tag)).append('"');
					previousTag = true;
				}
			}
			output.append(']');

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.SOULBOUND)) {
			if (previous) output.append(',');
			else output.append('{');

			output
				.append("\"soulbound\":")
				.append((boolean) getAttribute(MagicItemAttribute.SOULBOUND));

			previous = true;
		}

		if (hasAttribute(MagicItemAttribute.EXPIRATION)) {
			if (previous) output.append(',');
			else output.append('{');

			output
				.append("\"expiration\":")
				.append((Double) getAttribute(MagicItemAttribute.EXPIRATION));

			previous = true;
		}

		if (!ignoredAttributes.isEmpty()) {
			if (previous) output.append(",");
			else output.append('{');

			output.append("\"ignored-attributes\":[");
			boolean previousAttribute = false;
			for (MagicItemAttribute attr : ignoredAttributes) {
				if (previousAttribute) output.append(',');

				output
					.append('"')
					.append(attr.name())
					.append('"');

				previousAttribute = true;
			}

			output.append(']');
			previous = true;
		}

		if (!blacklistedAttributes.isEmpty()) {
			if (previous) output.append(",");
			else output.append('{');

			output.append("\"blacklisted-attributes\":[");
			boolean previousAttribute = false;
			for (MagicItemAttribute attr : blacklistedAttributes) {
				if (previousAttribute) output.append(',');

				output
					.append('"')
					.append(attr.name())
					.append('"');

				previousAttribute = true;
			}

			output.append(']');
			previous = true;
		}

		if (!strictEnchants) {
			if (previous) output.append(",");
			else output.append('{');

			output.append("\"strict-enchants\": false");
			previous = true;
		}

		if (!strictDurability) {
			if (previous) output.append(',');
			else output.append('{');

			output.append("\"strict-durability\": false");
			previous = true;
		}

		if (!strictBlockData) {
			if (previous) output.append(',');
			else output.append('{');

			output.append("\"strict-block-data\": false");
			previous = true;
		}

		if (!strictEnchantLevel) {
			if (previous) output.append(",");
			else output.append('{');

			output.append("\"strict-enchant-level\": false");
			previous = true;
		}

		if (previous) output.append('}');

		return output.toString();
	}

}
