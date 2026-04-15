package tgbot

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object TelegramKeyboards {
    fun inline(vararg rows: List<InlineButton>): JsonObject {
        return JsonObject(
            mapOf(
                "inline_keyboard" to JsonArray(
                    rows.map { row ->
                        JsonArray(
                            row.map { button ->
                                JsonObject(
                                    buildMap {
                                        put("text", JsonPrimitive(button.text))
                                        put("callback_data", JsonPrimitive(button.callbackData))
                                    }
                                )
                            }
                        )
                    }
                )
            )
        )
    }

    fun contactRequest(buttonText: String = "Поделиться номером"): JsonObject {
        return JsonObject(
            mapOf(
                "keyboard" to JsonArray(
                    listOf(
                        JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "text" to JsonPrimitive(buttonText),
                                        "request_contact" to JsonPrimitive(true)
                                    )
                                )
                            )
                        )
                    )
                ),
                "resize_keyboard" to JsonPrimitive(true),
                "one_time_keyboard" to JsonPrimitive(true)
            )
        )
    }

    fun removeReplyKeyboard(): JsonObject {
        return JsonObject(mapOf("remove_keyboard" to JsonPrimitive(true)))
    }
}

data class InlineButton(
    val text: String,
    val callbackData: String
)
