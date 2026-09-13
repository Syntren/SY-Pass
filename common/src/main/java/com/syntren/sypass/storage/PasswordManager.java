package com.syntren.sypass.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.syntren.sypass.platform.PlatformHelper;
import com.syntren.sypass.util.ChatProtectionMatcher;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PasswordManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Path getConfigDir() {
        return PlatformHelper.get().getConfigDir().resolve("sypass");
    }

    public static Path getConfigFile() {
        return getConfigDir().resolve("sypass.json");
    }

    public static Path getKeyFile() {
        return getConfigDir().resolve("sypass.key");
    }

    public static Path getOldConfigFile() {
        return PlatformHelper.get().getConfigDir().resolve("sypass.json");
    }

    public static Path getOldKeyFile() {
        return PlatformHelper.get().getConfigDir().resolve("sypass.key");
    }

    private static final Map<String, Map<String, AccountData>> memoryData = new ConcurrentHashMap<>();
    private static String savedBwSessionKey = "";
    private static SecretKey secretKey;

    public record AccountData(String password, String command, boolean isSynced, String remoteId, boolean isFavorite, long lastUsed) {
        public AccountData(String password, String command) {
            this(password, command, false, "", false, 0L);
        }

        public AccountData(String password, String command, boolean isSynced, String remoteId) {
            this(password, command, isSynced, remoteId, false, 0L);
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
            Path configDir = getConfigDir();
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }
            loadOrCreateKey();
            loadPasswords();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static boolean legacyFormatDetected = false;

    public static void enforceSecurePermissions(Path path) {
        if (path == null || !Files.exists(path)) return;
        try {
            Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            File file = path.toFile();
            file.setReadable(false, false);
            file.setReadable(true, true);
            file.setWritable(false, false);
            file.setWritable(true, true);
        } catch (Exception e) {
            System.err.println("[SYPass] Failed to enforce strict file permissions: " + e.getMessage());
        }
    }

    public static void writeSecureFile(Path path, byte[] data) throws Exception {
        if (path.getParent() != null && !Files.exists(path.getParent())) {
            Files.createDirectories(path.getParent());
        }
        Path tempFile = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(tempFile, data);
        enforceSecurePermissions(tempFile);
        try {
            Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING);
        }
        enforceSecurePermissions(path);
    }

    private static void loadOrCreateKey() throws Exception {
        Path keyFile = getKeyFile();
        Path oldKeyFile = getOldKeyFile();
        Path targetKeyFile = Files.exists(keyFile) ? keyFile : (Files.exists(oldKeyFile) ? oldKeyFile : null);

        if (targetKeyFile != null) {
            byte[] keyBytes = Files.readAllBytes(targetKeyFile);
            if (keyBytes.length == 32 || keyBytes.length == 16) {
                secretKey = new SecretKeySpec(keyBytes, "AES");
                if (targetKeyFile.equals(oldKeyFile) && !Files.exists(keyFile)) {
                    writeSecureFile(keyFile, keyBytes);
                } else {
                    enforceSecurePermissions(keyFile);
                }
                return;
            } else {
                System.err.println("[SYPass] Corrupted or invalid key size (" + keyBytes.length + " bytes). Backing up corrupted key.");
                Files.move(targetKeyFile, targetKeyFile.resolveSibling(targetKeyFile.getFileName() + ".corrupted"), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        secretKey = keyGen.generateKey();
        writeSecureFile(keyFile, secretKey.getEncoded());
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
        boolean favorite = (existing != null && existing.isFavorite());
        long lastUsed = (existing != null) ? existing.lastUsed() : 0L;
        savePassword(serverIp, username, password, command, synced, remoteId, favorite, lastUsed);
    }

    public static synchronized void savePassword(String serverIp, String username, String password, String command, boolean isSynced, String remoteId) {
        AccountData existing = getPassword(serverIp, username);
        boolean favorite = (existing != null && existing.isFavorite());
        long lastUsed = (existing != null) ? existing.lastUsed() : 0L;
        savePassword(serverIp, username, password, command, isSynced, remoteId, favorite, lastUsed);
    }

    public static synchronized void savePassword(String serverIp, String username, String password, String command, boolean isSynced, String remoteId, boolean isFavorite, long lastUsed) {
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
                .put(username, new AccountData(password, formattedCommand, isSynced, remoteId, isFavorite, lastUsed));
        saveToFile();
    }

    public static synchronized void toggleFavorite(String serverIp, String username) {
        AccountData existing = getPassword(serverIp, username);
        if (existing != null) {
            savePassword(serverIp, username, existing.password(), existing.command(), existing.isSynced(), existing.remoteId(), !existing.isFavorite(), existing.lastUsed());
        }
    }

    public static synchronized void setFavorite(String serverIp, String username, boolean favorite) {
        AccountData existing = getPassword(serverIp, username);
        if (existing != null) {
            savePassword(serverIp, username, existing.password(), existing.command(), existing.isSynced(), existing.remoteId(), favorite, existing.lastUsed());
        }
    }

    public static synchronized void updateLastUsed(String serverIp, String username) {
        AccountData existing = getPassword(serverIp, username);
        if (existing != null) {
            savePassword(serverIp, username, existing.password(), existing.command(), existing.isSynced(), existing.remoteId(), existing.isFavorite(), System.currentTimeMillis());
        }
    }

    public static AccountData getPassword(String serverIp, String username) {
        if (serverIp == null || username == null) return null;
        String cleanUser = username.trim();
        String normServer = normalizeServerAddress(serverIp);

        Map<String, AccountData> serverAccounts = memoryData.get(normServer);
        if (serverAccounts != null && serverAccounts.containsKey(cleanUser)) {
            return serverAccounts.get(cleanUser);
        }

        String rawServer = serverIp.trim();
        if (!rawServer.equals(normServer)) {
            serverAccounts = memoryData.get(rawServer);
            if (serverAccounts != null && serverAccounts.containsKey(cleanUser)) {
                return serverAccounts.get(cleanUser);
            }
        }

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
                serverAccounts.put(username.trim(), new AccountData(old.password(), old.command(), false, "", old.isFavorite(), old.lastUsed()));
                saveToFile();
            }
        }
    }

    public static synchronized void resetSyncFlags() {
        for (Map.Entry<String, Map<String, AccountData>> sEntry : memoryData.entrySet()) {
            for (Map.Entry<String, AccountData> aEntry : sEntry.getValue().entrySet()) {
                AccountData old = aEntry.getValue();
                if (old.isSynced()) {
                    aEntry.setValue(new AccountData(old.password(), old.command(), false, "", old.isFavorite(), old.lastUsed()));
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
            Path configDir = getConfigDir();
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }
            String rawJson = GSON.toJson(memoryData);
            String encryptedJson = encrypt(rawJson);

            Map<String, String> wrapper = new HashMap<>();
            wrapper.put("vault", encryptedJson);
            if (savedBwSessionKey != null && !savedBwSessionKey.isBlank()) {
                wrapper.put("bw_session", encrypt(savedBwSessionKey));
            }
            String jsonOutput = GSON.toJson(wrapper);
            writeSecureFile(getConfigFile(), jsonOutput.getBytes(StandardCharsets.UTF_8));
            ChatProtectionMatcher.invalidateCache();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void loadPasswords() {
        File file = getConfigFile().toFile();
        if (!file.exists()) {
            file = getOldConfigFile().toFile();
            if (!file.exists()) return;
        }

        legacyFormatDetected = false;
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

                if (legacyFormatDetected || file.equals(getOldConfigFile().toFile())) {
                    System.out.println("[SYPass] Migrating legacy ECB vault to secure AES-GCM format...");
                    saveToFile();
                } else {
                    enforceSecurePermissions(file.toPath());
                }
                ChatProtectionMatcher.invalidateCache();
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
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BIT, iv);
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
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BIT, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

            byte[] decryptedBytes = cipher.doFinal(cipherText);
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        }

        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            byte[] decryptedBytes = cipher.doFinal(Base64.getDecoder().decode(encryptedData));
            legacyFormatDetected = true;
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
            Path backupsDir = getConfigDir().resolve("backups");
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
                    acc.addProperty("isFavorite", aEntry.getValue().isFavorite());
                    acc.addProperty("lastUsed", aEntry.getValue().lastUsed());
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
                wrapper.addProperty("mode", "plain");
                wrapper.add("servers", servers);
            }

            writeSecureFile(backupFile, GSON.toJson(wrapper).getBytes(StandardCharsets.UTF_8));
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
            Path backupsDir = getConfigDir().resolve("backups");
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
        if (file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".csv")) {
            return importBitwardenCsv(file);
        }
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
                    return -3;
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
                    return -4;
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
                    return -5;
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
                    boolean favorite = acc.has("isFavorite") && acc.get("isFavorite").getAsBoolean();
                    long lastUsed = acc.has("lastUsed") ? acc.get("lastUsed").getAsLong() : 0L;
                    savePassword(serverIp, user, pass, cmd, synced, remoteId, favorite, lastUsed);
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

    public static synchronized String exportBitwardenCsv() {
        try {
            Path backupsDir = getConfigDir().resolve("backups");
            if (!Files.exists(backupsDir)) {
                Files.createDirectories(backupsDir);
            }
            String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new java.util.Date());
            Path exportFile = backupsDir.resolve("sypass-export-bitwarden-" + timestamp + ".csv");

            StringBuilder csv = new StringBuilder();
            csv.append("folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp\n");

            for (Map.Entry<String, Map<String, AccountData>> sEntry : memoryData.entrySet()) {
                String serverIp = sEntry.getKey();
                for (Map.Entry<String, AccountData> aEntry : sEntry.getValue().entrySet()) {
                    String username = aEntry.getKey();
                    AccountData acc = aEntry.getValue();

                    csv.append(escapeCsvField("Minecraft")).append(",");
                    csv.append(acc.isFavorite() ? "1" : "0").append(",");
                    csv.append("login,");
                    csv.append(escapeCsvField(serverIp)).append(",");
                    csv.append(escapeCsvField(acc.command())).append(",");
                    csv.append(",");
                    csv.append("0,");
                    csv.append(escapeCsvField(serverIp)).append(",");
                    csv.append(escapeCsvField(username)).append(",");
                    csv.append(escapeCsvField(acc.password())).append(",");
                    csv.append("\n");
                }
            }

            writeSecureFile(exportFile, csv.toString().getBytes(StandardCharsets.UTF_8));
            return exportFile.getFileName().toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static String escapeCsvField(String field) {
        if (field == null) return "";
        if (field.contains(",") || field.contains("\"") || field.contains("\n") || field.contains("\r")) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }

    public static synchronized int importLatestCsvBackup() {
        try {
            Path backupsDir = getConfigDir().resolve("backups");
            if (!Files.exists(backupsDir)) {
                return -1;
            }
            File[] files = backupsDir.toFile().listFiles((dir, name) -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".csv"));
            if (files == null || files.length == 0) {
                return -1;
            }
            java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            return importBitwardenCsv(files[0]);
        } catch (Exception e) {
            e.printStackTrace();
            return -2;
        }
    }

    public static synchronized int importBitwardenCsv(File file) {
        if (file == null || !file.exists()) return -1;
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            if (lines.isEmpty()) return 0;

            List<String> header = parseCsvLine(lines.get(0));
            Map<String, Integer> colIndex = new HashMap<>();
            for (int i = 0; i < header.size(); i++) {
                colIndex.put(header.get(i).trim().toLowerCase(java.util.Locale.ROOT), i);
            }

            int nameIdx = colIndex.getOrDefault("name", colIndex.getOrDefault("title", -1));
            int userIdx = colIndex.getOrDefault("login_username", colIndex.getOrDefault("username", -1));
            int passIdx = colIndex.getOrDefault("login_password", colIndex.getOrDefault("password", -1));
            int notesIdx = colIndex.getOrDefault("notes", colIndex.getOrDefault("comment", -1));
            int favIdx = colIndex.getOrDefault("favorite", -1);
            int uriIdx = colIndex.getOrDefault("login_uri", colIndex.getOrDefault("url", -1));

            if (passIdx == -1) {
                return -2;
            }

            int count = 0;
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;
                List<String> cols = parseCsvLine(line);

                String server = nameIdx >= 0 && nameIdx < cols.size() ? cols.get(nameIdx).trim() : "";
                if (server.isEmpty() && uriIdx >= 0 && uriIdx < cols.size()) {
                    server = cols.get(uriIdx).trim();
                }
                String username = userIdx >= 0 && userIdx < cols.size() ? cols.get(userIdx).trim() : "";
                String password = passIdx >= 0 && passIdx < cols.size() ? cols.get(passIdx).trim() : "";
                String notes = notesIdx >= 0 && notesIdx < cols.size() ? cols.get(notesIdx).trim() : "";
                boolean favorite = false;
                if (favIdx >= 0 && favIdx < cols.size()) {
                    String favVal = cols.get(favIdx).trim();
                    favorite = "1".equals(favVal) || "true".equalsIgnoreCase(favVal);
                }

                if (server.isEmpty() || username.isEmpty() || password.isEmpty()) {
                    continue;
                }

                String cmd = "/login";
                if (notes.startsWith("/")) {
                    int firstSpace = notes.indexOf(' ');
                    cmd = firstSpace > 0 ? notes.substring(0, firstSpace) : notes;
                }

                savePassword(server, username, password, cmd, false, "", favorite, 0L);
                count++;
            }

            saveToFile();
            return count;
        } catch (Exception e) {
            e.printStackTrace();
            return -2;
        }
    }

    public static List<String> parseCsvLine(String line) {
        List<String> result = new ArrayList<>();
        if (line == null) return result;
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '\"') {
                    current.append('\"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        result.add(current.toString());
        return result;
    }
}
