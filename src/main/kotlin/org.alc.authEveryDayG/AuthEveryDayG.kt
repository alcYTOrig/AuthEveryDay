package org.alc.authEveryDayG

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.requests.GatewayIntent
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.*

class AuthEveryDayG : JavaPlugin() {

    var dbConnection: Connection? = null
        private set
    var jda: JDA? = null
        private set

    val authenticatedPlayers: MutableSet<UUID> = Collections.synchronizedSet(HashSet())

    // UUID игрока -> ID сообщения в Discord (чтобы одобрять вход только по актуальной кнопке)
    val pending2FA: MutableMap<UUID, String> = Collections.synchronizedMap(HashMap())

    // Управление сессиями: UUID игрока -> Время его последнего выхода (в миллисекундах)
    val lastDisconnectTimes: MutableMap<UUID, Long> = Collections.synchronizedMap(HashMap())

    override fun onEnable() {
        if (!dataFolder.exists()) {
            logger.info("Создание основной папки конфигурации: \${dataFolder.name}")
            dataFolder.mkdirs()
        }

        var configFile = java.io.File(dataFolder, "config.yaml")
        if (!configFile.exists()) {
            logger.info("Файл config.yml не найден на диске. Попытка извлечения из JAR...")
            runCatching {
                getResource("config.yml")?.use { inputStream ->
                    configFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                } ?: logger.severe("❌ КРИТИЧЕСКАЯ ОШИБКА: Файл config.yml отсутствует внутри собранного JAR файла плагина!")
            }.onFailure {
                logger.severe("❌ Не удалось записать config.yml на диск: ${it.message}")
            }
        }

        reloadConfig()

        if (!initDatabase()) {
            logger.severe("Выключение плагина из-за критической ошибки SQLite!")
            server.pluginManager.disablePlugin(this)
            return
        }

        initDiscordBot()

        server.pluginManager.registerEvents(PlayerListener(this), this)

        val commands = AuthCommands(this)
        getCommand("register")?.setExecutor(commands)
        getCommand("login")?.setExecutor(commands)
        getCommand("2fa")?.setExecutor(commands)
    }

    override fun onDisable() {
        runCatching {
            dbConnection?.close()
            jda?.shutdown()
        }
    }

    private fun initDatabase(): Boolean {
        return runCatching {
            if (!dataFolder.exists()) dataFolder.mkdirs()
            dbConnection = DriverManager.getConnection("jdbc:sqlite:${File(dataFolder, "auth.db")}")
            dbConnection?.createStatement().use { statement ->
                statement?.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                        username TEXT PRIMARY KEY,
                        password_hash TEXT NOT NULL,
                        discord_id TEXT NOT NULL,
                        is_enabled INTEGER DEFAULT 1
                    );
                """.trimIndent())
            }
            true
        }.getOrElse { false }
    }

    private fun initDiscordBot() {
        val token = config.getString("discord-token") ?: return
        runCatching {
            jda = JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.DIRECT_MESSAGES)
                // Добавляем слушатель нажатия кнопок в Discord
                .addEventListeners(DiscordButtonListener(this))
                .build().awaitReady()
        }
    }
}
