package io.remotestudy.telegram

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Setup-only API. It discovers candidates but never executes an incoming command. */
class TelegramSetupClient {
    fun verifyBot(token: String): String {
        val result = request(token, "getMe", emptyMap()).getJSONObject("result")
        return result.optString("username").takeIf(String::isNotBlank)
            ?: result.optString("first_name", "Telegram bot")
    }

    fun findConnectionChats(token: String): List<TelegramSetupChat> {
        request(token, "deleteWebhook", mapOf("drop_pending_updates" to "false"))
        val updates = request(
            token,
            "getUpdates",
            mapOf("timeout" to "0", "allowed_updates" to "[\"message\"]"),
        ).getJSONArray("result")
        val newestByChat = linkedMapOf<Long, TelegramSetupChat>()
        repeat(updates.length()) { index ->
            val update = updates.getJSONObject(index)
            val message = update.optJSONObject("message") ?: return@repeat
            val chat = message.optJSONObject("chat") ?: return@repeat
            if (chat.optString("type") != "private") return@repeat
            val text = message.optString("text").trim().substringBefore('@')
            if (text !in setOf("/연결", "/connect")) return@repeat
            val chatId = chat.optLong("id")
            if (chatId == 0L) return@repeat
            val displayName = listOf(chat.optString("first_name"), chat.optString("last_name"))
                .filter(String::isNotBlank).joinToString(" ").ifBlank { "텔레그램 사용자" }
            newestByChat[chatId] = TelegramSetupChat(
                updateId = update.getLong("update_id"),
                chatId = chatId,
                displayName = displayName,
                username = chat.optString("username").takeIf(String::isNotBlank),
            )
        }
        return newestByChat.values.sortedByDescending(TelegramSetupChat::updateId)
    }

    fun sendConnectionTest(token: String, chatId: Long) {
        request(
            token,
            "sendMessage",
            mapOf(
                "chat_id" to chatId.toString(),
                "text" to "FocusMonitor2 학생폰 연결 완료 · /start 로 공부를 시작하세요",
            ),
        )
    }

    private fun request(token: String, method: String, values: Map<String, String>): JSONObject {
        require(TOKEN.matches(token.trim())) { "BotFather가 준 봇 토큰 형식이 아닙니다" }
        val body = values.entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }.toByteArray(StandardCharsets.UTF_8)
        val connection = URL("https://api.telegram.org/bot${token.trim()}/$method")
            .openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        connection.setFixedLengthStreamingMode(body.size)
        try {
            BufferedOutputStream(connection.outputStream).use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = BufferedInputStream(stream).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val json = JSONObject(text)
            if (code !in 200..299 || !json.optBoolean("ok")) {
                throw TelegramApiException(code, json.optString("description", "텔레그램 연결 실패"))
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private companion object {
        val TOKEN = Regex("^[0-9]{6,}:[A-Za-z0-9_-]{20,}$")
    }
}
