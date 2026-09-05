package com.syntren.sypass.handler;

import com.syntren.sypass.config.SYPassConfig;
import com.syntren.sypass.gui.SYPassToast;
import com.syntren.sypass.storage.BitwardenManager;
import com.syntren.sypass.storage.PasswordManager;
import com.syntren.sypass.util.PasswordGenerator;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.regex.Pattern;

public class AutoLoginHandler {
    private static int ticksToWait = -1;
    private static PasswordManager.AccountData pendingEntry = null;

    // Session control to prevent spam across subserver/lobby transfers
    private static String activeServerAddress = null;
    private static boolean hasLoggedInThisSession = false;

    // Smart authentication state & cooldowns
    private static long lastLoginAttemptMs = 0;
    private static long lastRegisterAttemptMs = 0;
    private static int loginAttemptsThisSession = 0;
    private static int registerAttemptsThisSession = 0;
    private static boolean hasPromptedRegisterToast = false;

    // Regex patterns for matching server prompts
    private static final Pattern LOGIN_PROMPT_PATTERN = Pattern.compile(
            "(?i)(?:/(?:login|l)\\b)|(?:(?:авториз|увійдіть|войдите|пароль|password|введіть|введите|login|log\\s*in)\\b.*(?:/(?:login|l)\\b))"
    );

    private static final Pattern REGISTER_PROMPT_PATTERN = Pattern.compile(
            "(?i)(?:/(?:register|reg)\\b)"
    );

