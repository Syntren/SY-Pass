package com.syntren.sypass.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.syntren.sypass.handler.AutoLoginHandler;
import com.syntren.sypass.platform.PlatformHelper;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class SYPassConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path getConfigDir() {
        return PlatformHelper.get().getConfigDir().resolve("sypass");
    }

    private static Path getConfigFile() {
        return getConfigDir().resolve("config.json");
    }

    private static ConfigData data = new ConfigData();

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
        public int masterPasswordIterations = 100000;
        public String masterPasswordVerifier = "";
        public int autoLockTimeoutMinutes = 0;
        public String registerCommandTemplate = "/register %password% %password%";
        public List<String> customLoginPatterns = new ArrayList<>();
    }

    public static void load() {
        try {
            Path configDir = getConfigDir();
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }
            File file = getConfigFile().toFile();
            if (file.exists()) {
                try (FileReader reader = new FileReader(file)) {
                    ConfigData loaded = GSON.fromJson(reader, ConfigData.class);
                    if (loaded != null) {
                        data = loaded;
                    }
                }
            } else {
                save();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void save() {
        try {
            Path configDir = getConfigDir();
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }
            try (FileWriter writer = new FileWriter(getConfigFile().toFile())) {
                GSON.toJson(data, writer);
            }
        } catch (Exception e) {
            e.printStackTrace();
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
        if (data.chatProtectionScope == null) {
            data.chatProtectionScope = ChatProtectionScope.CURRENT_SERVER;
        }
        return data.chatProtectionScope;
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
        if (data.defaultPasswordLength < 6 || data.defaultPasswordLength > 64) {
            data.defaultPasswordLength = 16;
        }
        return data.defaultPasswordLength;
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
        return data.masterPasswordIterations > 0 ? data.masterPasswordIterations : 100000;
    }

    public static void setMasterPasswordIterations(int iterations) {
        data.masterPasswordIterations = Math.max(10000, iterations);
        save();
    }

    public static String getMasterPasswordVerifier() {
        return data.masterPasswordVerifier != null ? data.masterPasswordVerifier : "";
    }

    public static void setMasterPasswordVerifier(String verifier) {
        data.masterPasswordVerifier = verifier != null ? verifier : "";
        save();
    }

    public static int getAutoLockTimeoutMinutes() {
        if (data.autoLockTimeoutMinutes < 0 || data.autoLockTimeoutMinutes > 120) {
            data.autoLockTimeoutMinutes = 0;
        }
        return data.autoLockTimeoutMinutes;
    }

    public static void setAutoLockTimeoutMinutes(int minutes) {
        data.autoLockTimeoutMinutes = Math.max(0, Math.min(120, minutes));
        save();
    }

    public static String getRegisterCommandTemplate() {
        if (data.registerCommandTemplate == null || data.registerCommandTemplate.isBlank()) {
            data.registerCommandTemplate = "/register %password% %password%";
        }
        return data.registerCommandTemplate;
    }

    public static void setRegisterCommandTemplate(String template) {
        data.registerCommandTemplate = (template != null && !template.isBlank()) ? template.trim() : "/register %password% %password%";
        save();
    }

    public static List<String> getCustomLoginPatterns() {
        if (data.customLoginPatterns == null) {
            data.customLoginPatterns = new ArrayList<>();
        }
        return data.customLoginPatterns;
    }

    public static void setCustomLoginPatterns(List<String> patterns) {
        data.customLoginPatterns = patterns != null ? new ArrayList<>(patterns) : new ArrayList<>();
        save();
        AutoLoginHandler.invalidateCustomPatterns();
    }

    public static void addCustomLoginPattern(String pattern) {
        if (pattern != null && !pattern.isBlank()) {
            if (data.customLoginPatterns == null) {
                data.customLoginPatterns = new ArrayList<>();
            }
            if (!data.customLoginPatterns.contains(pattern.trim())) {
                data.customLoginPatterns.add(pattern.trim());
                save();
                AutoLoginHandler.invalidateCustomPatterns();
            }
        }
    }

    public static void removeCustomLoginPattern(String pattern) {
        if (data.customLoginPatterns != null && pattern != null) {
            data.customLoginPatterns.remove(pattern.trim());
            save();
            AutoLoginHandler.invalidateCustomPatterns();
        }
    }
}
