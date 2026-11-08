package com.sneakyitems.commands

import com.sneakyitems.SneakyItems
import com.sneakyitems.service.LegacyItemTagMappings
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands

/** Root `/sneakyitems` command tree. */
class SneakyItemsCommand(private val plugin: SneakyItems) {

  fun create(): LiteralCommandNode<CommandSourceStack> =
    Commands.literal(SneakyItems.IDENTIFIER)
      .then(reloadLiteral())
      .then(legacyTagsLiteral())
      .build()

  private fun reloadLiteral() =
    Commands.literal("reload")
      .requires { it.sender.hasPermission("${SneakyItems.IDENTIFIER}.command.reload") }
      .executes { context ->
        plugin.reloadPlugin()
        context.source.sender.sendMessage("${plugin.name} configuration reloaded.")
        1
      }

  private fun legacyTagsLiteral() =
    Commands.literal("legacy-tags")
      .requires { it.sender.hasPermission("${SneakyItems.IDENTIFIER}.command.legacy-tags") }
      .then(
        Commands.literal("status")
          .executes { context ->
            context.source.sender.sendMessage(statusMessage())
            1
          },
      )
      .then(
        Commands.literal("on")
          .executes { context ->
            val count = LegacyItemTagMappings.setRuntimeEnabled(plugin, true)
            context.source.sender.sendMessage(
              "Legacy item-tag mappings enabled in memory ($count mapping(s) loaded). Config unchanged.",
            )
            1
          },
      )
      .then(
        Commands.literal("off")
          .executes { context ->
            LegacyItemTagMappings.setRuntimeEnabled(plugin, false)
            context.source.sender.sendMessage(
              "Legacy item-tag mappings disabled in memory. Config unchanged.",
            )
            1
          },
      )
      .executes { context ->
        context.source.sender.sendMessage(statusMessage())
        1
      }

  private fun statusMessage(): String {
    val runtime = if (LegacyItemTagMappings.runtimeEnabled) "on" else "off"
    val configOn = plugin.config.getBoolean("load-legacy-item-tag-mappings", true)
    return "Legacy item-tag mappings: runtime=$runtime, config=${if (configOn) "on" else "off"} " +
      "(${LegacyItemTagMappings.FILE_NAME})."
  }
}
