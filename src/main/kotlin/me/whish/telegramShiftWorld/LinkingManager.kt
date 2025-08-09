package me.whish.telegramShiftWorld

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import me.whish.telegramShiftWorld.data.LinkData
import me.whish.telegramShiftWorld.data.PendingCode
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class LinkingManager(private val plugin: TelegramShiftWorld) {

    private val gson = Gson()
    private val linksFile = File(plugin.dataFolder, "links.json")

    // UUID игрока -> ID Telegram пользователя
    private val playerLinks = ConcurrentHashMap<UUID, Long>()

    // Код -> данные ожидающего кода
    private val pendingCodes = ConcurrentHashMap<String, PendingCode>()

    // ID Telegram пользователя -> UUID игрока (для быстрого поиска)
    private val reverseLinks = ConcurrentHashMap<Long, UUID>()

    fun loadLinks() {
        if (!linksFile.exists()) {
            plugin.debug("Файл links.json не существует, создаем новый")
            return
        }

        try {
            val type = object : TypeToken<Map<String, LinkData>>() {}.type
            val links: Map<String, LinkData> = gson.fromJson(FileReader(linksFile), type) ?: emptyMap()

            for ((uuidString, linkData) in links) {
                val uuid = UUID.fromString(uuidString)
                playerLinks[uuid] = linkData.telegramId
                reverseLinks[linkData.telegramId] = uuid
            }

            plugin.logger.info("Загружено ${playerLinks.size} привязок аккаунтов")
        } catch (e: Exception) {
            plugin.logger.severe("Ошибка при загрузке привязок: ${e.message}")
        }
    }

    fun saveLinks() {
        try {
            if (!plugin.dataFolder.exists()) {
                plugin.dataFolder.mkdirs()
            }

            val linksToSave = mutableMapOf<String, LinkData>()
            for ((uuid, telegramId) in playerLinks) {
                linksToSave[uuid.toString()] = LinkData(telegramId, System.currentTimeMillis())
            }

            FileWriter(linksFile).use { writer ->
                gson.toJson(linksToSave, writer)
            }

            plugin.debug("Сохранено ${playerLinks.size} привязок")
        } catch (e: Exception) {
            plugin.logger.severe("Ошибка при сохранении привязок: ${e.message}")
        }
    }

    fun isPlayerLinked(playerUuid: UUID): Boolean {
        return playerLinks.containsKey(playerUuid)
    }

    fun getPlayerTelegramId(playerUuid: UUID): Long? {
        return playerLinks[playerUuid]
    }

    fun getTelegramPlayerUuid(telegramId: Long): UUID? {
        return reverseLinks[telegramId]
    }

    fun linkPlayer(playerUuid: UUID, telegramId: Long) {
        // Удаляем старые привязки если есть
        val oldTelegramId = playerLinks[playerUuid]
        if (oldTelegramId != null) {
            reverseLinks.remove(oldTelegramId)
        }

        val oldPlayerUuid = reverseLinks[telegramId]
        if (oldPlayerUuid != null) {
            playerLinks.remove(oldPlayerUuid)
        }

        // Добавляем новую привязку
        playerLinks[playerUuid] = telegramId
        reverseLinks[telegramId] = playerUuid

        plugin.debug("Привязан игрок $playerUuid к Telegram ID $telegramId")

        // Сохраняем асинхронно
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            saveLinks()
        })
    }

    fun unlinkPlayer(playerUuid: UUID): Boolean {
        val telegramId = playerLinks.remove(playerUuid)
        if (telegramId != null) {
            reverseLinks.remove(telegramId)
            plugin.debug("Отвязан игрок $playerUuid от Telegram ID $telegramId")

            // Сохраняем асинхронно
            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                saveLinks()
            })
            return true
        }
        return false
    }

    fun generateCode(playerUuid: UUID, playerName: String): String {
        // Удаляем старые коды для этого игрока
        pendingCodes.entries.removeAll { it.value.playerUuid == playerUuid }

        // Генерируем новый код
        val code = generateRandomCode()
        val expiryTime = System.currentTimeMillis() + (plugin.configManager.getCodeExpiryMinutes() * 60 * 1000)

        pendingCodes[code] = PendingCode(playerUuid, playerName, expiryTime)

        plugin.debug("Сгенерирован код $code для игрока $playerName ($playerUuid)")

        return code
    }

    fun verifyCode(code: String, telegramId: Long): Boolean {
        val pendingCode = pendingCodes[code] ?: return false

        // Проверяем не истек ли код
        if (System.currentTimeMillis() > pendingCode.expiryTime) {
            pendingCodes.remove(code)
            plugin.debug("Код $code истек")
            return false
        }

        // Привязываем аккаунты
        linkPlayer(pendingCode.playerUuid, telegramId)
        pendingCodes.remove(code)

        plugin.debug("Код $code успешно использован для привязки")
        return true
    }

    fun cleanupExpiredCodes() {
        val currentTime = System.currentTimeMillis()
        val expiredCodes = pendingCodes.filterValues { it.expiryTime < currentTime }.keys

        for (expiredCode in expiredCodes) {
            pendingCodes.remove(expiredCode)
        }

        if (expiredCodes.isNotEmpty()) {
            plugin.debug("Удалено ${expiredCodes.size} истекших кодов")
        }
    }

    private fun generateRandomCode(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val length = plugin.configManager.getCodeLength()

        return (1..length)
            .map { chars[Random.nextInt(chars.length)] }
            .joinToString("")
    }

    fun getLinkedPlayersCount(): Int = playerLinks.size

    fun getPendingCodesCount(): Int = pendingCodes.size

}