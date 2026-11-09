package tgbot

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerStatusAlertsTest {
    @Test
    fun `only admins receive confirmed changes and bot restarts retain the baseline`() {
        val file = Files.createTempDirectory("server-alerts").resolve("observations.json")
        var alerts = ServerStatusAlerts(file)
        val admins = listOf(MonitoringAdmin("+79991112233", 123), MonitoringAdmin("+79991112234"))
        val sessions = listOf(UserSession(456, "+79991112234"), UserSession(789, "+79991112235"))
        val messages = mutableListOf<Pair<Long, String>>()
        fun poll(checkedAt: Long, ssh: Boolean, ping: Boolean, recipients: List<MonitoringAdmin> = admins) {
            val server = MonitoredServer(6, "Vienna 2", "Vienna", ssh, checkedAt = checkedAt, pingReachable = ping)
            alerts.poll(MonitoringSnapshot(listOf(server), recipients), sessions) { chat, text -> messages += chat to text }
        }

        poll(1, true, true)
        poll(2, false, false)
        poll(2, false, false) // A cached sample is not a second observation.
        poll(3, true, true)
        assertTrue(messages.isEmpty())

        poll(4, false, false)
        alerts = ServerStatusAlerts(file)
        poll(5, false, false)
        assertEquals(listOf(123L, 456L), messages.map { it.first })
        assertTrue(messages.all { it.second.contains("пинг и SSH не отвечают") })

        poll(6, false, false)
        poll(7, true, true, admins.take(1))
        poll(8, true, true, admins.take(1))
        assertEquals(listOf(123L, 456L, 123L), messages.map { it.first })
        assertTrue(messages.last().second.contains("→ SSH доступен"))
    }

    @Test
    fun `failed delivery retries a still current transition without repeating successful alerts`() {
        val file = Files.createTempDirectory("server-alerts").resolve("observations.json")
        val alerts = ServerStatusAlerts(file)
        val admin = MonitoringAdmin("+79991112233", 123)
        var attempts = 0
        fun poll(checkedAt: Long, ssh: Boolean) {
            val server = MonitoredServer(6, "Vienna 2", "Vienna", ssh, checkedAt = checkedAt, pingReachable = true)
            alerts.poll(MonitoringSnapshot(listOf(server), listOf(admin)), emptyList()) { _, _ ->
                attempts++
                check(attempts != 1) { "Telegram temporarily unavailable" }
            }
        }
        poll(1, true)
        poll(2, false)
        poll(3, false)
        poll(4, false)
        poll(5, false)
        assertEquals(2, attempts)
    }
}
