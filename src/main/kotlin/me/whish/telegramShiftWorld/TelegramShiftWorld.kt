package me.whish.telegramShiftWorld

import me.whish.telegramShiftWorld.Events.PlayerJoinListener
import org.bukkit.plugin.java.JavaPlugin

class TelegramShiftWorld : JavaPlugin() {

    lateinit var linkingManager: LinkingManager
    lateinit var httpApiManager: HttpApiManager

    override fun onEnable() {
        saveDefaultConfig()

        linkingManager = LinkingManager(this)

        // Запускаем только HTTP API, НЕ Telegram бота
        if (config.getBoolean("api.enabled", true)) {
            httpApiManager = HttpApiManager(this)
            httpApiManager.startServer()
        }

        // Регистрация событий
        server.pluginManager.registerEvents(PlayerJoinListener(this), this)

        logger.info("TelegramShiftWorld has been enabled!")
    }

    override fun onDisable() {
        if (::httpApiManager.isInitialized) {
            httpApiManager.stopServer()
        }

        if (::linkingManager.isInitialized) {
            linkingManager.saveLinks()
        }

        logger.info("TelegramShiftWorld has been disabled!")
    }
}