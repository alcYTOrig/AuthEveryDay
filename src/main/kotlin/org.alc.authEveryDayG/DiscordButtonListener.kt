package org.alc.authEveryDayG

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.bukkit.Bukkit
import java.util.UUID

class DiscordButtonListener(private val plugin: AuthEveryDayG) : ListenerAdapter() {
    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        if (event.componentId.startsWith("auth_approve:")) {
            val playersUuidStr = event.componentId.substringAfter("auth_approve:")
            val playerUuid = UUID.fromString(playersUuidStr)

            val exceptedMessageId = plugin.pending2FA[playerUuid]
            if (exceptedMessageId != event.messageId) {
                event.reply("❌ Этот запрос на вход устарел или недействителен.").setEphemeral(true).queue()
                return
            }
            val player = Bukkit.getPlayer(playerUuid)
            if (player == null || !player.isOnline) {
                event.reply("❌ Игрок уже вышел с сервера.").setEphemeral(true).queue()
                plugin.pending2FA.remove(playerUuid)
                return
            }
            plugin.pending2FA.remove(playerUuid)
            plugin.authenticatedPlayers.add(playerUuid)
            event.editMessage("✅ Вход успешно подтвержден!").setComponents(emptyList()).queue()
            Bukkit.getScheduler().runTask(plugin, Runnable {
                player.sendMessage("§6[AuthEveryDayG] §aАвторизация успешно пройдена через Discord! Приятной игры.")
            })
        }
    }
}
