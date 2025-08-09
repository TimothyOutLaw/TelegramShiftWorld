package me.whish.telegramShiftWorld

import me.whish.telegramShiftWorld.Commands.TelegramAdminCommand
import me.whish.telegramShiftWorld.Commands.TelegramCommand
import me.whish.telegramShiftWorld.Events.PlayerJoinListener
import org.bukkit.plugin.java.JavaPlugin

class TelegramShiftWorld : JavaPlugin() {

    companion object {
        lateinit var instance: TelegramShiftWorld
            private set
    }

    lateinit var configManager: ConfigManager
        private set

    lateinit var linkingManager: LinkingManager
        private set

    lateinit var telegramBotManager: TelegramBotManager
        private set

    override fun onEnable() {
        instance = this

        configManager = ConfigManager(this)
        linkingManager = LinkingManager(this)
        telegramBotManager = TelegramBotManager(this)

        // Загрузка конфигурации
        configManager.loadConfig()

        // Загрузка привязок
        linkingManager.loadLinks()

        // Запуск Telegram бота
        if (!telegramBotManager.startBot()) {
            logger.severe("Не удалось запустить Telegram бота! Проверьте токен в конфигурации.")
            server.pluginManager.disablePlugin(this)
            return
        }

        // Регистрация событий
        server.pluginManager.registerEvents(PlayerJoinListener(this), this)

        // Регистрация команд
        getCommand("telegram")?.setExecutor(TelegramCommand(this))
        getCommand("telegramadmin")?.setExecutor(TelegramAdminCommand(this))

        logger.info("TelegramShiftWorld has been enabled!")
    }

    override fun onDisable() {
        if (::telegramBotManager.isInitialized) {
            telegramBotManager.stopBot()
        }

        if (::linkingManager.isInitialized) {
            linkingManager.saveLinks()
        }

        logger.info("TelegramShiftWorld has been disabled!")
    }

    fun debug(message: String) {
        if (configManager.isDebugEnabled()) {
            logger.info("[DEBUG] $message")
        }
    }
}