package org.YanPl.listener;

import org.YanPl.MineAgent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * 聊天监听器，负责拦截处于 CLI 模式玩家的消息
 */
public class ChatListener implements Listener {
    private final MineAgent plugin;

    public ChatListener(MineAgent plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        // 检查玩家是否处于 CLI 模式或正在等待协议
        if (plugin.getCliManager().handleChat(player, message)) {
            // 在 1.19.1+ 中，直接取消事件会导致 Secure Chat 警告
            // 我们通过清空收件人来隐藏消息，而不取消事件，以减少干扰
            event.getRecipients().clear();
            // 仍然需要取消，否则某些服务端实现可能会因为没有收件人而报错或记录为空白行
            // 但我们先清空收件人是最佳实践
            event.setCancelled(true);
        }
    }
}
