package tgbot

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.*

class ServerNotificationsTest {
    @Test fun `new server notification reaches linked users once and unlinked users keep retry state`() {
        val delivered=mutableSetOf<Long>();val reports=mutableMapOf<Long,Boolean>();val messages=mutableListOf<TelegramSendMessageRequest>()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/") { e ->
            val body=e.requestBody.bufferedReader().readText();val path=e.requestURI.path
            val response=when {
                path=="/api/bot/notifications" -> {
                    assertEquals("super-test",e.requestHeaders.getFirst("X-Super-Key"))
                    val notifications=listOf(BotNotification(1,5,"London","UK","+79991112233",123),BotNotification(2,5,"London","UK","+79991112234"),BotNotification(3,5,"London","UK","+79991112235"))
                    json.encodeToString(kotlinx.serialization.builtins.ListSerializer(BotNotification.serializer()),notifications.filter { it.id !in delivered })
                }
                path.endsWith("/delivery") -> {
                    val id=path.split('/')[4].toLong();val report=json.decodeFromString<DeliveryReport>(body);reports[id]=report.delivered
                    if(report.delivered) delivered+=id
                    ""
                }
                else -> {messages+=json.decodeFromString<TelegramSendMessageRequest>(body);"{\"ok\":true}"}
            }
            val bytes=response.toByteArray();e.sendResponseHeaders(if(response.isEmpty())204 else 200,if(response.isEmpty())-1 else bytes.size.toLong());e.responseBody.use {it.write(bytes)}
        }
        server.start()
        try {
            val url="http://127.0.0.1:${server.address.port}";val sessions=SessionStore(Files.createTempDirectory("notification-bot").resolve("sessions.json"));sessions.put(UserSession(456,"+79991112234"))
            val bot=VpnTelegramBot(TelegramApiClient("test",url),BackendApiClient(url,"super-test"),sessions)
            bot.deliverNotifications();assertEquals(listOf(123L,456L),messages.map {it.chatId});assertEquals(true,reports[1]);assertEquals(true,reports[2]);assertEquals(false,reports[3])
            assertTrue(messages.all {it.text.contains("London") && it.replyMarkup.toString().contains("bulk:5")})
            bot.deliverNotifications();assertEquals(2,messages.size)
        } finally {server.stop(0)}
    }
}
