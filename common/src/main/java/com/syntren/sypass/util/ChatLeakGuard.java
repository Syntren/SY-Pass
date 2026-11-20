package com.syntren.sypass.util;

import com.syntren.sypass.config.SYPassConfig;
import com.syntren.sypass.handler.AutoLoginHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

/**
 * Shared client-side chat and command password leak guard used by both Fabric and NeoForge.
 */
public final class ChatLeakGuard {

    private ChatLeakGuard() {}

    /**
     * Checks an outgoing chat message or command for saved password leaks.
     *
     * @param message   the outgoing text (for commands, without leading slash or with it)
     * @param isCommand true if the outgoing payload is a command
     * @return {@code true} if allowed to send, {@code false} if blocked due to a detected leak
     */
    public static boolean allowOutgoing(String message, boolean isCommand) {
        if (!SYPassConfig.isChatLeakProtectionEnabled() || message == null || message.isBlank()) {
            return true;
        }

        if (isCommand && AutoLoginHandler.isDispatchingAuth()) {
            return true;
        }

        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) {
            return true;
        }

        ServerData server = client.getCurrentServer();
        String serverAddress = (server != null && server.ip != null) ? server.ip : "";

        if (isCommand && ChatProtectionMatcher.isAuthCommand(message, serverAddress)) {
            return true;
        }

        SYPassConfig.ChatProtectionScope scope = SYPassConfig.getChatProtectionScope();
        if (ChatProtectionMatcher.checkMessageLeaks(message, serverAddress, scope)) {
            String msgKey = (scope == SYPassConfig.ChatProtectionScope.ALL_SERVERS)
                    ? "sypass.chat.blocked_leak_all"
                    : "sypass.chat.blocked_leak";
            client.player.displayClientMessage(
                    Component.translatable(msgKey).withStyle(ChatFormatting.RED),
                    false
            );
            return false;
        }

        return true;
    }
}
