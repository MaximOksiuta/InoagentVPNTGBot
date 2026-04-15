# tg_bot

Telegram-бот на Kotlin, который работает поверх существующего backend API.

## Переменные окружения

- `TG_BOT_TOKEN` — токен Telegram-бота
- `TG_SUPER_KEY` — тот же super key, что используется backend
- `TG_BOT_API_BASE_URL` — базовый URL backend API, по умолчанию `http://backend:8080`
- `TG_BOT_DATA_DIR` — каталог для хранения сессий, по умолчанию `data`

## Команды

- `/start`, `/help`
- `/register <phone> <nickname> <password>`
- `/login <phone>`
- `/logout`
- `/me`
- `/devices`
- `/device_add <name>`
- `/device_open <device_id>`
- `/device_rename <device_id> <new_name>`
- `/device_delete <device_id>`
- `/servers`
- `/config_generate <device_id> <server_id>`
- `/configs <device_id>`
- `/config_show <device_id> <config_id>`
- `/config_file <device_id> <config_id>`
- `/config_qr <device_id> <config_id>`

### Команды администратора

- `/admin_users`
- `/admin_approve <user_id>`
- `/admin_ban <user_id>`
- `/admin_servers`
- `/admin_server_get <server_id>`
- `/admin_server_add key=value ...`
- `/admin_server_update <server_id> key=value ...`
- `/admin_server_delete <server_id>`
- `/admin_configs`
- `/admin_config_show <config_id>`
- `/admin_config_delete <config_id>`

Значения с пробелами передавайте в кавычках:

```text
/admin_server_add name="Main Server" location="Frankfurt 1" host=1.2.3.4 username=root password=secret
```
