package me.whish.telegramShiftWorld.Commands

import me.whish.telegramShiftWorld.TelegramShiftWorld
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

@Suppress("UNCHECKED_CAST", "DEPRECATION")
class TelegramAdminCommand(private val plugin: TelegramShiftWorld) : CommandExecutor, TabCompleter {
    override fun onCommand(
        sender: CommandSender,
        p1: Command,
        p2: String,
        args: Array<out String?>
    ): Boolean {
        if (!sender.hasPermission("telegramshiftworld.admin")) {
            sender.sendMessage("§cУ вас нет прав для использования этой команды!")
            return true
        }

        when (args.getOrNull(0)?.lowercase()) {
            "stats", "статистика" -> handleStats(sender)
            "reload", "перезагрузить" -> handleReload(sender)
            "check", "проверить" -> handleCheck(sender, args as Array<String>)
            "unlink", "отвязать" -> handleUnlink(sender, args as Array<String>)
            "cleanup", "очистить" -> handleCleanup(sender)
            "help", "помощь" -> handleHelp(sender)
            else -> handleHelp(sender)
        }

        return true
    }

    private fun handleStats(sender: CommandSender) {
        val linkedCount = plugin.linkingManager.getLinkedPlayersCount()
        val pendingCount = plugin.linkingManager.getPendingCodesCount()
        val onlineCount = plugin.server.onlinePlayers.size
        val totalPlayers = plugin.server.offlinePlayers.size

        sender.sendMessage("§e§l━━━━━━━ TelegramShiftWorld - Статистика ━━━━━━━")
        sender.sendMessage("§f")
        sender.sendMessage("§a§lПривязанные аккаунты: §e$linkedCount")
        sender.sendMessage("§6§lОжидающие коды: §e$pendingCount")
        sender.sendMessage("§b§lИгроков онлайн: §e$onlineCount")
        sender.sendMessage("§7§lВсего игроков: §e$totalPlayers")
        sender.sendMessage("§f")

        val linkPercentage = if (totalPlayers > 0) {
            String.format("%.1f", (linkedCount.toDouble() / totalPlayers * 100))
        } else {
            "0.0"
        }

        sender.sendMessage("§d§lПроцент привязанных: §e$linkPercentage%")
        sender.sendMessage("§e§l━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }

    private fun handleReload(sender: CommandSender) {
        sender.sendMessage("§e§lПерезагрузка конфигурации...")

        try {
            plugin.configManager.reloadConfig()
            sender.sendMessage("§a§l✅ Конфигурация успешно перезагружена!")
        } catch (e: Exception) {
            sender.sendMessage("§c§l❌ Ошибка при перезагрузке: ${e.message}")
        }
    }

    private fun handleCheck(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            sender.sendMessage("§cИспользование: /telegramadmin check <ник>")
            return
        }

        val playerName = args[1]
        val offlinePlayer = plugin.server.getOfflinePlayer(playerName)

        if (!offlinePlayer.hasPlayedBefore() && !offlinePlayer.isOnline) {
            sender.sendMessage("§c§lИгрок §e$playerName §cне найден!")
            return
        }

        val isLinked = plugin.linkingManager.isPlayerLinked(offlinePlayer.uniqueId)

        sender.sendMessage("§e§l━━━━━━━ Информация об игроке ━━━━━━━")
        sender.sendMessage("§f")
        sender.sendMessage("§a§lИгрок: §e$playerName")
        sender.sendMessage("§7UUID: §f${offlinePlayer.uniqueId}")
        sender.sendMessage("§b§lСтатус: ${if (offlinePlayer.isOnline) "§a§lВ сети" else "§c§lНе в сети"}")

        if (isLinked) {
            val telegramId = plugin.linkingManager.getPlayerTelegramId(offlinePlayer.uniqueId)
            sender.sendMessage("§a§lTelegram: §e✅ Привязан (ID: $telegramId)")
        } else {
            sender.sendMessage("§c§lTelegram: §e❌ Не привязан")
        }

        sender.sendMessage("§e§l━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }

    private fun handleUnlink(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            sender.sendMessage("§cИспользование: /telegramadmin unlink <ник>")
            return
        }

        val playerName = args[1]
        val offlinePlayer = plugin.server.getOfflinePlayer(playerName)

        if (!offlinePlayer.hasPlayedBefore() && !offlinePlayer.isOnline) {
            sender.sendMessage("§c§lИгрок §e$playerName §cне найден!")
            return
        }

        val wasLinked = plugin.linkingManager.unlinkPlayer(offlinePlayer.uniqueId)
        if (wasLinked) {
            sender.sendMessage("§a§l✅ Игрок §e$playerName §a§lуспешно отвязан от Telegram!")
        } else {
            sender.sendMessage("§c§lИгрок §e$playerName §cне был привязан к Telegram!")
        }
    }

    private fun handleCleanup(sender: CommandSender) {
        val beforeCount = plugin.linkingManager.getPendingCodesCount()
        plugin.linkingManager.cleanupExpiredCodes()
        val afterCount = plugin.linkingManager.getPendingCodesCount()
        val cleanedCount = beforeCount - afterCount

        sender.sendMessage("§a§l✅ Очистка завершена!")
        sender.sendMessage("§eУдалено истекших кодов: §6$cleanedCount")
        sender.sendMessage("§eОсталось активных кодов: §6$afterCount")
    }

    private fun handleHelp(sender: CommandSender) {
        sender.sendMessage("§e§l━━━━━━━ TelegramShiftWorld - Админ команды ━━━━━━━")
        sender.sendMessage("§f")
        sender.sendMessage("§6/telegramadmin stats §7- показать статистику")
        sender.sendMessage("§6/telegramadmin reload §7- перезагрузить конфигурацию")
        sender.sendMessage("§6/telegramadmin check <ник> §7- проверить игрока")
        sender.sendMessage("§6/telegramadmin unlink <ник> §7- отвязать игрока")
        sender.sendMessage("§6/telegramadmin cleanup §7- очистить истекшие коды")
        sender.sendMessage("§6/telegramadmin help §7- показать эту помощь")
        sender.sendMessage("§f")
        sender.sendMessage("§e§l━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("telegramshiftworld.admin")) {
            return emptyList()
        }

        return when (args.size) {
            1 -> {
                val subcommands = listOf("stats", "reload", "check", "unlink", "cleanup", "help")
                subcommands.filter { it.startsWith(args[0].lowercase()) }
            }
            2 -> {
                when (args[0].lowercase()) {
                    "check", "unlink" -> {
                        plugin.server.onlinePlayers.map { it.name }
                            .filter { it.startsWith(args[1], ignoreCase = true) }
                    }
                    else -> emptyList()
                }
            }
            else -> emptyList()
        }
    }
}