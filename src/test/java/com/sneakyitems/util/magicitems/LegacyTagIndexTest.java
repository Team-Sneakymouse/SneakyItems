package com.sneakyitems.util.magicitems;

import java.util.List;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import net.kyori.adventure.text.Component;

import com.sneakyitems.util.magicitems.MagicItemData.MagicItemAttribute;
import com.sneakyitems.util.magicitems.MagicItemTagging.TagMapping;
import com.sneakyitems.util.magicitems.MagicItemTagging.TagResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyTagIndexTest {

	private static final Object OWNER = new Object();

	@BeforeEach
	void setUp() {
		MockBukkit.mock();
	}

	@AfterEach
	void tearDown() {
		MagicItemTagging.unregister(OWNER);
		MagicItems.getMagicItems().remove("known-item");
		MagicItems.clearItemStackCache();
		MockBukkit.unmock();
	}

	private static MagicItemData data(Material type) {
		MagicItemData data = new MagicItemData();
		if (type != null)
			data.setAttribute(MagicItemAttribute.TYPE, type);
		return data;
	}

	private static TagMapping mapping(String name, MagicItemData pattern) {
		pattern.getIgnoredAttributes().add(MagicItemAttribute.AMOUNT);
		pattern.getIgnoredAttributes().add(MagicItemAttribute.MAGIC_ITEM_NAME);
		return new TagMapping(name, pattern, null);
	}

	private static MagicItemData withCmd(MagicItemData data, int value) {
		data.setAttribute(MagicItemAttribute.CUSTOM_MODEL_DATA, CustomModelDataValues.ofLegacyInt(value));
		return data;
	}

	private static MagicItemData withModel(MagicItemData data, String key) {
		data.setAttribute(MagicItemAttribute.ITEM_MODEL, NamespacedKey.fromString(key));
		return data;
	}

	private static List<String> names(List<TagMapping> mappings) {
		return mappings.stream().map(TagMapping::internalName).toList();
	}

	@Test
	void narrowsByTypeThenModelAndKeepsOrder() {
		LegacyTagIndex index = new LegacyTagIndex(List.of(
				mapping("stone-plain", data(Material.STONE)),
				mapping("stone-5", withCmd(data(Material.STONE), 5)),
				mapping("stone-6", withCmd(data(Material.STONE), 6)),
				mapping("dirt", data(Material.DIRT)),
				mapping("any-modelled", withModel(data(null), "pack:thing")),
				mapping("stone-modelled", withModel(data(Material.STONE), "pack:thing"))));

		assertEquals(List.of("stone-plain", "stone-5"), names(index.candidates(withCmd(data(Material.STONE), 5))));
		assertEquals(List.of("stone-plain", "any-modelled", "stone-modelled"),
				names(index.candidates(withModel(data(Material.STONE), "pack:thing"))));
		assertEquals(List.of("dirt"), names(index.candidates(withCmd(data(Material.DIRT), 6))));
		assertEquals(List.of(), names(index.candidates(data(Material.GLASS))));
	}

	@Test
	void ignoredPatternValuesGoInTheAnyList() {
		MagicItemData pattern = withCmd(data(Material.STONE), 5);
		pattern.getIgnoredAttributes().add(MagicItemAttribute.CUSTOM_MODEL_DATA);
		LegacyTagIndex index = new LegacyTagIndex(List.of(mapping("stone-any-model", pattern)));
		assertEquals(List.of("stone-any-model"), names(index.candidates(withCmd(data(Material.STONE), 9))));
	}

	@Test
	void typeStageSkipsTypesNoMappingUses() {
		LegacyTagIndex typedOnly = new LegacyTagIndex(List.of(mapping("stone", data(Material.STONE))));
		assertTrue(typedOnly.mayMatchType(Material.STONE));
		assertFalse(typedOnly.mayMatchType(Material.DIRT));

		LegacyTagIndex withUntyped = new LegacyTagIndex(List.of(mapping("any", data(null))));
		assertTrue(withUntyped.mayMatchType(Material.DIRT));
	}

	// applyTagIfNeeded

	private static MagicItemData namedStonePattern() {
		MagicItemData pattern = data(Material.STONE);
		pattern.setAttribute(MagicItemAttribute.NAME, Component.text("Old Stone"));
		return pattern;
	}

	private static ItemStack namedStone(String tag) {
		ItemStack stack = new ItemStack(Material.STONE);
		ItemMeta meta = stack.getItemMeta();
		meta.displayName(Component.text("Old Stone"));
		if (tag != null)
			meta.getPersistentDataContainer().set(MagicItemBehaviorKeys.magicItem(), PersistentDataType.STRING, tag);
		stack.setItemMeta(meta);
		return stack;
	}

	@Test
	void untaggedLegacyStackGetsTagged() {
		MagicItemTagging.register(OWNER, List.of(mapping("legacy-stone", namedStonePattern())));
		TagResult result = MagicItemTagging.applyTagIfNeeded(namedStone(null));
		assertTrue(result.tagged());
		assertEquals("legacy-stone", result.internalName());
	}

	@Test
	void stackTaggedWithKnownItemSkipsLegacyScan() {
		MagicItemTagging.register(OWNER, List.of(mapping("legacy-stone", namedStonePattern())));
		MagicItems.getMagicItems().put("known-item", new MagicItem(new ItemStack(Material.STONE), data(Material.STONE)));
		TagResult result = MagicItemTagging.applyTagIfNeeded(namedStone("known-item"));
		assertFalse(result.tagged());
	}

	@Test
	void stackTaggedWithUnknownNameIsStillChecked() {
		MagicItemTagging.register(OWNER, List.of(mapping("legacy-stone", namedStonePattern())));
		TagResult result = MagicItemTagging.applyTagIfNeeded(namedStone("merged-stone-variant"));
		assertTrue(result.tagged());
		assertEquals("legacy-stone", result.internalName());
	}

	@Test
	void otherTypesAreNotTagged() {
		MagicItemTagging.register(OWNER, List.of(mapping("legacy-stone", namedStonePattern())));
		ItemStack dirt = new ItemStack(Material.DIRT);
		ItemMeta meta = dirt.getItemMeta();
		meta.displayName(Component.text("Old Stone"));
		dirt.setItemMeta(meta);
		assertFalse(MagicItemTagging.applyTagIfNeeded(dirt).tagged());
	}
}
