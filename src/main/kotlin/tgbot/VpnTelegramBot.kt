package tgbot

import java.nio.file.Path

class VpnTelegramBot(
    private val telegramApi: TelegramApiClient,
    private val backendApi: BackendApiClient,
    private val sessionStore: SessionStore
) {
    fun handleUpdate(update: TelegramUpdate) {
        val message = update.message ?: return
        val text = message.text?.trim().orEmpty()
        val sender = message.from ?: return
        val chatId = message.chat.id

        if (message.chat.type != "private") {
            telegramApi.sendMessage(chatId, "Бот работает только в личных сообщениях.")
            return
        }
        if (text.isBlank() || !text.startsWith("/")) {
            telegramApi.sendMessage(chatId, "Используй команды. Начни с /help.")
            return
        }

        runCatching {
            dispatch(chatId, sender.id, text)
        }.onFailure { error ->
            val messageText = error.message ?: "Внутренняя ошибка."
            telegramApi.sendMessage(chatId, "Ошибка: $messageText")
        }
    }

    private fun dispatch(chatId: Long, telegramUserId: Long, text: String) {
        val tokens = CommandParser.tokenize(text)
        if (tokens.isEmpty()) {
            telegramApi.sendMessage(chatId, "Используй /help.")
            return
        }

        val command = tokens.first().substringBefore('@').lowercase()
        when (command) {
            "/start", "/help" -> telegramApi.sendMessage(chatId, helpText())
            "/register" -> handleRegister(chatId, telegramUserId, tokens)
            "/login" -> handleLogin(chatId, telegramUserId, tokens)
            "/logout" -> handleLogout(chatId, telegramUserId)
            "/me" -> handleMe(chatId, telegramUserId)
            "/devices" -> handleDevices(chatId, telegramUserId)
            "/device_add" -> handleDeviceAdd(chatId, telegramUserId, tokens)
            "/device_open" -> handleDeviceOpen(chatId, telegramUserId, tokens)
            "/device_rename" -> handleDeviceRename(chatId, telegramUserId, tokens)
            "/device_delete" -> handleDeviceDelete(chatId, telegramUserId, tokens)
            "/servers" -> handleServers(chatId, telegramUserId)
            "/config_generate" -> handleConfigGenerate(chatId, telegramUserId, tokens)
            "/configs" -> handleConfigs(chatId, telegramUserId, tokens)
            "/config_show" -> handleConfigShow(chatId, telegramUserId, tokens)
            "/config_file" -> handleConfigFile(chatId, telegramUserId, tokens)
            "/config_qr" -> handleConfigQr(chatId, telegramUserId, tokens)
            "/admin_users" -> handleAdminUsers(chatId, telegramUserId)
            "/admin_approve" -> handleAdminApprove(chatId, telegramUserId, tokens)
            "/admin_ban" -> handleAdminBan(chatId, telegramUserId, tokens)
            "/admin_servers" -> handleAdminServers(chatId, telegramUserId)
            "/admin_server_get" -> handleAdminServerGet(chatId, telegramUserId, tokens)
            "/admin_server_add" -> handleAdminServerAdd(chatId, telegramUserId, tokens)
            "/admin_server_update" -> handleAdminServerUpdate(chatId, telegramUserId, tokens)
            "/admin_server_delete" -> handleAdminServerDelete(chatId, telegramUserId, tokens)
            "/admin_configs" -> handleAdminConfigs(chatId, telegramUserId)
            "/admin_config_show" -> handleAdminConfigShow(chatId, telegramUserId, tokens)
            "/admin_config_delete" -> handleAdminConfigDelete(chatId, telegramUserId, tokens)
            else -> telegramApi.sendMessage(chatId, "Неизвестная команда. Используй /help.")
        }
    }

    private fun handleRegister(chatId: Long, telegramUserId: Long, tokens: List<String>) {
        require(tokens.size >= 4) { "Формат: /register <phone> <nickname> <password>" }
        val request = RegisterRequest(
            phone = tokens[1].trim(),
            nickname = tokens[2].trim(),
            password = tokens[3],
            telegramId = telegramUserId
        )
        val created = backendApi.register(request)
        telegramApi.sendMessage(
            chatId,
            "Аккаунт создан: ${created.nickname} (${created.phone}). После одобрения администратором выполни /login ${created.phone}"
        )
    }

    private fun handleLogin(chatId: Long, telegramUserId: Long, tokens: List<String>) {
        require(tokens.size >= 2) { "Формат: /login <phone>" }
        val phone = tokens[1].trim()
        val currentUser = backendApi.getCurrentUser(phone)
        sessionStore.put(UserSession(telegramUserId = telegramUserId, phone = phone))
        telegramApi.sendMessage(
            chatId,
            buildString {
                appendLine("Сессия активирована.")
                appendLine("Пользователь: ${currentUser.nickname} (${currentUser.phone})")
                append("Роль: ")
                append(if (currentUser.isAdmin) "администратор" else "пользователь")
            }
        )
    }

    private fun handleLogout(chatId: Long, telegramUserId: Long) {
        sessionStore.remove(telegramUserId)
        telegramApi.sendMessage(chatId, "Сессия удалена.")
    }

    private fun handleMe(chatId: Long, telegramUserId: Long) {
        val currentUser = requireCurrentUser(telegramUserId)
        telegramApi.sendMessage(
            chatId,
            buildString {
                appendLine("Текущий пользователь")
                appendLine("id: ${currentUser.id}")
                appendLine("nickname: ${currentUser.nickname}")
                appendLine("phone: ${currentUser.phone}")
                append("role: ")
                append(if (currentUser.isAdmin) "admin" else "user")
            }
        )
    }

    private fun handleDevices(chatId: Long, telegramUserId: Long) {
        val session = requireSession(telegramUserId)
        val devices = backendApi.listDevices(session.phone)
        if (devices.isEmpty()) {
            telegramApi.sendMessage(chatId, "У тебя пока нет устройств. Создай первое через /device_add <name>")
            return
        }
        telegramApi.sendMessage(
            chatId,
            buildString {
                appendLine("Мои устройства:")
                devices.forEach { device ->
                    appendLine("• #${device.id} ${device.name}")
                }
            }
        )
    }

    private fun handleDeviceAdd(chatId: Long, telegramUserId: Long, tokens: List<String>) {
        require(tokens.size >= 2) { "Формат: /device_add <name>" }
        val session = requireSession(telegramUserId)
        val created = backendApi.createDevice(session.phone, CreateDeviceRequest(name = tokens.drop(1).joinToString(" ")))
        telegramApi.sendMessage(chatId, "Устройство создано: #${created.id} ${created.name}")
    }

    private fun handleDeviceOpen(chatId: Long, telegramUserId: Long, tokens: List<String>) {
        require(tokens.size >= 2) { "Формат: /device_open <device_id>" }
        val session = requireSession(telegramUserId)
        val device = backendApi.getDevice(session.phone, tokens[1].toLong())
        telegramApi.sendMessage(
            chatId,
            buildString {
                appendLine("Устройство #${device.id}")
                appendLine("Название: ${device.name}")
                appendLine("Конфигов: ${device.configs.size}")
                device.configs.forEach { config ->
                    appendLine("• config #${config.id} — ${config.serverName} (${config.serverLocation})")
                }
            }
        )
    }

    private fun handleDeviceRename(chatId: Long, telegramUserId: Long, tokens: List<String>) {
        require(tokens.size >= 3) { "Формат: /device_rename <device_id> <new_name>" }
        val session = requireSession(telegramUserId)
        val updated = backendApi.updateDevice(
            session.phone,
            tokens[1].toLong(),
            UpdateDeviceRequest(name = tokens.drop(2).joinToString(" "))
        )
        telegramApi.sendMessage(chatId, "Устройство обновлено: #${updated.id} ${updated.name}")
    }

    private fun handleDeviceDelete(chatId: Long, telegramUserId: Long, tokens: List<String>) {
        require(tokens.size >= 2) { "Формат: /device_delete <device_id>" }
        val session = requireSession(telegramUserId)
        val deviceId = tokens[1].toLong()
        backendApi.deleteDevice(session.phone, deviceId)
        telegramApi.sendMessage(chatId, "Устройство #$deviceId удалено.")
    }

    private fun handleServers(chatId: Long, telegramUserId: Long) {
        val session = requireSession(telegramUserId)
        val servers = backendApi.listServers(session.phone)
        if (servers.isEmpty()) {
            telegramApi.sendMessage(chatId, "Серверов пока нет.")
            return
        }
        telegramApi.sendMessage(
            chatId,
            buildString {
                appendLine("Доступные серверы:")
                servers.forEach { server ->
                    appendLine("• #${server.id} ${server.name} — ${server.location}")
                }
            }
        )
    }

    private fun handleConfigGenerate(chatId: Long, telegramUserId: Long, tokens: List<String>) {
        require(tokens.size >= 3) { "Формат: /config_generate <device_id> <server_id>" }
        val session = requireSession(telegramUserId)
        val created = backendApi.generateConfig(
            session.phone,
            tokens[1].toLong(),
            tokens[2].toLong()
        )
        telegramApi.sendMessage(
            chatId,
            "Конфиг создан: #${created.id} для сервера ${created.serverName} (${created.serverLocation}). " +
                "Смотреть: /config_show ${tokens[1]} ${created.id}"
        )
    }

    private fun handleConfigs(chatId: Long, telegramUserId: Long, tokens: List<String>) {
        require(tokens.size >= 2) { "Формат: /configs <device_id>" }
        val session = requireSession(telegramUserId)
        val device = backendApi.getDevice(session.phone, tokens[1].toLong())
        if (device.configs.isEmpty()) {
            telegramApi.sendMessage(chatId, "У устройства #${device.id} пока нет конфигов.")
            return
        }
        telegramApi.sendMessage(
            chatId,
            buildString {
                appendLine("Конфиги устройства #${device.id} ${device.name}:")
                device.configs.forEach { config ->
                    appendLine("• #${config.id} ${config.serverName} (${config.serverLocation})")
                }
            }
        )
    }

    private fun handleConfigShow(chatId: Long, telegramUserId: Long, tokens: List<String>) {
        require(tokens.size >= 3) { "Формат: /config_show <device_id> <config_id>" }
        val config = findUserConfig(telegramUserId, tokens[1].toLong(), tokens[2].toLong())
        telegramApi.sendMessage(
            chatId,
            buildString {
                appendLine("config #${config.id} — ${config.serverName} (${config.serverLocation})")
                appendLine()
                append(config.config)
            }
        )
    }

    private fun handleConfigFile(chatId: Long, telegramUserId: Long, tokens: List<String>) {
        require(tokens.size >= 3) { "Формат: /config_file <device_id> <config_id>" }
        val session = requireSession(telegramUserId)
        val deviceId = tokens[1].toLong()
        val configId = tokens[2].toLong()
        val device = backendApi.getDevice(session.phone, deviceId)
        val config = device.configs.firstOrNull { it.id == configId }
            ?: error("Config #$configId not found for device #$deviceId")
        val payload = backendApi.downloadConfigFile(session.phone, deviceId, configId)
        telegramApi.sendDocument(
            chatId = chatId,
            filename = buildConfigFilename(config.serverLocation, device.name),
            bytes = payload.bytes,
            caption = "config #${config.id} — ${config.serverName}"
        )
    }

    private fun handleConfigQr(chatId: Long, telegramUserId: Long, tokens: List<String>) {
        require(tokens.size >= 3) { "Формат: /config_qr <device_id> <config_id>" }
        val session = requireSession(telegramUserId)
        val deviceId = tokens[1].toLong()
        val configId = tokens[2].toLong()
        val config = findUserConfig(telegramUserId, deviceId, configId)
        val payload = backendApi.downloadConfigQr(session.phone, deviceId, configId)
        telegramApi.sendPhoto(
            chatId = chatId,
            filename = "config-$configId.png",
            bytes = payload.bytes,
            caption = "QR для config #${config.id} — ${config.serverName}"
        )
    }

    private fun handleAdminUsers(chatId: Long, telegramUserId: Long) {
        val session = requireAdminSession(telegramUserId)
        val users = backendApi.listUsers(session.phone)
        if (users.isEmpty()) {
            telegramApi.sendMessage(chatId, "Пользователей нет.")
            return
        }
        telegramApi.sendMessage(
            chatId,
            buildString {
                appendLine("Пользователи:")
                users.forEach { user ->
                    appendLine(
                        "• #${user.id} ${user.nickname} ${user.phone} " +
                            "[approved=${user.isApproved}, banned=${user.isBanned}, admin=${user.isAdmin}]"
                    )
                }
            }
        )
    }

    private fun handleAdminApprove(chatId: Long, telegramUserId: Long, tokens: List<String>) {
        require(tokens.size >= 2) { "Формат: /admin_approve <user_id>" }
        val session = requireAdminSession(telegramUserId)
        val updated = backendApi.approveUser(session.phone, tokens[1].toLong())
        telegramApi.sendMessage(chatId, "Пользователь подтверждён: #${updated.id} ${updated.nickname}")
    }

    private fun handleAdminBan(chatId: Long, telegramUserId: Long, tokens: List<String>) {
        require(tokens.size >= 2) { "Формат: /admin_ban <user_id>" }
        val session = requireAdminSession(telegramUserId)
        val updated = backendApi.banUser(session.phone, tokens[1].toLong())
        telegramApi.sendMessage(chatId, "Пользователь заблокирован: #${updated.id} ${updated.nickname}")
    }

    private fun handleAdminServers(chatId: Long, telegramUserId: Long) {
        val session = requireAdminSession(telegramUserId)
        val servers = backendApi.adminListServers(session.phone)
        if (servers.isEmpty()) {
            telegramApi.sendMessage(chatId, "Серверов нет.")
            return
        }
        telegramApi.sendMessage(
            chatId,
            buildString {
                appendLine("Серверы:")
                servers.forEach { server ->
                    appendLine("• #${server.id} ${server.name} — ${server.location}")
                }
            }
        )
    }

    private fun handleAdminServerGet(chatId: Long, telegramUserId: Long, tokens: List<String>) {
        require(tokens.size >= 2) { "Формат: /admin_server_get <server_id>" }
        val session = requireAdminSession(telegramUserId)
        val server = backendApi.getServer(session.phone, tokens[1].toLong())
        telegramApi.sendMessage(
            chatId,
            buildString {
                appendLine("Сервер #${server.id}")
                appendLine("name=${server.name}")
                appendLine("location=${server.location}")
                appendLine("host=${server.host}")
                appendLine("port=${server.port}")
                appendLine("username=${server.username}")
                appendLine("password=${server.password.orEmpty()}")
                appendLine("sshKeyPath=${server.sshKeyPath.orEmpty()}")
                appendLine("containerName=${server.containerName}")
                appendLine("containerConfigDir=${server.containerConfigDir}")
                appendLine("interfaceName=${server.interfaceName}")
            }
        )
    }

    private fun handleAdminServerAdd(chatId: Long, telegramUserId: Long, tokens: List<String>) {
        require(tokens.size >= 2) { "Формат: /admin_server_add key=value ..." }
        val session = requireAdminSession(telegramUserId)
        val request = buildServerRequest(CommandParser.parseKeyValueArgs(tokens.drop(1)))
        val created = backendApi.createServer(session.phone, request)
        telegramApi.sendMessage(chatId, "Сервер создан: #${created.id} ${created.name}")
    }

    private fun handleAdminServerUpdate(chatId: Long, telegramUserId: Long, tokens: List<String>) {
        require(tokens.size >= 3) { "Формат: /admin_server_update <server_id> key=value ..." }
        val session = requireAdminSession(telegramUserId)
        val serverId = tokens[1].toLong()
        val current = backendApi.getServer(session.phone, serverId)
        val request = buildServerRequest(CommandParser.parseKeyValueArgs(tokens.drop(2)), current)
        val updated = backendApi.updateServer(session.phone, serverId, request)
        telegramApi.sendMessage(chatId, "Сервер обновлён: #${updated.id} ${updated.name}")
    }

    private fun handleAdminServerDelete(chatId: Long, telegramUserId: Long, tokens: List<String>) {
        require(tokens.size >= 2) { "Формат: /admin_server_delete <server_id>" }
        val session = requireAdminSession(telegramUserId)
        val serverId = tokens[1].toLong()
        backendApi.deleteServer(session.phone, serverId)
        telegramApi.sendMessage(chatId, "Сервер #$serverId удалён.")
    }

    private fun handleAdminConfigs(chatId: Long, telegramUserId: Long) {
        val session = requireAdminSession(telegramUserId)
        val configs = backendApi.listAllConfigs(session.phone)
        if (configs.isEmpty()) {
            telegramApi.sendMessage(chatId, "Сохранённых конфигов нет.")
            return
        }
        telegramApi.sendMessage(
            chatId,
            buildString {
                appendLine("Все конфиги:")
                configs.forEach { config ->
                    appendLine(
                        "• #${config.id} user=${config.userNickname} (${config.userPhone}) " +
                            "device=${config.deviceName} (#${config.deviceId}) " +
                            "server=${config.serverName} (${config.serverLocation})"
                    )
                }
            }
        )
    }

    private fun handleAdminConfigShow(chatId: Long, telegramUserId: Long, tokens: List<String>) {
        require(tokens.size >= 2) { "Формат: /admin_config_show <config_id>" }
        val session = requireAdminSession(telegramUserId)
        val configId = tokens[1].toLong()
        val config = backendApi.listAllConfigs(session.phone).firstOrNull { it.id == configId }
            ?: error("Config #$configId not found")
        telegramApi.sendMessage(
            chatId,
            buildString {
                appendLine("config #${config.id}")
                appendLine("user=${config.userNickname} (${config.userPhone})")
                appendLine("device=${config.deviceName} (#${config.deviceId})")
                appendLine("server=${config.serverName} (${config.serverLocation})")
                appendLine()
                append(config.config)
            }
        )
    }

    private fun handleAdminConfigDelete(chatId: Long, telegramUserId: Long, tokens: List<String>) {
        require(tokens.size >= 2) { "Формат: /admin_config_delete <config_id>" }
        val session = requireAdminSession(telegramUserId)
        val configId = tokens[1].toLong()
        backendApi.deleteAdminConfig(session.phone, configId)
        telegramApi.sendMessage(chatId, "Конфиг #$configId удалён.")
    }

    private fun requireSession(telegramUserId: Long): UserSession {
        return sessionStore.get(telegramUserId)
            ?: error("Сначала выполни /login <phone>")
    }

    private fun requireCurrentUser(telegramUserId: Long): CurrentUserResponse {
        val session = requireSession(telegramUserId)
        return try {
            backendApi.getCurrentUser(session.phone)
        } catch (exception: ApiException) {
            if (exception.statusCode == 401) {
                sessionStore.remove(telegramUserId)
                error("Сессия устарела или пользователь не подтверждён. Выполни /login заново.")
            } else {
                throw exception
            }
        }
    }

    private fun requireAdminSession(telegramUserId: Long): UserSession {
        val currentUser = requireCurrentUser(telegramUserId)
        check(currentUser.isAdmin) { "Требуются права администратора." }
        return requireSession(telegramUserId)
    }

    private fun findUserConfig(telegramUserId: Long, deviceId: Long, configId: Long): DeviceServerResponse {
        val session = requireSession(telegramUserId)
        val device = backendApi.getDevice(session.phone, deviceId)
        return device.configs.firstOrNull { it.id == configId }
            ?: error("Config #$configId not found for device #$deviceId")
    }

    private fun buildServerRequest(args: Map<String, String>, fallback: ServerResponse? = null): UpsertServerRequest {
        return UpsertServerRequest(
            name = args["name"] ?: fallback?.name ?: error("name is required"),
            location = args["location"] ?: fallback?.location ?: error("location is required"),
            host = args["host"] ?: fallback?.host ?: error("host is required"),
            port = args["port"]?.toIntOrNull() ?: fallback?.port ?: 22,
            username = args["username"] ?: fallback?.username ?: error("username is required"),
            password = args["password"] ?: fallback?.password,
            sshKeyPath = args["sshKeyPath"] ?: fallback?.sshKeyPath,
            containerName = args["containerName"] ?: fallback?.containerName ?: "amnezia-awg2",
            containerConfigDir = args["containerConfigDir"] ?: fallback?.containerConfigDir ?: "/opt/amnezia/awg",
            interfaceName = args["interfaceName"] ?: fallback?.interfaceName ?: "awg0"
        )
    }

    private fun buildConfigFilename(region: String, deviceName: String): String {
        fun normalize(value: String): String =
            value.trim()
                .lowercase()
                .replace("\\s+".toRegex(), "_")
                .replace("[^a-z0-9_-]+".toRegex(), "")

        return "${normalize(region)}_${normalize(deviceName)}.conf"
    }

    private fun helpText(): String {
        return """
            InnoagentVPN Telegram Bot

            Базовые команды:
            /register <phone> <nickname> <password>
            /login <phone>
            /logout
            /me

            Пользователь:
            /devices
            /device_add <name>
            /device_open <device_id>
            /device_rename <device_id> <new_name>
            /device_delete <device_id>
            /servers
            /config_generate <device_id> <server_id>
            /configs <device_id>
            /config_show <device_id> <config_id>
            /config_file <device_id> <config_id>
            /config_qr <device_id> <config_id>

            Админ:
            /admin_users
            /admin_approve <user_id>
            /admin_ban <user_id>
            /admin_servers
            /admin_server_get <server_id>
            /admin_server_add key=value ...
            /admin_server_update <server_id> key=value ...
            /admin_server_delete <server_id>
            /admin_configs
            /admin_config_show <config_id>
            /admin_config_delete <config_id>

            Пример:
            /admin_server_add name="Main Server" location="Frankfurt 1" host=1.2.3.4 username=root password=secret
        """.trimIndent()
    }
}
