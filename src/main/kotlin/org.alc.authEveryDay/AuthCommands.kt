package org.alc.authEveryDay

import com.warrenstrange.googleauth.GoogleAuthenticator
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class AuthCommands(private val plugin: AuthEveryDay) : CommandExecutor {

    private val gAuth = GoogleAuthenticator()

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("Команды доступны только игрокам.")
            return true
        }

        when (command.name.lowercase()) {
            "register" -> {
                if (args.isEmpty()) {
                    sender.sendMessage("§6[AuthEveryDay] §cИспользование: /register <пароль>")
                    return true
                }
                registerPlayer(sender, args[0])
            }
            "login" -> {
                if (args.isEmpty()) {
                    sender.sendMessage("§cИспользование: /login <пароль>")
                    return true
                }
                loginPlayer(sender, args[0])
            }
            "2fa_discord" -> handleDiscordCommand(sender, args)
            "2fa_app" -> handleAppCommand(sender, args)
        }
        return true
    }

    private fun registerPlayer(player: Player, javaPass: String) {
        runCatching {
            val hash = hashPassword(javaPass)
            val query = "INSERT INTO users (username, password_hash, discord_id, totp_secret, is_enabled) VALUES (?, ?, '', '', 1);"
            plugin.dbConnection?.prepareStatement(query).use { ps ->
                ps?.setString(1, player.name.lowercase())
                ps?.setString(2, hash)
                ps?.executeUpdate()
            }

            player.sendMessage("§6[AuthEveryDay] §aВы успешно зарегистрированы!")

            if (plugin.config.getBoolean("2fa_only", false)) {
                player.sendMessage("§6[AuthEveryDay] §c[КРИТИЧЕСКИ ВАЖНО] На сервере включена обязательная защита аккаунтов!")
                player.sendMessage("§eПожалуйста, выберите и привяжите ОДИН из способов для завершения входа:")
                player.sendMessage("§b➡ /2fa_discord <ваш_Discord_ID> §7(Вход по кнопке в Discord)")
                player.sendMessage("§b➡ /2fa_app setup §7(Вход по коду из приложения Microsoft/Google)")
            } else {
                player.sendMessage("§6[AuthEveryDay] §aТеперь введите команду: /login <ваш_пароль>")
            }
        }.onFailure {
            player.sendMessage("§6[AuthEveryDay] §cОшибка! Возможно этот ник уже зарегистрирован.")
        }
    }

    private fun loginPlayer(player: Player, javaPass: String) {
        plugin.debugLog("Игрок ${player.name} пытается войти (/login)...")
        runCatching {
            val query = "SELECT password_hash, discord_id, totp_secret, is_enabled FROM users WHERE username = ?;"
            plugin.dbConnection?.prepareStatement(query).use { ps ->
                ps?.setString(1, player.name.lowercase())
                val rs = ps?.executeQuery()

                if (rs?.next() == true) {
                    val savedHash = rs.getString("password_hash")
                    val discordId = rs.getString("discord_id")
                    val totpSecret = rs.getString("totp_secret")
                    val isEnabled = rs.getInt("is_enabled") == 1

                    if (savedHash == hashPassword(javaPass)) {
                        val is2faEnabled = plugin.config.getBoolean("2fa-enabled", true)

                        if (is2faEnabled && plugin.config.getBoolean("2fa_only", false) && discordId.isEmpty() && totpSecret.isEmpty()) {
                            player.sendMessage("§6[AuthEveryDay] §cВход заблокирован! У вас не настроен ни один способ защиты.")
                            player.sendMessage("§eПожалуйста, привяжите защиту: /2fa_discord <ID> или /2fa_app setup")
                            return
                        }

                        if (!isEnabled) {
                            plugin.authenticatedPlayers.add(player.uniqueId)
                            player.sendMessage("§6[AuthEveryDay] §aВход выполнен успешно (Защита отключена).")
                            return
                        }

                        if (is2faEnabled && discordId.isNotEmpty() && totpSecret.isNotEmpty()) {
                            player.sendMessage("§6[AuthEveryDay] §eЗапущена двухэтапная проверка (Discord + App)!")
                            triggerDiscordAuth(player, discordId)
                            return
                        }

                        if (is2faEnabled && discordId.isNotEmpty()) {
                            triggerDiscordAuth(player, discordId)
                            return
                        }

                        if (totpSecret.isNotEmpty()) {
                            plugin.pendingTOTPAuth.add(player.uniqueId)
                            // УПРОЩЕНИЕ: Просим написать прямо в чат без команд!
                            player.sendMessage("§6[AuthEveryDay] §eВведите 6-значный код из вашего приложения Authenticator прямо в чат:")
                            return
                        }

                        plugin.authenticatedPlayers.add(player.uniqueId)
                        player.sendMessage("§6[AuthEveryDay] §aВход выполнен успешно по паролю!")

                    } else {
                        player.sendMessage("§6[AuthEveryDay] §cНеверный пароль!")
                    }
                } else {
                    player.sendMessage("§6[AuthEveryDay] §cВы не зарегистрированы! Используйте /register")
                }
            }
        }
    }

    private fun triggerDiscordAuth(player: Player, discordId: String) {
        val ip = player.address?.address?.hostAddress ?: "Неизвестно"
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val loc = player.location
        val coords = "Мир: ${loc.world?.name} | X: ${loc.blockX}, Y: ${loc.blockY}, Z: ${loc.blockZ}"

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val location = getGeoLocation(ip)
            val messageText = """
                🔐 **Попытка входа в аккаунт Minecraft!**
                👤 **Игрок:** `${player.name}`
                🕒 **Время:** `$time`
                🌐 **IP-Адрес:** `$ip`
                📍 **Локация IP:** `$location`
                🎮 **Координаты в игре:** `$coords`
                
                Выберите действие для текущей сессии:
            """.trimIndent()
            sendDiscordButtonMessage(discordId, messageText, player)
        })
        player.sendMessage("§6[AuthEveryDay] §eПароль верен! Подтвердите вход кнопкой в вашем Discord.")
    }

    private fun handleDiscordCommand(player: Player, args: Array<out String>) {
        if (!plugin.config.getBoolean("2fa-enabled", true)) {
            player.sendMessage("§6[AuthEveryDay] §cИнтеграция с Discord отключена администрацией.")
            return
        }

        if (args.isEmpty() || args[0].equals("help", ignoreCase = true)) {
            player.sendMessage("§6=== [Управление 2FA Discord] ===")
            player.sendMessage("§e/2fa_discord <ваш_Discord_ID> §7- Привязать/изменить ID")
            player.sendMessage("§e/2fa_discord unlink §7- Отвязать Discord")
            return
        }

        val sub = args[0].lowercase()
        if (sub == "unlink") {
            if (plugin.config.getBoolean("2fa_only", false) && getTotpSecret(player).isEmpty()) {
                player.sendMessage("§6[AuthEveryDay] §cОшибка! Вы не можете отвязать Discord, так как защита обязательна, а приложение у вас не настроено!")
                return
            }
            updateDiscordId(player, "")
            player.sendMessage("§6[AuthEveryDay] §aАккаунт Discord успешно отвязан.")
        } else {
            updateDiscordId(player, args[0])
            player.sendMessage("§6[AuthEveryDay] §aDiscord ID (${args[0]}) успешно привязан!")
            player.sendMessage("§6[AuthEveryDay] §aЗащита успешно активирована! Введите /login <пароль> для входа.")
        }
    }

    private fun handleAppCommand(player: Player, args: Array<out String>) {
        if (args.isEmpty() || args[0].equals("help", ignoreCase = true)) {
            player.sendMessage("§6=== [Управление Authenticator App] ===")
            player.sendMessage("§e/2fa_app setup §7- Начать привязку приложения")
            player.sendMessage("§e/2fa_app confirm <код> §7- Подтвердить привязку кодом")
            player.sendMessage("§e/2fa_app unlink §7- Отвязать приложение")
            return
        }

        when (args[0].lowercase()) {
            "setup" -> {
                val credentials = gAuth.createCredentials()
                val secretKey = credentials.key
                plugin.pendingTOTPSetup[player.uniqueId] = secretKey

                player.sendMessage("§6=== [Настройка Authenticator] ===")
                player.sendMessage("§e1. Скачайте Microsoft или Google Authenticator на телефон.")
                player.sendMessage("§e2. Выберите 'Добавить аккаунт' -> 'Ввести ключ вручную'.")
                player.sendMessage("§e3. Введите имя: §b${player.name}")
                player.sendMessage("§e4. Введите секретный ключ: §d$secretKey")
                player.sendMessage("§e5. После добавления введите команду подтверждения:")
                player.sendMessage("§a➡ /2fa_app confirm <6_значный_код_из_приложения>")
            }
            "confirm" -> {
                if (args.size < 2) {
                    player.sendMessage("§cИспользование: /2fa_app confirm <код>")
                    return
                }
                val secret = plugin.pendingTOTPSetup[player.uniqueId]
                if (secret == null) {
                    player.sendMessage("§cВы не запускали настройку! Введите сначала /2fa_app setup")
                    return
                }
                val code = args[1].toIntOrNull() ?: 0
                if (gAuth.authorize(secret, code)) {
                    saveTotpSecret(player, secret)
                    plugin.pendingTOTPSetup.remove(player.uniqueId)
                    player.sendMessage("§6[AuthEveryDay] §aПриложение Authenticator успешно привязано и активировано!")
                    player.sendMessage("§6[AuthEveryDay] §aЗащита успешно активирована! Введите /login <пароль> для входа.")
                } else {
                    player.sendMessage("§cНеверный код! Проверьте ключ и время на телефоне и сервере.")
                }
            }
            "unlink" -> {
                if (plugin.config.getBoolean("2fa_only", false) && getDiscordId(player).isEmpty()) {
                    player.sendMessage("§6[AuthEveryDay] §cОшибка! Вы не можете отвязать приложение, так как защита обязательна, а Discord у вас не привязан!")
                    return
                }
                saveTotpSecret(player, "")
                player.sendMessage("§6[AuthEveryDay] §aПриложение Authenticator успешно отвязано.")
            }
        }
    }

    private fun updateDiscordId(player: Player, id: String) {
        runCatching {
            val query = "UPDATE users SET discord_id = ? WHERE username = ?;"
            plugin.dbConnection?.prepareStatement(query).use { ps ->
                ps?.setString(1, id)
                ps?.setString(2, player.name.lowercase())
                ps?.executeUpdate()
            }
        }
    }

    private fun saveTotpSecret(player: Player, secret: String) {
        runCatching {
            val query = "UPDATE users SET totp_secret = ? WHERE username = ?;"
            plugin.dbConnection?.prepareStatement(query).use { ps ->
                ps?.setString(1, secret)
                ps?.setString(2, player.name.lowercase())
                ps?.executeUpdate()
            }
        }
    }

    private fun getTotpSecret(player: Player): String {
        return runCatching {
            val query = "SELECT totp_secret FROM users WHERE username = ?;"
            plugin.dbConnection?.prepareStatement(query).use { ps ->
                ps?.setString(1, player.name.lowercase())
                val rs = ps?.executeQuery()
                if (rs?.next() == true) rs.getString("totp_secret") else ""
            }
        }.getOrElse { "" }
    }

    private fun getDiscordId(player: Player): String {
        return runCatching {
            val query = "SELECT discord_id FROM users WHERE username = ?;"
            plugin.dbConnection?.prepareStatement(query).use { ps ->
                ps?.setString(1, player.name.lowercase())
                val rs = ps?.executeQuery()
                if (rs?.next() == true) rs.getString("discord_id") else ""
            }
        }.getOrElse { "" }
    }

    private fun sendDiscordButtonMessage(userId: String, messageText: String, player: Player) {
        if (plugin.jda == null) return
        plugin.jda?.retrieveUserById(userId)?.queue({ user ->
            user.openPrivateChannel().queue({ channel ->
                // ПРИ ВХОДЕ СТРОГО ДВЕ КНОПКИ В ОДИН РЯД
                val authButton = Button.success("auth_approve:${player.uniqueId}", "✅ Авторизовать")
                val denyButton = Button.danger("auth_block_ip:${player.uniqueId}", "🚨 Заблокировать IP")

                channel.sendMessage(messageText)
                    .setComponents(net.dv8tion.jda.api.interactions.components.ActionRow.of(authButton, denyButton))
                    .queue({ sentMessage ->
                        plugin.pending2FA[player.uniqueId] = sentMessage.id

                        val timeout = plugin.config.getLong("auth-timeout-seconds", 60)
                        if (timeout > 0) {
                            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                                if (player.isOnline && !plugin.authenticatedPlayers.contains(player.uniqueId)) {
                                    player.kick(net.kyori.adventure.text.Component.text("§6[AuthEveryDay]\n§cВремя на подтверждение 2FA истекло!"))
                                    sentMessage.editMessage("❌ Время на вход истекло.").setComponents(emptyList()).queue()
                                    plugin.pending2FA.remove(player.uniqueId)
                                }
                            }, timeout * 20L)
                        }
                    })
            })
        })
    }

    private fun getGeoLocation(ip: String): String {
        if (ip == "127.0.0.1" || ip.startsWith("192.168.") || ip.startsWith("10.")) return "Локальный хост"
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
        }.getOrElse { "Неизвестно" }
    }

    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(password.toByteArray(StandardCharsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}

