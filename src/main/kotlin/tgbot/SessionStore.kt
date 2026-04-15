package tgbot

import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path

class SessionStore(private val storageFile: Path) {
    private val sessions = linkedMapOf<Long, UserSession>()

    init {
        storageFile.parent?.let(Files::createDirectories)
        if (Files.exists(storageFile)) {
            runCatching {
                json.decodeFromString<PersistedSessions>(Files.readString(storageFile))
            }.getOrNull()
                ?.sessions
                ?.forEach { session -> sessions[session.telegramUserId] = session }
        }
    }

    @Synchronized
    fun get(telegramUserId: Long): UserSession? = sessions[telegramUserId]

    @Synchronized
    fun put(session: UserSession) {
        sessions[session.telegramUserId] = session
        persist()
    }

    @Synchronized
    fun remove(telegramUserId: Long) {
        sessions.remove(telegramUserId)
        persist()
    }

    @Synchronized
    private fun persist() {
        Files.writeString(
            storageFile,
            json.encodeToString(PersistedSessions(sessions.values.toList()))
        )
    }
}
