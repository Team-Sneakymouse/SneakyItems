package com.sneakyitems.util.magicitems;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MagicItemTagsTest {

	@Test
	void normalizeTrimsAndDropsBlanks() {
		assertEquals(Set.of("food", "weapon"), MagicItemTagStrings.normalize(List.of(" food ", "", "weapon", "  ")));
	}

	@Test
	void serializeJoinsWithCommas() {
		assertEquals("food,weapon", MagicItemTagStrings.serialize(List.of("food", "weapon")));
		assertEquals("", MagicItemTagStrings.serialize(List.of()));
	}

	@Test
	void deserializeSplitsAndTrims() {
		assertEquals(Set.of("food", "weapon"), MagicItemTagStrings.deserialize("food, weapon"));
		assertEquals(Set.of(), MagicItemTagStrings.deserialize(""));
		assertEquals(Set.of(), MagicItemTagStrings.deserialize(null));
	}

	@Test
	void roundTripPreservesOrderAndValues() {
		String raw = MagicItemTagStrings.serialize(List.of("a", "b", "c"));
		assertEquals(Set.of("a", "b", "c"), MagicItemTagStrings.deserialize(raw));
	}

	@Test
	void fromConfigListDelegatesToNormalize() {
		assertEquals(Set.of("food"), MagicItemTagStrings.fromConfigList(List.of("food")));
		assertTrue(MagicItemTagStrings.fromConfigList(null).isEmpty());
	}

}
