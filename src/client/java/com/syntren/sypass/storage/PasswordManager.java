package com.syntren.sypass.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PasswordManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("sypass");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("sypass.json");
    private static final Path KEY_FILE = CONFIG_DIR.resolve("sypass.key");

    // Legacy fallback paths
    private static final Path OLD_CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("sypass.json");
    private static final Path OLD_KEY_FILE = FabricLoader.getInstance().getConfigDir().resolve("sypass.key");

    // Структура: IP Сервера -> (Нікнейм -> Дані акаунта)
    private static final Map<String, Map<String, AccountData>> memoryData = new ConcurrentHashMap<>();
    private static SecretKey secretKey;

    public record AccountData(String password, String command) {
        public AccountData {
            if (command == null || command.isBlank()) {
                command = "/login";
            }
            if (!command.startsWith("/")) {
                command = "/" + command;
            }
        }
    }

    public static void init() {
        try {
            if (!Files.exists(CONFIG_DIR)) {
                Files.createDirectories(CONFIG_DIR);
            }
            loadOrCreateKey();
            loadPasswords();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void loadOrCreateKey() throws Exception {
        // Check current key location
        if (Files.exists(KEY_FILE)) {
            byte[] keyBytes = Files.readAllBytes(KEY_FILE);
            secretKey = new SecretKeySpec(keyBytes, "AES");
            return;
        }

        // Migrate from old location if present
        if (Files.exists(OLD_KEY_FILE)) {
            byte[] keyBytes = Files.readAllBytes(OLD_KEY_FILE);
            secretKey = new SecretKeySpec(keyBytes, "AES");
            Files.write(KEY_FILE, keyBytes);
            return;
        }

        // Generate new key
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        secretKey = keyGen.generateKey();
        Files.write(KEY_FILE, secretKey.getEncoded());
    }

    public static synchronized void savePassword(String serverIp, String username, String password, String command) {
        if (serverIp == null || serverIp.isBlank() || username == null || username.isBlank() || password == null) {
            return;
        }
        serverIp = serverIp.trim();
        username = username.trim();
        password = password.trim();

        String formattedCommand = (command != null && !command.isBlank()) ? command.trim() : "/login";
        if (!formattedCommand.startsWith("/")) {
            formattedCommand = "/" + formattedCommand;
        }

        memoryData.computeIfAbsent(serverIp, k -> new ConcurrentHashMap<>())
                .put(username, new AccountData(password, formattedCommand));
        saveToFile();
    }

    public static AccountData getPassword(String serverIp, String username) {
        if (serverIp == null || username == null) return null;
        Map<String, AccountData> serverAccounts = memoryData.get(serverIp.trim());
        if (serverAccounts != null) {
            return serverAccounts.get(username.trim());
        }
        return null;
    }

    public static boolean hasPassword(String serverIp, String username) {
        return getPassword(serverIp, username) != null;
    }

    public static Map<String, AccountData> getServerAccounts(String serverIp) {
        if (serverIp == null) return Collections.emptyMap();
        return memoryData.getOrDefault(serverIp.trim(), Collections.emptyMap());
    }

    public static Map<String, Map<String, AccountData>> getAllData() {
        return memoryData;
    }

    public static int getTotalCount() {
        int total = 0;
        for (Map<String, AccountData> accs : memoryData.values()) {
            total += accs.size();
        }
        return total;
    }

    public static synchronized void removePassword(String serverIp, String username) {
        if (serverIp == null || username == null) return;
        String cleanServer = serverIp.trim();
        String cleanUser = username.trim();

        Map<String, AccountData> serverAccounts = memoryData.get(cleanServer);
        if (serverAccounts != null) {
            serverAccounts.remove(cleanUser);
            if (serverAccounts.isEmpty()) {
                memoryData.remove(cleanServer);
            }
            saveToFile();
        }
    }

    private static synchronized void saveToFile() {
        try {
            if (!Files.exists(CONFIG_DIR)) {
                Files.createDirectories(CONFIG_DIR);
            }
            try (FileWriter writer = new FileWriter(CONFIG_FILE.toFile())) {
                String rawJson = GSON.toJson(memoryData);
                String encryptedJson = encrypt(rawJson);

                Map<String, String> wrapper = new HashMap<>();
                wrapper.put("vault", encryptedJson);
                GSON.toJson(wrapper, writer);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void loadPasswords() {
        File file = CONFIG_FILE.toFile();
        if (!file.exists()) {
            // Check legacy path
            file = OLD_CONFIG_FILE.toFile();
            if (!file.exists()) return;
        }

        try (FileReader reader = new FileReader(file)) {
            Map<String, String> wrapper = GSON.fromJson(reader, new TypeToken<Map<String, String>>(){}.getType());
            if (wrapper != null && wrapper.containsKey("vault")) {
                String decryptedJson = decrypt(wrapper.get("vault"));
                Map<String, Map<String, AccountData>> loadedData = GSON.fromJson(
                        decryptedJson,
                        new TypeToken<Map<String, Map<String, AccountData>>>(){}.getType()
                );
                if (loadedData != null) {
                    memoryData.clear();
                    for (Map.Entry<String, Map<String, AccountData>> entry : loadedData.entrySet()) {
                        memoryData.put(entry.getKey(), new ConcurrentHashMap<>(entry.getValue()));
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String encrypt(String data) throws Exception {
        if (secretKey == null) {
            loadOrCreateKey();
        }
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        byte[] encryptedBytes = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encryptedBytes);
    }

    private static String decrypt(String encryptedData) throws Exception {
        if (secretKey == null) {
            loadOrCreateKey();
        }
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.DECRYPT_MODE, secretKey);
        byte[] decryptedBytes = cipher.doFinal(Base64.getDecoder().decode(encryptedData));
        return new String(decryptedBytes, StandardCharsets.UTF_8);
    }
}