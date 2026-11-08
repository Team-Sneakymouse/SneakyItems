package com.sneakyitems.commands

import com.sneakyitems.SneakyItems
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands

class ReloadCommand(private val plugin: SneakyItems) {

    fun create(): LiteralCommandNode<CommandSourceStack> =
        Commands.literal(SneakyItems.IDENTIFIER)
            .then(
                Commands.literal("reload")
                    .requires { it.sender.hasPermission("${SneakyItems.IDENTIFIER}.command.reload") }
                    .executes { context ->
                        plugin.reloadPlugin()
                        context.source.sender.sendMessage("${plugin.name} configuration reloaded.")
                        1
                    }
            )
            .build()
}
