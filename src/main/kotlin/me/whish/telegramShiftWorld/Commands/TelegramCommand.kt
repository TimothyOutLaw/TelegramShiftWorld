package me.whish.telegramShiftWorld.Commands

import me.whish.telegramShiftWorld.TelegramShiftWorld
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class TelegramCommand(private val plugin: TelegramShiftWorld) : CommandExecutor, TabCompleter {
    override fun onCommand(
        sender: CommandSender,
        p1: Command,
        p2: String,
        args: Array<out String?>
    ): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§cЭта команда доступна только игрокам!")
            return true
        }

        if (!sender.hasPermission("telegramshiftworld.use")) {
            sender.sendMessage("§cУ вас нет прав для использования этой команды!")
            return true
        }

        when (args.getOrNull(0)?.lowercase()) {
            "status", "статус" -> handleStatus(sender)
            "code", "код" -> handleCode(sender)
            "help", "помощь" -> handleHelp(sender)
            "unlink", "отвязать" -> handleUnlink(sender)
            else -> handleHelp(sender)
        }

        return true
    }

    private fun handleStatus(player: Player) {
        val isLinked = plugin.linkingManager.isPlayerLinked(player.uniqueId)

        if (isLinked) {
            val telegramId = plugin.linkingManager.getPlayerTelegramId(player.uniqueId)
            player.sendMessage("§a§l✅ Telegram привязан!")
            player.sendMessage("§7ID: §e$telegramId")
        } else {
            player.sendMessage("§c§l❌ Telegram не привязан!")
            player.sendMessage("§eИспользуйте §6/telegram code §eдля получения кода привязки")
        }
    }

    private fun handleCode(player: Player) {
        if (plugin.linkingManager.isPlayerLinked(player.uniqueId)) {
            player.sendMessage("§a§lВаш аккаунт уже привязан к Telegram!")
            return
        }

        val code = plugin.linkingManager.generateCode(player.uniqueId, player.name)
        val botUsername = plugin.configManager.getBotUsername()
        val expiryMinutes = plugin.configManager.getCodeExpiryMinutes()

        player.sendMessage("§e§l━━━━━━━ Код для привязки Telegram ━━━━━━━")
        player.sendMessage("§f")
        player.sendMessage("§a§lВаш код: §e§l$code")
        player.sendMessage("§f")
        player.sendMessage("§7Инструкция:")
        player.sendMessage("§71. Найдите бота: §b@$botUsername")
        player.sendMessage("§72. Отправьте команду: §a/link $code")
        player.sendMessage("§73. Код действителен §e$expiryMinutes минут")
        player.sendMessage("§f")
        player.sendMessage("§c§lНе передавайте код другим игрокам!")
        player.sendMessage("§e§l━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }

    private fun handleUnlink(player: Player) {
        if (!plugin.linkingManager.isPlayerLinked(player.uniqueId)) {
            player.sendMessage("§c§lВаш аккаунт не привязан к Telegram!")
            return
        }

        val success = plugin.linkingManager.unlinkPlayer(player.uniqueId)
        if (success) {
            player.sendMessage("§a§l✅ Аккаунт успешно отвязан от Telegram!")
            player.sendMessage("§eТеперь при следующем входе вам потребуется заново привязать аккаунт.")
        } else {
            player.sendMessage("§c§l❌ Ошибка при отвязке аккаунта!")
        }
    }

    private fun handleHelp(player: Player) {
        player.sendMessage("§e§l━━━━━━━ TelegramShiftWorld - Помощь ━━━━━━━")
        player.sendMessage("§f")
        player.sendMessage("§6/telegram status §7- проверить статус привязки")
        player.sendMessage("§6/telegram code §7- получить код для привязки")
        player.sendMessage("§6/telegram unlink §7- отвязать аккаунт")
        player.sendMessage("§6/telegram help §7- показать эту помощь")
        player.sendMessage("§f")
        player.sendMessage("§aДля привязки аккаунта получите код и отправьте его боту в Telegram!")
        player.sendMessage("§e§l━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            val subcommands = listOf("status", "code", "help", "unlink", "статус", "код", "помощь", "отвязать")
            return subcommands.filter { it.startsWith(args[0].lowercase()) }
        }
        return emptyList()
    }
}