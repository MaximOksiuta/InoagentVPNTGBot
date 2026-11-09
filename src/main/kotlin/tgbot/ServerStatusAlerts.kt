package tgbot

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Serializable
data class MonitoredServer(
    val id: Long,
    val name: String,
    val location: String,
    val hostReachable: Boolean,
    val latencyMs: Long? = null,
    val checkedAt: Long,
    val pingReachable: Boolean? = null,
    val pingLatencyMs: Long? = null
) {
    fun statusText(): String {
        val ping = when (pingReachable) {
            true -> pingLatencyMs?.let { "$it мс" } ?: "ответил"
            false -> "нет ответа"
            null -> "проверка недоступна"
        }
        val ssh = if (hostReachable) latencyMs?.let { "$it мс" } ?: "доступен" else "нет связи"
        return "Пинг: $ping · SSH: $ssh"
    }
}

@Serializable
data class MonitoringAdmin(val phone: String, val telegramId: Long? = null)

@Serializable
data class MonitoringSnapshot(val servers: List<MonitoredServer>, val admins: List<MonitoringAdmin>)

@Serializable
private enum class Reachability(val label: String) {
    SSH_OK("SSH доступен"),
    PING_ONLY("пинг отвечает, SSH недоступен"),
    NO_REPLY("пинг и SSH не отвечают"),
    SSH_UNREACHABLE("SSH недоступен, пинг не проверен");

    companion object {
        fun from(server: MonitoredServer) = when {
            server.hostReachable -> SSH_OK
            server.pingReachable == true -> PING_ONLY
            server.pingReachable == false -> NO_REPLY
            else -> SSH_UNREACHABLE
        }
    }
}

@Serializable
private data class StatusObservation(
    val confirmed: Reachability,
    val checkedAt: Long,
    val candidate: Reachability? = null,
    val samples: Int = 0
)

class ServerStatusAlerts(private val storageFile: Path) {
    private var observations: Map<String, StatusObservation> = if (Files.exists(storageFile)) {
        runCatching {
            json.decodeFromString<Map<String, StatusObservation>>(Files.readString(storageFile))
        }.getOrElse {
            System.err.println("Server monitoring will start with a fresh baseline")
            emptyMap()
        }
    } else emptyMap()

    fun poll(snapshot: MonitoringSnapshot, sessions: List<UserSession>, send: (Long, String) -> Unit) {
        val sessionsByPhone = sessions.associateBy(UserSession::phone)
        val chatIds = snapshot.admins.mapNotNull { admin ->
            (admin.telegramId ?: sessionsByPhone[admin.phone]?.telegramUserId)?.takeIf { it > 0 }
        }.distinct()
        val updated = mutableMapOf<String, StatusObservation>()

        for (chatId in chatIds) for (server in snapshot.servers) {
            val key = "$chatId:${server.id}"
            val status = Reachability.from(server)
            val previous = observations[key]
            val observation = when {
                previous == null -> StatusObservation(status, server.checkedAt)
                server.checkedAt <= previous.checkedAt -> previous
                status == previous.confirmed -> StatusObservation(status, server.checkedAt)
                else -> {
                    val samples = if (previous.candidate == status) (previous.samples + 1).coerceAtMost(2) else 1
                    val candidate = StatusObservation(previous.confirmed, server.checkedAt, status, samples)
                    if (samples < 2) candidate else {
                        val message = "Статус сервера #${server.id} ${server.name} изменился\n" +
                            "${previous.confirmed.label} → ${status.label}\n${server.statusText()}"
                        if (runCatching { send(chatId, message) }.isSuccess) {
                            StatusObservation(status, server.checkedAt)
                        } else candidate
                    }
                }
            }
            updated[key] = observation
        }
        if (updated != observations) {
            save(updated)
            observations = updated
        }
    }

    private fun save(updated: Map<String, StatusObservation>) {
        val destination = storageFile.toAbsolutePath()
        Files.createDirectories(destination.parent)
        val temporary = Files.createTempFile(destination.parent, "monitoring-", ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(updated))
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
