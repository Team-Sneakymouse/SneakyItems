package com.sneakyitems.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sneakyitems.SneakyItems;
import com.sneakyutils.SneakyUtils;
import com.sneakyutils.triggers.PlayerTimerOptions;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/** whileCarrying / whileHolding called from Java (the way MagicSpells and other Java plugins call it). */
class WatchJavaApiTest {

	private Plugin owner;

	@BeforeEach
	void setup() {
		MockBukkit.mock();
		MockBukkit.load(SneakyUtils.class);
		MockBukkit.load(SneakyItems.class);
		owner = MockBukkit.createMockPlugin("JavaConsumer");
	}

	@AfterEach
	void cleanup() {
		MockBukkit.unmock();
	}

	@Test
	void callableFromJavaWithLambdasAndOverloads() {
		AtomicInteger calls = new AtomicInteger();
		ItemWatch carried = SneakyItemsAPI.whileCarrying(owner, "wand", 5, p -> calls.incrementAndGet());
		ItemWatch held = SneakyItemsAPI.whileHolding(owner, "wand", HeldSlot.EITHER_HAND, 2, p -> calls.incrementAndGet(),
				PlayerTimerOptions.builder().label("test:java").build());
		assertTrue(carried.isActive());
		assertTrue(held.isActive());
		assertTrue("test:java".equals(held.getLabel()) && held.getTimerId().startsWith("JavaConsumer:"));
		assertTrue(held.getSize() == 0 && held.getItemId().equals("wand") && held.getWhere() == HeldSlot.EITHER_HAND);
		carried.close();
		held.close();
		assertFalse(carried.isActive());
		assertFalse(held.isActive());
	}
}