    public static void register() {
        // 1. Connection to server tracking
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ServerInfo server = client.getCurrentServerEntry();
            if (server == null) return;

            String currentServerIp = server.address;
            String username = client.getSession().getUsername();
            String normServerIp = PasswordManager.normalizeServerAddress(currentServerIp);

            if (normServerIp.equalsIgnoreCase(activeServerAddress) && hasLoggedInThisSession) {
                return;
            }

            activeServerAddress = normServerIp;
            hasLoggedInThisSession = false;
            loginAttemptsThisSession = 0;
            registerAttemptsThisSession = 0;
            hasPromptedRegisterToast = false;
            lastLoginAttemptMs = 0;
            lastRegisterAttemptMs = 0;

            if (!SYPassConfig.isAutoLoginEnabled()) {
                return;
            }

            PasswordManager.AccountData entry = PasswordManager.getPassword(currentServerIp, username);
            if (entry != null) {
                pendingEntry = entry;
                ticksToWait = SYPassConfig.getAutoLoginDelayTicks();
            }
        });

        // 2. Reset session on disconnect
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            activeServerAddress = null;
            hasLoggedInThisSession = false;
            pendingEntry = null;
            ticksToWait = -1;
            loginAttemptsThisSession = 0;
            registerAttemptsThisSession = 0;
            hasPromptedRegisterToast = false;
            lastLoginAttemptMs = 0;
            lastRegisterAttemptMs = 0;
        });

        // 3. Fallback tick delay countdown and execution
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (ticksToWait > 0) {
                ticksToWait--;
            } else if (ticksToWait == 0) {
                ticksToWait = -1;

                if (client.player != null && pendingEntry != null) {
                    sendLoginCommand(client, pendingEntry, false);
                    pendingEntry = null;
                }
            }
        });

        // 4. Smart message scanning for game/system messages & action bar
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (message != null) {
                handleIncomingMessage(message.getString());
            }
        });

        // 5. Smart message scanning for server chat messages (ignore player messages to prevent spoofing)
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            if (sender == null && message != null) {
                handleIncomingMessage(message.getString());
            }
        });
    }

    private static void handleIncomingMessage(String rawText) {
        if (rawText == null || rawText.isBlank()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return;

        ServerInfo server = client.getCurrentServerEntry();
        if (server == null) return;

        String currentServerIp = server.address;
        String username = client.getSession().getUsername();
        String cleanText = rawText.replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim();
        long now = System.currentTimeMillis();

        boolean hasSavedAccount = PasswordManager.hasPassword(currentServerIp, username);

        // Case 1: Account exists -> Smart Auto-Login
        if (hasSavedAccount && SYPassConfig.isAutoLoginEnabled() && SYPassConfig.isSmartAutoLoginEnabled()) {
            if (LOGIN_PROMPT_PATTERN.matcher(cleanText).find()) {
                if (now - lastLoginAttemptMs > 4000 && loginAttemptsThisSession < 3) {
                    lastLoginAttemptMs = now;
                    loginAttemptsThisSession++;

                    ticksToWait = -1;
                    pendingEntry = null;

                    PasswordManager.AccountData entry = PasswordManager.getPassword(currentServerIp, username);
                    if (entry != null) {
                        sendLoginCommand(client, entry, true);
                    }
                }
            }
            return;
        }

        // Case 2: No account exists -> Smart Auto-Register or Prompt Toast
        if (!hasSavedAccount && SYPassConfig.isAutoLoginEnabled()) {
            if (REGISTER_PROMPT_PATTERN.matcher(cleanText).find()) {
                if (SYPassConfig.isSmartAutoRegisterEnabled()) {
                    if (now - lastRegisterAttemptMs > 5000 && registerAttemptsThisSession < 2) {
                        lastRegisterAttemptMs = now;
                        registerAttemptsThisSession++;
                        executeQuickRegister(client, 16, true);
                    }
                } else {
                    if (!hasPromptedRegisterToast && now - lastRegisterAttemptMs > 10000) {
                        hasPromptedRegisterToast = true;
                        lastRegisterAttemptMs = now;
                        SYPassToast.show(
                                Text.translatable("sypass.toast.prompt_register.title"),
                                Text.translatable("sypass.toast.prompt_register.desc"),
                                new ItemStack(Items.WRITABLE_BOOK)
                        );
                    }
                }
            }
        }
    }

    private static void sendLoginCommand(MinecraftClient client, PasswordManager.AccountData entry, boolean isSmart) {
        if (client == null || client.player == null || entry == null) return;

        String cmd = entry.command().trim();
        if (cmd.startsWith("/")) {
            cmd = cmd.substring(1);
        }

        String fullCommand = cmd + " " + entry.password();
        client.player.networkHandler.sendChatCommand(fullCommand);

        String username = client.getSession().getUsername();
        String titleKey = isSmart ? "sypass.toast.smartlogin.title" : "sypass.toast.autologin.title";
        String descKey = isSmart ? "sypass.toast.smartlogin.desc" : "sypass.toast.autologin.desc";

        SYPassToast.show(
                Text.translatable(titleKey),
                Text.translatable(descKey, username),
                new ItemStack(Items.TRIPWIRE_HOOK)
        );

        hasLoggedInThisSession = true;
    }

    /**
     * Manual login triggered via keybinding or GUI
     */
    public static void executeManualLogin(MinecraftClient client) {
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

    /**
     * Quick register with generated password, auto-save, clipboard copy, and Bitwarden sync
     */
    public static void executeQuickRegister(MinecraftClient client, int length) {
        executeQuickRegister(client, length, false);
    }

    public static void executeQuickRegister(MinecraftClient client, int length, boolean isSmart) {
        if (client == null || client.player == null) return;

        ServerInfo server = client.getCurrentServerEntry();
        if (server == null) {
            SYPassToast.show(
                    Text.translatable("sypass.toast.quickregister.title"),
                    Text.translatable("sypass.toast.quicklogin.not_server"),
                    new ItemStack(Items.BARRIER)
            );
            return;
        }

        String username = client.getSession().getUsername();
        String serverIp = server.address;
        int passLen = Math.max(6, Math.min(64, length));
        String generatedPassword = PasswordGenerator.generate(passLen);

        // Store password with standard /login command for future automatic logins
        PasswordManager.savePassword(serverIp, username, generatedPassword, "/login");

        if (SYPassConfig.isAutoSyncEnabled() && BitwardenManager.hasActiveSession()) {
            BitwardenManager.pushSingleItemAsync(serverIp, username, generatedPassword, "/login");
        }

        if (client.keyboard != null) {
            client.keyboard.setClipboard(generatedPassword);
        }

        // Send registration command: /register <pass> <pass>
        client.player.networkHandler.sendChatCommand("register " + generatedPassword + " " + generatedPassword);

        String titleKey = isSmart ? "sypass.toast.smartregister.title" : "sypass.toast.quickregister.title";
        String descKey = isSmart ? "sypass.toast.smartregister.desc" : "sypass.toast.quickregister.desc";

        SYPassToast.show(
                Text.translatable(titleKey),
                Text.translatable(descKey, username),
                new ItemStack(Items.EMERALD)
        );

        hasLoggedInThisSession = true;
    }
}
