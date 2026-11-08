package com.sneakyitems

import org.mockbukkit.mockbukkit.MockBukkit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SneakyItemsTest {

    @BeforeEach
    fun setUp() {
        MockBukkit.mock()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun loadsAndEnablesWithoutError() {
        val plugin = MockBukkit.load(SneakyItems::class.java)
        assertNotNull(plugin)
        assertTrue(plugin.isEnabled)
        assertNotNull(SneakyItems.instance)
    }
}
