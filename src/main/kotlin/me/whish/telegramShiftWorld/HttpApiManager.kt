package me.whish.telegramShiftWorld

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class HttpApiManager(private val plugin: TelegramShiftWorld) {
    private var server: HttpServer? = null
    private val gson = Gson()
    private val port = plugin.config.getInt("api.port", 8080)
    private val apiKey = getOrGenerateApiKey()

    // Rate limiting для API
    private val requestCounts = ConcurrentHashMap<String, MutableList<Long>>()
    private val maxRequestsPerMinute = plugin.config.getInt("api.rate_limit.requests_per_minute", 60)
    private val rateLimitWindow = plugin.config.getLong("api.rate_limit.window_ms", 60000L)

    private fun getOrGenerateApiKey(): String {
        val configKey = plugin.config.getString("api.key")

        if (configKey.isNullOrBlank() || configKey == "your-secret-api-key-here" || configKey == "default-secret-key") {
            // Генерируем новый безопасный ключ
            val newKey = generateSecureApiKey()
            plugin.logger.warning("Сгенерирован новый API ключ. Обновите конфигурацию!")
            plugin.logger.warning("Новый API ключ: $newKey")

            // Обновляем конфигурацию
            plugin.config.set("api.key", newKey)
            plugin.saveConfig()

            return newKey
        }

        if (configKey.length < 32) {
            plugin.logger.warning("API ключ слишком короткий! Рекомендуется использовать ключ длиной не менее 32 символов")
        }

        return configKey
    }

    private fun generateSecureApiKey(): String {
        val secureRandom = SecureRandom()
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..64).map { chars[secureRandom.nextInt(chars.length)] }.joinToString("")
    }

    fun startServer() {
        try {
            server = HttpServer.create(InetSocketAddress(port), 0)

            // API endpoints с улучшенной безопасностью
            server?.createContext("/api/codes", CodesHandler())
            server?.createContext("/api/verify", VerifyHandler())
            server?.createContext("/api/links", LinksHandler())
            server?.createContext("/api/health", HealthHandler())
            server?.createContext("/api/stats", StatsHandler())

            server?.executor = Executors.newFixedThreadPool(
                plugin.config.getInt("api.thread_pool_size", 4)
            )
            server?.start()

            plugin.logger.info("🔐 HTTP API сервер запущен на порту $port")
            plugin.logger.info("🔑 API ключ настроен (${apiKey.length} символов)")

        } catch (e: IOException) {
            plugin.logger.severe("Не удалось запустить HTTP API сервер: ${e.message}")
        }
    }

    fun stopServer() {
        server?.stop(0)
        plugin.logger.info("HTTP API сервер остановлен")
    }

    private fun validateApiKey(exchange: HttpExchange): Boolean {
        val authHeader = exchange.requestHeaders.getFirst("Authorization")
        val expectedAuth = "Bearer $apiKey"

        // Защита от timing attacks
        return authHeader != null && constantTimeEquals(authHeader, expectedAuth)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false

        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }

    private fun isRateLimited(clientIp: String): Boolean {
        val now = System.currentTimeMillis()
        val requests = requestCounts.computeIfAbsent(clientIp) { mutableListOf() }

        // Очищаем старые запросы
        requests.removeAll { now - it > rateLimitWindow }

        if (requests.size >= maxRequestsPerMinute) {
            return true
        }

        requests.add(now)
        return false
    }

    private fun getClientIp(exchange: HttpExchange): String {
        // Проверяем заголовки для получения реального IP
        val xForwardedFor = exchange.requestHeaders.getFirst("X-Forwarded-For")
        val xRealIp = exchange.requestHeaders.getFirst("X-Real-IP")

        return when {
            xForwardedFor != null -> xForwardedFor.split(",")[0].trim()
            xRealIp != null -> xRealIp
            else -> exchange.remoteAddress.address.hostAddress
        }
    }

    private fun sendResponse(exchange: HttpExchange, code: Int, response: String) {
        // Добавляем безопасные заголовки
        exchange.responseHeaders.apply {
            set("X-Content-Type-Options", "nosniff")
            set("X-Frame-Options", "DENY")
            set("X-XSS-Protection", "1; mode=block")
            set("Server", "TelegramShiftWorld/${plugin.description.version}")
        }

        exchange.sendResponseHeaders(code, response.toByteArray().size.toLong())
        exchange.responseBody.use { it.write(response.toByteArray()) }
    }

    private fun sendJsonResponse(exchange: HttpExchange, code: Int, data: Any) {
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        val json = gson.toJson(data)
        sendResponse(exchange, code, json)
    }

    private fun sendErrorResponse(exchange: HttpExchange, code: Int, message: String, details: String? = null) {
        val errorData = mutableMapOf<String, Any>(
            "error" to message,
            "timestamp" to System.currentTimeMillis(),
            "status" to code
        )

        if (details != null) {
            errorData["details"] = details
        }

        sendJsonResponse(exchange, code, errorData)
    }

    private fun logApiAccess(exchange: HttpExchange, endpoint: String, success: Boolean, details: String = "") {
        val clientIp = getClientIp(exchange)
        val method = exchange.requestMethod
        val status = if (success) "SUCCESS" else "FAILED"

        plugin.logger.info("API [$status] $clientIp $method $endpoint $details")
    }

    // Валидация UUID
    private fun validateUuid(uuidString: String?): UUID? {
        if (uuidString.isNullOrBlank()) return null
        return try {
            UUID.fromString(uuidString)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    // Обработчик для создания кодов привязки
    inner class CodesHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val clientIp = getClientIp(exchange)

            if (isRateLimited(clientIp)) {
                logApiAccess(exchange, "/api/codes", false, "RATE_LIMITED")
                sendErrorResponse(exchange, 429, "Too Many Requests", "Превышен лимит запросов")
                return
            }

            if (!validateApiKey(exchange)) {
                logApiAccess(exchange, "/api/codes", false, "UNAUTHORIZED")
                sendErrorResponse(exchange, 401, "Unauthorized", "Неверный API ключ")
                return
            }

            when (exchange.requestMethod) {
                "POST" -> {
                    try {
                        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)

                        if (body.length > 1024) { // Лимит размера запроса
                            sendErrorResponse(exchange, 413, "Request Entity Too Large")
                            return
                        }

                        val request = gson.fromJson(body, JsonObject::class.java)
                        val playerName = request["player_name"]?.asString?.trim()
                        val playerUuid = validateUuid(request["player_uuid"]?.asString?.trim())

                        // Улучшенная валидация
                        if (playerName.isNullOrBlank() || playerName.length > 16 ||
                            !playerName.matches(Regex("^[a-zA-Z0-9_]{3,16}$"))) {
                            logApiAccess(exchange, "/api/codes", false, "INVALID_PLAYER_NAME")
                            sendErrorResponse(exchange, 400, "Invalid player name",
                                "Имя игрока должно содержать 3-16 символов (a-z, A-Z, 0-9, _)")
                            return
                        }

                        if (playerUuid == null) {
                            logApiAccess(exchange, "/api/codes", false, "INVALID_UUID")
                            sendErrorResponse(exchange, 400, "Invalid UUID format")
                            return
                        }

                        val linkingManager = plugin.linkingManager
                        val code = linkingManager.generateLinkingCode(playerUuid, playerName)

                        logApiAccess(exchange, "/api/codes", true, "CODE_GENERATED for $playerName")
                        sendJsonResponse(exchange, 200, mapOf(
                            "code" to code,
                            "expires_at" to (System.currentTimeMillis() + 600000), // 10 минут
                            "expires_in_seconds" to 600,
                            "player_name" to playerName,
                            "player_uuid" to playerUuid.toString()
                        ))

                    } catch (_: JsonSyntaxException) {
                        logApiAccess(exchange, "/api/codes", false, "INVALID_JSON")
                        sendErrorResponse(exchange, 400, "Invalid JSON format")
                    } catch (e: Exception) {
                        plugin.logger.warning("Ошибка создания кода: ${e.message}")
                        logApiAccess(exchange, "/api/codes", false, "INTERNAL_ERROR")
                        sendErrorResponse(exchange, 500, "Internal server error")
                    }
                }
                else -> sendErrorResponse(exchange, 405, "Method not allowed")
            }
        }
    }

    // Обработчик для верификации кодов
    inner class VerifyHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val clientIp = getClientIp(exchange)

            if (isRateLimited(clientIp)) {
                logApiAccess(exchange, "/api/verify", false, "RATE_LIMITED")
                sendErrorResponse(exchange, 429, "Too Many Requests")
                return
            }

            if (!validateApiKey(exchange)) {
                logApiAccess(exchange, "/api/verify", false, "UNAUTHORIZED")
                sendErrorResponse(exchange, 401, "Unauthorized")
                return
            }

            when (exchange.requestMethod) {
                "POST" -> {
                    try {
                        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)

                        if (body.length > 1024) {
                            sendErrorResponse(exchange, 413, "Request Entity Too Large")
                            return
                        }

                        val request = gson.fromJson(body, JsonObject::class.java)
                        val code = request["code"]?.asString?.trim()?.uppercase()
                        val telegramId = request["telegram_id"]?.asLong

                        // Улучшенная валидация
                        if (code.isNullOrBlank() || !code.matches(Regex("^[A-Z0-9]{8}$"))) {
                            logApiAccess(exchange, "/api/verify", false, "INVALID_CODE_FORMAT")
                            sendErrorResponse(exchange, 400, "Invalid code format",
                                "Код должен содержать 8 символов (A-Z, 0-9)")
                            return
                        }

                        if (telegramId == null || telegramId <= 0) {
                            logApiAccess(exchange, "/api/verify", false, "INVALID_TELEGRAM_ID")
                            sendErrorResponse(exchange, 400, "Invalid telegram_id",
                                "Telegram ID должен быть положительным числом")
                            return
                        }

                        val linkingManager = plugin.linkingManager
                        val success = linkingManager.verifyCode(code, telegramId)

                        if (success) {
                            val playerName = linkingManager.getLinkedPlayerName(telegramId)
                            logApiAccess(exchange, "/api/verify", true, "VERIFIED for TG:$telegramId")

                            sendJsonResponse(exchange, 200, mapOf(
                                "success" to true,
                                "player_name" to playerName,
                                "telegram_id" to telegramId,
                                "linked_at" to System.currentTimeMillis()
                            ))
                        } else {
                            logApiAccess(exchange, "/api/verify", false, "INVALID_OR_EXPIRED_CODE")
                            sendJsonResponse(exchange, 400, mapOf(
                                "success" to false,
                                "error" to "Invalid or expired code",
                                "code" to code
                            ))
                        }

                    } catch (_: JsonSyntaxException) {
                        logApiAccess(exchange, "/api/verify", false, "INVALID_JSON")
                        sendErrorResponse(exchange, 400, "Invalid JSON format")
                    } catch (e: Exception) {
                        plugin.logger.warning("Ошибка верификации кода: ${e.message}")
                        logApiAccess(exchange, "/api/verify", false, "INTERNAL_ERROR")
                        sendErrorResponse(exchange, 500, "Internal server error")
                    }
                }
                else -> sendErrorResponse(exchange, 405, "Method not allowed")
            }
        }
    }

    // Обработчик для получения привязок
    inner class LinksHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val clientIp = getClientIp(exchange)

            if (isRateLimited(clientIp)) {
                logApiAccess(exchange, "/api/links", false, "RATE_LIMITED")
                sendErrorResponse(exchange, 429, "Too Many Requests")
                return
            }

            if (!validateApiKey(exchange)) {
                logApiAccess(exchange, "/api/links", false, "UNAUTHORIZED")
                sendErrorResponse(exchange, 401, "Unauthorized")
                return
            }

            when (exchange.requestMethod) {
                "GET" -> {
                    val query = exchange.requestURI.query

                    if (query != null && query.startsWith("telegram_id=")) {
                        val telegramIdStr = query.substringAfter("telegram_id=")
                        val telegramId = telegramIdStr.toLongOrNull()

                        if (telegramId == null || telegramId <= 0) {
                            logApiAccess(exchange, "/api/links", false, "INVALID_QUERY_PARAM")
                            sendErrorResponse(exchange, 400, "Invalid telegram_id parameter")
                            return
                        }

                        val linkingManager = plugin.linkingManager
                        val playerUuid = linkingManager.getLinkedPlayer(telegramId)
                        val playerName = linkingManager.getLinkedPlayerName(telegramId)

                        if (playerName != null && playerUuid != null) {
                            logApiAccess(exchange, "/api/links", true, "FOUND for TG:$telegramId")
                            sendJsonResponse(exchange, 200, mapOf(
                                "linked" to true,
                                "player_name" to playerName,
                                "player_uuid" to playerUuid.toString(),
                                "telegram_id" to telegramId
                            ))
                        } else {
                            logApiAccess(exchange, "/api/links", true, "NOT_FOUND for TG:$telegramId")
                            sendJsonResponse(exchange, 404, mapOf(
                                "linked" to false,
                                "telegram_id" to telegramId,
                                "error" to "No linked player found"
                            ))
                        }
                        return
                    }

                    // Если нет параметров, возвращаем общую статистику
                    val stats = plugin.linkingManager.getStats()
                    logApiAccess(exchange, "/api/links", true, "STATS_REQUEST")
                    sendJsonResponse(exchange, 200, mapOf(
                        "total_links" to stats["total_links"],
                        "endpoint" to "links",
                        "usage" to "Add ?telegram_id=ID parameter to check specific link"
                    ))
                }

                "DELETE" -> {
                    // Добавляем возможность отвязки через API
                    val query = exchange.requestURI.query

                    if (query != null && query.startsWith("telegram_id=")) {
                        val telegramIdStr = query.substringAfter("telegram_id=")
                        val telegramId = telegramIdStr.toLongOrNull()

                        if (telegramId == null || telegramId <= 0) {
                            sendErrorResponse(exchange, 400, "Invalid telegram_id parameter")
                            return
                        }

                        val linkingManager = plugin.linkingManager
                        val success = linkingManager.unlinkTelegram(telegramId)

                        if (success) {
                            logApiAccess(exchange, "/api/links", true, "UNLINKED TG:$telegramId")
                            sendJsonResponse(exchange, 200, mapOf(
                                "success" to true,
                                "message" to "Successfully unlinked",
                                "telegram_id" to telegramId
                            ))
                        } else {
                            logApiAccess(exchange, "/api/links", false, "UNLINK_FAILED TG:$telegramId")
                            sendJsonResponse(exchange, 404, mapOf(
                                "success" to false,
                                "error" to "No linked account found"
                            ))
                        }
                        return
                    }

                    sendErrorResponse(exchange, 400, "Missing telegram_id parameter")
                }

                else -> sendErrorResponse(exchange, 405, "Method not allowed")
            }
        }
    }

    // Расширенный health check
    inner class HealthHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val stats = plugin.linkingManager.getStats()
            val health = mapOf(
                "status" to "OK",
                "plugin" to mapOf(
                    "name" to plugin.description.name,
                    "version" to plugin.description.version,
                    "authors" to plugin.description.authors
                ),
                "server" to mapOf(
                    "version" to plugin.server.version,
                    "online_players" to plugin.server.onlinePlayers.size,
                    "max_players" to plugin.server.maxPlayers
                ),
                "api" to mapOf(
                    "port" to port,
                    "rate_limit" to mapOf(
                        "requests_per_minute" to maxRequestsPerMinute,
                        "window_ms" to rateLimitWindow
                    )
                ),
                "statistics" to stats,
                "timestamp" to System.currentTimeMillis(),
                "uptime" to System.currentTimeMillis() - (stats["uptime_minutes"] as? Long ?: 0L) * 60000
            )

            sendJsonResponse(exchange, 200, health)
        }
    }

    // Новый обработчик статистики
    inner class StatsHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (!validateApiKey(exchange)) {
                sendErrorResponse(exchange, 401, "Unauthorized")
                return
            }

            when (exchange.requestMethod) {
                "GET" -> {
                    val stats = plugin.linkingManager.getStats()
                    val detailedStats = stats.toMutableMap()

                    // Добавляем дополнительную информацию
                    detailedStats["server_info"] = mapOf(
                        "online_players" to plugin.server.onlinePlayers.size,
                        "max_players" to plugin.server.maxPlayers,
                    )

                    detailedStats["api_info"] = mapOf(
                        "active_connections" to requestCounts.size,
                        "port" to port
                    )

                    sendJsonResponse(exchange, 200, detailedStats)
                }
                else -> sendErrorResponse(exchange, 405, "Method not allowed")
            }
        }
    }

    // Периодическая очистка rate limit данных
    init {
        plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, {
            val cutoffTime = System.currentTimeMillis() - rateLimitWindow
            requestCounts.entries.removeAll { (_, requests) ->
                requests.removeAll { it < cutoffTime }
                requests.isEmpty()
            }
        }, 1200L, 1200L) // Каждую минуту
    }
}