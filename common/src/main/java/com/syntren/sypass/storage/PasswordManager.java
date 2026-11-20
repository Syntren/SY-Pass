package com.syntren.sypass.storage;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.syntren.sypass.SYPassCommon;
import com.syntren.sypass.config.SYPassConfig;
import com.syntren.sypass.gui.SYPassToast;
import com.syntren.sypass.platform.PlatformHelper;
import com.syntren.sypass.security.VaultCrypto;
import com.syntren.sypass.util.ChatProtectionMatcher;
import com.syntren.sypass.util.ServerAddressUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Core encrypted local vault manager.
 * Delegates cryptography to {@link VaultCrypto}, CSV I/O to {@link CsvCodec}, and backups to {@link BackupService}.
 */
public final class PasswordManager {

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(AccountData.class, new AccountDataAdapter())
            .setPrettyPrinting()
            .create();

    private static final Type VAULT_MAP_TYPE = new TypeToken<Map<String, Map<String, AccountData>>>() {}.getType();
    private static final Type STRING_MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private static final Map<String, Map<String, AccountData>> memoryData = new ConcurrentHashMap<>();
    private static volatile String savedBwSessionKey = "";
    private static volatile SecretKey secretKey;
    private static volatile boolean vaultLocked = false;
    private static volatile boolean legacyFormatDetected = false;
    private static volatile long lastActivityMs = System.currentTimeMillis();

    private PasswordManager() {}

    // =========================================================================
    // Paths
    // =========================================================================

    public static Path getConfigDir() {
        return SYPassConfig.getConfigDir();
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

    public static Path getBackupsDir() {
        return getConfigDir().resolve("backups");
    }

    public static Path getOldConfigFile() {
        return PlatformHelper.get().getConfigDir().resolve("sypass.json");
    }

    public static Path getOldKeyFile() {
        return PlatformHelper.get().getConfigDir().resolve("sypass.key");
    }

    // =========================================================================
    // Lifecycle & Vault Locking
    // =========================================================================

    public static synchronized void init() {
        try {
            Path configDir = getConfigDir();
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }

            if (SYPassConfig.isMasterPasswordEnabled() || Files.exists(getEncKeyFile())) {
                vaultLocked = true;
                secretKey = null;
                wipeAndClearMemory();
            } else {
                vaultLocked = false;
                loadOrCreateKey();
                loadPasswords();
            }
        } catch (Exception e) {
            SYPassCommon.LOGGER.error("[SY-Pass] Failed to initialize PasswordManager", e);
        }
    }

    public static void recordActivity() {
        lastActivityMs = System.currentTimeMillis();
    }

    public static boolean isVaultLocked() {
        return vaultLocked;
    }

    public static synchronized void lockVault() {
        if (!SYPassConfig.isMasterPasswordEnabled()) return;
        vaultLocked = true;
        secretKey = null;
        wipeAndClearMemory();
        ChatProtectionMatcher.invalidateCache();
    }

