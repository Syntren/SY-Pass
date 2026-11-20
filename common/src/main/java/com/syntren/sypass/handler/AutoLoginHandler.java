package com.syntren.sypass.handler;

import com.syntren.sypass.config.SYPassConfig;
import com.syntren.sypass.gui.EditPasswordScreen;
import com.syntren.sypass.gui.SYPassScreen;
import com.syntren.sypass.gui.SYPassToast;
import com.syntren.sypass.platform.PlatformHelper;
import com.syntren.sypass.storage.AccountData;
import com.syntren.sypass.storage.BitwardenManager;
import com.syntren.sypass.storage.PasswordManager;
import com.syntren.sypass.util.PasswordGenerator;
import com.syntren.sypass.util.ServerAddressUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class AutoLoginHandler {
    private static final long SCAN_WINDOW_MS = 25_000L;
    private static final long LOGIN_COOLDOWN_MS = 4_000L;
    private static final long REGISTER_COOLDOWN_MS = 5_000L;
    private static final long PROMPT_TOAST_COOLDOWN_MS = 10_000L;
    private static final int MAX_LOGIN_ATTEMPTS = 3;
    private static final int MAX_REGISTER_ATTEMPTS = 2;

    private static final Pattern STRIP_COLOR_PATTERN = Pattern.compile("(?i)§[0-9a-fk-or]");

    private static final Pattern LOGIN_PROMPT_PATTERN = Pattern.compile(
            "(?i)(?:(?:введіть|введите|enter|type|use|please|пароль|password|авториз|увійдіть|войдите|login|log\\s*in)\\b.*(?:/(?:login|l)\\b))|" +
            "(?:/(?:login|l)\\s+<(?:пароль|password)>)|" +
            "(?:(?:авториз|увійдіть|войдите|authenticate).*:(?:\\s*/(?:login|l)\\b)?)"
    );

    private static final Pattern REGISTER_PROMPT_PATTERN = Pattern.compile("(?i)(?:/(?:register|reg)\\b)");

    private static int ticksToWait = -1;
    private static int autoLockTickCounter = 0;
    private static AccountData pendingEntry = null;
    private static volatile boolean dispatchingAuth = false;

    private static String activeServerAddress = null;
    private static boolean hasLoggedInThisSession = false;
    private static long connectionTimeMs = 0L;
    private static boolean sessionScanningActive = false;

    private static long lastLoginAttemptMs = 0L;
    private static long lastRegisterAttemptMs = 0L;
    private static int loginAttemptsThisSession = 0;
    private static int registerAttemptsThisSession = 0;
    private static boolean hasPromptedRegisterToast = false;

    private static volatile List<Pattern> cachedCustomPatterns = null;

    private AutoLoginHandler() {}

    public static boolean isDispatchingAuth() {
        return dispatchingAuth;
    }

    public static boolean isValidRegex(String pattern) {
        if (pattern == null || pattern.isBlank()) return false;
        try {
            Pattern.compile(pattern.trim(), Pattern.CASE_INSENSITIVE);
            return true;
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    public static void invalidateCustomPatterns() {
        cachedCustomPatterns = null;
    }

    public static List<Pattern> getCompiledCustomPatterns() {
        List<Pattern> patterns = cachedCustomPatterns;
        if (patterns == null) {
            List<Pattern> compiled = new ArrayList<>();
            for (String pat : SYPassConfig.getCustomLoginPatterns()) {
                if (pat != null && !pat.isBlank()) {
                    try {
                        compiled.add(Pattern.compile(pat.trim(), Pattern.CASE_INSENSITIVE));
                    } catch (PatternSyntaxException ignored) {}
                }
            }
            cachedCustomPatterns = compiled;
            patterns = compiled;
        }
        return patterns;
    }

    public static void onJoinServer(String serverAddress, String username) {
        if (serverAddress == null || username == null) return;

        String normServerIp = ServerAddressUtil.normalize(serverAddress);
        if (normServerIp.equalsIgnoreCase(activeServerAddress) && hasLoggedInThisSession) {
            return;
        }

        resetSessionState();
        activeServerAddress = normServerIp;
        connectionTimeMs = System.currentTimeMillis();
        sessionScanningActive = true;

        if (PasswordManager.isVaultLocked() || !SYPassConfig.isAutoLoginEnabled()) {
            return;
        }

        AccountData entry = PasswordManager.getPassword(serverAddress, username);
        if (entry != null) {
            pendingEntry = entry;
            ticksToWait = SYPassConfig.isSmartAutoLoginEnabled()
                    ? Math.max(100, SYPassConfig.getAutoLoginDelayTicks() * 3)
                    : SYPassConfig.getAutoLoginDelayTicks();
        }
    }

    public static void onDisconnect() {
        resetSessionState();
    }

    private static void resetSessionState() {
        activeServerAddress = null;
        hasLoggedInThisSession = false;
        connectionTimeMs = 0L;
        sessionScanningActive = false;
        pendingEntry = null;
        ticksToWait = -1;
        loginAttemptsThisSession = 0;
        registerAttemptsThisSession = 0;
        hasPromptedRegisterToast = false;
        lastLoginAttemptMs = 0L;
        lastRegisterAttemptMs = 0L;
    }

    public static void onClientTick(Minecraft client) {
        if (ticksToWait > 0) {
            ticksToWait--;
        } else if (ticksToWait == 0) {
            ticksToWait = -1;
            if (client != null && client.player != null && pendingEntry != null) {
                dispatchLogin(client, pendingEntry, "sypass.toast.autologin.title", "sypass.toast.autologin.desc");
                pendingEntry = null;
            }
        }

        if (++autoLockTickCounter >= 20) {
            autoLockTickCounter = 0;
            if (client != null) {
                if (client.screen instanceof SYPassScreen || client.screen instanceof EditPasswordScreen) {
                    PasswordManager.recordActivity();
                } else {
                    PasswordManager.checkAutoLock();
                }
            }
        }
    }

    public static void onIncomingMessage(String rawText, Minecraft client) {
        if (!sessionScanningActive || rawText == null || rawText.isBlank()) return;

        long now = System.currentTimeMillis();
        if (now - connectionTimeMs > SCAN_WINDOW_MS) {
            sessionScanningActive = false;
            return;
        }

        if (client == null || client.player == null) return;
        ServerData server = client.getCurrentServer();
        if (server == null) return;

        if (rawText.indexOf('/') == -1 && rawText.indexOf(':') == -1) {
            return;
        }

        String lowerRaw = rawText.toLowerCase(Locale.ROOT);
        boolean mightBeLogin = lowerRaw.contains("login") || lowerRaw.contains("/l")
                || lowerRaw.contains("auth") || lowerRaw.contains("парол")
                || lowerRaw.contains("увійдіть") || lowerRaw.contains("войдите");
        boolean mightBeRegister = lowerRaw.contains("reg");

        if (!mightBeLogin && !mightBeRegister && getCompiledCustomPatterns().isEmpty()) {
            return;
        }

        if (PasswordManager.isVaultLocked()) {
            return;
        }

        String currentServerIp = server.ip;
        String username = client.getUser().getName();
        String cleanText = (rawText.indexOf('§') >= 0)
                ? STRIP_COLOR_PATTERN.matcher(rawText).replaceAll("").trim()
                : rawText.trim();

        boolean hasSavedAccount = PasswordManager.hasPassword(currentServerIp, username);

        if (hasSavedAccount && SYPassConfig.isAutoLoginEnabled() && SYPassConfig.isSmartAutoLoginEnabled() && !hasLoggedInThisSession) {
            boolean matched = mightBeLogin && LOGIN_PROMPT_PATTERN.matcher(cleanText).find();
            if (!matched) {
                for (Pattern pat : getCompiledCustomPatterns()) {
                    if (pat.matcher(cleanText).find()) {
                        matched = true;
                        break;
                    }
                }
            }

            if (matched && now - lastLoginAttemptMs > LOGIN_COOLDOWN_MS && loginAttemptsThisSession < MAX_LOGIN_ATTEMPTS) {
                lastLoginAttemptMs = now;
                loginAttemptsThisSession++;
                ticksToWait = -1;
                pendingEntry = null;

                AccountData entry = PasswordManager.getPassword(currentServerIp, username);
                if (entry != null) {
                    dispatchLogin(client, entry, "sypass.toast.smartlogin.title", "sypass.toast.smartlogin.desc");
                }
            }
            return;
        }

        if (mightBeRegister && !hasSavedAccount && SYPassConfig.isAutoLoginEnabled()
                && REGISTER_PROMPT_PATTERN.matcher(cleanText).find()) {
            if (SYPassConfig.isSmartAutoRegisterEnabled()) {
                if (now - lastRegisterAttemptMs > REGISTER_COOLDOWN_MS && registerAttemptsThisSession < MAX_REGISTER_ATTEMPTS) {
                    lastRegisterAttemptMs = now;
                    registerAttemptsThisSession++;
                    executeQuickRegister(client, SYPassConfig.getDefaultPasswordLength(), true);
                }
            } else if (!hasPromptedRegisterToast && now - lastRegisterAttemptMs > PROMPT_TOAST_COOLDOWN_MS) {
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
            showVaultLockedToast();
            return;
        }

        String username = client.getUser().getName();
        AccountData entry = PasswordManager.getPassword(server.ip, username);
        if (entry != null) {
            dispatchLogin(client, entry, "sypass.toast.quicklogin.title", "sypass.toast.quicklogin.desc");
        } else {
            SYPassToast.show(
                    Component.translatable("sypass.toast.quicklogin.title"),
                    Component.translatable("sypass.toast.quicklogin.not_found", server.ip),
                    new ItemStack(Items.BARRIER)
            );
        }
    }

    private static void dispatchLogin(Minecraft client, AccountData entry, String toastTitleKey, String toastDescKey) {
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
            dispatchingAuth = true;
            client.player.connection.sendCommand(cmd + " " + new String(passChars));
        } finally {
            dispatchingAuth = false;
            Arrays.fill(passChars, '\0');
        }

        String username = client.getUser().getName();
        SYPassToast.show(
                Component.translatable(toastTitleKey),
                Component.translatable(toastDescKey, username),
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
            showVaultLockedToast();
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

            String registerCmd = buildRegisterCommand(SYPassConfig.getRegisterCommandTemplate(), generatedPassword);
            dispatchingAuth = true;
            client.player.connection.sendCommand(registerCmd);
        } finally {
            dispatchingAuth = false;
            Arrays.fill(passChars, '\0');
        }

        SYPassToast.show(
                Component.translatable(isSmart ? "sypass.toast.smartregister.title" : "sypass.toast.quickregister.title"),
                Component.translatable(isSmart ? "sypass.toast.smartregister.desc" : "sypass.toast.quickregister.desc", username),
                new ItemStack(Items.EMERALD)
        );

        hasLoggedInThisSession = true;
        sessionScanningActive = false;
        return true;
    }

    static String buildRegisterCommand(String template, String password) {
        String cmd;
        if (template.contains("%password%") || template.contains("%pass%") || template.contains("%p%")) {
            cmd = template
                    .replace("%password%", password)
                    .replace("%pass%", password)
                    .replace("%p%", password);
        } else {
            cmd = template + " " + password + " " + password;
        }
        return cmd.startsWith("/") ? cmd.substring(1) : cmd;
    }

    private static void showVaultLockedToast() {
        SYPassToast.show(
                Component.translatable("sypass.toast.vault_locked.title"),
                Component.translatable("sypass.toast.vault_locked.desc", "["),
                new ItemStack(Items.IRON_DOOR)
        );
    }
}
