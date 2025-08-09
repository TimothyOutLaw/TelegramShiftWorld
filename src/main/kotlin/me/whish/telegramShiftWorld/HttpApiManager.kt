package me.whish.telegramShiftWorld

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.Executors

class HttpApiManager(private val plugin: TelegramShiftWorld) {
    private var server: HttpServer? = null
    private val gson = Gson()
    private val port = plugin.config.getInt("api.port", 8080)
    private val apiKey = plugin.config.getString("api.key", "default-secret-key")

    fun startServer() {
        try {
            server = HttpServer.create(InetSocketAddress(port), 0)

            // API endpoints
            server?.createContext("/api/codes", CodesHandler())
            server?.createContext("/api/verify", VerifyHandler())
            server?.createContext("/api/links", LinksHandler())
            server?.createContext("/api/health", HealthHandler())

            server?.executor = Executors.newFixedThreadPool(4)
            server?.start()

            plugin.logger.info("HTTP API сервер запущен на порту $port")

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
        return authHeader == "Bearer $apiKey"
    }

    private fun sendResponse(exchange: HttpExchange, code: Int, response: String) {
        exchange.sendResponseHeaders(code, response.toByteArray().size.toLong())
        exchange.responseBody.use { it.write(response.toByteArray()) }
    }

    private fun sendJsonResponse(exchange: HttpExchange, code: Int, data: Any) {
        exchange.responseHeaders.set("Content-Type", "application/json")
        val json = gson.toJson(data)
        sendResponse(exchange, code, json)
    }

    // Обработчик для создания кодов привязки
    inner class CodesHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (!validateApiKey(exchange)) {
                sendJsonResponse(exchange, 401, mapOf("error" to "Unauthorized"))
                return
            }

            when (exchange.requestMethod) {
                "POST" -> {
                    try {
                        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                        val request = gson.fromJson(body, JsonObject::class.java)

                        val playerName = request["player_name"]?.asString
                        val playerUuid = request["player_uuid"]?.asString

                        if (playerName.isNullOrBlank() || playerUuid.isNullOrBlank()) {
                            sendJsonResponse(exchange, 400, mapOf("error" to "Missing player_name or player_uuid"))
                            return
                        }

                        val linkingManager = plugin.linkingManager
                        val code = linkingManager.generateLinkingCode(
                            java.util.UUID.fromString(playerUuid as String?),
                            playerName
                        )

                        sendJsonResponse(exchange, 200, mapOf(
                            "code" to code,
                            "expires_at" to (System.currentTimeMillis() + 600000), // 10 минут
                            "player_name" to playerName
                        ))

                    } catch (e: Exception) {
                        plugin.logger.warning("Ошибка создания кода: ${e.message}")
                        sendJsonResponse(exchange, 500, mapOf("error" to "Internal server error"))
                    }
                }
                else -> sendJsonResponse(exchange, 405, mapOf("error" to "Method not allowed"))
            }
        }
    }

    // Обработчик для верификации кодов
    inner class VerifyHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (!validateApiKey(exchange)) {
                sendJsonResponse(exchange, 401, mapOf("error" to "Unauthorized"))
                return
            }

            when (exchange.requestMethod) {
                "POST" -> {
                    try {
                        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                        val request = gson.fromJson(body, JsonObject::class.java)

                        val code = request["code"]?.asString
                        val telegramId = request["telegram_id"]?.asLong

                        if (code.isNullOrBlank() || telegramId == null) {
                            sendJsonResponse(exchange, 400, mapOf("error" to "Missing code or telegram_id"))
                            return
                        }

                        val linkingManager = plugin.linkingManager
                        val success = linkingManager.verifyCode(code, telegramId)

                        if (success) {
                            val playerName = linkingManager.getLinkedPlayer(telegramId)
                            sendJsonResponse(exchange, 200, mapOf(
                                "success" to true,
                                "player_name" to playerName
                            ))
                        } else {
                            sendJsonResponse(exchange, 400, mapOf(
                                "success" to false,
                                "error" to "Invalid or expired code"
                            ))
                        }

                    } catch (e: Exception) {
                        plugin.logger.warning("Ошибка верификации кода: ${e.message}")
                        sendJsonResponse(exchange, 500, mapOf("error" to "Internal server error"))
                    }
                }
                else -> sendJsonResponse(exchange, 405, mapOf("error" to "Method not allowed"))
            }
        }
    }

    // Обработчик для получения привязок
    inner class LinksHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (!validateApiKey(exchange)) {
                sendJsonResponse(exchange, 401, mapOf("error" to "Unauthorized"))
                return
            }

            when (exchange.requestMethod) {
                "GET" -> {
                    val query = exchange.requestURI.query
                    if (query != null && query.startsWith("telegram_id=")) {
                        val telegramId = query.substringAfter("telegram_id=").toLongOrNull()
                        if (telegramId != null) {
                            val linkingManager = plugin.linkingManager
                            val playerName = linkingManager.getLinkedPlayer(telegramId)

                            if (playerName != null) {
                                sendJsonResponse(exchange, 200, mapOf(
                                    "linked" to true,
                                    "player_name" to playerName
                                ))
                            } else {
                                sendJsonResponse(exchange, 404, mapOf(
                                    "linked" to false,
                                    "error" to "No linked player found"
                                ))
                            }
                            return
                        }
                    }
                    sendJsonResponse(exchange, 400, mapOf("error" to "Invalid query parameters"))
                }
                else -> sendJsonResponse(exchange, 405, mapOf("error" to "Method not allowed"))
            }
        }
    }

    // Health check
    inner class HealthHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            sendJsonResponse(exchange, 200, mapOf(
                "status" to "OK",
                "plugin" to plugin.description.name,
                "version" to plugin.description.version,
                "timestamp" to System.currentTimeMillis()
            ))
        }
    }
}