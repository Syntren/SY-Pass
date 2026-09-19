package com.syntren.sypass.storage;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.syntren.sypass.config.SYPassConfig;
import com.syntren.sypass.gui.SYPassToast;
import com.syntren.sypass.platform.PlatformHelper;
import com.syntren.sypass.util.ChatProtectionMatcher;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class PasswordManager {

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(AccountData.class, new JsonDeserializer<AccountData>() {
                @Override
                public AccountData deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
                    JsonObject obj = json.getAsJsonObject();
                    char[] pass = new char[0];
                    if (obj.has("password") && !obj.get("password").isJsonNull()) {
                        JsonElement pElem = obj.get("password");
                        if (pElem.isJsonPrimitive() && pElem.getAsJsonPrimitive().isString()) {
                            pass = pElem.getAsString().toCharArray();
                        } else if (pElem.isJsonArray()) {
                            JsonArray arr = pElem.getAsJsonArray();
                            pass = new char[arr.size()];
                            for (int i = 0; i < arr.size(); i++) {
                                String s = arr.get(i).getAsString();
                                pass[i] = s.isEmpty() ? ' ' : s.charAt(0);
                            }
                        }
                    }
                    String cmd = obj.has("command") ? obj.get("command").getAsString() : "/login";
                    boolean synced = obj.has("isSynced") && obj.get("isSynced").getAsBoolean();
                    String remoteId = obj.has("remoteId") ? obj.get("remoteId").getAsString() : "";
                    boolean favorite = obj.has("isFavorite") && obj.get("isFavorite").getAsBoolean();
                    long lastUsed = obj.has("lastUsed") ? obj.get("lastUsed").getAsLong() : 0L;
                    return new AccountData(pass, cmd, synced, remoteId, favorite, lastUsed);
                }
            })
            .registerTypeAdapter(AccountData.class, new JsonSerializer<AccountData>() {
                @Override
                public JsonElement serialize(AccountData src, Type typeOfSrc, JsonSerializationContext context) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("password", src.getPasswordAsString());
                    obj.addProperty("command", src.command());
                    obj.addProperty("isSynced", src.isSynced());
                    obj.addProperty("remoteId", src.remoteId());
                    obj.addProperty("isFavorite", src.isFavorite());
                    obj.addProperty("lastUsed", src.lastUsed());
                    return obj;
                }
            })
            .setPrettyPrinting()
            .create();

    public static Path getConfigDir() {
        return PlatformHelper.get().getConfigDir().resolve("sypass");
    }

    public static Path getConfigFile() {
        return getConfigDir().resolve("sypass.json");
    }

    public static Path getKeyFile() {
        return getConfigDir().resolve("sypass.key");
    }

    public static Path getEncKeyFile() {
        return getConfigDir().resolve("sypass.key.enc");
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
    private static volatile boolean vaultLocked = false;
    private static boolean legacyFormatDetected = false;
    private static volatile long lastActivityMs = System.currentTimeMillis();

    public static void recordActivity() {
        lastActivityMs = System.currentTimeMillis();
    }

    public static synchronized void lockVault() {
        if (!SYPassConfig.isMasterPasswordEnabled()) return;
        vaultLocked = true;
        secretKey = null;
        for (Map<String, AccountData> accs : memoryData.values()) {
            for (AccountData d : accs.values()) {
                d.wipe();
            }
        }
        memoryData.clear();
        ChatProtectionMatcher.invalidateCache();
    }

    public static void checkAutoLock() {
        if (vaultLocked || !SYPassConfig.isMasterPasswordEnabled()) return;
        int timeoutMin = SYPassConfig.getAutoLockTimeoutMinutes();
        if (timeoutMin <= 0) return;
        long timeoutMs = timeoutMin * 60L * 1000L;
        if (System.currentTimeMillis() - lastActivityMs >= timeoutMs) {
            lockVault();
            if (SYPassConfig.isToastsEnabled()) {
                SYPassToast.show(
                        Component.translatable("sypass.toast.autolock.title"),
                        Component.translatable("sypass.toast.autolock.desc"),
                        new ItemStack(Items.IRON_DOOR)
                );
            }
        }
    }

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BIT = 128;

    public record AccountData(char[] password, String command, boolean isSynced, String remoteId, boolean isFavorite, long lastUsed) {
        public AccountData(char[] password, String command) {
            this(password, command, false, "", false, 0L);
        }

        public AccountData(char[] password, String command, boolean isSynced, String remoteId) {
            this(password, command, isSynced, remoteId, false, 0L);
        }

        public AccountData(String password, String command, boolean isSynced, String remoteId, boolean isFavorite, long lastUsed) {
            this(password != null ? password.toCharArray() : new char[0], command, isSynced, remoteId, isFavorite, lastUsed);
        }

        public AccountData {
            password = (password != null) ? password.clone() : new char[0];
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

        public char[] getPasswordCopy() {
            return password.clone();
        }

        public String getPasswordAsString() {
            return new String(password);
        }

        public void wipe() {
            Arrays.fill(password, '\0');
        }
    }

    public static void init() {
        try {
            Path configDir = getConfigDir();
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }

            if (SYPassConfig.isMasterPasswordEnabled() || Files.exists(getEncKeyFile())) {
                vaultLocked = true;
                secretKey = null;
                memoryData.clear();
            } else {
                vaultLocked = false;
                loadOrCreateKey();
                loadPasswords();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static boolean isVaultLocked() {
        return vaultLocked;
    }

    public static synchronized boolean unlockVault(char[] masterPassword) {
        if (!vaultLocked) return true;
        if (masterPassword == null || masterPassword.length == 0) return false;
        try {
            Path encKeyFile = getEncKeyFile();
            if (!Files.exists(encKeyFile)) return false;

            String saltBase64 = SYPassConfig.getMasterPasswordSalt();
            if (saltBase64.isBlank()) return false;
            byte[] salt = Base64.getDecoder().decode(saltBase64);
            int iterations = SYPassConfig.getMasterPasswordIterations();

            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            PBEKeySpec spec = new PBEKeySpec(masterPassword, salt, iterations, 256);
            SecretKey derivedKey = new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
            spec.clearPassword();

            byte[] encData = Files.readAllBytes(encKeyFile);
            String encStr = new String(encData, StandardCharsets.UTF_8).trim();
            if (!encStr.startsWith("gcm:")) {
                return false;
            }
            byte[] combined = Base64.getDecoder().decode(encStr.substring(4));
            if (combined.length <= GCM_IV_LENGTH) return false;

            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            byte[] cipherText = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, derivedKey, new GCMParameterSpec(GCM_TAG_LENGTH_BIT, iv));
            byte[] rawKeyBytes = cipher.doFinal(cipherText);

            secretKey = new SecretKeySpec(rawKeyBytes, "AES");
            Arrays.fill(rawKeyBytes, (byte) 0);

            vaultLocked = false;
            recordActivity();
            loadPasswords();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            Arrays.fill(masterPassword, '\0');
        }
    }

    public static synchronized boolean enableMasterPassword(char[] masterPassword) {
        if (masterPassword == null || masterPassword.length == 0) return false;
        try {
            if (secretKey == null) {
                loadOrCreateKey();
            }
            exportBackup();

            byte[] salt = new byte[16];
            new java.security.SecureRandom().nextBytes(salt);
            int iterations = 100000;

            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            PBEKeySpec spec = new PBEKeySpec(masterPassword, salt, iterations, 256);
            SecretKey derivedKey = new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
            spec.clearPassword();

            byte[] iv = new byte[GCM_IV_LENGTH];
            new java.security.SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, derivedKey, new GCMParameterSpec(GCM_TAG_LENGTH_BIT, iv));
            byte[] encKey = cipher.doFinal(secretKey.getEncoded());

            byte[] combined = new byte[iv.length + encKey.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encKey, 0, combined, iv.length, encKey.length);

            Path encKeyFile = getEncKeyFile();
            writeSecureFile(encKeyFile, ("gcm:" + Base64.getEncoder().encodeToString(combined)).getBytes(StandardCharsets.UTF_8));

            Path plainKey = getKeyFile();
            if (Files.exists(plainKey)) {
                Files.delete(plainKey);
            }

            SYPassConfig.setMasterPasswordEnabled(true);
            SYPassConfig.setMasterPasswordSalt(Base64.getEncoder().encodeToString(salt));
            SYPassConfig.setMasterPasswordIterations(iterations);
            vaultLocked = false;
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            Arrays.fill(masterPassword, '\0');
        }
    }

    public static synchronized boolean disableMasterPassword(char[] masterPassword) {
        if (masterPassword == null || masterPassword.length == 0) return false;
        try {
            Path encKeyFile = getEncKeyFile();
            if (!Files.exists(encKeyFile)) return false;

            String saltBase64 = SYPassConfig.getMasterPasswordSalt();
            byte[] salt = Base64.getDecoder().decode(saltBase64);
            int iterations = SYPassConfig.getMasterPasswordIterations();

            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            PBEKeySpec spec = new PBEKeySpec(masterPassword, salt, iterations, 256);
            SecretKey derivedKey = new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
            spec.clearPassword();

            byte[] encData = Files.readAllBytes(encKeyFile);
            String encStr = new String(encData, StandardCharsets.UTF_8).trim();
            byte[] combined = Base64.getDecoder().decode(encStr.substring(4));
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            byte[] cipherText = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, derivedKey, new GCMParameterSpec(GCM_TAG_LENGTH_BIT, iv));
            byte[] rawKeyBytes = cipher.doFinal(cipherText);

            secretKey = new SecretKeySpec(rawKeyBytes, "AES");
            writeSecureFile(getKeyFile(), rawKeyBytes);
            Arrays.fill(rawKeyBytes, (byte) 0);

            Files.deleteIfExists(encKeyFile);

            SYPassConfig.setMasterPasswordEnabled(false);
            SYPassConfig.setMasterPasswordSalt("");
            vaultLocked = false;
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            Arrays.fill(masterPassword, '\0');
        }
    }

    public static synchronized boolean resetVaultWithBitwarden() {
        try {
            Files.deleteIfExists(getEncKeyFile());
            Files.deleteIfExists(getKeyFile());
            Files.deleteIfExists(getConfigFile());
            SYPassConfig.setMasterPasswordEnabled(false);
            SYPassConfig.setMasterPasswordSalt("");

            secretKey = null;
            memoryData.clear();
            loadOrCreateKey();
            vaultLocked = false;

            if (BitwardenManager.isCliInstalled() && BitwardenManager.hasActiveSession()) {
                BitwardenManager.pullFromBitwarden();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

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
                System.err.println("[SYPass] Corrupted key size (" + keyBytes.length + " bytes). Backing up corrupted key.");
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
        char[] passChars = password != null ? password.toCharArray() : new char[0];
        try {
            savePassword(serverIp, username, passChars, command);
        } finally {
            Arrays.fill(passChars, '\0');
        }
    }

    public static synchronized void savePassword(String serverIp, String username, char[] password, String command) {
        AccountData existing = getPassword(serverIp, username);
        boolean synced = (existing != null && existing.isSynced());
        String remoteId = (existing != null && existing.remoteId() != null) ? existing.remoteId() : "";
        boolean favorite = (existing != null && existing.isFavorite());
        long lastUsed = (existing != null) ? existing.lastUsed() : 0L;
        savePassword(serverIp, username, password, command, synced, remoteId, favorite, lastUsed);
    }

    public static synchronized void savePassword(String serverIp, String username, String password, String command, boolean isSynced, String remoteId) {
        char[] passChars = password != null ? password.toCharArray() : new char[0];
        try {
            savePassword(serverIp, username, passChars, command, isSynced, remoteId);
        } finally {
            Arrays.fill(passChars, '\0');
        }
    }

    public static synchronized void savePassword(String serverIp, String username, char[] password, String command, boolean isSynced, String remoteId) {
        AccountData existing = getPassword(serverIp, username);
        boolean favorite = (existing != null && existing.isFavorite());
        long lastUsed = (existing != null) ? existing.lastUsed() : 0L;
        savePassword(serverIp, username, password, command, isSynced, remoteId, favorite, lastUsed);
    }

    public static synchronized void savePassword(String serverIp, String username, String password, String command, boolean isSynced, String remoteId, boolean isFavorite, long lastUsed) {
        char[] passChars = password != null ? password.toCharArray() : new char[0];
        try {
            savePassword(serverIp, username, passChars, command, isSynced, remoteId, isFavorite, lastUsed);
        } finally {
            Arrays.fill(passChars, '\0');
        }
    }

    public static synchronized void savePassword(String serverIp, String username, char[] password, String command, boolean isSynced, String remoteId, boolean isFavorite, long lastUsed) {
        if (serverIp == null || serverIp.isBlank() || username == null || username.isBlank() || password == null) {
            return;
        }
        String cleanServer = normalizeServerAddress(serverIp);
        username = username.trim();

        String formattedCommand = (command != null && !command.isBlank()) ? command.trim() : "/login";
        if (!formattedCommand.startsWith("/")) {
            formattedCommand = "/" + formattedCommand;
        }

        Map<String, AccountData> serverMap = memoryData.computeIfAbsent(cleanServer, k -> new ConcurrentHashMap<>());
        AccountData old = serverMap.put(username, new AccountData(password, formattedCommand, isSynced, remoteId, isFavorite, lastUsed));
        if (old != null) {
            old.wipe();
        }
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

    public static void copyPasswordToClipboard(String serverIp, String username) {
        AccountData acc = getPassword(serverIp, username);
        if (acc == null) return;
        char[] copy = acc.getPasswordCopy();
        try {
            PlatformHelper.get().copyToClipboard(new String(copy));
        } finally {
            Arrays.fill(copy, '\0');
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

    /**
     * Sanitized public view of all stored accounts.
     * Passwords are stripped to empty char arrays to protect against reflection and inspection.
     */
    public static Map<String, Map<String, AccountData>> getAllData() {
        Map<String, Map<String, AccountData>> sanitized = new HashMap<>();
        for (Map.Entry<String, Map<String, AccountData>> sEntry : memoryData.entrySet()) {
            Map<String, AccountData> sMap = new HashMap<>();
            for (Map.Entry<String, AccountData> aEntry : sEntry.getValue().entrySet()) {
                AccountData d = aEntry.getValue();
                sMap.put(aEntry.getKey(), new AccountData(new char[0], d.command(), d.isSynced(), d.remoteId(), d.isFavorite(), d.lastUsed()));
            }
            sanitized.put(sEntry.getKey(), Collections.unmodifiableMap(sMap));
        }
        return Collections.unmodifiableMap(sanitized);
    }

    /**
     * Internal raw access restricted to the package.
     */
    static Map<String, Map<String, AccountData>> getRawMemoryData() {
        return memoryData;
    }

    public static void populateProtectionAutomaton(Consumer<char[]> consumer, String serverFilter) {
        if (serverFilter == null) {
            for (Map<String, AccountData> accs : memoryData.values()) {
                for (AccountData acc : accs.values()) {
                    char[] pass = acc.password();
                    if (pass != null && pass.length > 0) {
                        consumer.accept(pass);
                    }
                }
            }
        } else {
            String norm = normalizeServerAddress(serverFilter);
            Map<String, AccountData> accs = memoryData.get(norm);
            if (accs == null) accs = memoryData.get(serverFilter.trim());
            if (accs == null) accs = memoryData.get(norm + ":25565");
            if (accs != null) {
                for (AccountData acc : accs.values()) {
                    char[] pass = acc.password();
                    if (pass != null && pass.length > 0) {
                        consumer.accept(pass);
                    }
                }
            }
        }
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
            AccountData old = serverAccounts.remove(user);
            if (old != null) {
                old.wipe();
            }
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
        if (vaultLocked) return;
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
                        for (Map<String, AccountData> m : memoryData.values()) {
                            for (AccountData d : m.values()) {
                                d.wipe();
                            }
                        }
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
                    acc.addProperty("password", aEntry.getValue().getPasswordAsString());
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
                pbeSpec.clearPassword();

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
            return importCsv(file);
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
                    pbeSpec.clearPassword();

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
                    csv.append(escapeCsvField(acc.getPasswordAsString())).append(",");
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
            return importCsv(files[0]);
        } catch (Exception e) {
            e.printStackTrace();
            return -2;
        }
    }

    public static synchronized int importBitwardenCsv(File file) {
        return importCsv(file);
    }

    /**
     * Enhanced CSV importer that auto-detects column mappings for:
     * - Bitwarden CSV
     * - KeePass 1.x & 2.x CSV
     * - 1Password CSV
     * - Generic / Browser CSV
     */
    public static synchronized int importCsv(File file) {
        if (file == null || !file.exists()) return -1;
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            if (lines.isEmpty()) return 0;

            List<String> header = parseCsvLine(lines.get(0));
            Map<String, Integer> colIndex = new HashMap<>();
            for (int i = 0; i < header.size(); i++) {
                colIndex.put(header.get(i).trim().toLowerCase(java.util.Locale.ROOT), i);
            }

            int nameIdx = getColumnIndex(colIndex, "name", "title", "server", "serverip", "host", "hostname", "address", "website", "web site", "url", "login_uri", "group", "account");
            int uriIdx = getColumnIndex(colIndex, "login_uri", "url", "website", "web site", "hostname", "server", "address");
            int userIdx = getColumnIndex(colIndex, "login_username", "username", "login name", "user", "login", "email", "account");
            int passIdx = getColumnIndex(colIndex, "login_password", "password", "pass", "code", "secret");
            int notesIdx = getColumnIndex(colIndex, "notes", "comment", "comments", "command", "cmd", "description");
            int favIdx = getColumnIndex(colIndex, "favorite", "fav", "starred");

            if (passIdx == -1) {
                return -2;
            }

            int count = 0;
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;
                List<String> cols = parseCsvLine(line);

                String server = (nameIdx >= 0 && nameIdx < cols.size()) ? cols.get(nameIdx).trim() : "";
                if (server.isEmpty() && uriIdx >= 0 && uriIdx < cols.size()) {
                    server = cols.get(uriIdx).trim();
                }

                // Strip URL prefixes if exported from browser or KeePass URL field
                if (server.startsWith("mc://")) server = server.substring(5);
                if (server.startsWith("https://")) server = server.substring(8);
                if (server.startsWith("http://")) server = server.substring(7);
                if (server.contains("/")) server = server.substring(0, server.indexOf('/'));

                String username = (userIdx >= 0 && userIdx < cols.size()) ? cols.get(userIdx).trim() : "";
                String password = (passIdx >= 0 && passIdx < cols.size()) ? cols.get(passIdx).trim() : "";
                String notes = (notesIdx >= 0 && notesIdx < cols.size()) ? cols.get(notesIdx).trim() : "";
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

                char[] passChars = password.toCharArray();
                try {
                    savePassword(server, username, passChars, cmd, false, "", favorite, 0L);
                } finally {
                    Arrays.fill(passChars, '\0');
                }
                count++;
            }

            saveToFile();
            return count;
        } catch (Exception e) {
            e.printStackTrace();
            return -2;
        }
    }

    private static int getColumnIndex(Map<String, Integer> colIndex, String... candidates) {
        for (String c : candidates) {
            Integer idx = colIndex.get(c.toLowerCase(java.util.Locale.ROOT));
            if (idx != null) {
                return idx;
            }
        }
        return -1;
    }

    public static String maskServerAddress(String serverAddress) {
        if (serverAddress == null || serverAddress.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(serverAddress.length());
        for (int i = 0; i < serverAddress.length(); i++) {
            char c = serverAddress.charAt(i);
            if (c == '.' || c == ':') {
                sb.append(c);
            } else {
                sb.append('*');
            }
        }
        return sb.toString();
    }

    public static List<String> parseCsvLine(String line) {
        List<String> result = new ArrayList<>();
        if (line == null || line.trim().isEmpty()) return result;
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
