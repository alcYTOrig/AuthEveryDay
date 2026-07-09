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

    private fun loginPlayer(player: Player, javaPass: String) {
        runCatching {
            val query = "SELECT password_hash, discord_id, is_enabled FROM users WHERE username = ?;"
            plugin.dbConnection?.prepareStatement(query).use { ps ->
                ps?.setString(1, player.name.lowercase())
                val rs = ps?.executeQuery()

                if (rs?.next() == true) {
                    val savedHash = rs.getString("password_hash")
                    val discordId = rs.getString("discord_id")
                    val isEnabled = rs.getInt("is_enabled") == 1

                    if (savedHash == hashPassword(javaPass)) {
                        if (!isEnabled) {
                            // Если 2FA выключен пользователем, пускаем сразу
                            plugin.authenticatedPlayers.add(player.uniqueId)
                            player.sendMessage("§6[AuthEveryDayG] §aВход выполнен (2FA отключена).")
                            return
                        }

                        // Собираем метаданные для отправки
                        val ip = player.address?.address?.hostAddress ?: "Неизвестно"
                        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

                        // Запрашиваем геолокацию асинхронно
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

                            sendDiscordButtonMessage(discordId, messageText, player)
                        })

                        player.sendMessage("§6[AuthEveryDayG] §eПароль верен! Подтвердите вход, нажав кнопку в вашем Discord.")
                    } else {
                        player.sendMessage("§6[AuthEveryDayG] §cНеверный пароль!")
                    }
                } else {
                    player.sendMessage("§6[AuthEveryDayG] §cВы не зарегистрированы! Используйте /register")
                }
            }
        }
    }

    private fun sendDiscordButtonMessage(userId: String, messageText: String, player: Player) {
        plugin.jda?.retrieveUserById(userId)?.queue({ user ->
            user.openPrivateChannel().queue { channel ->
                // Создаем кнопку с уникальным ID, содержащим UUID игрока
                val authButton = Button.success("auth_approve:${player.uniqueId}", "✅ Авторизовать")

                channel.sendMessage(messageText)
                    .setActionRow(authButton)
                    .queue { sentMessage ->
                        // Запоминаем ID сообщения, чтобы реагировать только на него
                        plugin.pending2FA[player.uniqueId] = sentMessage.id
                    }
            }
        }, {
            player.sendMessage("§6[AuthEveryDayG] §cБот не смог найти ваш Discord ID. Проверьте привязку /2fa <id>")
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
