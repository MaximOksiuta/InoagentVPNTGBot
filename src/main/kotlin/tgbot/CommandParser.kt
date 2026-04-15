package tgbot

object CommandParser {
    fun tokenize(input: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escape = false

        for (char in input.trim()) {
            when {
                escape -> {
                    current.append(char)
                    escape = false
                }
                char == '\\' -> escape = true
                quote != null && char == quote -> quote = null
                quote == null && (char == '"' || char == '\'') -> quote = char
                quote == null && char.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        result += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(char)
            }
        }

        if (current.isNotEmpty()) {
            result += current.toString()
        }

        return result
    }

    fun parseKeyValueArgs(tokens: List<String>): Map<String, String> {
        return tokens.associate { token ->
            val separatorIndex = token.indexOf('=')
            require(separatorIndex > 0) { "Expected key=value argument, got: $token" }
            val key = token.substring(0, separatorIndex).trim()
            val value = token.substring(separatorIndex + 1).trim()
            require(key.isNotBlank()) { "Argument key must not be blank" }
            key to value
        }
    }
}
