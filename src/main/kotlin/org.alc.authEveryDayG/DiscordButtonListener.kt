package org.alc.authEveryDayG

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.bukkit.Bukkit
import java.util.UUID

class DiscordButtonListener(private val plugin: AuthEveryDayG) : ListenerAdapter() {

    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        if (!event.componentId.startsWith("auth_approve:")) return

        val playerUuidStr = event.componentId.substringAfter("auth_approve:")

        // Безопасно парсим UUID, чтобы плагин не крашился при неверных данных
        val playerUuid = runCatching { UUID.fromString(playerUuidStr) }.getOrNull()
        if (playerUuid == null) {
            plugin.debugLog("❌ Критическая ошибка: Не удалось спарсить UUID из кнопки Дискорда. Получено: '$playerUuidStr'")
            event.reply("❌ Произошла внутренняя ошибка структуры кнопки. Обратитесь к администратору.").setEphemeral(true).queue()
            return
        }

        plugin.debugLog("Discord перехватил клик по кнопке для UUID: $playerUuidStr")

        val expectedMessageId = plugin.pending2FA[playerUuid]
        if (expectedMessageId != event.messageId) {
            plugin.debugLog("Отказ по кнопке: ID сообщения в Discord не совпадает с ожидаемым (${event.messageId} != $expectedMessageId)")
            event.reply("❌ Этот запрос на вход устарел или недействителен.").setEphemeral(true).queue()
            return
        }

        val player = Bukkit.getPlayer(playerUuid)
        if (player == null || !player.isOnline) {
            plugin.debugLog("Отказ по кнопке: Игрок с UUID $playerUuidStr сейчас оффлайн в Minecraft.")
            event.reply("❌ Игрок уже вышел с сервера.").setEphemeral(true).queue()
            plugin.pending2FA.remove(playerUuid)
            return
        }

        // Успешная авторизация
        plugin.pending2FA.remove(playerUuid)
        plugin.authenticatedPlayers.add(playerUuid)
        plugin.debugLog("Игрок ${player.name} успешно авторизован по кнопке!")

        // Отправляем ответ и убираем кнопку
        event.editMessage("✅ Вход успешно подтвержден!")
            .setComponents(emptyList())
            .queue()

        // Переводим выполнение команды в основной поток сервера Minecraft
        Bukkit.getScheduler().runTask(plugin, Runnable {
            player.sendMessage("§6[AuthEveryDayG] §aАвторизация успешно пройдена через Discord! Приятной игры.")
        })
    }
}
