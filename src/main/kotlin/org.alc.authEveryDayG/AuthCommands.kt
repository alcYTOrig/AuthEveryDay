package org.alc.authEveryDayG

import net.dv8tion.jda.api.interactions.components.buttons.Button
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.PreparedStatement
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class AuthCommands(private val plugin: AuthEveryDayG) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) return true

        when (command.name.lowercase()) {
            "register" -> {
                if (args.size < 2) {
                    sender.sendMessage("§cИспользование: /register <пароль> <Дискорд_ID>")
                    return true
                }
                registerPlayer(sender, args[0], args[1])
            }
            "login" -> {
                if (args.isEmpty()) {
                    sender.sendMessage("§cИспользование: /login <пароль>")
                    return true
                }
                loginPlayer(sender, args[0])
            }
            "2fa" -> handle2FACommand(sender, args)
        }
        return true
    }

    private fun handle2FACommand(player: Player, args: Array<out String>) {
        // 1. Проверяем, пустой ли массив аргументов или ввели "help"
        if (args.isEmpty() || args[0].equals("help", ignoreCase = true)) {
            player.sendMessage("§6=== [Панель управления 2FA] ===")
            player.sendMessage("§e/2fa enable §7- Включить защиту")
            player.sendMessage("§e/2fa disable §7- Отключить защиту")
            player.sendMessage("§e/2fa unlink §7- Отвязать аккаунт Discord")
            player.sendMessage("§e/2fa <id> §7- Привязать новый ID Discord")
            return
        }

        // 2. Только ПОСЛЕ проверки берем первый элемент
        val subCommand = args[0].lowercase()

        when (subCommand) {
            "enable" -> change2FAStatus(player, 1, "§aДвухфакторная аутентификация включена!")
            "disable" -> change2FAStatus(player, 0, "§cДвухфакторная аутентификация отключена!")
            "unlink" -> {
                runCatching {
                    val query = "UPDATE users SET discord_id = '', is_enabled = 0 WHERE username = ?;"
                    plugin.dbConnection?.prepareStatement(query).use { ps ->
                        ps?.setString(1, player.name.lowercase())
                        ps?.executeUpdate()
                    }
                    player.sendMessage("§6[AuthEveryDayG] §aАккаунт Discord успешно отвязан.")
                }
            }
            else -> {
                // Если ввели не подкоманду, а просто цифры (Discord ID)
                val discordId = args[0]
                runCatching {
                    val query = "UPDATE users SET discord_id = ? WHERE username = ?;"
                    plugin.dbConnection?.prepareStatement(query).use { ps ->
                        ps?.setString(1, discordId)
                        ps?.setString(2, player.name.lowercase())
                        ps?.executeUpdate()
                    }
                    player.sendMessage("§6[AuthEveryDayG] §aНовый Discord ID ($discordId) успешно установлен.")
                }
            }
        }
    }


    private fun change2FAStatus(player: Player, status: Int, message: String) {
        runCatching {
            val query = "UPDATE users SET is_enabled = ? WHERE username = ?;"
            plugin.dbConnection?.prepareStatement(query).use { ps ->
                ps?.setInt(1, status)
                ps?.setString(2, player.name.lowercase())
                ps?.executeUpdate()
            }
            player.sendMessage("§6[AuthEveryDayG] $message")
        }
    }

    private fun registerPlayer(player: Player, javaPass: String, discordId: String) {
        runCatching {
            val hash = hashPassword(javaPass)
            val default2fa = if (plugin.config.getBoolean("default-2fa-enabled")) 1 else 0
            val query = "INSERT INTO users (username, password_hash, discord_id, is_enabled) VALUES (?, ?, ?, ?);"
            plugin.dbConnection?.prepareStatement(query).use { ps ->
                ps?.setString(1, player.name.lowercase())
                ps?.setString(2, hash)
                ps?.setString(3, discordId)
                ps?.setInt(4, default2fa)
                ps?.executeUpdate()
            }
            player.sendMessage("§6[AuthEveryDayG] §aУспешная регистрация! Теперь войдите: /login <пароль>")
        }.onFailure {
            player.sendMessage("§6[AuthEveryDayG] §cОшибка! Ник занят.")
        }
    }

    // Замените методы loginPlayer и sendDiscordButtonMessage внутри AuthCommands.kt на эти варианты:

    private fun loginPlayer(player: Player, javaPass: String) {
        plugin.debugLog("Игрок \${player.name} пытается войти (/login)...")
        runCatching {
            val query = "SELECT password_hash, discord_id, is_enabled FROM users WHERE username = ?;"
            plugin.dbConnection?.prepareStatement(query).use { ps ->
                ps?.setString(1, player.name.lowercase())
                val rs = ps?.executeQuery()

                if (rs?.next() == true) {
                    val savedHash = rs.getString("password_hash")
                    val discordId = rs.getString("discord_id")
                    val isEnabled = rs.getInt("is_enabled") == 1

                    plugin.debugLog("Аккаунт найден в БД. Привязанный Discord ID: $discordId, Статус 2FA: $isEnabled")

                    if (savedHash == hashPassword(javaPass)) {
                        if (!isEnabled) {
                            plugin.authenticatedPlayers.add(player.uniqueId)
                            player.sendMessage("§6[AuthEveryDayG] §aВход выполнен (2FA отключена).")
                            plugin.debugLog("Игрок \${player.name} зашел без 2FA (отключено в профиле).")
                            return
                        }

                        val ip = player.address?.address?.hostAddress ?: "Неизвестно"
                        val time = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

                        plugin.debugLog("Пароль верный. Запуск асинхронного сбора GeoIP для IP: $ip")
                        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                            val location = getGeoLocation(ip)

                            val messageText = """
                                🔐 **Попытка входа в аккаунт Minecraft!**
                                👤 **Игрок:** `${player.name}`
                                🕒 **Время:** `$time`
                                🌐 **IP-Адрес:** `$ip`
                                📍 **Локация:** `$location`
                                
                                Если это вы, нажмите кнопку **Авторизовать** ниже для входа на сервер.
                            """.trimIndent()

                            plugin.debugLog("Отправка сообщения с кнопкой пользователю Discord ID: $discordId")
                            sendDiscordButtonMessage(discordId, messageText, player)
                        })

                        player.sendMessage("§6[AuthEveryDayG] §eПароль верен! Подтвердите вход, нажав кнопку в вашем Discord.")
                    } else {
                        plugin.debugLog("Игрок \${player.name} ввел неверный пароль.")
                        player.sendMessage("§6[AuthEveryDayG] §cНеверный пароль!")
                    }
                } else {
                    plugin.debugLog("Игрок \${player.name} не найден в базе данных.")
                    player.sendMessage("§6[AuthEveryDayG] §cВы не зарегистрированы! Используйте /register")
                }
            }
        }.onFailure {
            plugin.debugLog("КРИТИЧЕСКАЯ ОШИБКА В loginPlayer: \${it.message}")
            it.printStackTrace()
        }
    }

    private fun sendDiscordButtonMessage(userId: String, messageText: String, player: Player) {
        if (plugin.jda == null) {
            plugin.debugLog("Ошибка отправки: Объект JDA равен null! Бот не подключен к сети.")
            player.sendMessage("§6[AuthEveryDayG] §cОшибка: Discord-бот плагина сейчас выключен!")
            return
        }

        plugin.debugLog("Запрос в Discord API на поиск пользователя с ID: $userId...")
        plugin.jda?.retrieveUserById(userId)?.queue({ user ->
            plugin.debugLog("Пользователь найден: \${user.name}. Открываем ЛС-канал...")

            user.openPrivateChannel().queue({ channel ->
                val authButton = Button.success("auth_approve:${player.uniqueId}", "✅ Авторизовать")


                plugin.debugLog("ЛС открыто. Отправляем сообщение с кнопкой...")
                channel.sendMessage(messageText)
                    .setActionRow(authButton)
                    .queue({ sentMessage ->
                        plugin.pending2FA[player.uniqueId] = sentMessage.id
                        plugin.debugLog("Сообщение успешно доставлено! ID сообщения в Discord: \${sentMessage.id}")
                    }, { throwable ->
                        plugin.debugLog("❌ ОШИБКА ДОСТАВКИ СООБЩЕНИЯ В ЛС: \${throwable.message}")
                        player.sendMessage("§6[AuthEveryDayG] §cНе удалось отправить кнопку! Откройте ЛС на сервере Discord.")
                    })
            }, { throwable ->
                plugin.debugLog("❌ ОШИБКА ОТКРЫТИЯ ЛС КАНАЛА: \${throwable.message}")
                player.sendMessage("§6[AuthEveryDayG] §cDiscord запретил открыть ЛС с вами.")
            })
        }, { throwable ->
            plugin.debugLog("❌ ОШИБКА: Пользователь с Discord ID $userId вообще не найден в API Discord: \${throwable.message}")
            player.sendMessage("§6[AuthEveryDayG] §cУказанный Discord ID не существует!")
        })
    }


    private fun getGeoLocation(ip: String): String {
        if (ip == "127.0.0.1" || ip.startsWith("192.168.")) return "Локальный хост (Владелец сервера)"
        return runCatching {
            val client = HttpClient.newHttpClient()
            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://ipapi.co"))
                .header("User-Agent", "Mozilla/5.0")
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            val body = response.body()

            val city = body.substringAfter("\"city\": \"").substringBefore("\"")
            val country = body.substringAfter("\"country_name\": \"").substringBefore("\"")
            if (city.contains("{") || country.contains("{")) "Не удалось определить" else "$country, $city"
        }.getOrElse { "Неизвестно (Ошибка API)" }
    }

    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(password.toByteArray(StandardCharsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
