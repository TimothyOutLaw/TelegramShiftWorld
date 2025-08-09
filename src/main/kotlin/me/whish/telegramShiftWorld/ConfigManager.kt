package me.whish.telegramShiftWorld

import org.bukkit.configuration.file.FileConfiguration

class ConfigManager(private val plugin: TelegramShiftWorld) {

    private lateinit var config: FileConfiguration

    fun loadConfig() {
        plugin.saveDefaultConfig()
        plugin.reloadConfig()
        config = plugin.config

        // Проверка обязательных параметров
        if (getBotToken().equals("YOUR_BOT_TOKEN_HERE", ignoreCase = true)) {
            plugin.logger.warning("Внимание! Не установлен токен Telegram бота в конфигурации!")
            plugin.logger.warning("Установите токен в config.yml в параметре telegram.bot_token")
        }
    }

    fun reloadConfig() {
        plugin.reloadConfig()
        config = plugin.config
    }

    // Telegram настройки
    fun getBotToken(): String = config.getString("telegram.bot_token", "")!!

    fun getBotUsername(): String = config.getString("telegram.bot_username", "")!!

    // Сообщения
    fun getKickMessage(): String = config.getString("messages.kick_message",
        "§c§lТребуется привязка Telegram!\n\n§fДля игры на сервере необходимо привязать ваш Telegram аккаунт.")!!

    fun getAlreadyLinkedMessage(): String = config.getString("messages.already_linked",
        "§a§lВаш аккаунт уже привязан к Telegram!")!!

    fun getLinkSuccessMessage(): String = config.getString("messages.link_success",
        "§a§lУспешно! Ваш аккаунт привязан к Telegram!")!!

    fun getCodeExpiredMessage(): String = config.getString("messages.code_expired",
        "§c§lКод истек! Зайдите на сервер снова для получения нового кода.")!!

    fun getInvalidCodeMessage(): String = config.getString("messages.invalid_code",
        "§c§lНеверный код! Проверьте правильность введенного кода.")!!

    // Настройки кодов
    fun getCodeExpiryMinutes(): Int = config.getInt("codes.expiry_minutes", 10)

    fun getCodeLength(): Int = config.getInt("codes.length", 8)

    // Общие настройки
    fun isCheckOnJoinEnabled(): Boolean = config.getBoolean("settings.check_on_join", true)

    fun isDebugEnabled(): Boolean = config.getBoolean("settings.debug", false)

    // Форматирование сообщения кика
    fun formatKickMessage(code: String, botUsername: String): String {
        return getKickMessage()
            .replace("%code%", code)
            .replace("%bot_username%", botUsername)
            .replace("\\n", "\n")
    }
}