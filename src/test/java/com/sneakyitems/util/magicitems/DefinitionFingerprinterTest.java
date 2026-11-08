package com.sneakyitems.util.magicitems;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DefinitionFingerprinterTest {

	@Test
	void mapKeyOrderDoesNotChangeFingerprint() {
		Map<String, Object> a = new LinkedHashMap<>();
		a.put("type", "iron_sword");
		a.put("name", "Blade");
		a.put("amount", 1);

		Map<String, Object> b = new LinkedHashMap<>();
		b.put("amount", 1);
		b.put("name", "Blade");
		b.put("type", "iron_sword");

		assertEquals(DefinitionFingerprinter.fingerprintValue(a), DefinitionFingerprinter.fingerprintValue(b));
	}

	@Test
	void nestedSectionStability() {
		Map<String, Object> innerA = new LinkedHashMap<>();
		innerA.put("b", 2);
		innerA.put("a", 1);
		Map<String, Object> outerA = new LinkedHashMap<>();
		outerA.put("type", "stone");
		outerA.put("pdc", innerA);

		Map<String, Object> innerB = Map.of("a", 1, "b", 2);
		Map<String, Object> outerB = Map.of("pdc", innerB, "type", "stone");

		assertEquals(DefinitionFingerprinter.fingerprintValue(outerA), DefinitionFingerprinter.fingerprintValue(outerB));
	}

	@Test
	void listAndStringSensitivity() {
		Map<String, Object> base = Map.of(
				"type", "iron_sword",
				"lore", List.of("line1", "line2"));
		Map<String, Object> changedLore = Map.of(
				"type", "iron_sword",
				"lore", List.of("line1", "line2-changed"));
		Map<String, Object> changedType = Map.of(
				"type", "diamond_sword",
				"lore", List.of("line1", "line2"));

		long baseHash = DefinitionFingerprinter.fingerprintValue(base);
		assertNotEquals(baseHash, DefinitionFingerprinter.fingerprintValue(changedLore));
		assertNotEquals(baseHash, DefinitionFingerprinter.fingerprintValue(changedType));
	}

	@Test
	void stringFingerprintChangesWhenTextChanges() {
		long a = DefinitionFingerprinter.fingerprintString("stone_sword{name:Foo}");
		long b = DefinitionFingerprinter.fingerprintString("stone_sword{name:Bar}");
		assertNotEquals(a, b);
		assertEquals(a, DefinitionFingerprinter.fingerprintString("stone_sword{name:Foo}"));
	}

	@Test
	void equivalentNumbersCanonicalize() {
		Map<String, Object> intAmount = Map.of("amount", 1);
		Map<String, Object> doubleAmount = Map.of("amount", 1.0);
		assertEquals(DefinitionFingerprinter.fingerprintValue(intAmount),
				DefinitionFingerprinter.fingerprintValue(doubleAmount));
	}

}
