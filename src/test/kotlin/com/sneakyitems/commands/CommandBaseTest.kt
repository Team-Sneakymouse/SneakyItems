package com.sneakyitems.commands

import com.sneakyitems.SneakyItems
import org.bukkit.command.CommandSender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CommandBaseTest {

    private class TestCommand : CommandBase("reload") {
        override fun execute(
            sender: CommandSender,
            commandLabel: String,
            args: Array<out String>,
        ): Boolean = true
    }

    @Test
    fun permissionFollowsIdentifierAndCommandName() {
        assertEquals("${SneakyItems.IDENTIFIER}.command.reload", TestCommand().permission)
    }
}
