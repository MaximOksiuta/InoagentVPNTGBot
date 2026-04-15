package tgbot

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class RegisterRequest(
    val nickname: String,
    val phone: String,
    val password: String,
    val telegramId: Long? = null
)

@Serializable
data class RegisterResponse(
    val id: Long,
    val nickname: String,
    val phone: String,
    val telegramId: Long? = null
)

@Serializable
data class CurrentUserResponse(
    val id: Long,
    val isAdmin: Boolean,
    val nickname: String,
    val phone: String,
    val telegramId: Long? = null
)

@Serializable
data class AdminUserResponse(
    val id: Long,
    val phone: String,
    val nickname: String,
    val telegramId: Long? = null,
    val isAdmin: Boolean,
    val isApproved: Boolean,
    val isBanned: Boolean
)

@Serializable
data class DeviceResponse(
    val id: Long,
    val name: String
)

@Serializable
data class DeviceServerResponse(
    val id: Long,
    val serverId: Long,
    val serverName: String,
    val serverLocation: String,
    val config: String
)

@Serializable
data class DeviceDetailsResponse(
    val id: Long,
    val name: String,
    val configs: List<DeviceServerResponse>
)

@Serializable
data class CreateDeviceRequest(
    val name: String
)

@Serializable
data class UpdateDeviceRequest(
    val name: String
)

@Serializable
data class GenerateDeviceConfigRequest(
    val serverId: Long
)

@Serializable
data class ServerListItemResponse(
    val id: Long,
    val name: String,
    val location: String
)

@Serializable
data class UpsertServerRequest(
    val name: String,
    val location: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String? = null,
    val sshKeyPath: String? = null,
    val containerName: String = "amnezia-awg2",
    val containerConfigDir: String = "/opt/amnezia/awg",
    val interfaceName: String = "awg0"
)

@Serializable
data class ServerResponse(
    val id: Long,
    val name: String,
    val location: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String? = null,
    val sshKeyPath: String? = null,
    val containerName: String,
    val containerConfigDir: String,
    val interfaceName: String
)

@Serializable
data class AdminDeviceConfigResponse(
    val id: Long,
    val userId: Long,
    val userPhone: String,
    val userNickname: String,
    val deviceId: Long,
    val deviceName: String,
    val serverId: Long,
    val serverName: String,
    val serverLocation: String,
    val config: String
)

@Serializable
data class ErrorResponse(
    val message: String
)

@Serializable
data class PersistedBotState(
    val sessions: List<UserSession> = emptyList(),
    val flows: List<UserFlowState> = emptyList()
)

@Serializable
data class UserSession(
    val telegramUserId: Long,
    val phone: String
)

@Serializable
data class UserFlowState(
    val telegramUserId: Long,
    val step: String,
    val data: Map<String, String> = emptyMap()
)

@Serializable
data class TelegramGetUpdatesRequest(
    val offset: Long? = null,
    val timeout: Int = 30,
    @SerialName("allowed_updates")
    val allowedUpdates: List<String> = listOf("message", "callback_query")
)

@Serializable
data class TelegramUpdatesEnvelope(
    val ok: Boolean,
    val result: List<TelegramUpdate> = emptyList(),
    val description: String? = null
)

@Serializable
data class TelegramOkEnvelope(
    val ok: Boolean,
    val description: String? = null
)

@Serializable
data class TelegramUpdate(
    @SerialName("update_id")
    val updateId: Long,
    val message: TelegramMessage? = null,
    @SerialName("callback_query")
    val callbackQuery: TelegramCallbackQuery? = null
)

@Serializable
data class TelegramMessage(
    @SerialName("message_id")
    val messageId: Long,
    val from: TelegramUser? = null,
    val chat: TelegramChat,
    val text: String? = null,
    val contact: TelegramContact? = null
)

@Serializable
data class TelegramUser(
    val id: Long,
    @SerialName("is_bot")
    val isBot: Boolean = false,
    @SerialName("first_name")
    val firstName: String? = null,
    @SerialName("last_name")
    val lastName: String? = null,
    val username: String? = null
)

@Serializable
data class TelegramChat(
    val id: Long,
    val type: String
)

@Serializable
data class TelegramContact(
    @SerialName("phone_number")
    val phoneNumber: String,
    @SerialName("first_name")
    val firstName: String? = null,
    @SerialName("last_name")
    val lastName: String? = null,
    @SerialName("user_id")
    val userId: Long? = null
)

@Serializable
data class TelegramCallbackQuery(
    val id: String,
    val from: TelegramUser,
    val message: TelegramMessage? = null,
    val data: String? = null
)

@Serializable
data class TelegramSendMessageRequest(
    @SerialName("chat_id")
    val chatId: Long,
    val text: String,
    @SerialName("disable_web_page_preview")
    val disableWebPagePreview: Boolean = true,
    @SerialName("reply_markup")
    val replyMarkup: JsonElement? = null
)

@Serializable
data class TelegramAnswerCallbackQueryRequest(
    @SerialName("callback_query_id")
    val callbackQueryId: String,
    val text: String? = null
)

data class BinaryPayload(
    val bytes: ByteArray,
    val contentType: String?,
    val disposition: String?
)
