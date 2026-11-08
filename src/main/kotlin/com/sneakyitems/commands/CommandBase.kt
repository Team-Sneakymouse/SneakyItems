package com.sneakyitems.commands

import com.sneakyitems.SneakyItems
import org.bukkit.command.Command

/**
 * Base class for plugin commands. Sets permission to
 * `{identifier}.command.{name}` by default.
 */
abstract class CommandBase(name: String) : Command(name) {

    init {
        this.permission = "${SneakyItems.IDENTIFIER}.command.$name"
    }
}
