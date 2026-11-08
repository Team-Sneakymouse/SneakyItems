package com.sneakyitems.util.magicitems;

import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.ArrayList;

import java.io.IOException;
import java.io.StringReader;

import com.google.gson.Gson;
import com.google.gson.Strictness;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonToken;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.JsonSyntaxException;

import com.google.common.collect.Multimap;
import com.google.common.collect.HashMultimap;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;

import org.bukkit.*;
import org.bukkit.potion.PotionType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.block.banner.PatternType;
import org.bukkit.attribute.AttributeModifier;

import com.sneakyitems.util.ItemParseUtil;
import com.sneakyitems.platform.SneakyItemsPlatform;
import com.sneakyutils.util.AttributeSpecParser;
import com.sneakyutils.util.AttributeUtil;
import com.sneakyutils.util.SoundUtil;
import com.sneakyitems.handlers.DebugHandler;
import com.sneakyitems.handlers.EnchantmentHandler;
import com.sneakyitems.util.itemreader.PotionHandler;
import com.sneakyitems.util.itemreader.CustomModelDataHandler;
import com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute;
import static com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute.*;

public class MagicItemDataParser {

	private static final Gson gson = new Gson();
	private static final TypeAdapter<JsonElement> jsonElementTypeAdapter = gson.getAdapter(JsonElement.class);

	/* splits the saved magicItemData string by the "|" char
		itemType{data}|itemType{data}...
	*/
	public static final String DATA_REGEX = "(?=(?:(?:[^\"]*\"){2})*[^\"]*$)(?![^{]*})(?![^\\[]*\\])\\|+";

