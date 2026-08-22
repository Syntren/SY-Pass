package com.syntren.sypass.handler;

import com.syntren.sypass.storage.PasswordManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;

public class AutoLoginHandler {
    private static int ticksToWait = -1;
    private static PasswordManager.AccountData pendingEntry = null;

    public static void register() {
        // Відстеження входу на сервер
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ServerInfo server = client.getCurrentServerEntry();
            if (server == null) return;

            String serverIp = server.address;
            String username = client.getSession().getUsername();
            PasswordManager.AccountData entry = PasswordManager.getPassword(serverIp, username);

            if (entry != null) {
                // Затримка у 30 тіків (~1.5 секунди), щоб світ і чат встигли завантажитися
                pendingEntry = entry;
                ticksToWait = 30;
            }
        });

        // Відлік таймера та відправлення команди в чат
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (ticksToWait > 0) {
                ticksToWait--;
            } else if (ticksToWait == 0) {
                ticksToWait = -1;

                if (client.player != null && pendingEntry != null) {
                    String cmd = pendingEntry.command().trim();
                    if (cmd.startsWith("/")) {
                        cmd = cmd.substring(1);
                    }

                    String fullCommand = cmd + " " + pendingEntry.password();

                    // Надсилаємо команду авторизації від імені гравця
                    client.player.networkHandler.sendChatCommand(fullCommand);
                    client.player.sendMessage(Text.literal("§a[SY-Pass] Автоматично виконано вхід для акаунта §e" + client.getSession().getUsername() + "§a!"), false);

                    pendingEntry = null;
                }
            }
        });
    }
}