    public static void checkAutoLock() {
        if (vaultLocked || !SYPassConfig.isMasterPasswordEnabled()) return;
        int timeoutMin = SYPassConfig.getAutoLockTimeoutMinutes();
        if (timeoutMin <= 0) return;

        long timeoutMs = timeoutMin * 60_000L;
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

    public static synchronized boolean unlockVault(char[] masterPassword) {
        if (!vaultLocked) return true;
        if (masterPassword == null || masterPassword.length == 0) return false;
        try {
            byte[] rawKeyBytes = decryptMasterKeyFile(masterPassword);
            if (rawKeyBytes == null) return false;
            try {
                secretKey = new SecretKeySpec(rawKeyBytes, "AES");
            } finally {
                Arrays.fill(rawKeyBytes, (byte) 0);
            }
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

            byte[] salt = VaultCrypto.randomBytes(VaultCrypto.SALT_LENGTH);
            int iterations = VaultCrypto.DEFAULT_PBKDF2_ITERATIONS;
            SecretKey derivedKey = VaultCrypto.deriveKey(masterPassword, salt, iterations);

            byte[] rawKey = secretKey.getEncoded();
            String encryptedPayload;
            try {
                encryptedPayload = VaultCrypto.encryptGcm(rawKey, derivedKey);
            } finally {
                Arrays.fill(rawKey, (byte) 0);
            }

            VaultCrypto.writeSecureFile(getEncKeyFile(), encryptedPayload.getBytes(StandardCharsets.UTF_8));
            Files.deleteIfExists(getKeyFile());

            SYPassConfig.setMasterPasswordEnabled(true);
            SYPassConfig.setMasterPasswordSalt(Base64.getEncoder().encodeToString(salt));
            SYPassConfig.setMasterPasswordIterations(iterations);
            vaultLocked = false;
            recordActivity();
            return true;
        } catch (Exception e) {
            SYPassCommon.LOGGER.error("[SY-Pass] Failed to enable Master Password", e);
            return false;
        } finally {
            Arrays.fill(masterPassword, '\0');
        }
    }

    public static synchronized boolean disableMasterPassword(char[] masterPassword) {
        if (masterPassword == null || masterPassword.length == 0) return false;
        try {
            byte[] rawKeyBytes = decryptMasterKeyFile(masterPassword);
            if (rawKeyBytes == null) return false;
            try {
                secretKey = new SecretKeySpec(rawKeyBytes, "AES");
                VaultCrypto.writeSecureFile(getKeyFile(), rawKeyBytes);
            } finally {
                Arrays.fill(rawKeyBytes, (byte) 0);
            }

            Files.deleteIfExists(getEncKeyFile());
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
            wipeAndClearMemory();
            loadOrCreateKey();
            vaultLocked = false;
            ChatProtectionMatcher.invalidateCache();

            if (BitwardenManager.isCliInstalled() && BitwardenManager.hasActiveSession()) {
                BitwardenManager.pullFromBitwarden();
            }
            return true;
        } catch (Exception e) {
            SYPassCommon.LOGGER.error("[SY-Pass] Failed to reset vault", e);
            return false;
        }
    }

    private static byte[] decryptMasterKeyFile(char[] masterPassword) throws Exception {
        Path encKeyFile = getEncKeyFile();
        if (!Files.exists(encKeyFile)) return null;

        String saltBase64 = SYPassConfig.getMasterPasswordSalt();
        if (saltBase64.isBlank()) return null;

        byte[] salt = Base64.getDecoder().decode(saltBase64);
        int iterations = SYPassConfig.getMasterPasswordIterations();
        SecretKey derivedKey = VaultCrypto.deriveKey(masterPassword, salt, iterations);

        String encStr = Files.readString(encKeyFile, StandardCharsets.UTF_8).trim();
        return VaultCrypto.decryptGcmToBytes(encStr, derivedKey);
    }

    // =========================================================================
    // CRUD Operations
    // =========================================================================

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
        String remoteId = (existing != null) ? existing.remoteId() : "";
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
        if (putInMemory(serverIp, username, password, command, isSynced, remoteId, isFavorite, lastUsed)) {
            saveToFile();
        }
    }

    /**
     * Inserts a batch of accounts into memory and persists the encrypted vault only once.
     */
    public static synchronized int savePasswordBatch(List<CsvCodec.ImportedEntry> entries) {
        if (entries == null || entries.isEmpty()) return 0;
        int count = 0;
        for (CsvCodec.ImportedEntry entry : entries) {
            try {
                if (putInMemory(entry.serverIp(), entry.username(), entry.password(), entry.command(),
                        entry.isSynced(), entry.remoteId(), entry.isFavorite(), entry.lastUsed())) {
                    count++;
                }
            } finally {
                entry.wipe();
            }
        }
        if (count > 0) {
            saveToFile();
        }
        return count;
    }

    private static boolean putInMemory(String serverIp, String username, char[] password, String command,
                                       boolean isSynced, String remoteId, boolean isFavorite, long lastUsed) {
        if (serverIp == null || serverIp.isBlank() || username == null || username.isBlank() || password == null) {
            return false;
        }
        String cleanServer = ServerAddressUtil.normalize(serverIp);
        String cleanUser = username.trim();

        Map<String, AccountData> serverMap = memoryData.computeIfAbsent(cleanServer, k -> new ConcurrentHashMap<>());
        AccountData old = serverMap.put(cleanUser, new AccountData(password, command, isSynced, remoteId, isFavorite, lastUsed));
        if (old != null) {
            old.wipe();
        }
        return true;
    }

    public static synchronized void toggleFavorite(String serverIp, String username) {
        AccountData existing = getPassword(serverIp, username);
        if (existing != null) {
            replaceMetadata(serverIp, username, existing.withFavorite(!existing.isFavorite()));
        }
    }

