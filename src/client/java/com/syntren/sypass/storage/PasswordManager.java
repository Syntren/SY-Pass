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

    private static final Path OLD_CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("sypass.json");
    private static final Path OLD_KEY_FILE = FabricLoader.getInstance().getConfigDir().resolve("sypass.key");

    private static final Map<String, Map<String, AccountData>> memoryData = new ConcurrentHashMap<>();
    private static String savedBwSessionKey = "";
    private static SecretKey secretKey;

    public record AccountData(String password, String command, boolean isSynced, String remoteId) {
        public AccountData(String password, String command) {
            this(password, command, false, "");
        }

        public AccountData {
            if (command == null || command.isBlank()) {
                command = "/login";
            }
            if (!command.startsWith("/")) {
                command = "/" + command;
            }
            if (remoteId == null) {
                remoteId = "";
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
        if (Files.exists(KEY_FILE)) {
            byte[] keyBytes = Files.readAllBytes(KEY_FILE);
            secretKey = new SecretKeySpec(keyBytes, "AES");
            return;
        }

        if (Files.exists(OLD_KEY_FILE)) {
            byte[] keyBytes = Files.readAllBytes(OLD_KEY_FILE);
            secretKey = new SecretKeySpec(keyBytes, "AES");
            Files.write(KEY_FILE, keyBytes);
            return;
        }

        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        secretKey = keyGen.generateKey();
        Files.write(KEY_FILE, secretKey.getEncoded());
    }

    public static synchronized void savePassword(String serverIp, String username, String password, String command) {
        savePassword(serverIp, username, password, command, false, "");
    }

    public static synchronized void savePassword(String serverIp, String username, String password, String command, boolean isSynced, String remoteId) {
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
                .put(username, new AccountData(password, formattedCommand, isSynced, remoteId));
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

    public static synchronized void unmarkSynced(String serverIp, String username) {
        if (serverIp == null || username == null) return;
        Map<String, AccountData> serverAccounts = memoryData.get(serverIp.trim());
        if (serverAccounts != null) {
            AccountData old = serverAccounts.get(username.trim());
            if (old != null) {
                serverAccounts.put(username.trim(), new AccountData(old.password(), old.command(), false, ""));
                saveToFile();
            }
        }
    }

    // Скидання прапорців синхронізації на локальні (при виході з акаунту)
    public static synchronized void resetSyncFlags() {
        for (Map.Entry<String, Map<String, AccountData>> sEntry : memoryData.entrySet()) {
            for (Map.Entry<String, AccountData> aEntry : sEntry.getValue().entrySet()) {
                AccountData old = aEntry.getValue();
                if (old.isSynced()) {
                    aEntry.setValue(new AccountData(old.password(), old.command(), false, ""));
                }
            }
        }
        saveToFile();
    }

    public static synchronized void saveBwSession(String session) {
        savedBwSessionKey = session != null ? session.trim() : "";
        saveToFile();
    }

    public static String getSavedBwSession() {
        return savedBwSessionKey;
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
                if (savedBwSessionKey != null && !savedBwSessionKey.isBlank()) {
                    wrapper.put("bw_session", encrypt(savedBwSessionKey));
                }
                GSON.toJson(wrapper, writer);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void loadPasswords() {
        File file = CONFIG_FILE.toFile();
        if (!file.exists()) {
            file = OLD_CONFIG_FILE.toFile();
            if (!file.exists()) return;
        }

        try (FileReader reader = new FileReader(file)) {
            Map<String, String> wrapper = GSON.fromJson(reader, new TypeToken<Map<String, String>>(){}.getType());
            if (wrapper != null) {
                if (wrapper.containsKey("vault")) {
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

                if (wrapper.containsKey("bw_session")) {
                    try {
                        savedBwSessionKey = decrypt(wrapper.get("bw_session")).trim();
                        if (!savedBwSessionKey.isBlank()) {
                            BitwardenManager.setSessionKey(savedBwSessionKey, false);
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BIT = 128;

    private static String encrypt(String data) throws Exception {
        if (secretKey == null) {
            loadOrCreateKey();
        }
        byte[] iv = new byte[GCM_IV_LENGTH];
        new java.security.SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        javax.crypto.spec.GCMParameterSpec spec = new javax.crypto.spec.GCMParameterSpec(GCM_TAG_LENGTH_BIT, iv);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);

        byte[] encryptedBytes = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + encryptedBytes.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encryptedBytes, 0, combined, iv.length, encryptedBytes.length);

        return "gcm:" + Base64.getEncoder().encodeToString(combined);
    }

    private static String decrypt(String encryptedData) throws Exception {
        if (secretKey == null) {
            loadOrCreateKey();
        }
        if (encryptedData != null && encryptedData.startsWith("gcm:")) {
            byte[] combined = Base64.getDecoder().decode(encryptedData.substring(4));
            if (combined.length <= GCM_IV_LENGTH) {
                throw new IllegalArgumentException("Invalid encrypted payload size");
            }
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            byte[] cipherText = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            javax.crypto.spec.GCMParameterSpec spec = new javax.crypto.spec.GCMParameterSpec(GCM_TAG_LENGTH_BIT, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

            byte[] decryptedBytes = cipher.doFinal(cipherText);
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        }

        // Fallback for legacy encrypted files (ECB mode)
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            byte[] decryptedBytes = cipher.doFinal(Base64.getDecoder().decode(encryptedData));
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt data", e);
        }
    }
}