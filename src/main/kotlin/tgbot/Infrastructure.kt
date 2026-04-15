package tgbot

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@OptIn(ExperimentalSerializationApi::class)
val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = true
}

data class BotRuntimeConfig(
    val botToken: String,
    val superKey: String,
    val apiBaseUrl: String,
    val dataDir: Path,
    val pollTimeoutSeconds: Int
) {
    companion object {
        fun fromEnv(): BotRuntimeConfig {
            val botToken = System.getenv("TG_BOT_TOKEN")?.trim().orEmpty()
            check(botToken.isNotBlank()) { "TG_BOT_TOKEN is required" }

            val superKey = System.getenv("TG_SUPER_KEY")?.trim().orEmpty()
            check(superKey.isNotBlank()) { "TG_SUPER_KEY is required" }

            val apiBaseUrl = (System.getenv("TG_BOT_API_BASE_URL")?.trim()
                ?: "http://backend:8080").trimEnd('/')

            val dataDir = Paths.get(System.getenv("TG_BOT_DATA_DIR")?.trim().orEmpty().ifBlank { "data" })
            Files.createDirectories(dataDir)

            val pollTimeoutSeconds = System.getenv("TG_BOT_POLL_TIMEOUT_SECONDS")
                ?.toIntOrNull()
                ?.takeIf { it in 1..60 }
                ?: 30

            return BotRuntimeConfig(
                botToken = botToken,
                superKey = superKey,
                apiBaseUrl = apiBaseUrl,
                dataDir = dataDir,
                pollTimeoutSeconds = pollTimeoutSeconds
            )
        }
    }
}

class ApiException(
    val statusCode: Int,
    override val message: String
) : RuntimeException(message)
