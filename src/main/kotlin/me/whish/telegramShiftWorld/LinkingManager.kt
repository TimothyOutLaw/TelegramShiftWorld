package me.whish.telegramShiftWorld

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

data class PendingCode(
    val code: String,
    val playerUuid: UUID,
    val playerName: String,
    val expiryTime: Long,
    val createdAt: Long = System.currentTimeMillis()
)

@Suppress("CAST_NEVER_SUCCEEDS")
class LinkingManager(private val plugin: TelegramShiftWorld) {

    private val linksFile = File(plugin.dataFolder, "links.yml")
    private val links = ConcurrentHashMap<UUID, Long>() // UUID -> Telegram ID
    private val pendingCodes = ConcurrentHashMap<String, PendingCode>()
    private val reverseLinks = ConcurrentHashMap<Long, UUID>() // Telegram ID -> UUID

    // Улучшенная синхронизация для критических операций
    private val linkLock = ReentrantReadWriteLock()

    private val codeExpiryTime: Long = plugin.config.getLong("linking.code_expiry_minutes", 10) * 60 * 1000
    private val maxPendingCodes = plugin.config.getInt("linking.max_pending_codes", 1000)
    private val cleanupTask: Int

    // Для генерации криптографически стойких кодов
    private val secureRandom = SecureRandom()

