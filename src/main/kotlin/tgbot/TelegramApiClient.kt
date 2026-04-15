package tgbot

import kotlinx.serialization.encodeToString
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

class TelegramApiClient(
    botToken: String
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()
    private val apiBaseUrl = "https://api.telegram.org/bot$botToken"

    fun getUpdates(offset: Long?, timeoutSeconds: Int): List<TelegramUpdate> {
        val request = TelegramGetUpdatesRequest(
            offset = offset,
            timeout = timeoutSeconds
        )
        val response = postJson("/getUpdates", json.encodeToString(request))
        val payload = json.decodeFromString<TelegramUpdatesEnvelope>(response)
        check(payload.ok) { payload.description ?: "Telegram getUpdates failed" }
        return payload.result
    }

    fun sendMessage(chatId: Long, text: String) {
        splitTelegramText(text).forEach { chunk ->
            val request = TelegramSendMessageRequest(chatId = chatId, text = chunk)
            val response = postJson("/sendMessage", json.encodeToString(request))
            val payload = json.decodeFromString<TelegramOkEnvelope>(response)
            check(payload.ok) { payload.description ?: "Telegram sendMessage failed" }
        }
    }

    fun sendDocument(chatId: Long, filename: String, bytes: ByteArray, caption: String? = null) {
        postMultipart(
            method = "/sendDocument",
            fields = mapOf(
                "chat_id" to chatId.toString(),
                "caption" to caption
            ),
            fileField = "document",
            fileName = filename,
            contentType = "application/octet-stream",
            bytes = bytes
        )
    }

    fun sendPhoto(chatId: Long, filename: String, bytes: ByteArray, caption: String? = null) {
        postMultipart(
            method = "/sendPhoto",
            fields = mapOf(
                "chat_id" to chatId.toString(),
                "caption" to caption
            ),
            fileField = "photo",
            fileName = filename,
            contentType = "image/png",
            bytes = bytes
        )
    }

    private fun postJson(path: String, payload: String): String {
        val request = HttpRequest.newBuilder(URI.create("$apiBaseUrl$path"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(70))
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "Telegram API HTTP ${response.statusCode()}: ${response.body()}"
        }
        return response.body()
    }

    private fun postMultipart(
        method: String,
        fields: Map<String, String?>,
        fileField: String,
        fileName: String,
        contentType: String,
        bytes: ByteArray
    ) {
        val boundary = "----tg-bot-${UUID.randomUUID()}"
        val body = buildMultipartBody(boundary, fields, fileField, fileName, contentType, bytes)

        val request = HttpRequest.newBuilder(URI.create("$apiBaseUrl$method"))
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .timeout(Duration.ofSeconds(70))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "Telegram API HTTP ${response.statusCode()}: ${response.body()}"
        }
        val payload = json.decodeFromString<TelegramOkEnvelope>(response.body())
        check(payload.ok) { payload.description ?: "Telegram multipart method failed" }
    }

    private fun buildMultipartBody(
        boundary: String,
        fields: Map<String, String?>,
        fileField: String,
        fileName: String,
        contentType: String,
        bytes: ByteArray
    ): ByteArray {
        val newline = "\r\n"
        val output = mutableListOf<ByteArray>()

        fields.forEach { (key, value) ->
            if (value == null) return@forEach
            output += "--$boundary$newline".toByteArray(StandardCharsets.UTF_8)
            output += "Content-Disposition: form-data; name=\"$key\"$newline$newline".toByteArray(StandardCharsets.UTF_8)
            output += value.toByteArray(StandardCharsets.UTF_8)
            output += newline.toByteArray(StandardCharsets.UTF_8)
        }

        output += "--$boundary$newline".toByteArray(StandardCharsets.UTF_8)
        output += "Content-Disposition: form-data; name=\"$fileField\"; filename=\"$fileName\"$newline"
            .toByteArray(StandardCharsets.UTF_8)
        output += "Content-Type: $contentType$newline$newline".toByteArray(StandardCharsets.UTF_8)
        output += bytes
        output += newline.toByteArray(StandardCharsets.UTF_8)
        output += "--$boundary--$newline".toByteArray(StandardCharsets.UTF_8)

        val totalSize = output.sumOf { it.size }
        val result = ByteArray(totalSize)
        var offset = 0
        output.forEach { part ->
            part.copyInto(result, destinationOffset = offset)
            offset += part.size
        }
        return result
    }

    private fun splitTelegramText(text: String, maxLength: Int = 3500): List<String> {
        if (text.length <= maxLength) return listOf(text)

        val chunks = mutableListOf<String>()
        var current = text
        while (current.length > maxLength) {
            val splitIndex = current.lastIndexOf('\n', startIndex = maxLength).takeIf { it > 0 } ?: maxLength
            chunks += current.substring(0, splitIndex)
            current = current.substring(splitIndex).trimStart('\n')
        }
        if (current.isNotBlank()) {
            chunks += current
        }
        return chunks
    }
}
