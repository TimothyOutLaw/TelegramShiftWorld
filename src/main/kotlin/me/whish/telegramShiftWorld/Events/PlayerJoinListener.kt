package me.whish.telegramShiftWorld.Events

import me.whish.telegramShiftWorld.TelegramShiftWorld
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

class PlayerJoinListener(private val plugin: TelegramShiftWorld) : Listener {

    private val BOT_USERNAME = "@ShiftWorldLinkBot"

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        val uuid = player.uniqueId
        val playerName = player.name

        // Проверяем, есть ли привязка к Telegram
        val telegramId = plugin.linkingManager.getLinkedTelegram(uuid)

        if (telegramId != null) {
            return
        }

        // Генерируем код привязки
        val linkingCode = plugin.linkingManager.generateLinkingCode(uuid, playerName)

        // Создаем сообщение с кодом
        val kickMessage = (
                        "§9🔗 §c§lТРЕБУЕТСЯ ПРИВЯЗКА К TELEGRAM\n\n" +
                        "§7📱 Для входа привяжите Telegram.\n\n" +
                        "§e🤖 Бот: §b§n$BOT_USERNAME\n\n" +
                        "§e📝 Команда: §a§l/link $linkingCode\n\n" +
                        "§c⏰ Код на 10 минут\n\n" +
                        "§7💡 После привязки заходите снова!"
                )

        // Кикаем игрока с сообщением
        player.kickPlayer(kickMessage)

        plugin.logger.info("Игрок $playerName кикнут с кодом привязки: $linkingCode")
    }
}