	public static MagicItemData parseMagicItemData(String str) {
		String[] args = str.split("\\{", 2);
		// check if it contains additional data
		if (args.length < 2) {
			// it doesn't, check if it's a material type
			Material type = ItemParseUtil.getMaterial(str.trim());
			if (type == null) return null;

			MagicItemData magicItemData = new MagicItemData();
			magicItemData.setAttribute(TYPE, type);

			return magicItemData;
		}

		String base = args[0].trim();
		args[1] = "{" + args[1];

		MagicItemData data;

		Material type = ItemParseUtil.getMaterial(base);
		if (type != null) {
			data = new MagicItemData();
			data.setAttribute(TYPE, type);

			if (type.isAir()) return data;
		} else {
			MagicItem magicItem = MagicItems.getMagicItems().get(base);
			if (magicItem == null) return null;
			data = magicItem.getMagicItemData().clone();

			if (data.hasAttribute(TYPE) && ((Material) data.getAttribute(TYPE)).isAir()) return data;
		}

		JsonReader jsonReader = new JsonReader(new StringReader(args[1]));
		jsonReader.setStrictness(Strictness.LENIENT);

		try {
			while (jsonReader.peek() != JsonToken.END_DOCUMENT) {
				JsonElement jsonElement = jsonElementTypeAdapter.read(jsonReader);

				if (!jsonElement.isJsonObject()) continue;
				JsonObject jsonObject = jsonElement.getAsJsonObject();

				Set<Map.Entry<String, JsonElement>> jsonEntries = jsonObject.entrySet();
				for (Map.Entry<String, JsonElement> entry : jsonEntries) {
					String key = entry.getKey();
					JsonElement value = entry.getValue();

					switch (key.toLowerCase()) {
						case "name":
							data.setAttribute(NAME, ItemParseUtil.getMiniMessage(value.getAsString()));
							break;
						case "amount":
							data.setAttribute(AMOUNT, value.getAsInt());
							break;
						case "block-data":
							String blockDataString = value.getAsString();
							BlockData blockData;
							try {
								blockData = Bukkit.createBlockData(type, blockDataString);
							} catch (IllegalArgumentException e) {
								SneakyItemsPlatform.error("Invalid block data '" + blockDataString + "' when parsing magic item '" + str + "'.");
								DebugHandler.debugIllegalArgumentException(e);

								continue;
							}

							data.setAttribute(BLOCK_DATA, blockData);
						case "durability":
							data.setAttribute(DURABILITY, value.getAsInt());
							break;
						case "repaircost":
						case "repair-cost":
						case "repair_cost":
							data.setAttribute(REPAIR_COST, value.getAsInt());
							break;
						case "custommodeldata":
						case "custom-model-data":
						case "custom_model_data":
							CustomModelDataValues customModelData = CustomModelDataHandler.parseFromJson(value);
							if (customModelData != null) data.setAttribute(CUSTOM_MODEL_DATA, customModelData);
							break;
						case "maxstacksize":
						case "max-stack-size":
						case "max_stack_size":
							data.setAttribute(MAX_STACK_SIZE, value.getAsInt());
							break;
						case "itemmodel":
						case "item-model":
						case "item_model": {
							NamespacedKey itemModelKey = NamespacedKey.fromString(value.getAsString());
							if (itemModelKey != null) data.setAttribute(ITEM_MODEL, itemModelKey);
							break;
						}
						case "tooltipstyle":
						case "tooltip-style":
						case "tooltip_style": {
							NamespacedKey tooltipStyleKey = NamespacedKey.fromString(value.getAsString());
							if (tooltipStyleKey != null) data.setAttribute(TOOLTIP_STYLE, tooltipStyleKey);
							break;
						}
						case "rarity":
							try {
								data.setAttribute(RARITY, ItemRarity.valueOf(value.getAsString().trim().toUpperCase()));
							} catch (IllegalArgumentException e) {
								DebugHandler.debugBadEnumValue(ItemRarity.class, value.getAsString());
							}
							break;
						case "enchantable":
							data.setAttribute(ENCHANTABLE, value.getAsInt());
							break;
						case "glider":
							data.setAttribute(GLIDER, value.getAsBoolean());
							break;
						case "maxdamage":
						case "max-damage":
						case "max_damage":
							data.setAttribute(MAX_DAMAGE, value.getAsInt());
							break;
						case "food":
							if (value.isJsonObject()) {
								JsonObject food = value.getAsJsonObject();
								int nutrition = food.has("nutrition") ? food.get("nutrition").getAsInt() : 0;
								float saturation = food.has("saturation") ? food.get("saturation").getAsFloat() : 0;
								boolean canAlwaysEat = food.has("can-always-eat") && food.get("can-always-eat").getAsBoolean()
									|| food.has("can_always_eat") && food.get("can_always_eat").getAsBoolean();
								data.setAttribute(FOOD, new ItemComponentValues.Food(nutrition, saturation, canAlwaysEat));
							}
							break;
						case "usecooldown":
						case "use-cooldown":
						case "use_cooldown":
							if (value.isJsonObject()) {
								JsonObject cooldown = value.getAsJsonObject();
								float seconds = cooldown.has("seconds") ? cooldown.get("seconds").getAsFloat() : 0;
								NamespacedKey group = null;
								if (cooldown.has("cooldown-group")) group = NamespacedKey.fromString(cooldown.get("cooldown-group").getAsString());
								else if (cooldown.has("cooldown_group")) group = NamespacedKey.fromString(cooldown.get("cooldown_group").getAsString());
								data.setAttribute(USE_COOLDOWN, new ItemComponentValues.UseCooldown(seconds, group));
							}
							break;
						case "equippable":
							if (value.isJsonObject()) {
								JsonObject equippable = value.getAsJsonObject();
								if (!equippable.has("slot")) break;
								try {
									EquipmentSlot slot = EquipmentSlot.valueOf(equippable.get("slot").getAsString().trim().toUpperCase());
									Sound equipSound = null;
									if (equippable.has("equip-sound")) {
										try {
											equipSound = SoundUtil.resolve(equippable.get("equip-sound").getAsString().trim());
										} catch (IllegalArgumentException ignored) {}
									} else if (equippable.has("equip_sound")) {
										try {
											equipSound = SoundUtil.resolve(equippable.get("equip_sound").getAsString().trim());
										} catch (IllegalArgumentException ignored) {}
									}
									NamespacedKey model = null;
									if (equippable.has("model")) model = NamespacedKey.fromString(equippable.get("model").getAsString());
									NamespacedKey cameraOverlay = null;
									if (equippable.has("camera-overlay")) cameraOverlay = NamespacedKey.fromString(equippable.get("camera-overlay").getAsString());
									else if (equippable.has("camera_overlay")) cameraOverlay = NamespacedKey.fromString(equippable.get("camera_overlay").getAsString());
									boolean dispensable = !equippable.has("dispensable") || equippable.get("dispensable").getAsBoolean();
									boolean swappable = !equippable.has("swappable") || equippable.get("swappable").getAsBoolean();
									boolean damageOnHurt = !equippable.has("damage-on-hurt") || equippable.get("damage-on-hurt").getAsBoolean();
									if (equippable.has("damage_on_hurt")) damageOnHurt = equippable.get("damage_on_hurt").getAsBoolean();
									data.setAttribute(EQUIPPABLE, new ItemComponentValues.Equippable(
										slot, equipSound, model, cameraOverlay, dispensable, swappable, damageOnHurt
									));
								} catch (IllegalArgumentException e) {
									DebugHandler.debugBadEnumValue(EquipmentSlot.class, equippable.get("slot").getAsString());
								}
							}
							break;
						case "jukeboxplayable":
						case "jukebox-playable":
						case "jukebox_playable":
							if (value.isJsonPrimitive()) {
								NamespacedKey songKey = NamespacedKey.fromString(value.getAsString());
								if (songKey != null) data.setAttribute(JUKEBOX_PLAYABLE, new ItemComponentValues.JukeboxPlayable(songKey, true));
							} else if (value.isJsonObject()) {
								JsonObject playable = value.getAsJsonObject();
								if (!playable.has("song")) break;
								NamespacedKey songKey = NamespacedKey.fromString(playable.get("song").getAsString());
								boolean showInTooltip = !playable.has("show-in-tooltip") || playable.get("show-in-tooltip").getAsBoolean();
								if (playable.has("show_in_tooltip")) showInTooltip = playable.get("show_in_tooltip").getAsBoolean();
								if (songKey != null) data.setAttribute(JUKEBOX_PLAYABLE, new ItemComponentValues.JukeboxPlayable(songKey, showInTooltip));
							}
							break;
						case "components":
							data.setAttribute(COMPONENTS, value.getAsString());
							break;
						case "power":
							data.setAttribute(POWER, value.getAsInt());
							break;
						case "unbreakable":
							data.setAttribute(UNBREAKABLE, value.getAsBoolean());
							break;
						case "soulbound":
							data.setAttribute(SOULBOUND, value.getAsBoolean());
							break;
						case "expiration":
							data.setAttribute(EXPIRATION, value.getAsDouble());
							break;
						case "hidetooltip":
						case "hide-tooltip":
						case "hide_tooltip":
							data.setAttribute(HIDE_TOOLTIP, value.getAsBoolean());
							break;
						case "invisibletooltip":
						case "invisible-tooltip":
						case "invisible_tooltip":
							data.setAttribute(INVISIBLE_TOOLTIP, value.getAsBoolean());
							break;
						case "color":
							try {
								Color color = Color.fromRGB(Integer.parseInt(value.getAsString().replace("#", ""), 16));
								data.setAttribute(COLOR, color);
							} catch (NumberFormatException e) {
								DebugHandler.debugNumberFormat(e);
							}
							break;
						case "potiondata":
						case "potion-data":
						case "potion_data":
						case "potiontype":
						case "potion-type":
						case "potion_type":
							try {
								data.setAttribute(POTION_DATA, PotionHandler.resolvePotionType(value.getAsString()));
							} catch (IllegalArgumentException e) {
								DebugHandler.debugIllegalArgumentException(e);
							}
							break;
						case "fireworkeffect":
						case "firework-effect":
						case "firework_effect":
							String[] effectString = value.getAsString().split(" ");

							if (effectString.length >= 4) {
								try {
									FireworkEffect.Type fireworkType = FireworkEffect.Type.valueOf(effectString[0].toUpperCase());
									boolean trail = Boolean.parseBoolean(effectString[1]);
									boolean flicker = Boolean.parseBoolean(effectString[2]);
									Color[] colors = ItemParseUtil.getColorsFromString(effectString[3]);
									Color[] fadeColors = null;

									if (effectString.length > 4) fadeColors = ItemParseUtil.getColorsFromString(effectString[4]);
									if (fadeColors == null) fadeColors = new Color[0];

									FireworkEffect effect = FireworkEffect.builder()
										.flicker(flicker)
										.trail(trail)
										.with(fireworkType)
										.withColor(colors)
										.withFade(fadeColors)
										.build();

									data.setAttribute(FIREWORK_EFFECT, effect);
								} catch (IllegalArgumentException e) {
									DebugHandler.debugBadEnumValue(FireworkEffect.Type.class, effectString[0].toUpperCase());
									SneakyItemsPlatform.error("'" + value.getAsString() + "' could not be connected to a firework effect.");
								}
							} else SneakyItemsPlatform.error("'" + value.getAsString() + "' could not be connected to a firework effect.");
							break;
						case "skullowner":
						case "skull-owner":
						case "skull_owner":
							data.setAttribute(SKULL_OWNER, value.getAsString());
							break;
						case "title":
							data.setAttribute(TITLE, ItemParseUtil.colorize(value.getAsString()));
							break;
						case "author":
							data.setAttribute(AUTHOR, ItemParseUtil.colorize(value.getAsString()));
							break;
						case "uuid":
							data.setAttribute(UUID, value.getAsString());
							break;
						case "texture":
							data.setAttribute(TEXTURE, value.getAsString());
							break;
						case "signature":
							data.setAttribute(SIGNATURE, value.getAsString());
							break;
						case "enchantments":
						case "enchants":
							if (!value.isJsonObject()) continue;

							Map<String, Integer> objectMap;
							try {
								objectMap = gson.fromJson(value.getAsJsonObject().toString(), new TypeToken<HashMap<String, Integer>>(){}.getType());

								Map<Enchantment, Integer> enchantments = new HashMap<>();
								for (String enchantString : objectMap.keySet()) {
									Enchantment enchantment = EnchantmentHandler.getEnchantment(enchantString);

									if (enchantment == null) {
										SneakyItemsPlatform.error('\'' + enchantString + "' could not be connected to an enchantment");
										continue;
									}

									enchantments.put(enchantment, objectMap.get(enchantString));
								}

								if (data.hasAttribute(FAKE_GLINT)) {
									boolean fakeGlint = (boolean) data.getAttribute(FAKE_GLINT);

									if (!enchantments.isEmpty() && fakeGlint) data.removeAttribute(FAKE_GLINT);
								}

								if (!enchantments.isEmpty()) data.setAttribute(ENCHANTS, enchantments);
							} catch (JsonSyntaxException exception) {
								SneakyItemsPlatform.error("Invalid enchantment syntax!");
								continue;
							}
							break;
						case "fakeglint":
						case "fake-glint":
						case "fake_glint":
							if (data.hasAttribute(ENCHANTS)) {
								Map<Enchantment, Integer> enchantments = data.getAttribute(ENCHANTS);
								boolean fakeGlint = value.getAsBoolean();

								if (enchantments.isEmpty() && fakeGlint) data.setAttribute(FAKE_GLINT, true);
							} else if (value.getAsBoolean()) data.setAttribute(FAKE_GLINT, true);
							break;
						case "attributes":
							if (!value.isJsonArray()) continue;

							Multimap<Attribute, AttributeModifier> itemAttributes = HashMultimap.create();
							JsonArray attributeArray = value.getAsJsonArray();
							for (JsonElement element : attributeArray) {
								String[] attributeArgs = element.getAsString().split(" ");
								if (attributeArgs.length < 2) continue;

								Attribute attribute = AttributeUtil.getAttribute(attributeArgs[0]);
								double val = Double.parseDouble(attributeArgs[1]);

								AttributeModifier.Operation operation = AttributeModifier.Operation.ADD_NUMBER;
								if (attributeArgs.length >= 3) operation = AttributeUtil.getOperation(attributeArgs[2]);

								EquipmentSlot slot = null;
								if (attributeArgs.length >= 4) {
									try {
										slot = EquipmentSlot.valueOf(attributeArgs[3].toUpperCase());
									} catch (Exception ignored) {}
								}

								EquipmentSlotGroup slotGroup = slot == null ? EquipmentSlotGroup.ANY : slot.getGroup();
								NamespacedKey modifierKey = new NamespacedKey("magicspells",
									AttributeSpecParser.stableKeyPath(attributeArgs[0], itemAttributes.size()));
								AttributeModifier modifier = new AttributeModifier(modifierKey, val, operation, slotGroup);
								itemAttributes.put(attribute, modifier);
							}

							if (!itemAttributes.isEmpty()) data.setAttribute(ATTRIBUTES, itemAttributes);
							break;
						case "lore":
							if (!value.isJsonArray()) continue;

							List<Component> lore = new ArrayList<>();
							JsonArray jsonArray = value.getAsJsonArray();
							for (JsonElement elementInside : jsonArray) {
								lore.add(ItemParseUtil.getMiniMessage(elementInside.getAsString()));
							}

							if (!lore.isEmpty()) data.setAttribute(LORE, lore);
							break;
						case "pages":
							if (!value.isJsonArray()) continue;

							List<Component> pages = new ArrayList<>();
							JsonArray pageArray = value.getAsJsonArray();
							for (JsonElement page : pageArray) {
								pages.add(ItemParseUtil.getMiniMessage(page.getAsString()));
							}

							if (!pages.isEmpty()) data.setAttribute(PAGES, pages);
							break;
						case "patterns":
							if (!value.isJsonArray()) continue;

							List<Pattern> patterns = new ArrayList<>();
							JsonArray patternStrings = value.getAsJsonArray();
							for (JsonElement element : patternStrings) {
								String patternString = element.getAsString();
								String[] pattern = patternString.split(" ");

								if (pattern.length == 2) {
									PatternType patternType = null;
									DyeColor dyeColor;

									NamespacedKey namespacedKey = NamespacedKey.fromString(pattern[0]);
									if (namespacedKey != null) patternType = RegistryAccess.registryAccess().getRegistry(RegistryKey.BANNER_PATTERN).get(namespacedKey);
									if (patternType == null) {
										SneakyItemsPlatform.error("'" + patternString + "' could not be connected to a pattern.");
										continue;
									}

									try {
										dyeColor = DyeColor.valueOf(pattern[1].toUpperCase());
									} catch (IllegalArgumentException e) {
										DebugHandler.debugBadEnumValue(DyeColor.class, pattern[1]);
										SneakyItemsPlatform.error("'" + patternString + "' could not be connected to a pattern.");
										continue;
									}

									patterns.add(new Pattern(dyeColor, patternType));
								} else SneakyItemsPlatform.error("'" + patternString + "' could not be connected to a pattern.");
							}

							if (!patterns.isEmpty()) data.setAttribute(PATTERNS, patterns);
							break;
						case "potioneffects":
						case "potion-effects":
						case "potion_effects":
							if (!value.isJsonArray()) continue;

							List<PotionEffect> potionEffects = new ArrayList<>();
							JsonArray potionEffectStrings = value.getAsJsonArray();

							for (JsonElement element : potionEffectStrings) {
								String potionEffectString = element.getAsString();
								PotionEffect eff = ItemParseUtil.buildPotionEffect(potionEffectString);

								if (eff != null) potionEffects.add(eff);
								else SneakyItemsPlatform.error("'" + potionEffectString + "' could not be connected to a potion effect.");
							}

							if (!potionEffects.isEmpty()) data.setAttribute(POTION_EFFECTS, potionEffects);
							break;
						case "fireworkeffects":
						case "firework-effects":
						case "firework_effects":
							if (!value.isJsonArray()) continue;

							List<FireworkEffect> fireworkEffects = new ArrayList<>();
							JsonArray fireworkEffectStrings = value.getAsJsonArray();
							for (JsonElement eff : fireworkEffectStrings) {
								String[] effString = eff.getAsString().split(" ");

								if (effString.length == 4 || effString.length == 5) {
									try {
										FireworkEffect.Type fireworkType = FireworkEffect.Type.valueOf(effString[0].toUpperCase());
										boolean trail = Boolean.parseBoolean(effString[1]);
										boolean flicker = Boolean.parseBoolean(effString[2]);
										Color[] colors = ItemParseUtil.getColorsFromString(effString[3]);
										Color[] fadeColors = null;

										if (effString.length > 4) fadeColors = ItemParseUtil.getColorsFromString(effString[4]);
										if (fadeColors == null) fadeColors = new Color[0];

										FireworkEffect effect = FireworkEffect.builder()
											.flicker(flicker)
											.trail(trail)
											.with(fireworkType)
											.withColor(colors)
											.withFade(fadeColors)
											.build();

										fireworkEffects.add(effect);
									} catch (IllegalArgumentException e) {
										DebugHandler.debugBadEnumValue(FireworkEffect.Type.class, effString[0].toUpperCase());
										SneakyItemsPlatform.error("'" + eff.getAsString() + "' could not be connected to a firework effect.");
									}
								} else SneakyItemsPlatform.error("'" + eff.getAsString() + "' could not be connected to a firework effect.");
							}

							if (!fireworkEffects.isEmpty()) data.setAttribute(FIREWORK_EFFECTS, fireworkEffects);
							break;
						case "pdc":
						case "persistent-data":
						case "persistent_data":
							Map<String, String> pdcMap = new HashMap<>();
							if (value.isJsonArray()) {
								for (JsonElement element : value.getAsJsonArray()) {
									String[] split = element.getAsString().split(":", 2);
									if (split.length == 2) pdcMap.put(split[0], split[1]);
								}
							} else if (value.isJsonObject()) {
								for (Map.Entry<String, JsonElement> pdcEntry : value.getAsJsonObject().entrySet()) {
									pdcMap.put(pdcEntry.getKey(), pdcEntry.getValue().getAsString());
								}
							}

							if (!pdcMap.isEmpty()) data.setAttribute(PERSISTENT_DATA, pdcMap);
							break;
						case "pdc-permanent":
						case "persistent-data-permanent":
						case "persistent_data-permanent":
						case "pdc_permanent":
						case "permanent-data":
						case "permanent_data":
							Map<String, String> permanentPdcMap = new HashMap<>();
							if (value.isJsonArray()) {
								for (JsonElement element : value.getAsJsonArray()) {
									String[] split = element.getAsString().split(":", 2);
									if (split.length == 2) permanentPdcMap.put(split[0], split[1]);
								}
							} else if (value.isJsonObject()) {
								for (Map.Entry<String, JsonElement> permanentEntry : value.getAsJsonObject().entrySet()) {
									permanentPdcMap.put(permanentEntry.getKey(), permanentEntry.getValue().getAsString());
								}
							}

							if (!permanentPdcMap.isEmpty()) data.setAttribute(PERMANENT_DATA, permanentPdcMap);
							break;
						case "tags":
							java.util.LinkedHashSet<String> tags = new java.util.LinkedHashSet<>();
							if (value.isJsonArray()) {
								for (JsonElement element : value.getAsJsonArray()) {
									String tag = element.getAsString();
									if (tag != null && !tag.isBlank()) tags.add(tag.trim());
								}
							} else if (value.isJsonPrimitive()) {
								tags.addAll(MagicItemTags.deserialize(value.getAsString()));
							}
							if (!tags.isEmpty()) data.setAttribute(TAGS, java.util.Collections.unmodifiableSet(tags));
							break;
						case "ignoredattributes":
						case "ignored-attributes":
						case "ignored_attributes":
							if (!value.isJsonArray()) continue;
							EnumSet<MagicItemAttribute> ignoredAttributes = data.getIgnoredAttributes();
							JsonArray ignoredAttributeStrings = value.getAsJsonArray();

							for (JsonElement element : ignoredAttributeStrings) {
								String ignoredAttribute = element.getAsString().toUpperCase();
								try {
									ignoredAttributes.add(MagicItemAttribute.valueOf(ignoredAttribute));
								} catch (IllegalArgumentException e) {
									DebugHandler.debugBadEnumValue(MagicItemAttribute.class, ignoredAttribute);
								}
							}
							break;
						case "blacklistedattributes":
						case "blacklisted-attributes":
						case "blacklisted_attributes":
							if (!value.isJsonArray()) continue;
							EnumSet<MagicItemAttribute> blacklistedAttributes = data.getBlacklistedAttributes();
							JsonArray blacklistedAttributeStrings = value.getAsJsonArray();

							for (JsonElement element : blacklistedAttributeStrings) {
								String blacklistedAttribute = element.getAsString().toUpperCase();
								try {
									blacklistedAttributes.add(MagicItemAttribute.valueOf(blacklistedAttribute));
								} catch (IllegalArgumentException e) {
									DebugHandler.debugBadEnumValue(MagicItemAttribute.class, blacklistedAttribute);
								}
							}
							break;
						case "strictenchants":
						case "strict-enchants":
						case "strict_enchants":
							data.setStrictEnchants(value.getAsBoolean());
							break;
						case "strictdurability":
						case "strict-durability":
						case "strict_durability":
							data.setStrictDurability(value.getAsBoolean());
							break;
						case "strictblockdata":
						case "strict-block-data":
						case "strict_block_data":
							data.setStrictBlockData(value.getAsBoolean());
							break;
						case "strictenchantlevel":
						case "strict-enchant-level":
						case "strict_enchant_level":
							data.setStrictEnchantLevel(value.getAsBoolean());
							break;
					}
				}

			}

		} catch (IOException e) {
			e.printStackTrace();
		}

		return data;
	}

}
