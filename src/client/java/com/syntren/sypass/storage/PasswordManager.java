package com.syntren.sypass.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
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

    public static String normalizeServerAddress(String address) {
        if (address == null) return "";
        String clean = address.trim().toLowerCase(java.util.Locale.ROOT);
        if (clean.endsWith(":25565")) {
            clean = clean.substring(0, clean.length() - 6);
        }
        if (clean.endsWith(".")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }

    public static synchronized void savePassword(String serverIp, String username, String password, String command) {
        AccountData existing = getPassword(serverIp, username);
        boolean synced = (existing != null && existing.isSynced());
        String remoteId = (existing != null && existing.remoteId() != null) ? existing.remoteId() : "";
        savePassword(serverIp, username, password, command, synced, remoteId);
    }

    public static synchronized void savePassword(String serverIp, String username, String password, String command, boolean isSynced, String remoteId) {
        if (serverIp == null || serverIp.isBlank() || username == null || username.isBlank() || password == null) {
            return;
        }
        String cleanServer = normalizeServerAddress(serverIp);
        username = username.trim();
        password = password.trim();

        String formattedCommand = (command != null && !command.isBlank()) ? command.trim() : "/login";
        if (!formattedCommand.startsWith("/")) {
            formattedCommand = "/" + formattedCommand;
        }

        memoryData.computeIfAbsent(cleanServer, k -> new ConcurrentHashMap<>())
                .put(username, new AccountData(password, formattedCommand, isSynced, remoteId));
        saveToFile();
    }

    public static AccountData getPassword(String serverIp, String username) {
        if (serverIp == null || username == null) return null;
        String cleanUser = username.trim();
        String normServer = normalizeServerAddress(serverIp);

        // 1. Пошук за нормалізованою адресою (без :25565, у нижньому регістрі)
        Map<String, AccountData> serverAccounts = memoryData.get(normServer);
        if (serverAccounts != null && serverAccounts.containsKey(cleanUser)) {
            return serverAccounts.get(cleanUser);
        }

        // 2. Зворотна сумісність: пошук за сирим рядком
        String rawServer = serverIp.trim();
        if (!rawServer.equals(normServer)) {
            serverAccounts = memoryData.get(rawServer);
            if (serverAccounts != null && serverAccounts.containsKey(cleanUser)) {
                return serverAccounts.get(cleanUser);
            }
        }

        // 3. Зворотна сумісність: пошук за адресою з портом :25565
        serverAccounts = memoryData.get(normServer + ":25565");
        if (serverAccounts != null && serverAccounts.containsKey(cleanUser)) {
            return serverAccounts.get(cleanUser);
        }

        return null;
    }

    public static boolean hasPassword(String serverIp, String username) {
        return getPassword(serverIp, username) != null;
    }

    public static Map<String, AccountData> getServerAccounts(String serverIp) {
        if (serverIp == null) return Collections.emptyMap();
        String normServer = normalizeServerAddress(serverIp);
        Map<String, AccountData> accs = memoryData.get(normServer);
        if (accs != null) return accs;

        String rawServer = serverIp.trim();
        if (!rawServer.equals(normServer)) {
            accs = memoryData.get(rawServer);
            if (accs != null) return accs;
        }

        return memoryData.getOrDefault(normServer + ":25565", Collections.emptyMap());
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
        String normServer = normalizeServerAddress(serverIp);
        String rawServer = serverIp.trim();
        String cleanUser = username.trim();

        boolean changed = removePasswordFromMap(normServer, cleanUser);
        if (!rawServer.equals(normServer)) {
            changed |= removePasswordFromMap(rawServer, cleanUser);
        }
        changed |= removePasswordFromMap(normServer + ":25565", cleanUser);

        if (changed) {
            saveToFile();
        }
    }

    private static boolean removePasswordFromMap(String serverKey, String user) {
        Map<String, AccountData> serverAccounts = memoryData.get(serverKey);
        if (serverAccounts != null) {
            serverAccounts.remove(user);
            if (serverAccounts.isEmpty()) {
                memoryData.remove(serverKey);
            }
            return true;
        }
        return false;
    }

    public static synchronized void unmarkSynced(String serverIp, String username) {
        if (serverIp == null || username == null) return;
        String normServer = normalizeServerAddress(serverIp);
        Map<String, AccountData> serverAccounts = memoryData.get(normServer);
        if (serverAccounts == null) {
            serverAccounts = memoryData.get(serverIp.trim());
        }
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
                            String normKey = normalizeServerAddress(entry.getKey());
                            Map<String, AccountData> currentMap = memoryData.computeIfAbsent(normKey, k -> new ConcurrentHashMap<>());
                            currentMap.putAll(entry.getValue());
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

    public static synchronized String exportBackup() {
        return exportBackup(null);
    }

    public static synchronized String exportBackup(String backupPassword) {
        try {
            Path backupsDir = CONFIG_DIR.resolve("backups");
            if (!Files.exists(backupsDir)) {
                Files.createDirectories(backupsDir);
            }
            String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new java.util.Date());
            Path backupFile = backupsDir.resolve("sypass-backup-" + timestamp + ".json");

            JsonObject root = new JsonObject();
            JsonObject servers = new JsonObject();
            for (Map.Entry<String, Map<String, AccountData>> sEntry : memoryData.entrySet()) {
                JsonObject accs = new JsonObject();
                for (Map.Entry<String, AccountData> aEntry : sEntry.getValue().entrySet()) {
                    JsonObject acc = new JsonObject();
                    acc.addProperty("password", aEntry.getValue().password());
                    acc.addProperty("command", aEntry.getValue().command());
                    acc.addProperty("isSynced", aEntry.getValue().isSynced());
                    acc.addProperty("remoteId", aEntry.getValue().remoteId());
                    accs.add(aEntry.getKey(), acc);
                }
                servers.add(sEntry.getKey(), accs);
            }
            root.addProperty("version", 1);
            root.addProperty("timestamp", timestamp);
            root.add("servers", servers);

            String jsonStr = GSON.toJson(root);

            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("version", 1);
            wrapper.addProperty("timestamp", timestamp);

            if (backupPassword != null && !backupPassword.isBlank()) {
                byte[] salt = new byte[16];
                byte[] iv = new byte[GCM_IV_LENGTH];
                java.security.SecureRandom random = new java.security.SecureRandom();
                random.nextBytes(salt);
                random.nextBytes(iv);

                SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                PBEKeySpec pbeSpec = new PBEKeySpec(backupPassword.trim().toCharArray(), salt, 100000, 256);
                SecretKey derivedKey = new SecretKeySpec(factory.generateSecret(pbeSpec).getEncoded(), "AES");

                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, derivedKey, new GCMParameterSpec(GCM_TAG_LENGTH_BIT, iv));
                byte[] cipherBytes = cipher.doFinal(jsonStr.getBytes(StandardCharsets.UTF_8));

                wrapper.addProperty("mode", "pbkdf2");
                wrapper.addProperty("kdf", "PBKDF2WithHmacSHA256");
                wrapper.addProperty("iterations", 100000);
                wrapper.addProperty("salt", Base64.getEncoder().encodeToString(salt));
                wrapper.addProperty("iv", Base64.getEncoder().encodeToString(iv));
                wrapper.addProperty("ciphertext", Base64.getEncoder().encodeToString(cipherBytes));
            } else {
                // Без пароля: відкритий портативний JSON, імпортується скрізь без пароля!
                wrapper.addProperty("mode", "plain");
                wrapper.add("servers", servers);
            }

            try (FileWriter writer = new FileWriter(backupFile.toFile())) {
                GSON.toJson(wrapper, writer);
            }
            return backupFile.getFileName().toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static synchronized int importLatestBackup() {
        return importLatestBackup(null);
    }

    public static synchronized int importLatestBackup(String backupPassword) {
        try {
            Path backupsDir = CONFIG_DIR.resolve("backups");
            if (!Files.exists(backupsDir)) {
                return -1;
            }
            File[] files = backupsDir.toFile().listFiles((dir, name) -> name.endsWith(".json"));
            if (files == null || files.length == 0) {
                return -1;
            }
            java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            return importBackupFile(files[0], backupPassword);
        } catch (Exception e) {
            e.printStackTrace();
            return -2;
        }
    }

    public static synchronized int importBackupFile(File file) {
        return importBackupFile(file, null);
    }

    public static synchronized int importBackupFile(File file, String backupPassword) {
        if (file == null || !file.exists()) return -1;
        try (FileReader reader = new FileReader(file)) {
            JsonObject wrapper = GSON.fromJson(reader, JsonObject.class);
            if (wrapper == null) return -2;

            JsonObject servers = null;

            if (wrapper.has("mode") && "plain".equalsIgnoreCase(wrapper.get("mode").getAsString())) {
                if (wrapper.has("servers")) {
                    servers = wrapper.getAsJsonObject("servers");
                }
            } else if (wrapper.has("mode") && "pbkdf2".equalsIgnoreCase(wrapper.get("mode").getAsString())) {
                if (backupPassword == null || backupPassword.isBlank()) {
                    return -3; // Password required
                }
                try {
                    byte[] salt = Base64.getDecoder().decode(wrapper.get("salt").getAsString());
                    byte[] iv = Base64.getDecoder().decode(wrapper.get("iv").getAsString());
                    byte[] cipherBytes = Base64.getDecoder().decode(wrapper.get("ciphertext").getAsString());
                    int iterations = wrapper.has("iterations") ? wrapper.get("iterations").getAsInt() : 100000;

                    SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                    PBEKeySpec pbeSpec = new PBEKeySpec(backupPassword.trim().toCharArray(), salt, iterations, 256);
                    SecretKey derivedKey = new SecretKeySpec(factory.generateSecret(pbeSpec).getEncoded(), "AES");

                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE, derivedKey, new GCMParameterSpec(GCM_TAG_LENGTH_BIT, iv));
                    byte[] plainBytes = cipher.doFinal(cipherBytes);
                    String decryptedJson = new String(plainBytes, StandardCharsets.UTF_8);

                    JsonObject root = JsonParser.parseString(decryptedJson).getAsJsonObject();
                    if (root.has("servers")) {
                        servers = root.getAsJsonObject("servers");
                    }
                } catch (java.security.GeneralSecurityException e) {
                    return -4; // Wrong password
                }
            } else if (wrapper.has("encrypted_backup") || (wrapper.has("mode") && "local_key".equalsIgnoreCase(wrapper.get("mode").getAsString()))) {
                String encryptedStr = wrapper.has("ciphertext") ? wrapper.get("ciphertext").getAsString() : wrapper.get("encrypted_backup").getAsString();
                try {
                    String decryptedJson = decrypt(encryptedStr);
                    JsonObject root = JsonParser.parseString(decryptedJson).getAsJsonObject();
                    if (root.has("servers")) {
                        servers = root.getAsJsonObject("servers");
                    }
                } catch (Exception e) {
                    return -5; // Key mismatch
                }
            } else if (wrapper.has("servers")) {
                servers = wrapper.getAsJsonObject("servers");
            } else {
                return -2;
            }

            if (servers == null) return -2;
            int count = 0;
            for (String serverIp : servers.keySet()) {
                JsonObject accs = servers.getAsJsonObject(serverIp);
                for (String user : accs.keySet()) {
                    JsonObject acc = accs.getAsJsonObject(user);
                    String pass = acc.get("password").getAsString();
                    String cmd = acc.has("command") ? acc.get("command").getAsString() : "/login";
                    boolean synced = acc.has("isSynced") && acc.get("isSynced").getAsBoolean();
                    String remoteId = acc.has("remoteId") ? acc.get("remoteId").getAsString() : "";
                    savePassword(serverIp, user, pass, cmd, synced, remoteId);
                    count++;
                }
            }
            saveToFile();
            return count;
        } catch (Exception e) {
            e.printStackTrace();
            return -2;
        }
    }
}