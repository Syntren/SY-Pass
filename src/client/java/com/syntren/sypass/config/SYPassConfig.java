package com.syntren.sypass.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;

public class SYPassConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("sypass");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");

    private static ConfigData data = new ConfigData();

    public static class ConfigData {
        public boolean autoSync = true;
        public int autoLoginDelayTicks = 30;
    }

    public static void load() {
        try {
            if (!Files.exists(CONFIG_DIR)) {
                Files.createDirectories(CONFIG_DIR);
            }
            File file = CONFIG_FILE.toFile();
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
            if (!Files.exists(CONFIG_DIR)) {
                Files.createDirectories(CONFIG_DIR);
            }
            try (FileWriter writer = new FileWriter(CONFIG_FILE.toFile())) {
                GSON.toJson(data, writer);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static boolean isAutoSyncEnabled() {
        return data.autoSync;
    }

    public static void setAutoSyncEnabled(boolean enabled) {
        data.autoSync = enabled;
        save();
    }

    public static int getAutoLoginDelayTicks() {
        return Math.max(5, Math.min(200, data.autoLoginDelayTicks));
    }

    public static void setAutoLoginDelayTicks(int ticks) {
        data.autoLoginDelayTicks = Math.max(5, Math.min(200, ticks));
        save();
    }
}