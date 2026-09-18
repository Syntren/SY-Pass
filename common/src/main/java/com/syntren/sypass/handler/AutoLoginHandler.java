package com.syntren.sypass.handler;

import com.syntren.sypass.config.SYPassConfig;
import com.syntren.sypass.gui.SYPassToast;
import com.syntren.sypass.platform.PlatformHelper;
import com.syntren.sypass.storage.BitwardenManager;
import com.syntren.sypass.storage.PasswordManager;
import com.syntren.sypass.util.PasswordGenerator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Arrays;
import java.util.regex.Pattern;

public class AutoLoginHandler {
    private static int ticksToWait = -1;
    private static PasswordManager.AccountData pendingEntry = null;

    // Session control to prevent spam across subserver/lobby transfers
    private static String activeServerAddress = null;
    private static boolean hasLoggedInThisSession = false;
    private static long connectionTimeMs = 0L;
    private static boolean sessionScanningActive = false;

    // Smart authentication state & cooldowns
    private static long lastLoginAttemptMs = 0;
    private static long lastRegisterAttemptMs = 0;
    private static int loginAttemptsThisSession = 0;
    private static int registerAttemptsThisSession = 0;
    private static boolean hasPromptedRegisterToast = false;

    // Regex patterns for matching server prompts
    private static final Pattern STRIP_COLOR_PATTERN = Pattern.compile("(?i)§[0-9a-fk-or]");

    private static final Pattern LOGIN_PROMPT_PATTERN = Pattern.compile(
            "(?i)(?:(?:введіть|введите|enter|type|use|please|пароль|password|авториз|увійдіть|войдите|login|log\\s*in)\\b.*(?:/(?:login|l)\\b))|" +
            "(?:/(?:login|l)\\s+<(?:пароль|password)>)|" +
            "(?:(?:авториз|увійдіть|войдите|authenticate).*:(?:\\s*/(?:login|l)\\b)?)"
    );

    private static final Pattern REGISTER_PROMPT_PATTERN = Pattern.compile(
            "(?i)(?:/(?:register|reg)\\b)"
    );

    public static void onJoinServer(String serverAddress, String username) {
        if (serverAddress == null || username == null) return;

        String normServerIp = PasswordManager.normalizeServerAddress(serverAddress);

        if (normServerIp.equalsIgnoreCase(activeServerAddress) && hasLoggedInThisSession) {
            return;
        }

        activeServerAddress = normServerIp;
        hasLoggedInThisSession = false;
        connectionTimeMs = System.currentTimeMillis();
        sessionScanningActive = true;
        loginAttemptsThisSession = 0;
        registerAttemptsThisSession = 0;
        hasPromptedRegisterToast = false;
        lastLoginAttemptMs = 0;
        lastRegisterAttemptMs = 0;

        if (PasswordManager.isVaultLocked() || !SYPassConfig.isAutoLoginEnabled()) {
            return;
        }

        PasswordManager.AccountData entry = PasswordManager.getPassword(serverAddress, username);
        if (entry != null) {
            pendingEntry = entry;
            if (SYPassConfig.isSmartAutoLoginEnabled()) {
                ticksToWait = Math.max(100, SYPassConfig.getAutoLoginDelayTicks() * 3);
            } else {
                ticksToWait = SYPassConfig.getAutoLoginDelayTicks();
            }
        }
    }

    public static void onDisconnect() {
        activeServerAddress = null;
        hasLoggedInThisSession = false;
        connectionTimeMs = 0L;
        sessionScanningActive = false;
        pendingEntry = null;
        ticksToWait = -1;
        loginAttemptsThisSession = 0;
        registerAttemptsThisSession = 0;
        hasPromptedRegisterToast = false;
        lastLoginAttemptMs = 0;
        lastRegisterAttemptMs = 0;
    }

    public static void onClientTick(Minecraft client) {
        if (ticksToWait > 0) {
            ticksToWait--;
        } else if (ticksToWait == 0) {
            ticksToWait = -1;

            if (client.player != null && pendingEntry != null) {
                sendLoginCommand(client, pendingEntry, false);
                pendingEntry = null;
            }
        }
    }

