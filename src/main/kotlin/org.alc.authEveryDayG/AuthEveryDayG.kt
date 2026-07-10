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
    val pending2FA: MutableMap<UUID, String> = Collections.synchronizedMap(HashMap())
    val lastDisconnectTimes: MutableMap<UUID, Long> = Collections.synchronizedMap(HashMap())

    // Быстрая функция для дебаг-логов
    fun debugLog(message: String) {
        if (config.getBoolean("debug", false)) {
            logger.info("§d[DEBUG] $message")
        }
    }

    override fun onEnable() {
        if (!dataFolder.exists()) dataFolder.mkdirs()

        val configFile = File(dataFolder, "config.yml")
        if (!configFile.exists()) {
            runCatching {
                getResource("config.yml")?.use { inputStream ->
                    configFile.outputStream().use { outputStream -> inputStream.copyTo(outputStream) }
                }
            }
        }
        reloadConfig()
        debugLog("Конфигурация успешно загружена. Статус дебага: TRUE")

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

        logger.info("AuthEveryDay успешно запущен!")
    }

    override fun onDisable() {
        runCatching {
            dbConnection?.close()
            debugLog("Соединение с БД SQLite закрыто.")
            jda?.shutdown()
            debugLog("Discord бот отключен.")
        }
    }

    private fun initDatabase(): Boolean {
        return runCatching {
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
            debugLog("База данных SQLite успешно инициализирована.")
            true
        }.getOrElse {
            logger.severe("Ошибка БД: ${it.message}")
            false
        }
    }

    private fun initDiscordBot() {
        val token = config.getString("discord-token") ?: return
        debugLog("Попытка инициализации JDA с токеном: \${token.take(10)}...")
        runCatching {
            jda = JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.DIRECT_MESSAGES)
                .addEventListeners(DiscordButtonListener(this))
                .build().awaitReady()


            debugLog("JDA статус: \${jda?.status}. Бот успешно авторизован как: \${jda?.selfUser?.name}#\${jda?.selfUser?.discriminator}")
        }.onFailure {
            logger.severe("Ошибка запуска Дискорд бота: ${it.message}")
            it.printStackTrace()
        }
    }
}
