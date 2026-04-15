package tgbot

import kotlin.concurrent.thread

fun main() {
    val config = BotRuntimeConfig.fromEnv()
    val telegramApi = TelegramApiClient(config.botToken)
    val backendApi = BackendApiClient(config.apiBaseUrl, config.superKey)
    val sessionStore = SessionStore(config.dataDir.resolve("sessions.json"))
    val bot = VpnTelegramBot(telegramApi, backendApi, sessionStore)

    println("tg_bot started. API=${config.apiBaseUrl}, dataDir=${config.dataDir}")

    var offset: Long? = null
    while (true) {
        try {
            val updates = telegramApi.getUpdates(offset, config.pollTimeoutSeconds)
            updates.forEach { update ->
                bot.handleUpdate(update)
                offset = update.updateId + 1
            }
        } catch (exception: Exception) {
            System.err.println("tg_bot loop error: ${exception.message}")
            exception.printStackTrace()
            Thread.sleep(3_000)
        }
    }
}