    init {
        loadLinks()

        // Запускаем задачу очистки каждые 30 секунд
        cleanupTask = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, {
            cleanupExpiredCodes()
        }, 600L, 600L)
    }

    /**
     * Загружает привязки из файла с улучшенной валидацией
     */
    private fun loadLinks() {
        if (!linksFile.exists()) {
            plugin.logger.info("Файл привязок не существует, создаем новый")
            return
        }

        try {
            val config = YamlConfiguration.loadConfiguration(linksFile)
            val linksSection = config.getConfigurationSection("links")

            linksSection?.getKeys(false)?.forEach { uuidString ->
                try {
                    val uuid = UUID.fromString(uuidString)
                    val telegramId = linksSection.getLong(uuidString)

                    // Валидация данных
                    if (telegramId <= 0L) {
                        plugin.logger.warning("Некорректный Telegram ID для UUID $uuidString: $telegramId")
                        return@forEach
                    }

                    linkLock.write {
                        links[uuid] = telegramId
                        reverseLinks[telegramId] = uuid
                    }

                } catch (_: IllegalArgumentException) {
                    plugin.logger.warning("Неверный UUID в файле привязок: $uuidString")
                } catch (e: Exception) {
                    plugin.logger.warning("Ошибка загрузки привязки $uuidString: ${e.message}")
                }
            }

            plugin.logger.info("Загружено ${links.size} привязок из файла")

        } catch (e: Exception) {
            plugin.logger.severe("Критическая ошибка загрузки привязок: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Сохраняет привязки в файл с бэкапом
     */
    @Synchronized
    fun saveLinks() {
        try {
            if (!plugin.dataFolder.exists() && !plugin.dataFolder.mkdirs()) {
                plugin.logger.severe("Не удалось создать папку данных плагина")
                return
            }

            // Создаем бэкап перед сохранением
            if (linksFile.exists()) {
                val backupFile = File(plugin.dataFolder, "links.yml.backup")
                linksFile.copyTo(backupFile, overwrite = true)
            }

            val config = YamlConfiguration()
            val linksSection = config.createSection("links")

            linkLock.read {
                links.forEach { (uuid, telegramId) ->
                    linksSection.set(uuid.toString(), telegramId)
                }
            }

            // Добавляем метаданные
            config.set("metadata.saved_at", System.currentTimeMillis())
            config.set("metadata.total_links", links.size)
            config.set("metadata.plugin_version", plugin.description.version)

            config.save(linksFile)

        } catch (e: Exception) {
            plugin.logger.severe("Критическая ошибка сохранения привязок: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Генерирует криптографически стойкий код привязки
     */
    fun generateLinkingCode(playerUuid: UUID, playerName: String): String {
        // Проверяем лимит ожидающих кодов
        if (pendingCodes.size >= maxPendingCodes) {
            cleanupExpiredCodes()
            if (pendingCodes.size >= maxPendingCodes) {
                plugin.logger.warning("Достигнут лимит ожидающих кодов: $maxPendingCodes")
                // Удаляем самые старые коды
                pendingCodes.entries
                    .sortedBy { it.value.createdAt }
                    .take(100)
                    .forEach { pendingCodes.remove(it.key) }
            }
        }

        // Генерируем уникальный код
        var code: String
        var attempts = 0
        do {
            code = generateSecureRandomCode()
            attempts++

            if (attempts > 100) {
                plugin.logger.severe("Не удалось сгенерировать уникальный код после 100 попыток")
                throw RuntimeException("Невозможно сгенерировать уникальный код")
            }
        } while (pendingCodes.containsKey(code))

        val expiryTime = System.currentTimeMillis() + codeExpiryTime
        val pendingCode = PendingCode(code, playerUuid, playerName, expiryTime)

        pendingCodes[code] = pendingCode

        plugin.logger.info("Сгенерирован код привязки $code для игрока $playerName ($playerUuid)")
        return code
    }

    /**
     * Верифицирует код и создает привязку с улучшенной безопасностью
     */
    fun verifyCode(code: String, telegramId: Long): Boolean {
        // Валидация входных данных
        if (code.isBlank() || code.length != 8 || !code.matches(Regex("^[A-Z0-9]{8}$"))) {
            plugin.logger.warning("Попытка верификации с некорректным кодом: '$code'")
            return false
        }

        if (telegramId <= 0) {
            plugin.logger.warning("Попытка верификации с некорректным Telegram ID: $telegramId")
            return false
        }

        val pendingCode = pendingCodes[code]
        if (pendingCode == null) {
            plugin.logger.info("Попытка верификации несуществующего кода: $code (Telegram ID: $telegramId)")
            return false
        }

        if (System.currentTimeMillis() > pendingCode.expiryTime) {
            pendingCodes.remove(code)
            plugin.logger.info("Попытка верификации истекшего кода: $code (Telegram ID: $telegramId)")
            return false
        }

        return linkLock.write {
            try {
                // Удаляем старую привязку для этого Telegram аккаунта
                val oldUuid = reverseLinks.remove(telegramId)
                if (oldUuid != null) {
                    links.remove(oldUuid)
                    plugin.logger.info("Удалена старая привязка для Telegram ID $telegramId: $oldUuid")
                }

                // Удаляем старую привязку для этого игрока
                val oldTelegramId = links.remove(pendingCode.playerUuid)
                if (oldTelegramId != null) {
                    reverseLinks.remove(oldTelegramId)
                    plugin.logger.info("Удалена старая привязка для игрока ${pendingCode.playerUuid}: $oldTelegramId")
                }

                // Создаем новую привязку
                links[pendingCode.playerUuid] = telegramId
                reverseLinks[telegramId] = pendingCode.playerUuid
                pendingCodes.remove(code)

                plugin.logger.info("АУДИТ: Создана привязка ${pendingCode.playerName} (${pendingCode.playerUuid}) -> Telegram ID $telegramId")

                // Автоматически сохраняем
                plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                    saveLinks()
                })

                true
            } catch (e: Exception) {
                plugin.logger.severe("Ошибка при создании привязки: ${e.message}")
                e.printStackTrace()
                false
            }
        }
    }

    /**
     * Получает Telegram ID по UUID игрока (thread-safe)
     */
    fun getLinkedTelegram(playerUuid: UUID): Long? {
        return linkLock.read { links[playerUuid] }
    }

    /**
     * Получает UUID игрока по Telegram ID (thread-safe)
     */
    fun getLinkedPlayer(telegramId: Long): UUID? {
        if (telegramId <= 0) return null
        return linkLock.read { reverseLinks[telegramId] }
    }

    /**
     * Получает имя привязанного игрока по Telegram ID
     */
    fun getLinkedPlayerName(telegramId: Long): String? {
        val uuid = getLinkedPlayer(telegramId) ?: return null
        return plugin.server.getOfflinePlayer(uuid).name
    }

    /**
     * Отвязывает аккаунт игрока (thread-safe)
     */
    fun unlinkPlayer(playerUuid: UUID): Boolean {
        return linkLock.write {
            val telegramId = links.remove(playerUuid)
            if (telegramId != null) {
                reverseLinks.remove(telegramId)
                plugin.logger.info("АУДИТ: Отвязан аккаунт игрока: $playerUuid -> Telegram ID $telegramId")

                plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                    saveLinks()
                })
                true
            } else {
                false
            }
        }
    }

    /**
     * Отвязывает аккаунт по Telegram ID (thread-safe)
     */
    fun unlinkTelegram(telegramId: Long): Boolean {
        if (telegramId <= 0) return false

        return linkLock.write {
            val playerUuid = reverseLinks.remove(telegramId)
            if (playerUuid != null) {
                links.remove(playerUuid)
                plugin.logger.info("АУДИТ: Отвязан аккаунт Telegram ID: $telegramId -> $playerUuid")

                plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                    saveLinks()
                })
                true
            } else {
                false
            }
        }
    }

    /**
     * Исправленная очистка истекших кодов
     */
    fun cleanupExpiredCodes() {
        val currentTime = System.currentTimeMillis()
        val expiredCodes = mutableListOf<String>()

        pendingCodes.forEach { (code, pendingCode) ->
            if (currentTime > pendingCode.expiryTime) {
                expiredCodes.add(code)
            }
        }

        // ИСПРАВЛЕНО: теперь действительно удаляем истекшие коды
        expiredCodes.forEach { code ->
            pendingCodes.remove(code)
        }
    }

    /**
     * Получает информацию о коде для API с валидацией
     */
    fun getCodeInfo(code: String): PendingCode? {
        if (code.isBlank() || code.length != 8) return null

        val pendingCode = pendingCodes[code] ?: return null

        // Проверяем актуальность
        if (System.currentTimeMillis() > pendingCode.expiryTime) {
            pendingCodes.remove(code)
            return null
        }

        return pendingCode
    }

    /**
     * Получает расширенную статистику
     */
    fun getStats(): Map<String, Any> {
        val currentTime = System.currentTimeMillis()

        return linkLock.read {
            mapOf(
                "total_links" to links.size,
                "pending_codes" to pendingCodes.size,
                "active_codes" to pendingCodes.values.count { currentTime <= it.expiryTime },
                "expired_codes" to pendingCodes.values.count { currentTime > it.expiryTime },
                "last_cleanup" to currentTime
            )
        }
    }

    /**
     * Генерирует криптографически стойкий код
     */
    private fun generateSecureRandomCode(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val result = StringBuilder(8)

        repeat(8) {
            result.append(chars[secureRandom.nextInt(chars.length)])
        }

        return result.toString()
    }

    /**
     * Очистка при отключении плагина
     */
    fun shutdown() {
        plugin.server.scheduler.cancelTask(cleanupTask)
        saveLinks()
        plugin.logger.info("LinkingManager успешно остановлен")
    }
}