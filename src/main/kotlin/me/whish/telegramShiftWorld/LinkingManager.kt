package me.whish.telegramShiftWorld

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class PendingCode(
    val code: String,
    val playerUuid: UUID,
    val playerName: String,
    val expiryTime: Long
)

class LinkingManager(private val plugin: TelegramShiftWorld) {

    private val linksFile = File(plugin.dataFolder, "links.yml")
    private val links = ConcurrentHashMap<UUID, Long>() // UUID -> Telegram ID
    private val pendingCodes = ConcurrentHashMap<String, PendingCode>()
    private val reverseLinks = ConcurrentHashMap<Long, UUID>() // Telegram ID -> UUID

    private val codeExpiryTime: Long = plugin.config.getLong("linking.code_expiry_minutes", 10) * 60 * 1000
    private val cleanupTask: Int

    init {
        loadLinks()

        // Запускаем задачу очистки каждые 30 секунд
        cleanupTask = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, {
            cleanupExpiredCodes()
        }, 600L, 600L) // 30 секунд = 600 тиков
    }

    /**
     * Загружает привязки из файла
     */
    private fun loadLinks() {
        if (!linksFile.exists()) {
            return
        }

        try {
            val config = YamlConfiguration.loadConfiguration(linksFile)
            val linksSection = config.getConfigurationSection("links")

            linksSection?.getKeys(false)?.forEach { uuidString ->
                try {
                    val uuid = UUID.fromString(uuidString)
                    val telegramId = linksSection.getLong(uuidString)

                    if (telegramId != 0L) {
                        links[uuid] = telegramId
                        reverseLinks[telegramId] = uuid
                    }
                } catch (_: IllegalArgumentException) {
                    plugin.logger.warning("Неверный UUID в файле привязок: $uuidString")
                }
            }

            plugin.logger.info("Загружено ${links.size} привязок")

        } catch (e: Exception) {
            plugin.logger.severe("Ошибка загрузки привязок: ${e.message}")
        }
    }

    /**
     * Сохраняет привязки в файл
     */
    fun saveLinks() {
        try {
            if (!plugin.dataFolder.exists()) {
                plugin.dataFolder.mkdirs()
            }

            val config = YamlConfiguration()
            val linksSection = config.createSection("links")

            links.forEach { (uuid, telegramId) ->
                linksSection.set(uuid.toString(), telegramId)
            }

            config.save(linksFile)

        } catch (e: Exception) {
            plugin.logger.severe("Ошибка сохранения привязок: ${e.message}")
        }
    }

    /**
     * Генерирует код привязки для игрока
     */
    fun generateLinkingCode(playerUuid: UUID, playerName: String): String {
        val code = generateRandomCode()
        val expiryTime = System.currentTimeMillis() + codeExpiryTime

        val pendingCode = PendingCode(code, playerUuid, playerName, expiryTime)
        pendingCodes[code] = pendingCode

        return code
    }

    /**
     * Верифицирует код и создает привязку
     */
    @Synchronized
    fun verifyCode(code: String, telegramId: Long): Boolean {

        val pendingCode = pendingCodes[code]
        if (pendingCode == null) {
            return false
        }

        if (System.currentTimeMillis() > pendingCode.expiryTime) {
            pendingCodes.remove(code)
            return false
        }

        // Удаляем старую привязку если она существует
        val oldUuid = reverseLinks.remove(telegramId)
        if (oldUuid != null) {
            links.remove(oldUuid)
        }

        // Удаляем старую привязку для этого игрока
        val oldTelegramId = links.remove(pendingCode.playerUuid)
        if (oldTelegramId != null) {
            reverseLinks.remove(oldTelegramId)
        }

        // Создаем новую привязку
        links[pendingCode.playerUuid] = telegramId
        reverseLinks[telegramId] = pendingCode.playerUuid
        pendingCodes.remove(code)

        plugin.logger.info("Привязка создана: ${pendingCode.playerName} (${pendingCode.playerUuid}) -> Telegram ID $telegramId")

        // Автоматически сохраняем
        saveLinks()
        return true
    }

    /**
     * Получает Telegram ID по UUID игрока
     */
    fun getLinkedTelegram(playerUuid: UUID): Long? {
        return links[playerUuid]
    }

    /**
     * Получает UUID игрока по Telegram ID
     */
    fun getLinkedPlayer(telegramId: Long): UUID? {
        return reverseLinks[telegramId]
    }

    /**
     * Получает имя привязанного игрока по Telegram ID
     */
    fun getLinkedPlayerName(telegramId: Long): String? {
        val uuid = getLinkedPlayer(telegramId) ?: return null
        return plugin.server.getOfflinePlayer(uuid).name
    }

    /**
     * Отвязывает аккаунт игрока
     */
    @Synchronized
    fun unlinkPlayer(playerUuid: UUID): Boolean {
        val telegramId = links.remove(playerUuid)
        return if (telegramId != null) {
            reverseLinks.remove(telegramId)
            saveLinks()
            plugin.logger.info("Отвязан аккаунт игрока: $playerUuid")
            true
        } else {
            false
        }
    }

    /**
     * Отвязывает аккаунт по Telegram ID
     */
    @Synchronized
    fun unlinkTelegram(telegramId: Long): Boolean {
        val playerUuid = reverseLinks.remove(telegramId)
        return if (playerUuid != null) {
            links.remove(playerUuid)
            saveLinks()
            plugin.logger.info("Отвязан аккаунт Telegram ID: $telegramId")
            true
        } else {
            false
        }
    }

    /**
     * Очищает истекшие коды
     */
    fun cleanupExpiredCodes() {
        val currentTime = System.currentTimeMillis()
        val expiredCodes = mutableListOf<String>()

        pendingCodes.forEach { (code, pendingCode) ->
            if (currentTime > pendingCode.expiryTime) {
                expiredCodes.add(code)
            }
        }
    }

    /**
     * Получает информацию о коде (для API)
     */
    fun getCodeInfo(code: String): PendingCode? {
        val pendingCode = pendingCodes[code]

        // Проверяем актуальность
        if (pendingCode != null && System.currentTimeMillis() > pendingCode.expiryTime) {
            pendingCodes.remove(code)
            return null
        }

        return pendingCode
    }

    /**
     * Получает статистику
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "total_links" to links.size,
            "pending_codes" to pendingCodes.size,
            "active_codes" to pendingCodes.values.count {
                System.currentTimeMillis() <= it.expiryTime
            }
        )
    }

    /**
     * Генерирует случайный код
     */
    private fun generateRandomCode(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..8)
            .map { chars.random() }
            .joinToString("")
    }

    /**
     * Очистка при отключении плагина
     */
    fun shutdown() {
        plugin.server.scheduler.cancelTask(cleanupTask)
        saveLinks()
    }
}