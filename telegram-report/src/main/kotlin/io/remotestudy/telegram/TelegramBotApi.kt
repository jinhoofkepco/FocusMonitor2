package io.remotestudy.telegram

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Minimal synchronous Bot API client. Call only from a worker thread. */
class TelegramBotApi(private val config: TelegramConfig) {
    private val baseUrl = "https://api.telegram.org/bot${config.botToken}/"

    fun sendPhoto(photo: File, caption: String): TelegramApiResult =
        multipart("sendPhoto", "photo", photo, caption)

    fun sendDocument(document: File, caption: String): TelegramApiResult =
        multipart("sendDocument", "document", document, caption)

    fun sendMessage(text: String): TelegramApiResult = postForm(
        "sendMessage",
        mapOf("chat_id" to config.allowedChatId.toString(), "text" to text),
    ).result()

    fun pinChatMessage(messageId: Long): TelegramApiResult = postForm(
        "pinChatMessage",
        mapOf(
            "chat_id" to config.allowedChatId.toString(),
            "message_id" to messageId.toString(),
            "disable_notification" to "true",
        ),
    ).result()

    fun getUpdates(offset: Long, timeoutSeconds: Int = 50): List<TelegramUpdate> {
        val result = postForm(
            "getUpdates",
            mapOf(
                "offset" to offset.toString(),
                "timeout" to timeoutSeconds.toString(),
                "allowed_updates" to "[\"message\"]",
            ),
            readTimeoutMs = (timeoutSeconds + 10) * 1_000,
        ).body.getJSONArray("result")
        return buildList(result.length()) {
            repeat(result.length()) { index ->
                val update = result.getJSONObject(index)
                val message = update.optJSONObject("message")
                add(
                    TelegramUpdate(
                        updateId = update.getLong("update_id"),
                        chatId = message?.optJSONObject("chat")?.optLong("id"),
                        text = message?.optString("text")?.takeIf(String::isNotBlank),
                    ),
                )
            }
        }
    }

    fun deleteWebhook(): TelegramApiResult = postForm(
        "deleteWebhook",
        mapOf("drop_pending_updates" to "false"),
    ).result()

    private fun multipart(method: String, field: String, file: File, caption: String): TelegramApiResult {
        require(file.isFile) { "Upload file does not exist: $file" }
        val boundary = "----RemoteStudy${UUID.randomUUID()}"
        val connection = open(method).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = UPLOAD_TIMEOUT_MS
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            BufferedOutputStream(connection.outputStream).use { output ->
                fun fieldPart(name: String, value: String) {
                    output.write("--$boundary\r\n".toByteArray())
                    output.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                    output.write(value.toByteArray(StandardCharsets.UTF_8))
                    output.write("\r\n".toByteArray())
                }
                fieldPart("chat_id", config.allowedChatId.toString())
                fieldPart("caption", caption)
                output.write("--$boundary\r\n".toByteArray())
                output.write(
                    "Content-Disposition: form-data; name=\"$field\"; filename=\"${safeFilename(file.name)}\"\r\n".toByteArray(),
                )
                output.write("Content-Type: image/jpeg\r\n\r\n".toByteArray())
                file.inputStream().use { it.copyTo(output, DEFAULT_BUFFER_SIZE) }
                output.write("\r\n--$boundary--\r\n".toByteArray())
            }
            return parseResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun postForm(
        method: String,
        values: Map<String, String>,
        readTimeoutMs: Int = REQUEST_TIMEOUT_MS,
    ): ApiEnvelope {
        val body = values.entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }.toByteArray(StandardCharsets.UTF_8)
        val connection = open(method).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            this.readTimeout = readTimeoutMs
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setFixedLengthStreamingMode(body.size)
        }
        try {
            BufferedOutputStream(connection.outputStream).use { it.write(body) }
            return ApiEnvelope(parseJson(connection))
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResponse(connection: HttpURLConnection): TelegramApiResult {
        val json = parseJson(connection)
        val messageId = json.optJSONObject("result")?.optLong("message_id")
        return TelegramApiResult(messageId)
    }

    private fun parseJson(connection: HttpURLConnection): JSONObject {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = BufferedInputStream(stream).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val json = JSONObject(text)
        if (code !in 200..299 || !json.optBoolean("ok")) {
            throw TelegramApiException(code, json.optString("description", "Telegram API request failed"))
        }
        return json
    }

    private fun open(method: String) = URL(baseUrl + method).openConnection() as HttpURLConnection
    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun safeFilename(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private data class ApiEnvelope(val body: JSONObject) {
        val messageId: Long? get() = body.optJSONObject("result")?.optLong("message_id")
        fun result() = TelegramApiResult(messageId)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val REQUEST_TIMEOUT_MS = 30_000
        const val UPLOAD_TIMEOUT_MS = 60_000
    }
}

class TelegramApiException(val statusCode: Int, message: String) : java.io.IOException(message)
