package tgbot

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.*

class RecoveryFlowTest {
    @Test fun `reset accepts only own contact from private keyboard flow`() {
        val sent = mutableListOf<String>()
        var recoveryCalls = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.bufferedReader().readText()
            val response = if (exchange.requestURI.path == "/api/auth/recovery/telegram") {
                recoveryCalls++
                assertEquals("test-super", exchange.requestHeaders.getFirst("X-Super-Key"))
                assertEquals(123L, json.decodeFromString<RecoveryRequest>(body).telegramId)
                "{\"token\":\"one-time-test-token\"}"
            } else { sent += body; "{\"ok\":true}" }
            val bytes = response.toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}"
            val store = SessionStore(Files.createTempDirectory("bot-recovery").resolve("sessions.json"))
            val bot = VpnTelegramBot(TelegramApiClient("test", url), BackendApiClient(url, "test-super"), store)
            fun update(text: String? = null, contact: TelegramContact? = null, type: String = "private") = bot.handleUpdate(TelegramUpdate(1, TelegramMessage(1, TelegramUser(123), TelegramChat(123, type), text, contact)))
            update("/start reset")
            assertTrue(sent.last().contains("request_contact"))
            update(contact = TelegramContact("79778343379", userId = 999))
            assertEquals(0, recoveryCalls)
            update(contact = TelegramContact("79778343379", userId = 123), type = "group")
            assertEquals(0, recoveryCalls)
            update(contact = TelegramContact("79778343379", userId = 123))
            assertEquals(1, recoveryCalls)
            assertNull(store.getFlow(123), sent.last())
            assertTrue(sent.last().contains("reset-password#one-time-test-token"))
            update(contact = TelegramContact("79778343379", userId = 123))
            assertEquals(1, recoveryCalls)
        } finally { server.stop(0) }
    }
}
