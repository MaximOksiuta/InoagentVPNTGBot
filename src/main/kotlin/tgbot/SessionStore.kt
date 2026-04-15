package tgbot

import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path

class SessionStore(private val storageFile: Path) {
    private val sessions = linkedMapOf<Long, UserSession>()
    private val flows = linkedMapOf<Long, UserFlowState>()

    init {
        storageFile.parent?.let(Files::createDirectories)
        if (Files.exists(storageFile)) {
            runCatching {
                json.decodeFromString<PersistedBotState>(Files.readString(storageFile))
            }.getOrNull()
                ?.also { state ->
                    state.sessions.forEach { session -> sessions[session.telegramUserId] = session }
                    state.flows.forEach { flow -> flows[flow.telegramUserId] = flow }
                }
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
    fun getFlow(telegramUserId: Long): UserFlowState? = flows[telegramUserId]

    @Synchronized
    fun putFlow(flow: UserFlowState) {
        flows[flow.telegramUserId] = flow
        persist()
    }

    @Synchronized
    fun clearFlow(telegramUserId: Long) {
        flows.remove(telegramUserId)
        persist()
    }

    @Synchronized
    private fun persist() {
        Files.writeString(
            storageFile,
            json.encodeToString(
                PersistedBotState(
                    sessions = sessions.values.toList(),
                    flows = flows.values.toList()
                )
            )
        )
    }
}