    public static synchronized void setFavorite(String serverIp, String username, boolean favorite) {
        AccountData existing = getPassword(serverIp, username);
        if (existing != null) {
            replaceMetadata(serverIp, username, existing.withFavorite(favorite));
        }
    }

    public static synchronized void updateLastUsed(String serverIp, String username) {
        AccountData existing = getPassword(serverIp, username);
        if (existing != null) {
            replaceMetadata(serverIp, username, existing.withLastUsed(System.currentTimeMillis()));
        }
    }

    public static synchronized void unmarkSynced(String serverIp, String username) {
        AccountData existing = getPassword(serverIp, username);
        if (existing != null) {
            replaceMetadata(serverIp, username, existing.withoutSync());
        }
    }

    public static synchronized void resetSyncFlags() {
        boolean changed = false;
        for (Map<String, AccountData> serverMap : memoryData.values()) {
            for (Map.Entry<String, AccountData> aEntry : serverMap.entrySet()) {
                AccountData old = aEntry.getValue();
                if (old.isSynced()) {
                    aEntry.setValue(old.withoutSync());
                    old.wipe();
                    changed = true;
                }
            }
        }
        if (changed) {
            saveToFile();
        }
    }

    private static void replaceMetadata(String serverIp, String username, AccountData updated) {
        String cleanServer = ServerAddressUtil.normalize(serverIp);
        String cleanUser = username.trim();
        Map<String, AccountData> serverMap = memoryData.computeIfAbsent(cleanServer, k -> new ConcurrentHashMap<>());
        AccountData old = serverMap.put(cleanUser, updated);
        if (old != null) {
            old.wipe();
        }
        saveToFile();
    }

    public static void copyPasswordToClipboard(String serverIp, String username) {
        AccountData acc = getPassword(serverIp, username);
        if (acc == null) return;
        char[] copy = acc.getPasswordCopy();
        try {
            PlatformHelper.get().copyToClipboard(copy);
        } finally {
            Arrays.fill(copy, '\0');
        }
    }

    public static AccountData getPassword(String serverIp, String username) {
        if (serverIp == null || username == null) return null;
        String cleanUser = username.trim();
        Map<String, AccountData> serverAccounts = findServerMap(serverIp);
        return (serverAccounts != null) ? serverAccounts.get(cleanUser) : null;
    }

    public static boolean hasPassword(String serverIp, String username) {
        return getPassword(serverIp, username) != null;
    }

    public static Map<String, AccountData> getServerAccounts(String serverIp) {
        if (serverIp == null) return Collections.emptyMap();
        Map<String, AccountData> accs = findServerMap(serverIp);
        return accs != null ? Collections.unmodifiableMap(accs) : Collections.emptyMap();
    }

    /**
     * Sanitized public view of all stored accounts (passwords blanked to protect against reflection/rogue mods).
     */
    public static Map<String, Map<String, AccountData>> getAllData() {
        Map<String, Map<String, AccountData>> sanitized = new HashMap<>();
        for (Map.Entry<String, Map<String, AccountData>> sEntry : memoryData.entrySet()) {
            Map<String, AccountData> sMap = new HashMap<>();
            for (Map.Entry<String, AccountData> aEntry : sEntry.getValue().entrySet()) {
                sMap.put(aEntry.getKey(), aEntry.getValue().sanitized());
            }
            sanitized.put(sEntry.getKey(), Collections.unmodifiableMap(sMap));
        }
        return Collections.unmodifiableMap(sanitized);
    }

    static Map<String, Map<String, AccountData>> getRawMemoryData() {
        return memoryData;
    }

    public static void populateProtectionAutomaton(Consumer<char[]> consumer, String serverFilter) {
        if (serverFilter == null) {
            for (Map<String, AccountData> accs : memoryData.values()) {
                feedPasswords(accs, consumer);
            }
        } else {
            Map<String, AccountData> accs = findServerMap(serverFilter);
            if (accs != null) {
                feedPasswords(accs, consumer);
            }
        }
    }

