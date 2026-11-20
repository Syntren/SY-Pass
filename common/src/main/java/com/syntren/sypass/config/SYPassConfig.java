package com.syntren.sypass.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.syntren.sypass.SYPassCommon;
import com.syntren.sypass.handler.AutoLoginHandler;
import com.syntren.sypass.platform.PlatformHelper;
import com.syntren.sypass.security.VaultCrypto;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SYPassConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile ConfigData data = new ConfigData();

    private SYPassConfig() {}

    public static Path getConfigDir() {
        return PlatformHelper.get().getConfigDir().resolve("sypass");
    }

    private static Path getConfigFile() {
        return getConfigDir().resolve("config.json");
    }

    public enum ChatProtectionScope {
        ALL_SERVERS("sypass.gui.settings.chat_scope.all"),
        CURRENT_SERVER("sypass.gui.settings.chat_scope.current");

        private final String translationKey;

        ChatProtectionScope(String translationKey) {
            this.translationKey = translationKey;
        }

        public String getTranslationKey() {
            return translationKey;
        }
    }

    public static class ConfigData {
        public boolean enableBitwarden = false;
        public boolean autoLogin = true;
        public boolean autoSync = true;
        public boolean showToasts = true;
        public int autoLoginDelayTicks = 30;
        public String customServerUrl = "";
        public boolean smartAutoLogin = true;
        public boolean smartAutoRegister = false;
        public boolean preventRegisterOverwrite = true;
        public boolean chatLeakProtection = false;
        public ChatProtectionScope chatProtectionScope = ChatProtectionScope.CURRENT_SERVER;
        public boolean streamerMode = false;
        public int defaultPasswordLength = 16;
        public boolean masterPasswordEnabled = false;
        public String masterPasswordSalt = "";
        public int masterPasswordIterations = VaultCrypto.DEFAULT_PBKDF2_ITERATIONS;
        public int autoLockTimeoutMinutes = 0;
        public String registerCommandTemplate = "/register %password% %password%";
        public List<String> customLoginPatterns = new ArrayList<>();
    }

    public static synchronized void load() {
        try {
            Path configDir = getConfigDir();
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }
            Path file = getConfigFile();
            if (Files.exists(file)) {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                ConfigData loaded = GSON.fromJson(json, ConfigData.class);
                if (loaded != null) {
                    if (loaded.customLoginPatterns == null) {
                        loaded.customLoginPatterns = new ArrayList<>();
                    }
                    if (loaded.chatProtectionScope == null) {
                        loaded.chatProtectionScope = ChatProtectionScope.CURRENT_SERVER;
                    }
                    data = loaded;
                }
            } else {
                save();
            }
        } catch (Exception e) {
            SYPassCommon.LOGGER.error("[SY-Pass] Failed to load config.json", e);
        }
    }

    public static synchronized void save() {
        try {
            byte[] bytes = GSON.toJson(data).getBytes(StandardCharsets.UTF_8);
            VaultCrypto.writeSecureFile(getConfigFile(), bytes);
        } catch (Exception e) {
            SYPassCommon.LOGGER.error("[SY-Pass] Failed to save config.json", e);
        }
    }

    public static boolean isAutoLoginEnabled() {
        return data.autoLogin;
    }

    public static void setAutoLoginEnabled(boolean enabled) {
        data.autoLogin = enabled;
        save();
    }

    public static boolean isAutoSyncEnabled() {
        return data.autoSync;
    }

    public static void setAutoSyncEnabled(boolean enabled) {
        data.autoSync = enabled;
        save();
    }

    public static boolean isToastsEnabled() {
        return data.showToasts;
    }

    public static void setToastsEnabled(boolean enabled) {
        data.showToasts = enabled;
        save();
    }

    public static int getAutoLoginDelayTicks() {
        return Math.max(5, Math.min(200, data.autoLoginDelayTicks));
    }

    public static void setAutoLoginDelayTicks(int ticks) {
        data.autoLoginDelayTicks = Math.max(5, Math.min(200, ticks));
        save();
    }

    public static String getCustomServerUrl() {
        return data.customServerUrl != null ? data.customServerUrl.trim() : "";
    }

    public static void setCustomServerUrl(String url) {
        data.customServerUrl = url != null ? url.trim() : "";
        save();
    }

    public static boolean isSmartAutoLoginEnabled() {
        return data.smartAutoLogin;
    }

    public static void setSmartAutoLoginEnabled(boolean enabled) {
        data.smartAutoLogin = enabled;
        save();
    }

    public static boolean isSmartAutoRegisterEnabled() {
        return data.smartAutoRegister;
    }

    public static void setSmartAutoRegisterEnabled(boolean enabled) {
        data.smartAutoRegister = enabled;
        save();
    }

    public static boolean isPreventRegisterOverwriteEnabled() {
        return data.preventRegisterOverwrite;
    }

    public static void setPreventRegisterOverwriteEnabled(boolean enabled) {
        data.preventRegisterOverwrite = enabled;
        save();
    }

    public static boolean isChatLeakProtectionEnabled() {
        return data.chatLeakProtection;
    }

    public static void setChatLeakProtectionEnabled(boolean enabled) {
        data.chatLeakProtection = enabled;
        save();
    }

    public static ChatProtectionScope getChatProtectionScope() {
        ChatProtectionScope scope = data.chatProtectionScope;
        return scope != null ? scope : ChatProtectionScope.CURRENT_SERVER;
    }

    public static void setChatProtectionScope(ChatProtectionScope scope) {
        data.chatProtectionScope = (scope != null) ? scope : ChatProtectionScope.CURRENT_SERVER;
        save();
    }

    public static boolean isStreamerModeEnabled() {
        return data.streamerMode;
    }

    public static void setStreamerModeEnabled(boolean enabled) {
        data.streamerMode = enabled;
        save();
    }

    public static int getDefaultPasswordLength() {
        int len = data.defaultPasswordLength;
        return (len >= 6 && len <= 64) ? len : 16;
    }

    public static void setDefaultPasswordLength(int length) {
        data.defaultPasswordLength = Math.max(6, Math.min(64, length));
        save();
    }

    public static boolean isBitwardenEnabled() {
        return data.enableBitwarden;
    }

    public static void setBitwardenEnabled(boolean enabled) {
        data.enableBitwarden = enabled;
        save();
    }

    public static boolean isMasterPasswordEnabled() {
        return data.masterPasswordEnabled;
    }

    public static void setMasterPasswordEnabled(boolean enabled) {
        data.masterPasswordEnabled = enabled;
        save();
    }

    public static String getMasterPasswordSalt() {
        return data.masterPasswordSalt != null ? data.masterPasswordSalt : "";
    }

    public static void setMasterPasswordSalt(String salt) {
        data.masterPasswordSalt = salt != null ? salt : "";
        save();
    }

    public static int getMasterPasswordIterations() {
        return data.masterPasswordIterations > 0 ? data.masterPasswordIterations : VaultCrypto.DEFAULT_PBKDF2_ITERATIONS;
    }

    public static void setMasterPasswordIterations(int iterations) {
        data.masterPasswordIterations = Math.max(10_000, iterations);
        save();
    }

    public static int getAutoLockTimeoutMinutes() {
        int timeout = data.autoLockTimeoutMinutes;
        return (timeout >= 0 && timeout <= 120) ? timeout : 0;
    }

    public static void setAutoLockTimeoutMinutes(int minutes) {
        data.autoLockTimeoutMinutes = Math.max(0, Math.min(120, minutes));
        save();
    }

    public static String getRegisterCommandTemplate() {
        if (data.registerCommandTemplate == null || data.registerCommandTemplate.isBlank()) {
            return "/register %password% %password%";
        }
        return data.registerCommandTemplate;
    }

    public static void setRegisterCommandTemplate(String template) {
        data.registerCommandTemplate = (template != null && !template.isBlank())
                ? template.trim()
                : "/register %password% %password%";
        save();
    }

    public static synchronized List<String> getCustomLoginPatterns() {
        if (data.customLoginPatterns == null) {
            data.customLoginPatterns = new ArrayList<>();
        }
        return Collections.unmodifiableList(new ArrayList<>(data.customLoginPatterns));
    }

    public static synchronized void setCustomLoginPatterns(List<String> patterns) {
        data.customLoginPatterns = patterns != null ? new ArrayList<>(patterns) : new ArrayList<>();
        save();
        AutoLoginHandler.invalidateCustomPatterns();
    }

    public static synchronized boolean addCustomLoginPattern(String pattern) {
        if (pattern == null || pattern.isBlank()) return false;
        String trimmed = pattern.trim();
        if (data.customLoginPatterns == null) {
            data.customLoginPatterns = new ArrayList<>();
        }
        if (data.customLoginPatterns.contains(trimmed)) {
            return false;
        }
        data.customLoginPatterns.add(trimmed);
        save();
        AutoLoginHandler.invalidateCustomPatterns();
        return true;
    }

    public static synchronized void removeCustomLoginPattern(String pattern) {
        if (data.customLoginPatterns != null && pattern != null) {
            if (data.customLoginPatterns.remove(pattern.trim())) {
                save();
                AutoLoginHandler.invalidateCustomPatterns();
            }
        }
    }
}
