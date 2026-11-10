package com.sneakyitems.util.magicitems;

import com.sneakyitems.util.performance.PerformanceDiagnostics;

import java.util.*;

import net.kyori.adventure.text.Component;

import com.google.common.collect.Multimap;
import com.google.common.collect.HashMultimap;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.attribute.Attribute;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import com.sneakyitems.util.ItemParseUtil;
import com.sneakyitems.util.DataUtil;
import com.sneakyitems.platform.SneakyItemsPlatform;
import com.sneakyitems.util.ItemUtil;
import com.sneakyitems.util.itemreader.*;
import com.sneakyutils.util.AttributeSpecParser;
import com.sneakyutils.util.AttributeUtil;
import com.sneakyitems.handlers.DebugHandler;
import com.sneakyitems.handlers.EnchantmentHandler;
import com.sneakyitems.util.managers.ItemAttributeHelper;
import com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute;
import com.sneakyitems.util.itemreader.alternative.AlternativeReaderManager;
import static com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute.*;

public class MagicItems {

	private static final Map<String, MagicItem> magicItems = new HashMap<>();
	/** Cap on cached stacks; without it the cache keeps every stack ever seen for the server's lifetime. */
	private static final int ITEM_STACK_CACHE_MAX = 20_000;
	private static final Map<ItemStack, MagicItemData> itemStackCache = new java.util.LinkedHashMap<>(1024, 0.75f, false) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<ItemStack, MagicItemData> eldest) {
			return size() > ITEM_STACK_CACHE_MAX;
		}
	};

	public static void clearItemStackCache() {
		itemStackCache.clear();
	}

	/** Entries in the item-data cache (a level for diagnostics). */
	public static int itemStackCacheSize() {
		return itemStackCache.size();
	}

	public static Map<String, MagicItem> getMagicItems() {
		return magicItems;
	}

	public static Collection<String> getMagicItemKeys() {
		return magicItems.keySet();
	}

	public static Collection<MagicItem> getMagicItemValues() {
		return magicItems.values();
	}

	public static MagicItem getMagicItemByInternalName(String internalName) {
		if (!magicItems.containsKey(internalName)) return null;
		if (magicItems.get(internalName) == null) return null;
		return magicItems.get(internalName);
	}

	public static ItemStack getItemByInternalName(String internalName) {
		if (!magicItems.containsKey(internalName)) return null;
		if (magicItems.get(internalName) == null) return null;
		if (magicItems.get(internalName).getItemStack() == null) return null;
		return magicItems.get(internalName).getItemStack().clone();
	}

	public static ItemStack getItemStackForPlayer(String spec, Player player, int amount) {
		MagicItem magicItem = getMagicItemFromString(spec);
		if (magicItem == null) return null;
		return magicItem.createFor(player, amount);
	}

	public static MagicItemData getMagicItemDataByInternalName(String internalName) {
		if (!magicItems.containsKey(internalName)) return null;
		if (magicItems.get(internalName) == null) return null;
		return magicItems.get(internalName).getMagicItemData();
	}

	public static boolean matches(MagicItemData pattern, ItemStack stack) {
		return matches(pattern, stack, true);
	}

	public static boolean matches(MagicItemData pattern, ItemStack stack, boolean pdcIdentityMatch) {
		PerformanceDiagnostics.count("match.calls");
		if (pattern == null || stack == null)
			return false;

		if (pdcIdentityMatch && pattern.hasAttribute(MAGIC_ITEM_NAME)) {
			String patternName = (String) pattern.getAttribute(MAGIC_ITEM_NAME);
			String stackName = MagicItemBehaviorKeys.getMagicItemName(stack);
			if (patternName != null && patternName.equals(stackName))
				return true;
		}

		MagicItemData stackData = getMagicItemDataFromItemStack(stack);
		if (stackData == null)
			return false;

		EnumSet<MagicItemAttribute> stackIgnored = MagicItemIgnoredAttributes.fromItemStack(stack);
		if (DataUtil.getString(stack, "transmogrified") != null)
			stackIgnored.add(MagicItemAttribute.ITEM_MODEL);

		return pattern.matches(stackData, stackIgnored);
	}

	/**
	 * A stack's match inputs, read once. Matching one stack against many patterns (legacy tagging checks ~1.5k)
	 * would otherwise re-hash the whole stack, lore included, and re-read its PDC for every pattern.
	 */
	public record PreparedStack(String magicItemName, MagicItemData data, EnumSet<MagicItemAttribute> ignored) {
	}

	/** Reads the stack's name, data and ignored attributes once; null when the stack has no data. */
	public static PreparedStack prepare(ItemStack stack) {
		if (stack == null)
			return null;
		MagicItemData stackData = getMagicItemDataFromItemStack(stack);
		if (stackData == null)
			return null;

		EnumSet<MagicItemAttribute> stackIgnored = MagicItemIgnoredAttributes.fromItemStack(stack);
		if (DataUtil.getString(stack, "transmogrified") != null)
			stackIgnored.add(MagicItemAttribute.ITEM_MODEL);

		return new PreparedStack(MagicItemBehaviorKeys.getMagicItemName(stack), stackData, stackIgnored);
	}

	/** Same result as {@link #matches(MagicItemData, ItemStack, boolean)} with a stack prepared by {@link #prepare}. */
	public static boolean matches(MagicItemData pattern, PreparedStack prepared, boolean pdcIdentityMatch) {
		PerformanceDiagnostics.count("match.calls");
		if (pattern == null || prepared == null)
			return false;

		if (pdcIdentityMatch && pattern.hasAttribute(MAGIC_ITEM_NAME)) {
			String patternName = (String) pattern.getAttribute(MAGIC_ITEM_NAME);
			if (patternName != null && patternName.equals(prepared.magicItemName()))
				return true;
		}

		return pattern.matches(prepared.data(), EnumSet.copyOf(prepared.ignored()));
	}

	public static MagicItemData getMagicItemDataFromItemStack(ItemStack itemStack) {
		if (itemStack == null) return null;

		// The lookup hashes the stack (item and data components) and compares equal keys. It is counted, not timed:
		// legacy tagging runs it once per mapping (~1.5k per item), so a per-call timer would add ~40 ns each and
		// inflate the very path it measures. Its cost is in the enclosing item.tag/item.update self time.
		MagicItemData cached = itemStackCache.get(itemStack);
		// We can do this because itemStackCache doesn't have any null values
		if (cached != null) {
			PerformanceDiagnostics.count("item-data.cache-hits");
			return cached;
		}
		PerformanceDiagnostics.count("item-data.cache-misses");
		long extract = PerformanceDiagnostics.start();
		try {
			return extractMagicItemData(itemStack);
		} finally {
			PerformanceDiagnostics.end("item-data.extract", extract);
		}
	}

	/** Reads every supported attribute of the stack's meta and caches the result (a cache miss). */
	private static MagicItemData extractMagicItemData(ItemStack itemStack) {
		MagicItemData data = new MagicItemData();

		// type
		data.setAttribute(TYPE, itemStack.getType());

		if (itemStack.getType().isAir()) {
			itemStackCache.put(itemStack, data);
			return data;
		}

		// amount
		data.setAttribute(AMOUNT, itemStack.getAmount());

		ItemMeta meta = itemStack.getItemMeta();
		if (meta == null) {
			itemStackCache.put(itemStack, data);
			return data;
		}

		// name
		NameHandler.processMagicItemData(meta, data);

		// durability
		if (ItemUtil.hasDurability(itemStack.getType())) DurabilityHandler.processMagicItemData(meta, data);

		// repairCost
		RepairableHandler.processMagicItemData(meta, data);

		// customModelData
		CustomModelDataHandler.processMagicItemData(meta, data);

		// maxStackSize
		MaxStackSizeHandler.processMagicItemData(meta, data);

		// Modern data components
		DataComponentsHandler.processMagicItemData(meta, data);

		// power, fireworkEffects
		FireworkHandler.processMagicItemData(meta, data);

		// unbreakable
		data.setAttribute(UNBREAKABLE, meta.isUnbreakable());

		// tooltip
		boolean tooltip = true;
		for (ItemFlag itemFlag : ItemFlag.values()) {
			if (!meta.getItemFlags().contains(itemFlag)) tooltip = false;
		}
		data.setAttribute(HIDE_TOOLTIP, tooltip);

		data.setAttribute(INVISIBLE_TOOLTIP, meta.isHideTooltip());

		// color
		LeatherArmorHandler.processMagicItemData(meta, data);

		// potion, potionEffects, potionColor
		PotionHandler.processMagicItemData(meta, data);

		// suspiciousStew
		SuspiciousStewHandler.processMagicItemData(meta, data);

		// fireworkEffect
		FireworkEffectHandler.processMagicItemData(meta, data);

		// skullOwner
		SkullHandler.processMagicItemData(meta, data);

		// author, title, pages
		WrittenBookHandler.processMagicItemData(meta, data);

		// enchantments
		Map<Enchantment, Integer> enchants = new HashMap<>(meta.getEnchants());
		if (ItemUtil.hasFakeEnchantment(meta)) {
			enchants.remove(Enchantment.FROST_WALKER);

			data.setAttribute(FAKE_GLINT, true);
		}
		if (!enchants.isEmpty()) data.setAttribute(ENCHANTS, enchants);

		// attributes
		if (meta.hasAttributeModifiers()) {
			Multimap<Attribute, AttributeModifier> modifiers = meta.getAttributeModifiers();
			if (modifiers != null && !modifiers.isEmpty()) data.setAttribute(ATTRIBUTES, meta.getAttributeModifiers());
		}

		// lore
		if (meta.hasLore()) {
			List<Component> lore = meta.lore();
			if (lore != null && !lore.isEmpty()) data.setAttribute(LORE, lore);
		}

		// patterns
		BannerHandler.processMagicItemData(meta, data);

		// block data
		BlockDataHandler.processMagicItemData(meta, data, itemStack.getType());

		// PDC
		PersistentDataHandler.processMagicItemData(meta, data);

		itemStackCache.put(itemStack, data);
		return data;
	}

	public static MagicItemData getMagicItemDataFromString(String str) {
		if (str == null) return null;
		if (magicItems.containsKey(str)) return magicItems.get(str).getMagicItemData();

		return MagicItemDataParser.parseMagicItemData(str);
	}

	public static MagicItem getMagicItemFromString(String str) {
		if (str == null) return null;
		if (magicItems.containsKey(str)) return magicItems.get(str);

		MagicItemData itemData = MagicItemDataParser.parseMagicItemData(str);
		if (itemData == null) return null;

		MagicItem magicItem = getMagicItemFromData(itemData);
		if (magicItem != null)
			MagicItemDefinitionVersions.applyToMagicItem(magicItem,
					MagicItemDefinitionVersions.fingerprintString(str));
		return magicItem;
	}

	public static MagicItem getMagicItemFromData(MagicItemData data) {
		if (data == null) return null;

		Material type = (Material) data.getAttribute(TYPE);
		if (type == null) return null;

		ItemStack item = new ItemStack(type);

		if (type.isAir()) return new MagicItem(item, data);

		if (data.hasAttribute(AMOUNT)) {
			int amount = (int) data.getAttribute(AMOUNT);
			if (amount >= 1) item.setAmount(amount);
		}

		ItemMeta meta = item.getItemMeta();
		if (meta == null) return new MagicItem(item, data);

		// Name
		NameHandler.processItemMeta(meta, data);

		// Lore
		LoreHandler.processItemMeta(meta, data);

		// Custom Model Data
		CustomModelDataHandler.processItemMeta(meta, data);

		// Max Stack Size
		MaxStackSizeHandler.processItemMeta(meta, data);

		// Modern data components
		DataComponentsHandler.processItemMeta(meta, data);

		// Enchantments
		if (data.hasAttribute(ENCHANTS)) {
			Map<Enchantment, Integer> enchantments = data.getAttribute(ENCHANTS);
			for (Enchantment enchant : enchantments.keySet()) {
				int level = enchantments.get(enchant);

				if (meta instanceof EnchantmentStorageMeta) ((EnchantmentStorageMeta) meta).addStoredEnchant(enchant, level, true);
				else meta.addEnchant(enchant, level, true);
			}


		}

		if (data.hasAttribute(FAKE_GLINT)) {
			boolean fakeGlint = (boolean) data.getAttribute(FAKE_GLINT);

			if (fakeGlint && !meta.hasEnchants()) {
				ItemUtil.addFakeEnchantment(meta);
			}
		}

		// Armor color
		LeatherArmorHandler.processItemMeta(meta, data);

		// Potion effects and potion color
		PotionHandler.processItemMeta(meta, data);

		// Skull owner
		SkullHandler.processItemMeta(meta, data);

		// Durability
		if (ItemUtil.hasDurability(type)) DurabilityHandler.processItemMeta(meta, data);

		// Repair cost
		RepairableHandler.processItemMeta(meta, data);

		// Written book
		WrittenBookHandler.processItemMeta(meta, data);

		// Banner
		BannerHandler.processItemMeta(meta, data);

		// Firework Star
		FireworkEffectHandler.processItemMeta(meta, data);

		// Firework
		FireworkHandler.processItemMeta(meta, data);

		// Suspicious Stew
		SuspiciousStewHandler.processItemMeta(meta, data);

		// Block Data
		BlockDataHandler.processItemMeta(meta, data);

		// PDC
		PersistentDataHandler.processItemMeta(meta, data);

		// Unbreakable
		if (data.hasAttribute(UNBREAKABLE))
			meta.setUnbreakable((boolean) data.getAttribute(UNBREAKABLE));

		// Hide tooltip
		if (data.hasAttribute(HIDE_TOOLTIP) && (boolean) data.getAttribute(HIDE_TOOLTIP))
			meta.addItemFlags(ItemFlag.values());


		// Invisible tooltip
		if (data.hasAttribute(INVISIBLE_TOOLTIP) && (boolean) data.getAttribute(INVISIBLE_TOOLTIP))
			meta.setHideTooltip(true);

		// Set meta
		item.setItemMeta(meta);

		// Raw components string (Minecraft give-argument syntax)
		DataComponentsHandler.applyComponentsString(item, data);

		// Attributes
		ItemAttributeHelper attributeManager = ItemAttributeHelper.getInstance();
		Multimap<Attribute, AttributeModifier> attributes = data.getAttribute(ATTRIBUTES);
		if (attributes != null) {
			for (Attribute attribute : attributes.keySet()) {
				Collection<AttributeModifier> attributeModifiers = attributes.get(attribute);
				for (AttributeModifier modifier : attributeModifiers) {
					attributeManager.addItemAttribute(item, attribute, modifier);
				}
			}
		}

		return new MagicItem(item, data);
	}

	/**
	 * Applies or clears a single {@link MagicItemAttribute} on an item during updater rebuilds.
	 *
	 * @param item  the item being updated
	 * @param meta  current meta of the item (will be modified)
	 * @param source source data to copy from when not clearing; may be null when clearing
	 * @param attr  the attribute to apply or clear
	 * @param clear when true, remove the attribute from the item; otherwise copy from source
	 */
	public static void applyMagicItemAttribute(ItemStack item, ItemMeta meta, MagicItemData source, MagicItemAttribute attr, boolean clear) {
		if (item == null || meta == null || attr == null) return;

		if (clear) {
			clearMagicItemAttribute(item, meta, attr);
			return;
		}

		if (source == null || !source.hasAttribute(attr)) return;

		MagicItemData partial = new MagicItemData();
		partial.setAttribute(attr, source.getAttribute(attr));

		switch (attr) {
			case NAME, ITEM_NAME -> NameHandler.processItemMeta(meta, partial);
			case LORE -> LoreHandler.processItemMeta(meta, partial);
			case DURABILITY -> {
				if (ItemUtil.hasDurability(item.getType())) DurabilityHandler.processItemMeta(meta, partial);
			}
			case REPAIR_COST -> RepairableHandler.processItemMeta(meta, partial);
			case CUSTOM_MODEL_DATA -> CustomModelDataHandler.processItemMeta(meta, partial);
			case MAX_STACK_SIZE -> MaxStackSizeHandler.processItemMeta(meta, partial);
			case ITEM_MODEL, TOOLTIP_STYLE, RARITY, ENCHANTABLE, GLIDER, MAX_DAMAGE, FOOD, USE_COOLDOWN, EQUIPPABLE, JUKEBOX_PLAYABLE ->
				DataComponentsHandler.processItemMeta(meta, partial);
			case COMPONENTS -> DataComponentsHandler.applyComponentsString(item, partial);
			case POWER, UNBREAKABLE, HIDE_TOOLTIP, INVISIBLE_TOOLTIP -> applySimpleMetaAttribute(meta, partial, attr);
			case FAKE_GLINT -> {
				if ((boolean) partial.getAttribute(FAKE_GLINT) && !meta.hasEnchants()) ItemUtil.addFakeEnchantment(meta);
			}
			case COLOR -> LeatherArmorHandler.processItemMeta(meta, partial);
			case POTION_DATA -> PotionHandler.processItemMeta(meta, partial);
			case POTION_EFFECTS -> {
				PotionHandler.processItemMeta(meta, partial);
				SuspiciousStewHandler.processItemMeta(meta, partial);
			}
			case FIREWORK_EFFECT, FIREWORK_EFFECTS -> {
				FireworkEffectHandler.processItemMeta(meta, partial);
				FireworkHandler.processItemMeta(meta, partial);
			}
			case TITLE, AUTHOR, PAGES -> WrittenBookHandler.processItemMeta(meta, partial);
			case SKULL_OWNER, UUID, TEXTURE, SIGNATURE -> SkullHandler.processItemMeta(meta, partial);
			case BLOCK_DATA -> BlockDataHandler.processItemMeta(meta, partial);
			case PATTERNS -> BannerHandler.processItemMeta(meta, partial);
			case ENCHANTS -> applyEnchants(meta, partial.getAttribute(ENCHANTS));
			case ATTRIBUTES -> applyBukkitAttributes(item, meta, partial.getAttribute(ATTRIBUTES));
			case PERMANENT_DATA, PERSISTENT_DATA, TAGS -> PersistentDataHandler.processItemMeta(meta, partial);
			default -> {}
		}
	}

	private static void applySimpleMetaAttribute(ItemMeta meta, MagicItemData data, MagicItemAttribute attr) {
		switch (attr) {
			case UNBREAKABLE -> meta.setUnbreakable((boolean) data.getAttribute(UNBREAKABLE));
			case HIDE_TOOLTIP -> {
				if ((boolean) data.getAttribute(HIDE_TOOLTIP)) meta.addItemFlags(ItemFlag.values());
			}
			case INVISIBLE_TOOLTIP -> meta.setHideTooltip((boolean) data.getAttribute(INVISIBLE_TOOLTIP));
			case POWER -> {
				if (meta instanceof org.bukkit.inventory.meta.FireworkMeta fireworkMeta) {
					fireworkMeta.setPower((int) data.getAttribute(POWER));
				}
			}
			default -> {}
		}
	}

	private static void applyEnchants(ItemMeta meta, Map<Enchantment, Integer> enchantments) {
		if (enchantments == null || enchantments.isEmpty()) return;

		clearEnchants(meta);
		if (meta instanceof EnchantmentStorageMeta storageMeta) {
			for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
				storageMeta.addStoredEnchant(entry.getKey(), entry.getValue(), true);
			}
		} else {
			for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
				meta.addEnchant(entry.getKey(), entry.getValue(), true);
			}
		}
	}

	private static void applyBukkitAttributes(ItemStack item, ItemMeta meta, Multimap<Attribute, AttributeModifier> attributes) {
		clearBukkitAttributes(meta);

		if (attributes == null || attributes.isEmpty()) return;

		ItemAttributeHelper attributeManager = ItemAttributeHelper.getInstance();
		for (Attribute attribute : attributes.keySet()) {
			for (AttributeModifier modifier : attributes.get(attribute)) {
				attributeManager.addMetaAttribute(meta, attribute, modifier);
			}
		}
		item.setItemMeta(meta);
	}

	private static void clearBukkitAttributes(ItemMeta meta) {
		if (!meta.hasAttributeModifiers()) return;

		Multimap<Attribute, AttributeModifier> modifiers = meta.getAttributeModifiers();
		if (modifiers == null) return;

		for (Attribute attribute : new HashSet<>(modifiers.keySet())) {
			meta.removeAttributeModifier(attribute);
		}
	}

	private static void clearMagicItemAttribute(ItemStack item, ItemMeta meta, MagicItemAttribute attr) {
		switch (attr) {
			case NAME -> meta.displayName(null);
			case ITEM_NAME -> meta.itemName(null);
			case LORE -> meta.lore(null);
			case DURABILITY -> {
				if (meta instanceof org.bukkit.inventory.meta.Damageable damageable) damageable.setDamage(0);
			}
			case REPAIR_COST -> {
				if (meta instanceof org.bukkit.inventory.meta.Repairable repairable) repairable.setRepairCost(0);
			}
			case CUSTOM_MODEL_DATA -> {
				org.bukkit.inventory.meta.components.CustomModelDataComponent component = meta.getCustomModelDataComponent();
				component.setFloats(new ArrayList<>());
				component.setStrings(new ArrayList<>());
				component.setFlags(new ArrayList<>());
				component.setColors(new ArrayList<>());
				meta.setCustomModelDataComponent(component);
			}
			case MAX_STACK_SIZE -> meta.setMaxStackSize(null);
			case ITEM_MODEL -> meta.setItemModel(null);
			case TOOLTIP_STYLE -> meta.setTooltipStyle(null);
			case RARITY -> meta.setRarity(null);
			case ENCHANTABLE -> meta.setEnchantable(null);
			case GLIDER -> meta.setGlider(false);
			case MAX_DAMAGE -> {
				if (meta instanceof org.bukkit.inventory.meta.Damageable damageable) damageable.setMaxDamage(null);
			}
			case FOOD -> meta.setFood(null);
			case USE_COOLDOWN -> meta.setUseCooldown(null);
			case EQUIPPABLE -> meta.setEquippable(null);
			case JUKEBOX_PLAYABLE -> meta.setJukeboxPlayable(null);
			case UNBREAKABLE -> meta.setUnbreakable(false);
			case HIDE_TOOLTIP -> meta.removeItemFlags(ItemFlag.values());
			case INVISIBLE_TOOLTIP -> meta.setHideTooltip(false);
			case FAKE_GLINT -> meta.setEnchantmentGlintOverride(null);
			case COLOR -> {
				if (meta instanceof org.bukkit.inventory.meta.LeatherArmorMeta armorMeta) {
					armorMeta.setColor(org.bukkit.Color.fromRGB(0xA0, 0x65, 0x40));
				} else if (meta instanceof org.bukkit.inventory.meta.PotionMeta potionMeta) {
					potionMeta.setColor(null);
				}
			}
			case POTION_DATA -> {
				if (meta instanceof org.bukkit.inventory.meta.PotionMeta potionMeta) {
					potionMeta.setBasePotionType(org.bukkit.potion.PotionType.WATER);
				}
			}
			case POTION_EFFECTS -> {
				if (meta instanceof org.bukkit.inventory.meta.SuspiciousStewMeta stewMeta) stewMeta.clearCustomEffects();
				else if (meta instanceof org.bukkit.inventory.meta.PotionMeta potionMeta) potionMeta.clearCustomEffects();
			}
			case FIREWORK_EFFECT, FIREWORK_EFFECTS -> {
				if (meta instanceof org.bukkit.inventory.meta.FireworkEffectMeta effectMeta) {
					effectMeta.setEffect(null);
				} else if (meta instanceof org.bukkit.inventory.meta.FireworkMeta fireworkMeta) {
					fireworkMeta.clearEffects();
				}
			}
			case TITLE, AUTHOR, PAGES -> {
				if (meta instanceof org.bukkit.inventory.meta.BookMeta bookMeta) {
					switch (attr) {
						case TITLE -> bookMeta.setTitle(null);
						case AUTHOR -> bookMeta.setAuthor(null);
						case PAGES -> bookMeta.pages(new ArrayList<>());
						default -> {}
					}
				}
			}
			case SKULL_OWNER, UUID, TEXTURE, SIGNATURE -> {
				if (meta instanceof org.bukkit.inventory.meta.SkullMeta skullMeta) skullMeta.setPlayerProfile(null);
			}
			case BLOCK_DATA -> {
				if (meta instanceof org.bukkit.inventory.meta.BlockDataMeta blockDataMeta) {
					blockDataMeta.setBlockData(Bukkit.createBlockData(item.getType()));
				}
			}
			case PATTERNS -> {
				if (meta instanceof org.bukkit.inventory.meta.BannerMeta bannerMeta) bannerMeta.setPatterns(new ArrayList<>());
			}
			case ENCHANTS -> clearEnchants(meta);
			case ATTRIBUTES -> clearBukkitAttributes(meta);
			case PERMANENT_DATA -> clearPermanentData(meta);
			default -> {}
		}
	}

	private static void clearEnchants(ItemMeta meta) {
		if (meta instanceof EnchantmentStorageMeta storageMeta) {
			for (Enchantment enchant : new HashSet<>(storageMeta.getStoredEnchants().keySet())) {
				storageMeta.removeStoredEnchant(enchant);
			}
		} else {
			for (Enchantment enchant : new HashSet<>(meta.getEnchants().keySet())) {
				meta.removeEnchant(enchant);
			}
		}
		if (ItemUtil.hasFakeEnchantment(meta)) meta.setEnchantmentGlintOverride(null);
	}

	private static void clearPermanentData(ItemMeta meta) {
		String namespace = MagicItemBehaviorKeys.getPdcNamespace();
		String permanentPrefix = "magicspellpermanentdata_";
		for (NamespacedKey key : new HashSet<>(meta.getPersistentDataContainer().getKeys())) {
			if (key.getNamespace().equals(namespace) && key.getKey().startsWith(permanentPrefix)) {
				meta.getPersistentDataContainer().remove(key);
			}
		}
	}

	public static MagicItem getMagicItemFromSection(ConfigurationSection section) {
		try {
			// It MUST have a type option
			if (!section.contains("type")) return null;

			long definitionVersion = MagicItemDefinitionVersions.fingerprintSection(section);

			// See if this is managed by an alternative reader
			ItemStack item = AlternativeReaderManager.deserialize(section);
			if (item != null) {
				MagicItemData itemData = getMagicItemDataFromItemStack(item);
				ItemMeta meta = item.getItemMeta();
				if (meta != null) {
					PersistentDataHandler.process(section, meta, itemData);
					item.setItemMeta(meta);
				}
				MagicItem magicItem = new MagicItem(item, itemData);

				if (section.isList("ignored-attributes")) {
					EnumSet<MagicItemAttribute> ignoredAttributes = magicItem.getMagicItemData().getIgnoredAttributes();
					List<String> ignoredAttributeStrings = section.getStringList("ignored-attributes");

					for (String attr : ignoredAttributeStrings) {
						try {
							ignoredAttributes.add(MagicItemAttribute.valueOf(attr.toUpperCase().replace("-", "_")));
						} catch (IllegalArgumentException e) {
							DebugHandler.debugBadEnumValue(MagicItemAttribute.class, attr);
						}
					}
				}

				if (section.isList("blacklisted-attributes")) {
					EnumSet<MagicItemAttribute> blacklistedAttributes = magicItem.getMagicItemData().getBlacklistedAttributes();
					List<String> blacklistedAttributeStrings = section.getStringList("blacklisted-attributes");

					for (String attr : blacklistedAttributeStrings) {
						try {
							blacklistedAttributes.add(MagicItemAttribute.valueOf(attr.toUpperCase().replace("-", "_")));
						} catch (IllegalArgumentException e) {
							DebugHandler.debugBadEnumValue(MagicItemAttribute.class, attr);
						}
					}
				}

				if (section.isBoolean("strict-enchants"))
					magicItem.getMagicItemData().setStrictEnchants(section.getBoolean("strict-enchants"));

				if (section.isBoolean("strict-block-data"))
					magicItem.getMagicItemData().setStrictBlockData(section.getBoolean("strict-block-data"));

				if (section.isBoolean("strict-durability"))
					magicItem.getMagicItemData().setStrictDurability(section.getBoolean("strict-durability"));

				if (section.isBoolean("strict-enchant-level"))
					magicItem.getMagicItemData().setStrictEnchantLevel(section.getBoolean("strict-enchant-level"));

				MagicItemDefinitionVersions.applyToMagicItem(magicItem, definitionVersion);
				return magicItem;
			}

			MagicItemData itemData = new MagicItemData();

			String typeString = section.getString("type");
			Material type = ItemParseUtil.getMaterial(typeString);
			if (type == null) {
				DebugHandler.debugBadEnumValue(Material.class, typeString);
				return null;
			}

			item = new ItemStack(type);
			itemData.setAttribute(TYPE, type);

			if (type.isAir()) {
				MagicItem airItem = new MagicItem(item, itemData);
				MagicItemDefinitionVersions.applyToMagicItem(airItem, definitionVersion);
				return airItem;
			}

			if (section.isInt("amount")) {
				int amount = section.getInt("amount");

				if (amount >= 1) {
					item.setAmount(amount);
					itemData.setAttribute(AMOUNT, amount);
				}
			}

			ItemMeta meta = item.getItemMeta();
			if (meta == null) {
				MagicItem noMetaItem = new MagicItem(item, itemData);
				MagicItemDefinitionVersions.applyToMagicItem(noMetaItem, definitionVersion);
				return noMetaItem;
			}

			// Name
			NameHandler.process(section, meta, itemData);

			// Lore
			LoreHandler.process(section, meta, itemData);

			// CustomModelData
			CustomModelDataHandler.process(section, meta, itemData);

			// MaxStackSize
			MaxStackSizeHandler.process(section, meta, itemData);

			// Modern data components
			DataComponentsHandler.process(section, meta, itemData);

			// Enchants
			// <enchantmentName> <level>
			if (section.isList("enchants")) {
				List<String> enchants = section.getStringList("enchants");
				for (String enchant : enchants) {

					String[] data = enchant.split(" ");
					Enchantment e = EnchantmentHandler.getEnchantment(data[0]);
					if (e == null) {
						SneakyItemsPlatform.error('\'' + data[0] + "' could not be connected to an enchantment");
						continue;
					}

					int level = 0;
					if (data.length > 1) {
						try {
							level = Integer.parseInt(data[1]);
						} catch (NumberFormatException ex) {
							DebugHandler.debugNumberFormat(ex);
						}
					}

					if (meta instanceof EnchantmentStorageMeta) ((EnchantmentStorageMeta) meta).addStoredEnchant(e, level, true);
					else meta.addEnchant(e, level, true);
				}

				if (meta instanceof EnchantmentStorageMeta storageMeta) {

					if (storageMeta.hasStoredEnchants()) {
						Map<Enchantment, Integer> enchantments = storageMeta.getStoredEnchants();
						if (!enchantments.isEmpty()) itemData.setAttribute(ENCHANTS, enchantments);
					}
				} else if (meta.hasEnchants()) {
					Map<Enchantment, Integer> enchantments = meta.getEnchants();
					if (!enchantments.isEmpty()) itemData.setAttribute(ENCHANTS, enchantments);
				}
			}

			if (section.isBoolean("fake-glint")) {
				boolean fakeGlint = section.getBoolean("fake-glint");

				if (fakeGlint && !meta.hasEnchants()) {
					ItemUtil.addFakeEnchantment(meta);
					itemData.setAttribute(FAKE_GLINT, true);
				}
			}

			// Armor color
			LeatherArmorHandler.process(section, meta, itemData);

			// Potion effects, color, type
			PotionHandler.process(section, meta, itemData);

			// Skull owner
			SkullHandler.process(section, meta, itemData);

			// Durability
			if (ItemUtil.hasDurability(type)) DurabilityHandler.process(section, meta, itemData);

			// Repair cost
			RepairableHandler.process(section, meta, itemData);

			// Written book
			WrittenBookHandler.process(section, meta, itemData);

			// Banner
			BannerHandler.process(section, meta, itemData);

			// Firework Star
			FireworkEffectHandler.process(section, meta, itemData);

			// Firework
			FireworkHandler.process(section, meta, itemData);

			// Suspicious Stew
			SuspiciousStewHandler.process(section, meta, itemData);

			// Block Data
			BlockDataHandler.process(section, meta, itemData, type);

			// PDC
			PersistentDataHandler.process(section, meta, itemData);

			// Unbreakable
			if (section.isBoolean("unbreakable")) {
				boolean unbreakable = section.getBoolean("unbreakable");

				meta.setUnbreakable(unbreakable);
				itemData.setAttribute(UNBREAKABLE, unbreakable);
			}

			if (section.isBoolean("soulbound")) {
				itemData.setAttribute(SOULBOUND, section.getBoolean("soulbound"));
			}

			if (section.isDouble("expiration")) {
				double expiration = section.getDouble("expiration");
				if (expiration > 0)
					itemData.setAttribute(EXPIRATION, expiration);
			} else if (section.isInt("expiration")) {
				int expiration = section.getInt("expiration");
				if (expiration > 0)
					itemData.setAttribute(EXPIRATION, (double) expiration);
			}

			if (SneakyItemsPlatform.hideMagicItemTooltips()) {
				meta.addItemFlags(ItemFlag.values());
				itemData.setAttribute(HIDE_TOOLTIP, true);
			} else if (section.isBoolean("hide-tooltip")) {
				boolean hideTooltip = section.getBoolean("hide-tooltip");

				if (hideTooltip) meta.addItemFlags(ItemFlag.values());
				itemData.setAttribute(HIDE_TOOLTIP, hideTooltip);
			}

			if (section.isBoolean("invisible-tooltip")) {
				boolean invisibleTooltip = section.getBoolean("invisible-tooltip");
				meta.setHideTooltip(invisibleTooltip);
				itemData.setAttribute(INVISIBLE_TOOLTIP, invisibleTooltip);
			}

			// Set meta
			item.setItemMeta(meta);

			// Raw components string (Minecraft give-argument syntax)
			DataComponentsHandler.applyComponentsString(item, itemData);

			// Attributes
			//<attribute name> <value> (operation) (slot)
			ItemAttributeHelper attributeManager = ItemAttributeHelper.getInstance();
			if (section.isList("attributes")) {
				List<String> attributes = section.getStringList("attributes");
				Multimap<Attribute, AttributeModifier> itemAttributes = HashMultimap.create();
				for (String str : attributes) {
					String[] args = str.split(" ");
					if (args.length < 2) continue;

					Attribute attribute = AttributeUtil.getAttribute(args[0]);
					if (attribute == null) {
						SneakyItemsPlatform.error("Invalid attribute '" + args[0] + "' on magic item.");
						continue;
					}
					double value = Double.parseDouble(args[1]);

					AttributeModifier.Operation operation = AttributeModifier.Operation.ADD_NUMBER;
					if (args.length >= 3) {
						AttributeModifier.Operation parsed = AttributeUtil.getOperation(args[2]);
						if (parsed != null) operation = parsed;
					}

					EquipmentSlot slot = null;
					if (args.length >= 4) {
						try {
							slot = EquipmentSlot.valueOf(args[3].toUpperCase());
						} catch (Exception ignored) {}
					}

					EquipmentSlotGroup slotGroup = slot == null ? EquipmentSlotGroup.ANY : slot.getGroup();
					NamespacedKey modifierKey = new NamespacedKey("magicspells",
						AttributeSpecParser.stableKeyPath(args[0], itemAttributes.size()));
					AttributeModifier modifier = new AttributeModifier(modifierKey, value, operation, slotGroup);
					attributeManager.addItemAttribute(item, attribute, modifier);
					itemAttributes.put(attribute, modifier);
				}

				if (!itemAttributes.isEmpty()) itemData.setAttribute(ATTRIBUTES, itemAttributes);
			}

			if (section.isList("ignored-attributes")) {
				List<String> ignoredAttributeStrings = section.getStringList("ignored-attributes");
				EnumSet<MagicItemAttribute> ignoredAttributes = itemData.getIgnoredAttributes();

				for (String attr : ignoredAttributeStrings) {
					try {
						ignoredAttributes.add(MagicItemAttribute.valueOf(attr.toUpperCase().replace("-", "_")));
					} catch (IllegalArgumentException e) {
						DebugHandler.debugBadEnumValue(MagicItemAttribute.class, attr);
					}
				}
			}

			if (section.isList("blacklisted-attributes")) {
				List<String> blacklistedAttributeStrings = section.getStringList("blacklisted-attributes");
				EnumSet<MagicItemAttribute> blacklistedAttributes = itemData.getBlacklistedAttributes();

				for (String attr : blacklistedAttributeStrings) {
					try {
						blacklistedAttributes.add(MagicItemAttribute.valueOf(attr.toUpperCase().replace("-", "_")));
					} catch (IllegalArgumentException e) {
						DebugHandler.debugBadEnumValue(MagicItemAttribute.class, attr);
					}
				}
			}

			if (section.isBoolean("strict-enchants"))
				itemData.setStrictEnchants(section.getBoolean("strict-enchants"));

			if (section.isBoolean("strict-block-data"))
				itemData.setStrictBlockData(section.getBoolean("strict-block-data"));

			if (section.isBoolean("strict-durability"))
				itemData.setStrictDurability(section.getBoolean("strict-durability"));

			if (section.isBoolean("strict-enchant-level"))
				itemData.setStrictEnchantLevel(section.getBoolean("strict-enchant-level"));

			MagicItem magicItem = new MagicItem(item, itemData);
			MagicItemDefinitionVersions.applyToMagicItem(magicItem, definitionVersion);
			return magicItem;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

}
