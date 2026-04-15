package tgbot

class VpnTelegramBot(
    private val telegramApi: TelegramApiClient,
    private val backendApi: BackendApiClient,
    private val sessionStore: SessionStore
) {
    fun handleUpdate(update: TelegramUpdate) {
        update.callbackQuery?.let { callback ->
            val chatId = callback.message?.chat?.id ?: return
            runCatching {
                handleCallback(chatId, callback)
                telegramApi.answerCallbackQuery(callback.id)
            }.onFailure { error ->
                val messageText = error.message ?: "Внутренняя ошибка."
                runCatching { telegramApi.answerCallbackQuery(callback.id, messageText.take(150)) }
                telegramApi.sendMessage(chatId, "Ошибка: $messageText")
            }
            return
        }

        val message = update.message ?: return
        val sender = message.from ?: return
        val chatId = message.chat.id

        if (message.chat.type != "private") {
            telegramApi.sendMessage(chatId, "Бот работает только в личных сообщениях.")
            return
        }

        runCatching {
            handleMessage(chatId, sender.id, message)
        }.onFailure { error ->
            val messageText = error.message ?: "Внутренняя ошибка."
            telegramApi.sendMessage(chatId, "Ошибка: $messageText")
        }
    }

    private fun handleMessage(chatId: Long, telegramUserId: Long, message: TelegramMessage) {
        val text = message.text?.trim()
        if (!text.isNullOrBlank() && text.startsWith("/")) {
            when (text.substringBefore(' ').substringBefore('@').lowercase()) {
                "/start", "/help", "/menu" -> {
                    sessionStore.clearFlow(telegramUserId)
                    sendHomeMenu(chatId, telegramUserId, "Главное меню")
                }
                "/cancel" -> {
                    sessionStore.clearFlow(telegramUserId)
                    telegramApi.sendMessage(
                        chatId,
                        "Текущее действие отменено.",
                        TelegramKeyboards.removeReplyKeyboard()
                    )
                    sendHomeMenu(chatId, telegramUserId)
                }
                else -> telegramApi.sendMessage(chatId, "Используй кнопки меню или /start.")
            }
            return
        }

        message.contact?.let { contact ->
            handleContact(chatId, telegramUserId, contact)
            return
        }

        val flow = sessionStore.getFlow(telegramUserId)
        if (flow == null) {
            telegramApi.sendMessage(chatId, "Используй кнопки меню ниже или /start.")
            sendHomeMenu(chatId, telegramUserId)
            return
        }

        when (flow.step) {
            FlowSteps.REGISTER_WAIT_NICKNAME -> handleRegisterNickname(chatId, flow, text)
            FlowSteps.REGISTER_WAIT_PASSWORD -> handleRegisterPassword(chatId, telegramUserId, flow, text)
            FlowSteps.DEVICE_CREATE_WAIT_NAME -> handleDeviceCreateName(chatId, telegramUserId, text)
            FlowSteps.DEVICE_RENAME_WAIT_NAME -> handleDeviceRenameName(chatId, telegramUserId, flow, text)
            FlowSteps.ADMIN_SERVER_WIZARD -> handleAdminServerWizardText(chatId, telegramUserId, flow, text)
            else -> {
                telegramApi.sendMessage(chatId, "Неожиданное состояние. Начни заново через /start.")
                sessionStore.clearFlow(telegramUserId)
            }
        }
    }

    private fun handleCallback(chatId: Long, callback: TelegramCallbackQuery) {
        val telegramUserId = callback.from.id
        val data = callback.data.orEmpty()

        when {
            data == Callbacks.HOME -> sendHomeMenu(chatId, telegramUserId)
            data == Callbacks.LOGIN_START -> requestPhone(chatId, telegramUserId, FlowSteps.LOGIN_WAIT_CONTACT, "Отправь свой номер телефона для входа.")
            data == Callbacks.REGISTER_START -> requestPhone(chatId, telegramUserId, FlowSteps.REGISTER_WAIT_CONTACT, "Отправь свой номер телефона для регистрации.")
            data == Callbacks.LOGOUT -> {
                sessionStore.remove(telegramUserId)
                sessionStore.clearFlow(telegramUserId)
                telegramApi.sendMessage(chatId, "Сессия удалена.", TelegramKeyboards.removeReplyKeyboard())
                sendHomeMenu(chatId, telegramUserId)
            }
            data == Callbacks.ME -> showMe(chatId, telegramUserId)
            data == Callbacks.MENU_DEVICES -> showDevices(chatId, telegramUserId)
            data == Callbacks.MENU_SERVERS -> showServers(chatId, telegramUserId)
            data == Callbacks.MENU_ADMIN -> showAdminMenu(chatId, telegramUserId)
            data == Callbacks.DEVICE_CREATE -> {
                sessionStore.putFlow(UserFlowState(telegramUserId, FlowSteps.DEVICE_CREATE_WAIT_NAME))
                telegramApi.sendMessage(chatId, "Введи название нового устройства.", inlineBackKeyboard(Callbacks.MENU_DEVICES))
            }
            data.startsWith("dev:") -> showDevice(chatId, telegramUserId, data.substringAfter("dev:").toLong())
            data.startsWith("devren:") -> startDeviceRename(chatId, telegramUserId, data.substringAfter("devren:").toLong())
            data.startsWith("devdel:") -> deleteDevice(chatId, telegramUserId, data.substringAfter("devdel:").toLong())
            data.startsWith("devcfg:") -> showDeviceConfigs(chatId, telegramUserId, data.substringAfter("devcfg:").toLong())
            data.startsWith("devgen:") -> showGenerateConfigMenu(chatId, telegramUserId, data.substringAfter("devgen:").toLong())
            data.startsWith("gen:") -> handleGenerateConfig(chatId, telegramUserId, data)
            data.startsWith("cfg:") -> handleShowConfig(chatId, telegramUserId, data)
            data.startsWith("cfgfile:") -> handleConfigFile(chatId, telegramUserId, data)
            data.startsWith("cfgqr:") -> handleConfigQr(chatId, telegramUserId, data)
            data == Callbacks.ADMIN_USERS -> showAdminUsers(chatId, telegramUserId)
            data.startsWith("usr:") -> showAdminUser(chatId, telegramUserId, data.substringAfter("usr:").toLong())
            data.startsWith("uap:") -> approveAdminUser(chatId, telegramUserId, data.substringAfter("uap:").toLong())
            data.startsWith("uban:") -> banAdminUser(chatId, telegramUserId, data.substringAfter("uban:").toLong())
            data == Callbacks.ADMIN_SERVERS -> showAdminServers(chatId, telegramUserId)
            data == Callbacks.ADMIN_SERVER_CREATE -> startAdminServerWizard(chatId, telegramUserId, ServerWizardMode.CREATE)
            data.startsWith("asrv:") -> showAdminServer(chatId, telegramUserId, data.substringAfter("asrv:").toLong())
            data.startsWith("asrvedit:") -> startAdminServerWizard(chatId, telegramUserId, ServerWizardMode.UPDATE, data.substringAfter("asrvedit:").toLong())
            data.startsWith("asrvdel:") -> deleteAdminServer(chatId, telegramUserId, data.substringAfter("asrvdel:").toLong())
            data == Callbacks.ADMIN_CONFIGS -> showAdminConfigs(chatId, telegramUserId)
            data.startsWith("acfg:") -> showAdminConfig(chatId, telegramUserId, data.substringAfter("acfg:").toLong())
            data.startsWith("acfgdel:") -> deleteAdminConfig(chatId, telegramUserId, data.substringAfter("acfgdel:").toLong())
            data == Callbacks.WIZARD_SKIP -> handleAdminServerWizardSkip(chatId, telegramUserId)
            data == Callbacks.WIZARD_CANCEL -> {
                sessionStore.clearFlow(telegramUserId)
                telegramApi.sendMessage(chatId, "Мастер остановлен.", TelegramKeyboards.removeReplyKeyboard())
                sendHomeMenu(chatId, telegramUserId)
            }
            else -> telegramApi.sendMessage(chatId, "Неизвестное действие. Используй /start.")
        }
    }

    private fun requestPhone(chatId: Long, telegramUserId: Long, flowStep: String, prompt: String) {
        sessionStore.putFlow(UserFlowState(telegramUserId, flowStep))
        telegramApi.sendMessage(chatId, prompt, TelegramKeyboards.contactRequest())
    }

    private fun handleContact(chatId: Long, telegramUserId: Long, contact: TelegramContact) {
        require(contact.userId == telegramUserId) {
            "Нужно отправить свой собственный номер через кнопку Telegram."
        }
        val phone = normalizePhone(contact.phoneNumber)
        val flow = sessionStore.getFlow(telegramUserId)
            ?: error("Сначала выбери действие Вход или Регистрация.")

        when (flow.step) {
            FlowSteps.LOGIN_WAIT_CONTACT -> {
                val currentUser = backendApi.getCurrentUser(phone)
                sessionStore.put(UserSession(telegramUserId, phone))
                sessionStore.clearFlow(telegramUserId)
                telegramApi.sendMessage(
                    chatId,
                    "Вход выполнен: ${currentUser.nickname} (${currentUser.phone})",
                    TelegramKeyboards.removeReplyKeyboard()
                )
                sendHomeMenu(chatId, telegramUserId, "Сессия активирована.")
            }
            FlowSteps.REGISTER_WAIT_CONTACT -> {
                sessionStore.putFlow(
                    UserFlowState(
                        telegramUserId = telegramUserId,
                        step = FlowSteps.REGISTER_WAIT_NICKNAME,
                        data = mapOf("phone" to phone)
                    )
                )
                telegramApi.sendMessage(
                    chatId,
                    "Номер подтверждён: $phone\nТеперь отправь никнейм.",
                    TelegramKeyboards.removeReplyKeyboard()
                )
            }
            else -> error("Сейчас бот не ожидает номер телефона.")
        }
    }

    private fun handleRegisterNickname(chatId: Long, flow: UserFlowState, text: String?) {
        val nickname = text?.trim().orEmpty()
        require(nickname.isNotBlank()) { "Никнейм не должен быть пустым." }

        sessionStore.putFlow(
            flow.copy(
                step = FlowSteps.REGISTER_WAIT_PASSWORD,
                data = flow.data + ("nickname" to nickname)
            )
        )
        telegramApi.sendMessage(chatId, "Теперь отправь пароль для нового аккаунта.")
    }

    private fun handleRegisterPassword(chatId: Long, telegramUserId: Long, flow: UserFlowState, text: String?) {
        val password = text.orEmpty()
        require(password.length >= 8) { "Пароль должен быть не короче 8 символов." }

        val phone = flow.data["phone"] ?: error("Phone is missing")
        val nickname = flow.data["nickname"] ?: error("Nickname is missing")
        val created = backendApi.register(
            RegisterRequest(
                phone = phone,
                nickname = nickname,
                password = password,
                telegramId = telegramUserId
            )
        )
        sessionStore.clearFlow(telegramUserId)
        telegramApi.sendMessage(
            chatId,
            "Аккаунт создан: ${created.nickname} (${created.phone}). После одобрения администратором нажми «Войти».",
            TelegramKeyboards.removeReplyKeyboard()
        )
        sendHomeMenu(chatId, telegramUserId)
    }

    private fun showMe(chatId: Long, telegramUserId: Long) {
        val currentUser = requireCurrentUser(telegramUserId)
        telegramApi.sendMessage(
            chatId,
            buildString {
                appendLine("Профиль")
                appendLine("id: ${currentUser.id}")
                appendLine("nickname: ${currentUser.nickname}")
                appendLine("phone: ${currentUser.phone}")
                append("role: ")
                append(if (currentUser.isAdmin) "admin" else "user")
            },
            inlineBackKeyboard(Callbacks.HOME)
        )
    }

    private fun showDevices(chatId: Long, telegramUserId: Long) {
        val session = requireSession(telegramUserId)
        val devices = backendApi.listDevices(session.phone)
        if (devices.isEmpty()) {
            telegramApi.sendMessage(
                chatId,
                "У тебя пока нет устройств.",
                TelegramKeyboards.inline(
                    listOf(InlineButton("➕ Создать устройство", Callbacks.DEVICE_CREATE)),
                    listOf(InlineButton("⬅️ Назад", Callbacks.HOME))
                )
            )
            return
        }

        val rows = devices.map { listOf(InlineButton("📱 ${it.name}", "dev:${it.id}")) } +
            listOf(listOf(InlineButton("➕ Создать устройство", Callbacks.DEVICE_CREATE))) +
            listOf(listOf(InlineButton("⬅️ Назад", Callbacks.HOME)))

        telegramApi.sendMessage(
            chatId,
            buildString {
                appendLine("Мои устройства:")
                devices.forEach { appendLine("• ${it.name}") }
            },
            TelegramKeyboards.inline(*rows.toTypedArray())
        )
    }

    private fun showDevice(chatId: Long, telegramUserId: Long, deviceId: Long) {
        val session = requireSession(telegramUserId)
        val device = backendApi.getDevice(session.phone, deviceId)
        telegramApi.sendMessage(
            chatId,
            buildString {
                appendLine("Устройство")
                appendLine("Название: ${device.name}")
                appendLine("Конфигов: ${device.configs.size}")
            },
            TelegramKeyboards.inline(
                listOf(
                    InlineButton("✏️ Переименовать", "devren:${device.id}"),
                    InlineButton("🗑 Удалить", "devdel:${device.id}")
                ),
                listOf(
                    InlineButton("📄 Конфиги", "devcfg:${device.id}"),
                    InlineButton("⚙️ Сгенерировать", "devgen:${device.id}")
                ),
                listOf(InlineButton("⬅️ К устройствам", Callbacks.MENU_DEVICES))
            )
        )
    }

    private fun startDeviceRename(chatId: Long, telegramUserId: Long, deviceId: Long) {
        sessionStore.putFlow(
            UserFlowState(
                telegramUserId = telegramUserId,
                step = FlowSteps.DEVICE_RENAME_WAIT_NAME,
                data = mapOf("deviceId" to deviceId.toString())
            )
        )
        val session = requireSession(telegramUserId)
        val device = backendApi.getDevice(session.phone, deviceId)
        telegramApi.sendMessage(chatId, "Отправь новое имя для устройства ${device.name}.", inlineBackKeyboard("dev:$deviceId"))
    }

    private fun handleDeviceRenameName(chatId: Long, telegramUserId: Long, flow: UserFlowState, text: String?) {
        val session = requireSession(telegramUserId)
        val deviceId = flow.data["deviceId"]?.toLongOrNull() ?: error("Device id is missing")
        val newName = text?.trim().orEmpty()
        require(newName.isNotBlank()) { "Имя устройства не должно быть пустым." }
        val updated = backendApi.updateDevice(session.phone, deviceId, UpdateDeviceRequest(newName))
        sessionStore.clearFlow(telegramUserId)
        telegramApi.sendMessage(chatId, "Устройство обновлено: ${updated.name}")
        showDevice(chatId, telegramUserId, updated.id)
    }

    private fun handleDeviceCreateName(chatId: Long, telegramUserId: Long, text: String?) {
        val session = requireSession(telegramUserId)
        val name = text?.trim().orEmpty()
        require(name.isNotBlank()) { "Название устройства не должно быть пустым." }
        val created = backendApi.createDevice(session.phone, CreateDeviceRequest(name))
        sessionStore.clearFlow(telegramUserId)
        telegramApi.sendMessage(chatId, "Устройство создано: ${created.name}")
        showDevices(chatId, telegramUserId)
    }

    private fun deleteDevice(chatId: Long, telegramUserId: Long, deviceId: Long) {
        val session = requireSession(telegramUserId)
        val device = backendApi.getDevice(session.phone, deviceId)
        backendApi.deleteDevice(session.phone, deviceId)
        telegramApi.sendMessage(chatId, "Устройство ${device.name} удалено.")
        showDevices(chatId, telegramUserId)
    }

    private fun showServers(chatId: Long, telegramUserId: Long) {
        val session = requireSession(telegramUserId)
        val servers = backendApi.listServers(session.phone)
        telegramApi.sendMessage(
            chatId,
            if (servers.isEmpty()) {
                "Серверов пока нет."
            } else {
                buildString {
                    appendLine("Доступные серверы:")
                    servers.forEach { appendLine("• #${it.id} ${it.name} — ${it.location}") }
                }
            },
            inlineBackKeyboard(Callbacks.HOME)
        )
    }

    private fun showGenerateConfigMenu(chatId: Long, telegramUserId: Long, deviceId: Long) {
        val session = requireSession(telegramUserId)
        val servers = backendApi.listServers(session.phone)
        require(servers.isNotEmpty()) { "Нет доступных серверов." }

        val rows = servers.map { server ->
            listOf(InlineButton("⚙️ ${server.name} — ${server.location}", "gen:$deviceId:${server.id}"))
        } + listOf(listOf(InlineButton("⬅️ К устройству", "dev:$deviceId")))

        val device = backendApi.getDevice(session.phone, deviceId)
        telegramApi.sendMessage(
            chatId,
            "Выбери сервер для генерации конфига устройства ${device.name}.",
            TelegramKeyboards.inline(*rows.toTypedArray())
        )
    }

    private fun handleGenerateConfig(chatId: Long, telegramUserId: Long, data: String) {
        val (_, deviceIdRaw, serverIdRaw) = data.split(':')
        val session = requireSession(telegramUserId)
        telegramApi.sendMessage(chatId, "Генерирую конфиг, это может занять некоторое время.")
        val created = backendApi.generateConfig(session.phone, deviceIdRaw.toLong(), serverIdRaw.toLong())
        telegramApi.sendMessage(
            chatId,
            "Конфиг создан для ${created.serverName} (${created.serverLocation}).",
            TelegramKeyboards.inline(
                listOf(InlineButton("📄 Открыть конфиг", "cfg:${deviceIdRaw}:${created.id}")),
                listOf(InlineButton("⬅️ К устройству", "dev:$deviceIdRaw"))
            )
        )
    }

    private fun showDeviceConfigs(chatId: Long, telegramUserId: Long, deviceId: Long) {
        val session = requireSession(telegramUserId)
        val device = backendApi.getDevice(session.phone, deviceId)
        if (device.configs.isEmpty()) {
            telegramApi.sendMessage(chatId, "У устройства ${device.name} пока нет конфигов.", inlineBackKeyboard("dev:$deviceId"))
            return
        }
        val rows = device.configs.map { config ->
            listOf(InlineButton("📄 ${config.serverName}", "cfg:${deviceId}:${config.id}"))
        } + listOf(listOf(InlineButton("⬅️ К устройству", "dev:$deviceId")))
        telegramApi.sendMessage(
            chatId,
            buildString {
                appendLine("Конфиги устройства ${device.name}:")
                device.configs.forEach { appendLine("• ${it.serverName} (${it.serverLocation})") }
            },
            TelegramKeyboards.inline(*rows.toTypedArray())
        )
    }

    private fun handleShowConfig(chatId: Long, telegramUserId: Long, data: String) {
        val (_, deviceIdRaw, configIdRaw) = data.split(':')
        val config = findUserConfig(telegramUserId, deviceIdRaw.toLong(), configIdRaw.toLong())
        telegramApi.sendMessage(
            chatId,
            buildString {
                appendLine("Конфиг ${config.serverName} (${config.serverLocation})")
                appendLine()
                append(config.config)
            },
            TelegramKeyboards.inline(
                listOf(
                    InlineButton("📁 Файл", "cfgfile:${deviceIdRaw}:${configIdRaw}"),
                    InlineButton("🧾 QR", "cfgqr:${deviceIdRaw}:${configIdRaw}")
                ),
                listOf(InlineButton("⬅️ К конфигам", "devcfg:$deviceIdRaw"))
            )
        )
    }

    private fun handleConfigFile(chatId: Long, telegramUserId: Long, data: String) {
        val (_, deviceIdRaw, configIdRaw) = data.split(':')
        val session = requireSession(telegramUserId)
        val deviceId = deviceIdRaw.toLong()
        val configId = configIdRaw.toLong()
        val device = backendApi.getDevice(session.phone, deviceId)
        val config = device.configs.firstOrNull { it.id == configId } ?: error("Config not found")
        val payload = backendApi.downloadConfigFile(session.phone, deviceId, configId)
        telegramApi.sendDocument(
            chatId = chatId,
            filename = buildConfigFilename(config.serverLocation, device.name),
            bytes = payload.bytes,
            caption = "Конфиг ${config.serverName}"
        )
    }

    private fun handleConfigQr(chatId: Long, telegramUserId: Long, data: String) {
        val (_, deviceIdRaw, configIdRaw) = data.split(':')
        val session = requireSession(telegramUserId)
        val deviceId = deviceIdRaw.toLong()
        val configId = configIdRaw.toLong()
        val config = findUserConfig(telegramUserId, deviceId, configId)
        val payload = backendApi.downloadConfigQr(session.phone, deviceId, configId)
        telegramApi.sendPhoto(
            chatId = chatId,
            filename = "config-$configId.png",
            bytes = payload.bytes,
            caption = "QR для конфига ${config.serverName}"
        )
    }

    private fun showAdminMenu(chatId: Long, telegramUserId: Long) {
        requireAdminSession(telegramUserId)
        telegramApi.sendMessage(
            chatId,
            "Админ-панель",
            TelegramKeyboards.inline(
                listOf(
                    InlineButton("👥 Пользователи", Callbacks.ADMIN_USERS),
                    InlineButton("🖥 Серверы", Callbacks.ADMIN_SERVERS)
                ),
                listOf(InlineButton("📄 Все конфиги", Callbacks.ADMIN_CONFIGS)),
                listOf(InlineButton("⬅️ Домой", Callbacks.HOME))
            )
        )
    }

    private fun showAdminUsers(chatId: Long, telegramUserId: Long) {
        val session = requireAdminSession(telegramUserId)
        val users = backendApi.listUsers(session.phone)
        if (users.isEmpty()) {
            telegramApi.sendMessage(chatId, "Пользователей нет.", inlineBackKeyboard(Callbacks.MENU_ADMIN))
            return
        }
        val rows = users.map { user ->
            listOf(InlineButton("👤 #${user.id} ${user.nickname}", "usr:${user.id}"))
        } + listOf(listOf(InlineButton("⬅️ Назад", Callbacks.MENU_ADMIN)))
        telegramApi.sendMessage(
            chatId,
            "Пользователи:",
            TelegramKeyboards.inline(*rows.toTypedArray())
        )
    }

    private fun showAdminUser(chatId: Long, telegramUserId: Long, userId: Long) {
        val session = requireAdminSession(telegramUserId)
        val user = backendApi.listUsers(session.phone).firstOrNull { it.id == userId }
            ?: error("Пользователь #$userId не найден.")
        telegramApi.sendMessage(
            chatId,
            buildString {
                appendLine("Пользователь #${user.id}")
                appendLine("${user.nickname} — ${user.phone}")
                appendLine("approved=${user.isApproved}")
                appendLine("banned=${user.isBanned}")
                appendLine("admin=${user.isAdmin}")
            },
            TelegramKeyboards.inline(
                listOf(
                    InlineButton("✅ Подтвердить", "uap:${user.id}"),
                    InlineButton("⛔ Заблокировать", "uban:${user.id}")
                ),
                listOf(InlineButton("⬅️ К пользователям", Callbacks.ADMIN_USERS))
            )
        )
    }

    private fun approveAdminUser(chatId: Long, telegramUserId: Long, userId: Long) {
        val session = requireAdminSession(telegramUserId)
        val updated = backendApi.approveUser(session.phone, userId)
        telegramApi.sendMessage(chatId, "Пользователь подтверждён: #${updated.id} ${updated.nickname}")
        showAdminUser(chatId, telegramUserId, userId)
    }

    private fun banAdminUser(chatId: Long, telegramUserId: Long, userId: Long) {
        val session = requireAdminSession(telegramUserId)
        val updated = backendApi.banUser(session.phone, userId)
        telegramApi.sendMessage(chatId, "Пользователь заблокирован: #${updated.id} ${updated.nickname}")
        showAdminUser(chatId, telegramUserId, userId)
    }

    private fun showAdminServers(chatId: Long, telegramUserId: Long) {
        val session = requireAdminSession(telegramUserId)
        val servers = backendApi.adminListServers(session.phone)
        val rows = servers.map { server ->
            listOf(InlineButton("🖥 #${server.id} ${server.name}", "asrv:${server.id}"))
        }.toMutableList()
        rows += listOf(InlineButton("➕ Создать сервер", Callbacks.ADMIN_SERVER_CREATE))
        rows += listOf(InlineButton("⬅️ Назад", Callbacks.MENU_ADMIN))
        telegramApi.sendMessage(
            chatId,
            if (servers.isEmpty()) "Серверов нет." else "Серверы:",
            TelegramKeyboards.inline(*rows.toTypedArray())
        )
    }

    private fun showAdminServer(chatId: Long, telegramUserId: Long, serverId: Long) {
        val session = requireAdminSession(telegramUserId)
        val server = backendApi.getServer(session.phone, serverId)
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
            },
            TelegramKeyboards.inline(
                listOf(
                    InlineButton("✏️ Редактировать", "asrvedit:${server.id}"),
                    InlineButton("🗑 Удалить", "asrvdel:${server.id}")
                ),
                listOf(InlineButton("⬅️ К серверам", Callbacks.ADMIN_SERVERS))
            )
        )
    }

    private fun startAdminServerWizard(chatId: Long, telegramUserId: Long, mode: ServerWizardMode, serverId: Long? = null) {
        requireAdminSession(telegramUserId)
        val data = mutableMapOf(
            "mode" to mode.value,
            "fieldIndex" to "0"
        )

        if (mode == ServerWizardMode.UPDATE) {
            require(serverId != null) { "Server id is required" }
            val session = requireAdminSession(telegramUserId)
            val server = backendApi.getServer(session.phone, serverId)
            data["serverId"] = server.id.toString()
            data["name"] = server.name
            data["location"] = server.location
            data["host"] = server.host
            data["port"] = server.port.toString()
            data["username"] = server.username
            data["password"] = server.password.orEmpty()
            data["sshKeyPath"] = server.sshKeyPath.orEmpty()
            data["containerName"] = server.containerName
            data["containerConfigDir"] = server.containerConfigDir
            data["interfaceName"] = server.interfaceName
        }

        sessionStore.putFlow(UserFlowState(telegramUserId, FlowSteps.ADMIN_SERVER_WIZARD, data))
        promptAdminServerWizard(chatId, telegramUserId)
    }

    private fun handleAdminServerWizardText(chatId: Long, telegramUserId: Long, flow: UserFlowState, text: String?) {
        val currentField = currentServerWizardField(flow) ?: return finishAdminServerWizard(chatId, telegramUserId, flow)
        val value = text?.trim().orEmpty()
        val mode = ServerWizardMode.from(flow.data["mode"])
        require(value.isNotBlank() || currentField.canSkip(mode)) { "Поле ${currentField.label} обязательно." }

        val nextData = flow.data.toMutableMap()
        if (value.isNotBlank()) {
            nextData[currentField.key] = if (currentField.isOptional && value == "-") "" else value
        }
        nextData["fieldIndex"] = (fieldIndex(flow) + 1).toString()

        val nextFlow = flow.copy(data = nextData)
        sessionStore.putFlow(nextFlow)
        promptAdminServerWizard(chatId, telegramUserId)
    }

    private fun handleAdminServerWizardSkip(chatId: Long, telegramUserId: Long) {
        val flow = sessionStore.getFlow(telegramUserId)
            ?: error("Нет активного мастера.")
        require(flow.step == FlowSteps.ADMIN_SERVER_WIZARD) { "Сейчас нет активного мастера." }
        val field = currentServerWizardField(flow) ?: return finishAdminServerWizard(chatId, telegramUserId, flow)
        val mode = ServerWizardMode.from(flow.data["mode"])
        require(field.canSkip(mode)) { "Это поле нельзя пропустить." }

        val nextFlow = flow.copy(
            data = flow.data + ("fieldIndex" to (fieldIndex(flow) + 1).toString())
        )
        sessionStore.putFlow(nextFlow)
        promptAdminServerWizard(chatId, telegramUserId)
    }

    private fun promptAdminServerWizard(chatId: Long, telegramUserId: Long) {
        val flow = sessionStore.getFlow(telegramUserId) ?: return
        val field = currentServerWizardField(flow)
        if (field == null) {
            finishAdminServerWizard(chatId, telegramUserId, flow)
            return
        }

        val mode = ServerWizardMode.from(flow.data["mode"])
        val currentValue = flow.data[field.key].orEmpty()
        val text = buildString {
            appendLine(if (mode == ServerWizardMode.CREATE) "Создание сервера" else "Редактирование сервера")
            appendLine("Шаг ${fieldIndex(flow) + 1}/${SERVER_WIZARD_FIELDS.size}")
            appendLine("Поле: ${field.label}")
            if (currentValue.isNotBlank()) {
                appendLine("Текущее значение: $currentValue")
            } else if (field.defaultValue != null) {
                appendLine("Значение по умолчанию: ${field.defaultValue}")
            }
            if (field.isOptional) {
                appendLine("Отправь новое значение или `-`, чтобы очистить поле.")
            } else {
                appendLine("Отправь новое значение.")
            }
        }

        val rows = mutableListOf<List<InlineButton>>()
        if (field.canSkip(mode)) {
            rows += listOf(InlineButton("Пропустить", Callbacks.WIZARD_SKIP))
        }
        rows += listOf(InlineButton("Отменить", Callbacks.WIZARD_CANCEL))

        telegramApi.sendMessage(chatId, text, TelegramKeyboards.inline(*rows.toTypedArray()))
    }

    private fun finishAdminServerWizard(chatId: Long, telegramUserId: Long, flow: UserFlowState) {
        val session = requireAdminSession(telegramUserId)
        val request = buildServerRequestFromWizard(flow.data)
        val mode = ServerWizardMode.from(flow.data["mode"])
        val response = if (mode == ServerWizardMode.CREATE) {
            backendApi.createServer(session.phone, request)
        } else {
            val serverId = flow.data["serverId"]?.toLongOrNull() ?: error("Server id is missing")
            backendApi.updateServer(session.phone, serverId, request)
        }
        sessionStore.clearFlow(telegramUserId)
        telegramApi.sendMessage(
            chatId,
            if (mode == ServerWizardMode.CREATE) {
                "Сервер создан: #${response.id} ${response.name}"
            } else {
                "Сервер обновлён: #${response.id} ${response.name}"
            },
            TelegramKeyboards.removeReplyKeyboard()
        )
        showAdminServer(chatId, telegramUserId, response.id)
    }

    private fun deleteAdminServer(chatId: Long, telegramUserId: Long, serverId: Long) {
        val session = requireAdminSession(telegramUserId)
        backendApi.deleteServer(session.phone, serverId)
        telegramApi.sendMessage(chatId, "Сервер #$serverId удалён.")
        showAdminServers(chatId, telegramUserId)
    }

    private fun showAdminConfigs(chatId: Long, telegramUserId: Long) {
        val session = requireAdminSession(telegramUserId)
        val configs = backendApi.listAllConfigs(session.phone)
        if (configs.isEmpty()) {
            telegramApi.sendMessage(chatId, "Сохранённых конфигов нет.", inlineBackKeyboard(Callbacks.MENU_ADMIN))
            return
        }
        val rows = configs.map { config ->
            listOf(InlineButton("📄 #${config.id} ${config.deviceName}", "acfg:${config.id}"))
        } + listOf(listOf(InlineButton("⬅️ Назад", Callbacks.MENU_ADMIN)))
        telegramApi.sendMessage(chatId, "Все сохранённые конфиги:", TelegramKeyboards.inline(*rows.toTypedArray()))
    }

    private fun showAdminConfig(chatId: Long, telegramUserId: Long, configId: Long) {
        val session = requireAdminSession(telegramUserId)
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
            },
            TelegramKeyboards.inline(
                listOf(InlineButton("🗑 Удалить конфиг", "acfgdel:${config.id}")),
                listOf(InlineButton("⬅️ К конфигам", Callbacks.ADMIN_CONFIGS))
            )
        )
    }

    private fun deleteAdminConfig(chatId: Long, telegramUserId: Long, configId: Long) {
        val session = requireAdminSession(telegramUserId)
        backendApi.deleteAdminConfig(session.phone, configId)
        telegramApi.sendMessage(chatId, "Конфиг #$configId удалён.")
        showAdminConfigs(chatId, telegramUserId)
    }

    private fun sendHomeMenu(chatId: Long, telegramUserId: Long, title: String? = null) {
        val currentUser = runCatching { requireCurrentUser(telegramUserId) }.getOrNull()
        if (currentUser == null) {
            telegramApi.sendMessage(
                chatId,
                buildString {
                    if (!title.isNullOrBlank()) appendLine(title)
                    append("Чтобы войти или зарегистрироваться, используй кнопки ниже.")
                },
                TelegramKeyboards.inline(
                    listOf(
                        InlineButton("🔐 Войти", Callbacks.LOGIN_START),
                        InlineButton("📝 Регистрация", Callbacks.REGISTER_START)
                    )
                )
            )
            return
        }

        val rows = mutableListOf<List<InlineButton>>(
            listOf(
                InlineButton("👤 Профиль", Callbacks.ME),
                InlineButton("📱 Девайсы", Callbacks.MENU_DEVICES)
            ),
            listOf(InlineButton("🖥 Серверы", Callbacks.MENU_SERVERS))
        )
        if (currentUser.isAdmin) {
            rows += listOf(InlineButton("🛠 Админ-панель", Callbacks.MENU_ADMIN))
        }
        rows += listOf(InlineButton("🚪 Выйти", Callbacks.LOGOUT))

        telegramApi.sendMessage(
            chatId,
            buildString {
                if (!title.isNullOrBlank()) appendLine(title)
                appendLine("Привет, ${currentUser.nickname}.")
                append("Выбери действие.")
            },
            TelegramKeyboards.inline(*rows.toTypedArray())
        )
    }

    private fun inlineBackKeyboard(callback: String) =
        TelegramKeyboards.inline(listOf(InlineButton("⬅️ Назад", callback)))

    private fun requireSession(telegramUserId: Long): UserSession {
        return sessionStore.get(telegramUserId)
            ?: error("Сначала нажми «Войти» и отправь свой номер.")
    }

    private fun requireCurrentUser(telegramUserId: Long): CurrentUserResponse {
        val session = requireSession(telegramUserId)
        return try {
            backendApi.getCurrentUser(session.phone)
        } catch (exception: ApiException) {
            if (exception.statusCode == 401) {
                sessionStore.remove(telegramUserId)
                error("Сессия устарела или аккаунт ещё не одобрен. Нажми «Войти» заново.")
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

    private fun currentServerWizardField(flow: UserFlowState): ServerWizardField? {
        val index = fieldIndex(flow)
        return SERVER_WIZARD_FIELDS.getOrNull(index)
    }

    private fun fieldIndex(flow: UserFlowState): Int = flow.data["fieldIndex"]?.toIntOrNull() ?: 0

    private fun buildServerRequestFromWizard(data: Map<String, String>): UpsertServerRequest {
        fun value(key: String): String = data[key].orEmpty()

        return UpsertServerRequest(
            name = value("name").ifBlank { error("name is required") },
            location = value("location").ifBlank { error("location is required") },
            host = value("host").ifBlank { error("host is required") },
            port = value("port").toIntOrNull() ?: 22,
            username = value("username").ifBlank { error("username is required") },
            password = value("password").ifBlank { null },
            sshKeyPath = value("sshKeyPath").ifBlank { null },
            containerName = value("containerName").ifBlank { "amnezia-awg2" },
            containerConfigDir = value("containerConfigDir").ifBlank { "/opt/amnezia/awg" },
            interfaceName = value("interfaceName").ifBlank { "awg0" }
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

    private fun normalizePhone(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.startsWith("+")) trimmed else "+$trimmed"
    }
}

private object FlowSteps {
    const val LOGIN_WAIT_CONTACT = "login_wait_contact"
    const val REGISTER_WAIT_CONTACT = "register_wait_contact"
    const val REGISTER_WAIT_NICKNAME = "register_wait_nickname"
    const val REGISTER_WAIT_PASSWORD = "register_wait_password"
    const val DEVICE_CREATE_WAIT_NAME = "device_create_wait_name"
    const val DEVICE_RENAME_WAIT_NAME = "device_rename_wait_name"
    const val ADMIN_SERVER_WIZARD = "admin_server_wizard"
}

private object Callbacks {
    const val HOME = "home"
    const val LOGIN_START = "auth:login"
    const val REGISTER_START = "auth:register"
    const val LOGOUT = "auth:logout"
    const val ME = "me"
    const val MENU_DEVICES = "menu:devices"
    const val MENU_SERVERS = "menu:servers"
    const val MENU_ADMIN = "menu:admin"
    const val DEVICE_CREATE = "device:create"
    const val ADMIN_USERS = "admin:users"
    const val ADMIN_SERVERS = "admin:servers"
    const val ADMIN_SERVER_CREATE = "admin:server:create"
    const val ADMIN_CONFIGS = "admin:configs"
    const val WIZARD_SKIP = "wizard:skip"
    const val WIZARD_CANCEL = "wizard:cancel"
}

private enum class ServerWizardMode(val value: String) {
    CREATE("create"),
    UPDATE("update");

    companion object {
        fun from(value: String?): ServerWizardMode {
            return entries.firstOrNull { it.value == value } ?: CREATE
        }
    }
}

private data class ServerWizardField(
    val key: String,
    val label: String,
    val requiredForCreate: Boolean,
    val isOptional: Boolean = false,
    val defaultValue: String? = null
) {
    fun canSkip(mode: ServerWizardMode): Boolean {
        return mode == ServerWizardMode.UPDATE || !requiredForCreate || defaultValue != null || isOptional
    }
}

private val SERVER_WIZARD_FIELDS = listOf(
    ServerWizardField("name", "Название", requiredForCreate = true),
    ServerWizardField("location", "Локация", requiredForCreate = true),
    ServerWizardField("host", "Host", requiredForCreate = true),
    ServerWizardField("port", "Port", requiredForCreate = false, defaultValue = "22"),
    ServerWizardField("username", "Username", requiredForCreate = true),
    ServerWizardField("password", "Password", requiredForCreate = false, isOptional = true),
    ServerWizardField("sshKeyPath", "SSH key path", requiredForCreate = false, isOptional = true),
    ServerWizardField("containerName", "Container name", requiredForCreate = false, defaultValue = "amnezia-awg2"),
    ServerWizardField("containerConfigDir", "Container config dir", requiredForCreate = false, defaultValue = "/opt/amnezia/awg"),
    ServerWizardField("interfaceName", "Interface name", requiredForCreate = false, defaultValue = "awg0")
)
