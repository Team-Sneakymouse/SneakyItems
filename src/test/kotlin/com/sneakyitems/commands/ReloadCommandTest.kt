package com.sneakyitems.commands

import com.sneakyitems.SneakyItems
import com.sneakyitems.service.LegacyItemTagMappings
import com.mojang.brigadier.exceptions.CommandSyntaxException
import org.mockbukkit.mockbukkit.command.CommandSourceStackMock
import org.mockbukkit.mockbukkit.command.brigadier.PaperCommandsMock
import org.junit.jupiter.api.Assertions.assertThrows
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class ReloadCommandTest {

    private lateinit var server: ServerMock

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun rootLiteralsHaveNativeCompletion() {
        MockBukkit.load(SneakyItems::class.java)
        server.dispatchCommand(server.consoleSender, "sneakyitems")

        val dispatcher = PaperCommandsMock.INSTANCE.dispatcherInternal
        val parsed = dispatcher.parse("sneakyitems ", CommandSourceStackMock.from(server.consoleSender))
        val suggestions = dispatcher.getCompletionSuggestions(parsed).join().list.map { it.text }.toSet()
        assertEquals(setOf("reload", "legacy-tags"), suggestions)
    }

    @Test
    fun playerWithoutPermissionCannotReload() {
        val plugin = MockBukkit.load(SneakyItems::class.java)
        plugin.config.set("test-value", "before")
        File(plugin.dataFolder, "config.yml").writeText("test-value: after\n")
        val player = server.addPlayer()

        server.dispatchCommand(player, "sneakyitems reload")

        assertEquals("before", plugin.config.getString("test-value"))
    }

    @Test
    fun playerWithPermissionCanReloadRepeatedly() {
        val plugin = MockBukkit.load(SneakyItems::class.java)
        val player = server.addPlayer()
        player.addAttachment(plugin, "sneakyitems.command.reload", true)

        for (value in listOf("first", "second")) {
            File(plugin.dataFolder, "config.yml").writeText("test-value: $value\n")
            assertTrue(server.dispatchCommand(player, "sneakyitems reload"))
            assertEquals(value, plugin.config.getString("test-value"))
            assertEquals("SneakyItems configuration reloaded.", player.nextMessage())
        }
    }

    @Test
    fun invalidArgumentsAreRejectedWithoutReloading() {
        val plugin = MockBukkit.load(SneakyItems::class.java)
        server.dispatchCommand(server.consoleSender, "sneakyitems")
        plugin.config.set("test-value", "before")
        File(plugin.dataFolder, "config.yml").writeText("test-value: after\n")

        for (command in listOf("sneakyitems", "sneakyitems unknown", "sneakyitems reload extra")) {
            assertThrows(CommandSyntaxException::class.java) {
                PaperCommandsMock.INSTANCE.dispatcherInternal.execute(
                    command,
                    CommandSourceStackMock.from(server.consoleSender),
                )
            }
            assertEquals("before", plugin.config.getString("test-value"))
        }
    }

    @Test
    fun consoleReloadReparsesConfigFromDisk() {
        val plugin = MockBukkit.load(SneakyItems::class.java)
        plugin.config.set("test-value", "before")
        File(plugin.dataFolder, "config.yml").writeText("test-value: after\n")

        assertTrue(server.dispatchCommand(server.consoleSender, "sneakyitems reload"))

        assertEquals("after", plugin.config.getString("test-value"))
        assertEquals("SneakyItems configuration reloaded.", server.consoleSender.nextMessage())
        assertTrue(plugin.isEnabled)
    }

    @Test
    fun legacyTagsToggleIsInMemoryOnly() {
        val plugin = MockBukkit.load(SneakyItems::class.java)
        writeSimpleMappings(plugin)
        // Test config defaults load-legacy-item-tag-mappings to false.
        assertFalse(plugin.config.getBoolean("load-legacy-item-tag-mappings", true))
        assertFalse(LegacyItemTagMappings.runtimeEnabled)

        assertTrue(server.dispatchCommand(server.consoleSender, "sneakyitems legacy-tags on"))
        assertTrue(LegacyItemTagMappings.runtimeEnabled)
        assertFalse(plugin.config.getBoolean("load-legacy-item-tag-mappings", true))
        assertTrue(server.consoleSender.nextMessage()!!.contains("enabled in memory"))

        assertTrue(server.dispatchCommand(server.consoleSender, "sneakyitems legacy-tags off"))
        assertFalse(LegacyItemTagMappings.runtimeEnabled)
        assertFalse(plugin.config.getBoolean("load-legacy-item-tag-mappings", true))
        assertTrue(server.consoleSender.nextMessage()!!.contains("disabled in memory"))

        assertTrue(server.dispatchCommand(server.consoleSender, "sneakyitems legacy-tags status"))
        assertTrue(server.consoleSender.nextMessage()!!.contains("runtime=off"))
    }

    @Test
    fun reloadResetsRuntimeLegacyTagsFromConfig() {
        val plugin = MockBukkit.load(SneakyItems::class.java)
        writeSimpleMappings(plugin)
        assertTrue(server.dispatchCommand(server.consoleSender, "sneakyitems legacy-tags on"))
        assertTrue(LegacyItemTagMappings.runtimeEnabled)

        File(plugin.dataFolder, "config.yml").writeText(
            """
            update-item-data: false
            hide-magic-item-tooltips: false
            load-legacy-item-tag-mappings: false
            """.trimIndent() + "\n",
        )
        assertTrue(server.dispatchCommand(server.consoleSender, "sneakyitems reload"))
        assertFalse(LegacyItemTagMappings.runtimeEnabled)
    }

    @Test
    fun ensureDataFileCopiesDefaultMappings() {
        val plugin = MockBukkit.load(SneakyItems::class.java)
        val file = File(plugin.dataFolder, LegacyItemTagMappings.FILE_NAME)
        assertTrue(file.isFile)
        assertTrue(file.readText().contains("mapping:"))
    }

    /** Avoid jar defaults with serialized ItemStacks (MockBukkit cannot deserialize them). */
    private fun writeSimpleMappings(plugin: SneakyItems) {
        File(plugin.dataFolder, LegacyItemTagMappings.FILE_NAME).writeText(
            """
            mapping:
              'test-paper-by-model':
                internal-name: 'test-paper'
                type: "paper"
                item-model: "lom:test"
            """.trimIndent() + "\n",
        )
    }
}
