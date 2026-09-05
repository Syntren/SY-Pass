package com.syntren.sypass.handler;

import com.syntren.sypass.config.SYPassConfig;
import com.syntren.sypass.gui.SYPassToast;
import com.syntren.sypass.storage.PasswordManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

public class AutoLoginHandler {
    private static int ticksToWait = -1;
    private static PasswordManager.AccountData pendingEntry = null;

    // Змінні контролю сесії для запобігання спаму при переходах між підсерверами/лобі
    private static String activeServerAddress = null;
    private static boolean hasLoggedInThisSession = false;

    public static void register() {
        // 1. Відстеження підключення до сервера
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ServerInfo server = client.getCurrentServerEntry();
            if (server == null) return;

            String currentServerIp = server.address;
            String username = client.getSession().getUsername();
            String normServerIp = PasswordManager.normalizeServerAddress(currentServerIp);

            // Перевірка: якщо ми вже залогінилися на цьому сервері в цій сесії (наприклад, перейшли з лобі на виживання)
            if (normServerIp.equalsIgnoreCase(activeServerAddress) && hasLoggedInThisSession) {
                return; // Ігноруємо перехід між лобі
            }

            // Нове підключення до сервера
            activeServerAddress = normServerIp;
            hasLoggedInThisSession = false;

            PasswordManager.AccountData entry = PasswordManager.getPassword(currentServerIp, username);
            if (entry != null) {
                pendingEntry = entry;
                ticksToWait = SYPassConfig.getAutoLoginDelayTicks();
            }
        });

        // 2. Скидання сесії при виході в головне меню
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            activeServerAddress = null;
            hasLoggedInThisSession = false;
            pendingEntry = null;
            ticksToWait = -1;
        });

        // 3. Відлік затримки та виконання команди
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

                    // Відправляємо команду авторизації
                    client.player.networkHandler.sendChatCommand(fullCommand);

                    // Показуємо красивий Toast замість повідомлення в чат
                    String username = client.getSession().getUsername();
                    SYPassToast.show(
                            Text.translatable("sypass.toast.autologin.title"),
                            Text.translatable("sypass.toast.autologin.desc", username),
                            new ItemStack(Items.TRIPWIRE_HOOK)
                    );

                    hasLoggedInThisSession = true;
                    pendingEntry = null;
                }
            }
        });
    }

    /**
     * Ручний виклик входу за гарячою клавішею
     */
    public static void executeManualLogin(net.minecraft.client.MinecraftClient client) {
        if (client == null || client.player == null) return;

        ServerInfo server = client.getCurrentServerEntry();
        if (server == null) {
            SYPassToast.show(
                    Text.translatable("sypass.toast.quicklogin.title"),
                    Text.translatable("sypass.toast.quicklogin.not_server"),
                    new ItemStack(Items.BARRIER)
            );
            return;
        }

        String username = client.getSession().getUsername();
        PasswordManager.AccountData entry = PasswordManager.getPassword(server.address, username);
        if (entry != null) {
            String cmd = entry.command().trim();
            if (cmd.startsWith("/")) {
                cmd = cmd.substring(1);
            }

            String fullCommand = cmd + " " + entry.password();
            client.player.networkHandler.sendChatCommand(fullCommand);

            SYPassToast.show(
                    Text.translatable("sypass.toast.quicklogin.title"),
                    Text.translatable("sypass.toast.quicklogin.desc", username),
                    new ItemStack(Items.TRIPWIRE_HOOK)
            );
            hasLoggedInThisSession = true;
        } else {
            SYPassToast.show(
                    Text.translatable("sypass.toast.quicklogin.title"),
                    Text.translatable("sypass.toast.quicklogin.not_found", server.address),
                    new ItemStack(Items.BARRIER)
            );
        }
    }
}