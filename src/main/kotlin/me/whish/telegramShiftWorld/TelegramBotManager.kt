package me.whish.telegramShiftWorld

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class TelegramBotManager(private val plugin: TelegramShiftWorld) {

    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var isRunning = false
    private var updateOffset = 0

    private val botToken: String
        get() = plugin.configManager.getBotToken()

    private val baseUrl: String
        get() = "https://api.telegram.org/bot$botToken"

    fun startBot(): Boolean {
        if (botToken.isEmpty() || botToken.equals("YOUR_BOT_TOKEN_HERE", ignoreCase = true)) {
            plugin.logger.severe("Telegram бот токен не установлен в конфигурации!")
            return false
        }

        // Проверяем токен
        if (!checkBotToken()) {
            plugin.logger.severe("Неверный Telegram бот токен!")
            return false
        }

        isRunning = true

        // Запускаем бота в отдельном потоке
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            startPolling()
        })

        plugin.logger.info("Telegram бот запущен успешно!")
        return true
    }

    fun stopBot() {
        isRunning = false
        plugin.logger.info("Telegram бот остановлен")
    }

    private fun checkBotToken(): Boolean {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/getMe")
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: return false

            val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
            jsonResponse.get("ok")?.asBoolean == true
        } catch (e: Exception) {
            plugin.logger.severe("Ошибка проверки токена бота: ${e.message}")
            false
        }
    }

    private fun startPolling() {
        plugin.debug("Запущен polling для Telegram бота")

        while (isRunning) {
            try {
                val updates = getUpdates()

                for (update in updates) {
                    processUpdate(update)
                    updateOffset = update.get("update_id").asInt + 1
                }

                // Небольшая пауза между запросами
                Thread.sleep(1000)

            } catch (e: Exception) {
                plugin.logger.severe("Ошибка в polling: ${e.message}")
                Thread.sleep(5000) // Ждем дольше при ошибке
            }
        }

        plugin.debug("Polling остановлен")
    }

    private fun getUpdates(): List<JsonObject> {
        val request = Request.Builder()
            .url("$baseUrl/getUpdates?offset=$updateOffset&timeout=30")
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: return emptyList()

        val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)

        if (jsonResponse.get("ok")?.asBoolean != true) {
            return emptyList()
        }

        val result = jsonResponse.getAsJsonArray("result")
        return result.map { it.asJsonObject }
    }

    private fun processUpdate(update: JsonObject) {
        try {
            if (!update.has("message")) return

            val message = update.getAsJsonObject("message")
            if (!message.has("text")) return

            val text = message.get("text").asString
            val chatId = message.getAsJsonObject("chat").get("id").asLong
            val userId = message.getAsJsonObject("from").get("id").asLong
            val username = if (message.getAsJsonObject("from").has("username")) {
                message.getAsJsonObject("from").get("username").asString
            } else {
                "Неизвестно"
            }

            plugin.debug("Получено сообщение от $username ($userId): $text")

            when {
                text.startsWith("/start") -> handleStartCommand(chatId, userId, username)
                text.startsWith("/link ") -> handleLinkCommand(chatId, userId, username, text)
                text.startsWith("/help") -> handleHelpCommand(chatId, userId, username)
                text.startsWith("/status") -> handleStatusCommand(chatId, userId, username)
                else -> handleUnknownCommand(chatId, userId, username)
            }

        } catch (e: Exception) {
            plugin.logger.severe("Ошибка обработки обновления: ${e.message}")
        }
    }

    private fun handleStartCommand(chatId: Long, userId: Long, username: String) {
        val linkedUuid = plugin.linkingManager.getTelegramPlayerUuid(userId)

        val message = if (linkedUuid != null) {
            val playerName = plugin.server.getOfflinePlayer(linkedUuid).name ?: "Неизвестно"
            """
            👋 Добро пожаловать!
            
            ✅ Ваш аккаунт уже привязан к игроку: **$playerName**
            
            📋 Доступные команды:
            /help - показать помощь
            /status - проверить статус привязки
            """.trimIndent()
        } else {
            """
            👋 Добро пожаловать в бота привязки аккаунтов!
            
            ❌ Ваш Telegram не привязан к игровому аккаунту.
            
            🎮 Чтобы привязать аккаунт:
            1. Зайдите на сервер
            2. Получите код привязки (вас кикнет с кодом)
            3. Отправьте команду: /link ВАШ_КОД
            
            📋 Доступные команды:
            /help - показать помощь
            /status - проверить статус привязки
            """.trimIndent()
        }

        sendMessage(chatId, message)
    }

    private fun handleLinkCommand(chatId: Long, userId: Long, username: String, text: String) {
        val parts = text.split(" ")
        if (parts.size != 2) {
            sendMessage(chatId, "❌ Неверный формат команды!\n\nИспользуйте: /link ВАШ_КОД")
            return
        }

        val code = parts[1].uppercase()

        // Проверяем не привязан ли уже аккаунт
        val existingUuid = plugin.linkingManager.getTelegramPlayerUuid(userId)
        if (existingUuid != null) {
            val playerName = plugin.server.getOfflinePlayer(existingUuid).name ?: "Неизвестно"
            sendMessage(chatId, "⚠️ Ваш Telegram уже привязан к игроку: **$playerName**")
            return
        }

        // Выполняем привязку в основном потоке сервера
        plugin.server.scheduler.runTask(plugin, Runnable {
            val success = plugin.linkingManager.verifyCode(code, userId)

            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                if (success) {
                    val playerUuid = plugin.linkingManager.getTelegramPlayerUuid(userId)
                    val playerName = if (playerUuid != null) {
                        plugin.server.getOfflinePlayer(playerUuid).name ?: "Неизвестно"
                    } else {
                        "Неизвестно"
                    }

                    sendMessage(chatId, """
                        ✅ **Привязка успешна!**
                        
                        🎮 Ваш Telegram привязан к игроку: **$playerName**
                        
                        Теперь вы можете заходить на сервер!
                    """.trimIndent())

                    plugin.logger.info("Пользователь $username ($userId) привязал аккаунт игрока $playerName")
                } else {
                    sendMessage(chatId, """
                        ❌ **Неверный или истекший код!**
                        
                        🔄 Попробуйте еще раз:
                        1. Зайдите на сервер
                        2. Получите новый код привязки
                        3. Отправьте: /link НОВЫЙ_КОД
                    """.trimIndent())
                }
            })
        })
    }

    private fun handleHelpCommand(chatId: Long, userId: Long, username: String) {
        val message = """
        🤖 **Помощь по боту привязки аккаунтов**
        
        📋 **Доступные команды:**
        /start - приветствие и информация
        /link КОД - привязать аккаунт по коду
        /help - показать эту помощь
        /status - проверить статус привязки
        
        🎮 **Как привязать аккаунт:**
        1. Попробуйте зайти на сервер
        2. Вас кикнет с кодом привязки
        3. Отправьте боту: /link ВАШ_КОД
        4. Заходите на сервер!
        
        ⚠️ Коды действительны только 10 минут!
        """.trimIndent()

        sendMessage(chatId, message)
    }

    private fun handleStatusCommand(chatId: Long, userId: Long, username: String) {
        val linkedUuid = plugin.linkingManager.getTelegramPlayerUuid(userId)

        val message = if (linkedUuid != null) {
            val offlinePlayer = plugin.server.getOfflinePlayer(linkedUuid)
            val playerName = offlinePlayer.name ?: "Неизвестно"
            val isOnline = plugin.server.getPlayer(linkedUuid) != null
            val onlineStatus = if (isOnline) "🟢 В сети" else "🔴 Не в сети"

            """
            ✅ **Статус привязки: Активна**
            
            🎮 Привязанный игрок: **$playerName**
            📊 Статус игрока: $onlineStatus
            🆔 UUID: `${linkedUuid}`
            """.trimIndent()
        } else {
            """
            ❌ **Статус привязки: Не привязан**
            
            🎮 Ваш Telegram не привязан к игровому аккаунту.
            
            Для привязки используйте команду /help
            """.trimIndent()
        }

        sendMessage(chatId, message)
    }

    private fun handleUnknownCommand(chatId: Long, userId: Long, username: String) {
        sendMessage(chatId, """
            ❓ Неизвестная команда!
            
            Используйте /help для просмотра доступных команд.
        """.trimIndent())
    }

    fun sendMessage(chatId: Long, text: String) {
        try {
            val json = JsonObject().apply {
                addProperty("chat_id", chatId)
                addProperty("text", text)
                addProperty("parse_mode", "Markdown")
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = gson.toJson(json).toRequestBody(mediaType)

            val request = Request.Builder()
                .url("$baseUrl/sendMessage")
                .post(requestBody)
                .build()

            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    plugin.logger.severe("Ошибка отправки сообщения в Telegram: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        plugin.logger.warning("Telegram API вернул ошибку: ${response.code}")
                    }
                    response.close()
                }
            })

        } catch (e: Exception) {
            plugin.logger.severe("Ошибка при отправке сообщения: ${e.message}")
        }
    }
}