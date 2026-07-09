package org.alc.authEveryDayG

import io.papermc.paper.event.player.AsyncChatEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.*

class PlayerListener(val plugin: AuthEveryDayG) : Listener {
    private fun isNotAuth(player: Player): Boolean {
        return !plugin.authenticatedPlayers.contains(player.uniqueId)
    }
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val uuid = player.uniqueId

        val lastQuit = plugin.lastDisconnectTimes[uuid]
        val sessionDurationMs = plugin.config.getLong("session-duration-minutes") * 60 * 1000

        if (lastQuit != null && (System.currentTimeMillis() - lastQuit) > sessionDurationMs) {
            plugin.authenticatedPlayers.add(uuid)
            player.sendMessage("§6[AuthEveryDayG] §aС возвращением! Сессия восстановлена автоматически.")
            return
        }
        player.sendMessage("§6[AuthEveryDayG] §cВведите /login <пароль> или /register <пароль> <DiscordID>")
    }
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        if (plugin.authenticatedPlayers.contains(uuid)) {
            plugin.lastDisconnectTimes[uuid] = System.currentTimeMillis()
        }
        plugin.authenticatedPlayers.remove(uuid)
        plugin.pending2FA.remove(uuid)
    }
    @EventHandler fun onMove(event: PlayerMoveEvent) { if (isNotAuth(event.player)) event.isCancelled = true }
    @EventHandler fun onChat(event: AsyncChatEvent) { if (isNotAuth(event.player)) event.isCancelled = true }
    @EventHandler fun onBreak(event: BlockBreakEvent) { if (isNotAuth(event.player)) event.isCancelled = true }
    @EventHandler fun onPlace(event: BlockPlaceEvent) { if (isNotAuth(event.player)) event.isCancelled = true }
}
