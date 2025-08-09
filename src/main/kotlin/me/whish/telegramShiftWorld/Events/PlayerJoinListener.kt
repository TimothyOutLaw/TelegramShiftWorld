package me.whish.telegramShiftWorld.Events

import me.whish.telegramShiftWorld.TelegramShiftWorld
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerLoginEvent

class PlayerJoinListener(private val plugin: TelegramShiftWorld) : Listener {

    @EventHandler(priority = EventPriority.HIGH)
    fun onPlayerLogin(event: PlayerLoginEvent) {
        // Проверяем включена ли проверка при входе
        if (!plugin.configManager.isCheckOnJoinEnabled()) {
            return
        }

        val player = event.player

        // Проверяем есть ли у игрока разрешение на обход проверки
        if (player.hasPermission("telegramshiftworld.bypass")) {
            plugin.debug("Игрок ${player.name} обошел проверку Telegram (permission bypass)")
            return
        }

        // Проверяем в вайтлисте ли игрок (если включен вайтлист)
        if (plugin.server.hasWhitelist()) {
            val whitelistedPlayers = plugin.server.whitelistedPlayers
            val isWhitelisted = whitelistedPlayers.any { it.uniqueId == player.uniqueId }

            if (!isWhitelisted) {
                plugin.debug("Игрок ${player.name} не в вайтлисте, пропускаем проверку Telegram")
                return
            }
        }

        // Проверяем привязан ли Telegram
        if (plugin.linkingManager.isPlayerLinked(player.uniqueId)) {
            plugin.debug("Игрок ${player.name} уже привязан к Telegram")
            return
        }

        // Игрок не привязан - генерируем код и кикаем
        val code = plugin.linkingManager.generateCode(player.uniqueId, player.name)
        val botUsername = plugin.configManager.getBotUsername()

        val kickMessage = plugin.configManager.formatKickMessage(code, botUsername)

        // Используем простой String для kick message
        event.disallow(PlayerLoginEvent.Result.KICK_OTHER, kickMessage)

        plugin.debug("Игрок ${player.name} кикнут для привязки Telegram, код: $code")

        // Очищаем истекшие коды
        plugin.linkingManager.cleanupExpiredCodes()
    }
}