    public static void onIncomingMessage(String rawText, Minecraft client) {
        if (!sessionScanningActive || rawText == null || rawText.isBlank()) return;

        long now = System.currentTimeMillis();
        if (now - connectionTimeMs > 25000) {
            sessionScanningActive = false;
            return;
        }

        if (client == null || client.player == null) return;
        ServerData server = client.getCurrentServer();
        if (server == null) return;

        if (rawText.indexOf('/') == -1 && rawText.indexOf(':') == -1) {
            return;
        }

        String lowerRaw = rawText.toLowerCase(java.util.Locale.ROOT);
        boolean mightBeLogin = lowerRaw.contains("login") || lowerRaw.contains("/l") || lowerRaw.contains("auth") || lowerRaw.contains("парол") || lowerRaw.contains("увійдіть") || lowerRaw.contains("войдите");
        boolean mightBeRegister = lowerRaw.contains("reg");

        if (!mightBeLogin && !mightBeRegister) {
            return;
        }

        String currentServerIp = server.ip;
        String username = client.getUser().getName();

        String cleanText = (rawText.indexOf('§') >= 0)
                ? STRIP_COLOR_PATTERN.matcher(rawText).replaceAll("").trim()
                : rawText.trim();

        boolean hasSavedAccount = PasswordManager.hasPassword(currentServerIp, username);

        if (PasswordManager.isVaultLocked()) {
            return;
        }

        if (mightBeLogin && hasSavedAccount && SYPassConfig.isAutoLoginEnabled() && SYPassConfig.isSmartAutoLoginEnabled() && !hasLoggedInThisSession) {
            boolean matched = LOGIN_PROMPT_PATTERN.matcher(cleanText).find();
            if (!matched) {
                for (String pat : SYPassConfig.getCustomLoginPatterns()) {
                    if (pat != null && !pat.isBlank()) {
                        try {
                            if (Pattern.compile(pat, Pattern.CASE_INSENSITIVE).matcher(cleanText).find()) {
                                matched = true;
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }

            if (matched) {
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

        if (mightBeRegister && !hasSavedAccount && SYPassConfig.isAutoLoginEnabled()) {
            if (REGISTER_PROMPT_PATTERN.matcher(cleanText).find()) {
                if (SYPassConfig.isSmartAutoRegisterEnabled()) {
                    if (now - lastRegisterAttemptMs > 5000 && registerAttemptsThisSession < 2) {
                        lastRegisterAttemptMs = now;
                        registerAttemptsThisSession++;
                        executeQuickRegister(client, SYPassConfig.getDefaultPasswordLength(), true);
                    }
                } else {
                    if (!hasPromptedRegisterToast && now - lastRegisterAttemptMs > 10000) {
                        hasPromptedRegisterToast = true;
                        lastRegisterAttemptMs = now;
                        SYPassToast.show(
                                Component.translatable("sypass.toast.prompt_register.title"),
                                Component.translatable("sypass.toast.prompt_register.desc"),
                                new ItemStack(Items.WRITABLE_BOOK)
                        );
                    }
                }
            }
        }
    }

    private static void sendLoginCommand(Minecraft client, PasswordManager.AccountData entry, boolean isSmart) {
        if (client == null || client.player == null || entry == null) return;

        String cmd = entry.command().trim().replaceAll("[\\r\\n]", "");
        if (cmd.startsWith("/")) {
            cmd = cmd.substring(1);
        }
        if (cmd.isBlank()) {
            cmd = "login";
        }

        char[] passChars = entry.getPasswordCopy();
        if (passChars.length == 0) return;

        try {
            String fullCommand = cmd + " " + new String(passChars);
            client.player.connection.sendCommand(fullCommand);
        } finally {
            Arrays.fill(passChars, '\0');
        }

        String username = client.getUser().getName();
        String titleKey = isSmart ? "sypass.toast.smartlogin.title" : "sypass.toast.autologin.title";
        String descKey = isSmart ? "sypass.toast.smartlogin.desc" : "sypass.toast.autologin.desc";

        SYPassToast.show(
                Component.translatable(titleKey),
                Component.translatable(descKey, username),
                new ItemStack(Items.TRIPWIRE_HOOK)
        );

        ServerData currentServer = client.getCurrentServer();
        if (currentServer != null && currentServer.ip != null) {
            PasswordManager.updateLastUsed(currentServer.ip, username);
        }
        lastLoginAttemptMs = System.currentTimeMillis();
        hasLoggedInThisSession = true;
        sessionScanningActive = false;
    }

    public static void executeManualLogin(Minecraft client) {
        if (client == null || client.player == null) return;

        ServerData server = client.getCurrentServer();
        if (server == null) {
            SYPassToast.show(
                    Component.translatable("sypass.toast.quicklogin.title"),
                    Component.translatable("sypass.toast.quicklogin.not_server"),
                    new ItemStack(Items.BARRIER)
            );
            return;
        }

        if (PasswordManager.isVaultLocked()) {
            SYPassToast.show(
                    Component.translatable("sypass.toast.vault_locked.title"),
                    Component.translatable("sypass.toast.vault_locked.desc", "["),
                    new ItemStack(Items.IRON_DOOR)
            );
            return;
        }

        String username = client.getUser().getName();
        PasswordManager.AccountData entry = PasswordManager.getPassword(server.ip, username);
        if (entry != null) {
            String cmd = entry.command().trim().replaceAll("[\\r\\n]", "");
            if (cmd.startsWith("/")) {
                cmd = cmd.substring(1);
            }
            if (cmd.isBlank()) {
                cmd = "login";
            }

            char[] passChars = entry.getPasswordCopy();
            if (passChars.length == 0) return;

            try {
                String fullCommand = cmd + " " + new String(passChars);
                client.player.connection.sendCommand(fullCommand);
            } finally {
                Arrays.fill(passChars, '\0');
            }

            SYPassToast.show(
                    Component.translatable("sypass.toast.quicklogin.title"),
                    Component.translatable("sypass.toast.quicklogin.desc", username),
                    new ItemStack(Items.TRIPWIRE_HOOK)
            );
            PasswordManager.updateLastUsed(server.ip, username);
            hasLoggedInThisSession = true;
            sessionScanningActive = false;
        } else {
            SYPassToast.show(
                    Component.translatable("sypass.toast.quicklogin.title"),
                    Component.translatable("sypass.toast.quicklogin.not_found", server.ip),
                    new ItemStack(Items.BARRIER)
            );
        }
    }

    public static boolean executeQuickRegister(Minecraft client) {
        return executeQuickRegister(client, SYPassConfig.getDefaultPasswordLength(), false);
    }

    public static boolean executeQuickRegister(Minecraft client, int length) {
        return executeQuickRegister(client, length, false);
    }

    public static boolean executeQuickRegister(Minecraft client, int length, boolean isSmart) {
        if (client == null || client.player == null) return false;

        ServerData server = client.getCurrentServer();
        if (server == null) {
            SYPassToast.show(
                    Component.translatable("sypass.toast.quickregister.title"),
                    Component.translatable("sypass.toast.quicklogin.not_server"),
                    new ItemStack(Items.BARRIER)
            );
            return false;
        }

        if (PasswordManager.isVaultLocked()) {
            SYPassToast.show(
                    Component.translatable("sypass.toast.vault_locked.title"),
                    Component.translatable("sypass.toast.vault_locked.desc", "["),
                    new ItemStack(Items.IRON_DOOR)
            );
            return false;
        }

        String username = client.getUser().getName();
        String serverIp = server.ip;

        if (SYPassConfig.isPreventRegisterOverwriteEnabled() && PasswordManager.hasPassword(serverIp, username)) {
            SYPassToast.show(
                    Component.translatable(isSmart ? "sypass.toast.smartregister.title" : "sypass.toast.quickregister.title"),
                    Component.translatable("sypass.toast.quickregister.already_exists", username),
                    new ItemStack(Items.BARRIER)
            );
            client.player.displayClientMessage(Component.translatable("sypass.chat.register.already_exists", username, serverIp), false);
            return false;
        }

        int passLen = Math.max(6, Math.min(64, length));
        String generatedPassword = PasswordGenerator.generate(passLen);
        char[] passChars = generatedPassword.toCharArray();

        try {
            PasswordManager.savePassword(serverIp, username, passChars, "/login");

            if (SYPassConfig.isBitwardenEnabled() && SYPassConfig.isAutoSyncEnabled() && BitwardenManager.hasActiveSession()) {
                BitwardenManager.pushSingleItemAsync(serverIp, username, generatedPassword, "/login");
            }

            PlatformHelper.get().copyToClipboard(passChars);

            String template = SYPassConfig.getRegisterCommandTemplate();
            String registerCmd = template
                    .replace("%password%", generatedPassword)
                    .replace("%pass%", generatedPassword);
            if (registerCmd.startsWith("/")) {
                registerCmd = registerCmd.substring(1);
            }
            client.player.connection.sendCommand(registerCmd);
        } finally {
            Arrays.fill(passChars, '\0');
        }

        String titleKey = isSmart ? "sypass.toast.smartregister.title" : "sypass.toast.quickregister.title";
        String descKey = isSmart ? "sypass.toast.smartregister.desc" : "sypass.toast.quickregister.desc";

        SYPassToast.show(
                Component.translatable(titleKey),
                Component.translatable(descKey, username),
                new ItemStack(Items.EMERALD)
        );

        hasLoggedInThisSession = true;
        sessionScanningActive = false;
        return true;
    }
}