    private static void feedPasswords(Map<String, AccountData> accs, Consumer<char[]> consumer) {
        for (AccountData acc : accs.values()) {
            char[] pass = acc.password();
            if (pass != null && pass.length > 0) {
                consumer.accept(pass);
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
        String normServer = ServerAddressUtil.normalize(serverIp);
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
            return old != null;
        }
        return false;
    }

    private static Map<String, AccountData> findServerMap(String serverIp) {
        String normServer = ServerAddressUtil.normalize(serverIp);
        Map<String, AccountData> accs = memoryData.get(normServer);
        if (accs != null) return accs;

        String rawServer = serverIp.trim();
        if (!rawServer.equals(normServer)) {
            accs = memoryData.get(rawServer);
            if (accs != null) return accs;
        }

        return memoryData.get(normServer + ":25565");
    }

    // =========================================================================
    // Bitwarden Session & Persistence
    // =========================================================================

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
            if (secretKey == null) {
                loadOrCreateKey();
            }
            String rawJson = GSON.toJson(memoryData);
            String encryptedJson = VaultCrypto.encryptGcm(rawJson, secretKey);

            Map<String, String> wrapper = new HashMap<>();
            wrapper.put("vault", encryptedJson);
            if (savedBwSessionKey != null && !savedBwSessionKey.isBlank()) {
                wrapper.put("bw_session", VaultCrypto.encryptGcm(savedBwSessionKey, secretKey));
            }
            byte[] output = GSON.toJson(wrapper).getBytes(StandardCharsets.UTF_8);
            VaultCrypto.writeSecureFile(getConfigFile(), output);
            ChatProtectionMatcher.invalidateCache();
        } catch (Exception e) {
            SYPassCommon.LOGGER.error("[SY-Pass] Failed to save encrypted vault", e);
        }
    }

    private static void loadPasswords() {
        Path file = getConfigFile();
        boolean fromLegacyPath = false;
        if (!Files.exists(file)) {
            file = getOldConfigFile();
            if (!Files.exists(file)) return;
            fromLegacyPath = true;
        }

        legacyFormatDetected = false;
        try {
            String rawContent = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, String> wrapper = GSON.fromJson(rawContent, STRING_MAP_TYPE);
            if (wrapper == null) return;

            if (wrapper.containsKey("vault")) {
                String decryptedJson = decryptVaultPayload(wrapper.get("vault"));
                Map<String, Map<String, AccountData>> loadedData = GSON.fromJson(decryptedJson, VAULT_MAP_TYPE);
                if (loadedData != null) {
                    wipeAndClearMemory();
                    for (Map.Entry<String, Map<String, AccountData>> entry : loadedData.entrySet()) {
                        String normKey = ServerAddressUtil.normalize(entry.getKey());
                        Map<String, AccountData> currentMap = memoryData.computeIfAbsent(normKey, k -> new ConcurrentHashMap<>());
                        currentMap.putAll(entry.getValue());
                    }
                }
            }

            if (wrapper.containsKey("bw_session")) {
                try {
                    savedBwSessionKey = decryptVaultPayload(wrapper.get("bw_session")).trim();
                    if (!savedBwSessionKey.isBlank()) {
                        BitwardenManager.setSessionKey(savedBwSessionKey, false);
                    }
                } catch (Exception e) {
                    SYPassCommon.LOGGER.warn("[SY-Pass] Could not restore saved Bitwarden session key: {}", e.getMessage());
                }
            }

            if (legacyFormatDetected || fromLegacyPath) {
                SYPassCommon.LOGGER.info("[SY-Pass] Migrating legacy vault to hardened AES-256-GCM format...");
                saveToFile();
            } else {
                VaultCrypto.enforceSecurePermissions(file);
            }
            ChatProtectionMatcher.invalidateCache();
        } catch (Exception e) {
            SYPassCommon.LOGGER.error("[SY-Pass] Failed to load encrypted vault", e);
        }
    }

    private static void loadOrCreateKey() throws Exception {
        Path keyFile = getKeyFile();
        Path oldKeyFile = getOldKeyFile();
        Path targetKeyFile = Files.exists(keyFile) ? keyFile : (Files.exists(oldKeyFile) ? oldKeyFile : null);

        if (targetKeyFile != null) {
            byte[] keyBytes = Files.readAllBytes(targetKeyFile);
            try {
                if (keyBytes.length == 32 || keyBytes.length == 16) {
                    secretKey = new SecretKeySpec(keyBytes, "AES");
                    if (targetKeyFile.equals(oldKeyFile) && !Files.exists(keyFile)) {
                        VaultCrypto.writeSecureFile(keyFile, keyBytes);
                    } else {
                        VaultCrypto.enforceSecurePermissions(keyFile);
                    }
                    return;
                } else {
                    SYPassCommon.LOGGER.error("[SY-Pass] Corrupted key size ({} bytes). Backing up corrupted key.", keyBytes.length);
                    Files.move(targetKeyFile, targetKeyFile.resolveSibling(targetKeyFile.getFileName() + ".corrupted"), StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Arrays.fill(keyBytes, (byte) 0);
            }
        }

        secretKey = VaultCrypto.generateAes256Key();
        byte[] encoded = secretKey.getEncoded();
        try {
            VaultCrypto.writeSecureFile(keyFile, encoded);
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private static String decryptVaultPayload(String encryptedData) throws Exception {
        if (secretKey == null) {
            loadOrCreateKey();
        }
        if (encryptedData != null && encryptedData.startsWith(VaultCrypto.GCM_PREFIX)) {
            return VaultCrypto.decryptGcm(encryptedData, secretKey);
        }
        String legacy = VaultCrypto.decryptLegacyEcb(encryptedData, secretKey);
        legacyFormatDetected = true;
        return legacy;
    }

    private static void wipeAndClearMemory() {
        for (Map<String, AccountData> accs : memoryData.values()) {
            for (AccountData d : accs.values()) {
                d.wipe();
            }
        }
        memoryData.clear();
    }

    // =========================================================================
    // Backups & CSV Delegates
    // =========================================================================

    public static synchronized String exportBackup() {
        return exportBackup(null);
    }

    public static synchronized String exportBackup(String backupPassword) {
        return BackupService.exportBackup(getBackupsDir(), memoryData, backupPassword, GSON);
    }

    public static synchronized int importLatestBackup() {
        return importLatestBackup(null);
    }

    public static synchronized int importLatestBackup(String backupPassword) {
        File latest = BackupService.findLatestFile(getBackupsDir(), ".json");
        if (latest == null) return -1;
        return importBackupFile(latest, backupPassword);
    }

    public static synchronized int importBackupFile(File file) {
        return importBackupFile(file, null);
    }

    public static synchronized int importBackupFile(File file, String backupPassword) {
        if (file == null || !file.exists()) return -1;
        if (file.getName().toLowerCase(Locale.ROOT).endsWith(".csv")) {
            return importCsv(file);
        }
        BackupService.ParseOutcome outcome = BackupService.parseBackupFile(file, backupPassword, GSON, PasswordManager::decryptVaultPayload);
        if (!outcome.isSuccess()) {
            return outcome.errorCode();
        }
        return savePasswordBatch(outcome.entries());
    }

    public static synchronized String exportBitwardenCsv() {
        return CsvCodec.exportBitwardenCsv(getBackupsDir(), memoryData);
    }

    public static synchronized int importLatestCsvBackup() {
        File latest = BackupService.findLatestFile(getBackupsDir(), ".csv");
        if (latest == null) return -1;
        return importCsv(latest);
    }

    public static synchronized int importBitwardenCsv(File file) {
        return importCsv(file);
    }

    public static synchronized int importCsv(File file) {
        List<CsvCodec.ImportedEntry> entries = CsvCodec.parseCsvFile(file);
        if (entries == null) return -2;
        return savePasswordBatch(entries);
    }

    // =========================================================================
    // Utility Delegates (source compatibility for tests & callers)
    // =========================================================================

    public static String normalizeServerAddress(String address) {
        return ServerAddressUtil.normalize(address);
    }

    public static String maskServerAddress(String serverAddress) {
        return ServerAddressUtil.mask(serverAddress);
    }

    public static List<String> parseCsvLine(String line) {
        return CsvCodec.parseLine(line);
    }

    // =========================================================================
    // Gson Adapter for AccountData
    // =========================================================================

    private static final class AccountDataAdapter implements JsonDeserializer<AccountData>, JsonSerializer<AccountData> {
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
            String cmd = obj.has("command") && !obj.get("command").isJsonNull() ? obj.get("command").getAsString() : "/login";
            boolean synced = obj.has("isSynced") && obj.get("isSynced").getAsBoolean();
            String remoteId = obj.has("remoteId") && !obj.get("remoteId").isJsonNull() ? obj.get("remoteId").getAsString() : "";
            boolean favorite = obj.has("isFavorite") && obj.get("isFavorite").getAsBoolean();
            long lastUsed = obj.has("lastUsed") ? obj.get("lastUsed").getAsLong() : 0L;
            return new AccountData(pass, cmd, synced, remoteId, favorite, lastUsed);
        }

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
    }
}
