package me.whish.telegramShiftWorld.Events

import me.whish.telegramShiftWorld.TelegramShiftWorld
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

class PlayerJoinListener(private val plugin: TelegramShiftWorld) : Listener {

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        val uuid = player.uniqueId
        val playerName = player.name

        plugin.debug("Игрок $playerName пытается войти на сервер")

        // Проверяем, есть ли привязка к Telegram
        val telegramId = plugin.linkingManager.getLinkedTelegram(uuid)

        if (telegramId != null) {
            plugin.debug("Игрок $playerName имеет привязку к Telegram ID: $telegramId")

            // Игрок привязан, разрешаем вход
            val welcomeMessage = Component.text()
                .append(Component.text("✅ ", NamedTextColor.GREEN))
                .append(Component.text("Добро пожаловать, ", NamedTextColor.GRAY))
                .append(Component.text(playerName, NamedTextColor.YELLOW, TextDecoration.BOLD))
                .append(Component.text("!", NamedTextColor.GRAY))
                .build()

            player.sendMessage(welcomeMessage)
            return
        }

        plugin.debug("Игрок $playerName не имеет привязки к Telegram")

        // Генерируем код привязки
        val linkingCode = plugin.linkingManager.generateLinkingCode(uuid, playerName)

        // Создаем сообщение с кодом
        val kickMessage = Component.text()
            .append(Component.text("🔗 ", NamedTextColor.BLUE))
            .append(Component.text("ТРЕБУЕТСЯ ПРИВЯЗКА К TELEGRAM", NamedTextColor.RED, TextDecoration.BOLD))
            .append(Component.newline())
            .append(Component.newline())
            .append(Component.text("📱 Для входа на сервер необходимо привязать аккаунт к Telegram.", NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.newline())
            .append(Component.text("🤖 Напишите боту: ", NamedTextColor.YELLOW))
            .append(Component.text("@YourBotUsername", NamedTextColor.AQUA, TextDecoration.UNDERLINED))
            .append(Component.newline())
            .append(Component.newline())
            .append(Component.text("📝 Отправьте команду: ", NamedTextColor.YELLOW))
            .append(Component.text("/link $linkingCode", NamedTextColor.GREEN, TextDecoration.BOLD))
            .append(Component.newline())
            .append(Component.newline())
            .append(Component.text("⏰ Код действителен 10 минут", NamedTextColor.RED))
            .append(Component.newline())
            .append(Component.newline())
            .append(Component.text("💡 После привязки заходите снова!", NamedTextColor.GRAY))
            .build()

        // Кикаем игрока с сообщением
        player.kick(kickMessage)

        plugin.logger.info("Игрок ${playerName} кикнут с кодом привязки: $linkingCode")
    }
}