package com.syntren.sypass.storage;

import com.google.gson.*;
import com.syntren.sypass.SYPassCommon;
import com.syntren.sypass.config.SYPassConfig;
import com.syntren.sypass.util.ServerAddressUtil;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Interfaces with a locally installed official Bitwarden CLI via stdin/environment isolation.
 * Never downloads external binaries at runtime.
 */
public final class BitwardenManager {
    private static final Logger LOGGER = SYPassCommon.LOGGER;
    private static final Gson GSON = new Gson();
    private static final long STATUS_CACHE_TTL_MS = 10_000L;

    public static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    public static final boolean IS_MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")
            || System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("darwin");

    private static final ExecutorService BW_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            runnable -> {
                Thread t = new Thread(runnable, "SYPass-BW-Worker");
                t.setDaemon(true);
                return t;
            }
    );

    private static volatile String sessionKey = "";
    private static volatile String cachedExecutablePath = null;
    private static volatile BwStatusInfo cachedStatus = null;
    private static volatile long lastStatusQueryMs = 0L;

    private BitwardenManager() {}

    public record BwResult(int exitCode, String output) {}

    public enum LoginStatus {
        SUCCESS,
        NEED_OTP,
        INVALID_PASSWORD,
        INVALID_OTP,
        CLI_NOT_FOUND,
        ERROR
    }

    public record BwLoginResponse(LoginStatus status, String message, String sessionKey) {
        public boolean isSuccess() {
            return status == LoginStatus.SUCCESS;
        }
    }

    public record BwStatusInfo(boolean isInstalled, boolean isLocal, String cliPath, String status, String userEmail, String serverUrl) {
        public boolean isUnlocked() {
            return "unlocked".equalsIgnoreCase(status);
        }

        public boolean isLocked() {
            return "locked".equalsIgnoreCase(status);
        }

        public boolean isAuthenticated() {
            return !"unauthenticated".equalsIgnoreCase(status) && userEmail != null && !userEmail.isBlank();
        }
    }

    public record BwSyncResult(boolean success, int count, String message) {}

    private record RemoteItem(String id, String serverIp, String username, String password, String command) {
        String key() {
            return ServerAddressUtil.normalize(serverIp) + "|" + username.toLowerCase(Locale.ROOT);
        }
    }

    public static ExecutorService getExecutor() {
        return BW_EXECUTOR;
    }

    public static Path getConfigDir() {
        return SYPassConfig.getConfigDir();
    }

    /**
     * Checks both {@code config/sypass/bw/bw(.exe)} (documented path) and {@code config/sypass/bw(.exe)} (legacy path).
     */
    public static Path getLocalCliPath() {
        String bin = IS_WINDOWS ? "bw.exe" : "bw";
        Path subDirPath = getConfigDir().resolve("bw").resolve(bin);
        if (Files.exists(subDirPath)) {
            return subDirPath;
        }
        return getConfigDir().resolve(bin);
    }

    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "***";
        int at = email.indexOf('@');
        String name = email.substring(0, at);
        String domain = email.substring(at);
        String maskedName = (name.length() <= 2) ? "*" : name.charAt(0) + "***" + name.charAt(name.length() - 1);
        return maskedName + domain;
    }

    public static void setSessionKey(String key) {
        setSessionKey(key, true);
    }

    public static void setSessionKey(String key, boolean saveToDisk) {
        sessionKey = key != null ? key.trim() : "";
        invalidateStatusCache();
        if (saveToDisk) {
            PasswordManager.saveBwSession(sessionKey);
        }
    }

    public static String getSessionKey() {
        return sessionKey;
    }

    public static boolean hasActiveSession() {
        return sessionKey != null && !sessionKey.isBlank();
    }

    public static void invalidateStatusCache() {
        lastStatusQueryMs = 0L;
        cachedStatus = null;
        cachedExecutablePath = null;
    }

    public static boolean isLocalCliInstalled() {
        Path localPath = getLocalCliPath();
        if (!Files.exists(localPath)) return false;
        if (!IS_WINDOWS) {
            File file = localPath.toFile();
            if (!file.canExecute()) {
                try {
                    file.setExecutable(true, true);
                    file.setReadable(true, true);
                } catch (Exception ignored) {}
            }
            return file.canExecute() || Files.isExecutable(localPath);
        }
        return true;
    }

    public static String getCliExecutable() {
        if (isLocalCliInstalled()) {
            return getLocalCliPath().toAbsolutePath().toString();
        }

        String cached = cachedExecutablePath;
        if (cached != null && !cached.equals("bw") && !cached.equals("bw.cmd")) {
            if (Files.exists(Path.of(cached))) {
                return cached;
            }
            cachedExecutablePath = null;
        }

        String userHome = System.getProperty("user.home", "");
        List<Path> candidates = new ArrayList<>();

        if (IS_WINDOWS) {
            addIfNotNull(candidates, System.getenv("APPDATA"), "npm", "bw.cmd");
            addIfNotNull(candidates, System.getenv("LOCALAPPDATA"), "npm", "bw.cmd");
            addIfNotNull(candidates, System.getenv("ProgramFiles"), "Bitwarden CLI", "bw.exe");
            addIfNotNull(candidates, System.getenv("ProgramFiles(x86)"), "Bitwarden CLI", "bw.exe");
            if (!userHome.isBlank()) candidates.add(Path.of(userHome, "scoop", "shims", "bw.exe"));
            candidates.add(Path.of("C:\\ProgramData\\chocolatey\\bin\\bw.exe"));
        } else {
            candidates.add(Path.of("/usr/local/bin/bw"));
            candidates.add(Path.of("/usr/bin/bw"));
            candidates.add(Path.of("/bin/bw"));
            candidates.add(Path.of("/opt/homebrew/bin/bw"));
            if (!userHome.isBlank()) {
                candidates.add(Path.of(userHome, ".local/bin/bw"));
                candidates.add(Path.of(userHome, ".cargo/bin/bw"));
                candidates.add(Path.of(userHome, ".npm-global/bin/bw"));
            }
        }

        for (Path path : candidates) {
            if (Files.exists(path) && (IS_WINDOWS || Files.isExecutable(path))) {
                cachedExecutablePath = path.toAbsolutePath().toString();
                return cachedExecutablePath;
            }
        }

        if (!IS_WINDOWS && !userHome.isBlank()) {
            Path nvmDir = Path.of(userHome, ".nvm/versions/node");
            if (Files.isDirectory(nvmDir)) {
                File[] nodeVersions = nvmDir.toFile().listFiles();
                if (nodeVersions != null) {
                    for (File nodeVer : nodeVersions) {
                        Path bwInNode = nodeVer.toPath().resolve("bin/bw");
                        if (Files.exists(bwInNode) && Files.isExecutable(bwInNode)) {
                            cachedExecutablePath = bwInNode.toAbsolutePath().toString();
                            return cachedExecutablePath;
                        }
                    }
                }
            }
        }

        return IS_WINDOWS ? "bw.cmd" : "bw";
    }

    private static void addIfNotNull(List<Path> list, String base, String... more) {
        if (base != null && !base.isBlank()) {
            list.add(Path.of(base, more));
        }
    }

    public static boolean isCliInstalled() {
        if (isLocalCliInstalled()) return true;
        String exe = getCliExecutable();
        return !exe.equals("bw") && !exe.equals("bw.cmd");
    }

    public static synchronized BwStatusInfo getStatusInfo() {
        long now = System.currentTimeMillis();
        if (cachedStatus != null && (now - lastStatusQueryMs < STATUS_CACHE_TTL_MS)) {
            return cachedStatus;
        }

        String cliPath = getCliExecutable();
        boolean local = isLocalCliInstalled();
        boolean installed = isCliInstalled();

        if (!installed) {
            cachedStatus = new BwStatusInfo(false, false, cliPath, "not_found", "", "");
            lastStatusQueryMs = now;
            return cachedStatus;
        }

        try {
            BwResult result = executeBwCommand("status");
            String jsonStr = (result != null) ? extractJson(result.output()) : "";
            if (result == null || result.exitCode() != 0 || jsonStr.isBlank()) {
                cachedStatus = new BwStatusInfo(true, local, cliPath, "unknown", "", "");
            } else {
                JsonElement parsed = JsonParser.parseString(jsonStr);
                if (parsed.isJsonObject()) {
                    JsonObject obj = parsed.getAsJsonObject();
                    String status = optString(obj, "status", "unknown");
                    String userEmail = optString(obj, "userEmail", "");
                    String serverUrl = optString(obj, "serverUrl", "");
                    if ("locked".equalsIgnoreCase(status) || "unauthenticated".equalsIgnoreCase(status)) {
                        setSessionKey("", true);
                    }
                    cachedStatus = new BwStatusInfo(true, local, cliPath, status, userEmail, serverUrl);
                } else {
                    cachedStatus = new BwStatusInfo(true, local, cliPath, "error", "", "");
                }
            }
        } catch (Exception e) {
            LOGGER.error("[SY-Pass] Error querying bw status", e);
            cachedStatus = new BwStatusInfo(true, local, cliPath, "error", "", "");
        }

        lastStatusQueryMs = now;
        return cachedStatus;
    }

    public static boolean configureServer(String serverUrl) {
        try {
            String target = (serverUrl != null && !serverUrl.isBlank()) ? serverUrl.trim() : "https://vault.bitwarden.com";
            URI uri = URI.create(target);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                LOGGER.error("[SY-Pass] Rejected invalid Bitwarden server protocol: {}", target);
                return false;
            }
            if (target.contains("\"") || target.contains("&") || target.contains("|") || target.contains(";") || target.contains("^")) {
                LOGGER.error("[SY-Pass] Rejected unsafe characters in server URL");
                return false;
            }

            BwResult res = executeBwCommand("config", "server", target);
            invalidateStatusCache();
            return res != null && res.exitCode() == 0;
        } catch (Exception e) {
            LOGGER.error("[SY-Pass] Failed to configure server URL", e);
            return false;
        }
    }

    // =========================================================================
    // Authentication
    // =========================================================================

    public static BwLoginResponse login(String email, String password, String otp) {
        return login(email, password, otp, null);
    }

    public static BwLoginResponse login(String email, String password, String otp, String method) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return new BwLoginResponse(LoginStatus.ERROR, Component.translatable("sypass.gui.bw.error.empty_credentials").getString(), "");
        }
        email = email.trim();
        password = password.trim();

        if (!isCliInstalled()) {
            return new BwLoginResponse(LoginStatus.CLI_NOT_FOUND, Component.translatable("sypass.gui.bw.error.cli_not_found").getString(), "");
        }

        BwStatusInfo statusInfo = getStatusInfo();
        try {
            applyCustomServerIfSet();

            if (statusInfo.isLocked() && statusInfo.userEmail().equalsIgnoreCase(email)) {
                LOGGER.info("[SY-Pass] Attempting secure unlock for {}", maskEmail(email));
                BwResult unlockResult = executeBwCommandWithEnv(Map.of("BW_PASSWORD", password), "unlock", "--passwordenv", "BW_PASSWORD", "--raw");
                String unlockedKey = unlockResult != null ? extractSessionKey(unlockResult.output()) : "";
                if (unlockResult != null && unlockResult.exitCode() == 0 && !unlockedKey.isEmpty()) {
                    setSessionKey(unlockedKey);
                    return new BwLoginResponse(LoginStatus.SUCCESS, Component.translatable("sypass.gui.bw.error.unlock_success").getString(), sessionKey);
                }
                executeBwCommand("logout");
            } else if (statusInfo.isAuthenticated() && !statusInfo.userEmail().equalsIgnoreCase(email)) {
                executeBwCommand("logout");
            }

            if (otp != null && !otp.isBlank() && (method == null || method.isBlank())) {
                BwLoginResponse res = performLogin(email, password, otp, "0", true);
                if (res.isSuccess()) return res;
                String errMsg = res.message().toLowerCase(Locale.ROOT);
                if (errMsg.contains("invalid two-step") || errMsg.contains("no provider") || errMsg.contains("method")) {
                    BwLoginResponse resEmail = performLogin(email, password, otp, "1", false);
                    if (resEmail.isSuccess()) return resEmail;
                }
                return res;
            }

            return performLogin(email, password, otp, method, true);
        } catch (Exception e) {
            LOGGER.error("[SY-Pass] Exception during Bitwarden login", e);
            return new BwLoginResponse(LoginStatus.ERROR, Component.translatable("sypass.gui.bw.error.generic", e.getMessage()).getString(), "");
        }
    }

    public static BwLoginResponse sendEmail2faCode(String email, String password) {
        return performLogin(email, password, null, "1", false);
    }

    private static BwLoginResponse performLogin(String email, String password, String otp, String method, boolean allowLogoutRetry) {
        boolean hasOtp = (otp != null && !otp.isBlank());
        List<String> args = new ArrayList<>(List.of("login", email, "--passwordenv", "BW_PASSWORD"));

        if (method != null && !method.isBlank()) {
            args.add("--method");
            args.add(method.trim());
        }
        if (hasOtp) {
            args.add("--code");
            args.add(otp.trim().replaceAll("[^0-9a-zA-Z]", ""));
        }
        args.add("--raw");

        LOGGER.info("[SY-Pass] Sending Bitwarden login for {} (hasOtp={}, method={})", maskEmail(email), hasOtp, method);
        BwResult result = executeBwCommandWithEnv(Map.of("BW_PASSWORD", password), args.toArray(String[]::new));
        if (result == null) {
            return new BwLoginResponse(LoginStatus.ERROR, Component.translatable("sypass.gui.bw.error.cli_launch_failed").getString(), "");
        }

        String output = result.output().trim();
        String lowerOutput = output.toLowerCase(Locale.ROOT);

        if (allowLogoutRetry && lowerOutput.contains("already logged in")) {
            executeBwCommand("logout");
            return performLogin(email, password, otp, method, false);
        }

        String key = extractSessionKey(output);
        if (result.exitCode() == 0 && !key.isEmpty()) {
            setSessionKey(key);
            return new BwLoginResponse(LoginStatus.SUCCESS, Component.translatable("sypass.gui.bw.error.login_success").getString(), sessionKey);
        }

        if (hasOtp) {
            if (lowerOutput.contains("invalid") || lowerOutput.contains("code") || lowerOutput.contains("token")
                    || lowerOutput.contains("fail") || lowerOutput.contains("incorrect")) {
                return new BwLoginResponse(LoginStatus.INVALID_OTP, Component.translatable("sypass.gui.bw.error.invalid_otp").getString(), "");
            }
            return new BwLoginResponse(LoginStatus.ERROR, output.replace("\n", " ").trim(), "");
        }

        if (lowerOutput.contains("two-step") || lowerOutput.contains("code is required")
                || lowerOutput.contains("verification") || lowerOutput.contains("two-factor")
                || lowerOutput.contains("2fa") || lowerOutput.contains("code:")
                || lowerOutput.contains("selectedprovider") || lowerOutput.contains("provider")) {
            String promptMsg = lowerOutput.contains("email")
                    ? Component.translatable("sypass.gui.bw.otp.prompt_email").getString()
                    : Component.translatable("sypass.gui.bw.otp.prompt_app").getString();
            return new BwLoginResponse(LoginStatus.NEED_OTP, promptMsg, "");
        }

        if (result.exitCode() != 0 || lowerOutput.contains("invalid")) {
            if (lowerOutput.contains("username or password") || lowerOutput.contains("master password") || lowerOutput.contains("credentials")) {
                return new BwLoginResponse(LoginStatus.INVALID_PASSWORD, Component.translatable("sypass.gui.bw.error.invalid_password").getString(), "");
            }
            return new BwLoginResponse(LoginStatus.ERROR, output.replace("\n", " ").trim(), "");
        }

        return new BwLoginResponse(LoginStatus.ERROR, Component.translatable("sypass.gui.bw.error.unexpected_response", output).getString(), "");
    }

    public static BwLoginResponse loginWithApiKey(String clientId, String clientSecret, String masterPassword) {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()
                || masterPassword == null || masterPassword.isBlank()) {
            return new BwLoginResponse(LoginStatus.ERROR, Component.translatable("sypass.gui.bw.error.empty_apikey").getString(), "");
        }
        if (!isCliInstalled()) {
            return new BwLoginResponse(LoginStatus.CLI_NOT_FOUND, Component.translatable("sypass.gui.bw.error.cli_not_found").getString(), "");
        }

        try {
            applyCustomServerIfSet();
            executeBwCommand("logout");

            BwResult loginRes = executeBwCommandWithEnv(
                    Map.of("BW_CLIENTID", clientId.trim(), "BW_CLIENTSECRET", clientSecret.trim()),
                    "login", "--apikey"
            );
            if (loginRes == null || loginRes.exitCode() != 0) {
                String err = loginRes != null ? loginRes.output().trim() : "Launch failed";
                return new BwLoginResponse(LoginStatus.ERROR, Component.translatable("sypass.gui.bw.error.apikey_failed", err).getString(), "");
            }

            BwResult unlockRes = executeBwCommandWithEnv(Map.of("BW_PASSWORD", masterPassword.trim()), "unlock", "--passwordenv", "BW_PASSWORD", "--raw");
            String key = unlockRes != null ? extractSessionKey(unlockRes.output()) : "";
            if (unlockRes != null && unlockRes.exitCode() == 0 && !key.isEmpty()) {
                setSessionKey(key);
                return new BwLoginResponse(LoginStatus.SUCCESS, Component.translatable("sypass.gui.bw.error.apikey_success").getString(), sessionKey);
            }
            return new BwLoginResponse(LoginStatus.INVALID_PASSWORD, Component.translatable("sypass.gui.bw.error.invalid_unlock_password").getString(), "");
        } catch (Exception e) {
            LOGGER.error("[SY-Pass] Error logging in with API key", e);
            return new BwLoginResponse(LoginStatus.ERROR, Component.translatable("sypass.gui.bw.error.generic", e.getMessage()).getString(), "");
        }
    }

    public static BwLoginResponse loginWithSessionKey(String sessionKeyToTest) {
        if (sessionKeyToTest == null || sessionKeyToTest.isBlank()) {
            return new BwLoginResponse(LoginStatus.ERROR, Component.translatable("sypass.gui.bw.error.empty_session").getString(), "");
        }
        if (!isCliInstalled()) {
            return new BwLoginResponse(LoginStatus.CLI_NOT_FOUND, Component.translatable("sypass.gui.bw.error.cli_not_found").getString(), "");
        }

        try {
            applyCustomServerIfSet();
            String key = sessionKeyToTest.trim();
            BwResult syncRes = executeBwCommandWithEnv(Map.of("BW_SESSION", key), "sync");
            if (syncRes != null && syncRes.exitCode() == 0) {
                setSessionKey(key);
                return new BwLoginResponse(LoginStatus.SUCCESS, Component.translatable("sypass.gui.bw.error.session_success").getString(), sessionKey);
            }
            String err = (syncRes != null && !syncRes.output().isBlank()) ? syncRes.output().replace("\n", " ").trim() : "Invalid session key";
            return new BwLoginResponse(LoginStatus.INVALID_PASSWORD, Component.translatable("sypass.gui.bw.error.invalid_session", err).getString(), "");
        } catch (Exception e) {
            LOGGER.error("[SY-Pass] Error logging in with session key", e);
            return new BwLoginResponse(LoginStatus.ERROR, Component.translatable("sypass.gui.bw.error.generic", e.getMessage()).getString(), "");
        }
    }

    public static void logout() {
        try {
            executeBwCommand("logout");
        } catch (Exception ignored) {}
        setSessionKey("", true);
        PasswordManager.resetSyncFlags();
        invalidateStatusCache();
    }

    private static void applyCustomServerIfSet() {
        String customServer = SYPassConfig.getCustomServerUrl();
        if (!customServer.isEmpty()) {
            configureServer(customServer);
        }
    }

    // =========================================================================
    // Vault Sync (Pull / Push / Single Item)
    // =========================================================================

    public static BwSyncResult pullFromBitwarden() {
        if (!hasActiveSession()) {
            return new BwSyncResult(false, 0, Component.translatable("sypass.gui.bw.sync.vault_locked").getString());
        }

        try {
            executeBwCommand("sync");
            List<RemoteItem> remoteItems = listVaultItems(true);
            if (remoteItems == null) {
                return new BwSyncResult(false, 0, Component.translatable("sypass.gui.bw.sync.vault_locked").getString());
            }

            Set<String> remoteKeys = new HashSet<>();
            List<CsvCodec.ImportedEntry> toImport = new ArrayList<>();

            for (RemoteItem item : remoteItems) {
                if (item.password().isEmpty()) continue;
                String key = item.key();
                if (remoteKeys.add(key)) {
                    AccountData existing = PasswordManager.getPassword(item.serverIp(), item.username());
                    boolean favorite = existing != null && existing.isFavorite();
                    long lastUsed = existing != null ? existing.lastUsed() : 0L;
                    toImport.add(new CsvCodec.ImportedEntry(
                            item.serverIp(), item.username(), item.password().toCharArray(),
                            item.command(), true, item.id(), favorite, lastUsed
                    ));
                } else {
                    LOGGER.warn("[SY-Pass] Skipping duplicate Bitwarden item: id={}, server={}, user={}", item.id(), item.serverIp(), item.username());
                }
            }

            int importedCount = PasswordManager.savePasswordBatch(toImport);

            // Unmark local items that are no longer in Bitwarden
            Map<String, Map<String, AccountData>> allData = PasswordManager.getRawMemoryData();
            for (Map.Entry<String, Map<String, AccountData>> sEntry : allData.entrySet()) {
                String sIp = sEntry.getKey();
                for (Map.Entry<String, AccountData> aEntry : sEntry.getValue().entrySet()) {
                    String uName = aEntry.getKey();
                    AccountData acc = aEntry.getValue();
                    String key = ServerAddressUtil.normalize(sIp) + "|" + uName.toLowerCase(Locale.ROOT);
                    if (acc.isSynced() && !remoteKeys.contains(key)) {
                        PasswordManager.unmarkSynced(sIp, uName);
                    }
                }
            }

            return new BwSyncResult(true, importedCount, Component.translatable("sypass.gui.bw.sync.pull_success", importedCount).getString());
        } catch (Exception e) {
            LOGGER.error("[SY-Pass] Error pulling from Bitwarden", e);
            return new BwSyncResult(false, 0, Component.translatable("sypass.gui.bw.sync.pull_failed", e.getMessage()).getString());
        }
    }

    public static BwSyncResult pushToBitwarden() {
        if (!hasActiveSession()) {
            return new BwSyncResult(false, 0, Component.translatable("sypass.gui.bw.sync.vault_locked").getString());
        }

        try {
            executeBwCommand("sync");
            List<RemoteItem> remoteItems = listVaultItems(true);
            if (remoteItems == null) {
                return new BwSyncResult(false, 0, Component.translatable("sypass.gui.bw.sync.vault_locked").getString());
            }

            Map<String, String> existingItemIds = new HashMap<>();
            for (RemoteItem item : remoteItems) {
                if (!item.id().isEmpty()) {
                    existingItemIds.putIfAbsent(item.key(), item.id());
                }
            }

            Map<String, Map<String, AccountData>> allData = PasswordManager.getRawMemoryData();
            int created = 0;
            int updated = 0;

            for (Map.Entry<String, Map<String, AccountData>> serverEntry : allData.entrySet()) {
                String serverIp = serverEntry.getKey();
                for (Map.Entry<String, AccountData> accEntry : serverEntry.getValue().entrySet()) {
                    String username = accEntry.getKey();
                    AccountData data = accEntry.getValue();

                    String key = ServerAddressUtil.normalize(serverIp) + "|" + username.toLowerCase(Locale.ROOT);
                    String existingId = !data.remoteId().isBlank() ? data.remoteId() : existingItemIds.get(key);

                    String assignedId = createOrUpdateBitwardenItem(existingId, serverIp, username, data.getPasswordAsString(), data.command());
                    if (assignedId != null) {
                        existingItemIds.put(key, assignedId);
                        if (existingId != null && !existingId.isBlank()) updated++;
                        else created++;
                    }
                }
            }

            executeBwCommand("sync");
            return new BwSyncResult(true, created + updated, Component.translatable("sypass.gui.bw.sync.push_summary", created, updated).getString());
        } catch (Exception e) {
            LOGGER.error("[SY-Pass] Error pushing to Bitwarden", e);
            return new BwSyncResult(false, 0, Component.translatable("sypass.gui.bw.sync.push_failed", e.getMessage()).getString());
        }
    }

    public static String findBitwardenItemId(String serverIp, String username) {
        executeBwCommand("sync");
        List<RemoteItem> items = listVaultItems(false);
        if (items == null) return "";
        String targetKey = ServerAddressUtil.normalize(serverIp) + "|" + username.toLowerCase(Locale.ROOT);
        for (RemoteItem item : items) {
            if (!item.id().isEmpty() && item.key().equals(targetKey)) {
                return item.id();
            }
        }
        return "";
    }

    /**
     * Unified parser for {@code bw list items}, replacing 3 duplicated loops.
     * Returns {@code null} when the vault is locked/unauthenticated.
     */
    private static List<RemoteItem> listVaultItems(boolean clearSessionIfLocked) {
        BwResult result = executeBwCommand("list", "items");
        if (result == null || result.exitCode() != 0 || result.output().isBlank()) {
            String rawErr = (result != null) ? result.output().trim().toLowerCase(Locale.ROOT) : "";
            if (clearSessionIfLocked && (rawErr.contains("vault is locked") || rawErr.contains("not logged in"))) {
                setSessionKey("", true);
                invalidateStatusCache();
            }
            return null;
        }

        List<RemoteItem> parsedItems = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(extractJson(result.output()));
            if (!root.isJsonArray()) return parsedItems;

            for (JsonElement elem : root.getAsJsonArray()) {
                if (!elem.isJsonObject()) continue;
                JsonObject obj = elem.getAsJsonObject();
                if (!obj.has("login") || obj.get("login").isJsonNull()) continue;

                String id = optString(obj, "id", "");
                String name = optString(obj, "name", "");
                String command = optString(obj, "notes", "/login");

                JsonObject login = obj.getAsJsonObject("login");
                String username = optString(login, "username", "");
                String password = optString(login, "password", "");

                String serverIp = extractMinecraftUri(login);
                if (serverIp.isEmpty() && (name.startsWith("Minecraft: ") || name.startsWith("MC: "))) {
                    serverIp = name.replace("Minecraft: ", "").replace("MC: ", "").trim();
                }

                if (!serverIp.isEmpty() && !username.isEmpty()) {
                    parsedItems.add(new RemoteItem(id, serverIp, username, password, command));
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[SY-Pass] Failed to parse Bitwarden items list: {}", e.getMessage());
        }
        return parsedItems;
    }

    private static String extractMinecraftUri(JsonObject login) {
        if (!login.has("uris") || !login.get("uris").isJsonArray()) return "";
        for (JsonElement uElem : login.getAsJsonArray("uris")) {
            if (!uElem.isJsonObject()) continue;
            String uriVal = optString(uElem.getAsJsonObject(), "uri", "").trim();
            if (uriVal.startsWith("mc://")) {
                return uriVal.substring(5);
            }
        }
        return "";
    }

    public static CompletableFuture<Boolean> pushSingleItemAsync(String serverIp, String username, String password, String command) {
        return CompletableFuture.supplyAsync(() -> {
            if (!hasActiveSession()) return false;
            try {
                AccountData acc = PasswordManager.getPassword(serverIp, username);
                String remoteId = (acc != null) ? acc.remoteId() : "";
                if (remoteId.isBlank()) {
                    remoteId = findBitwardenItemId(serverIp, username);
                }
                String assignedId = createOrUpdateBitwardenItem(remoteId, serverIp, username, password, command);
                if (assignedId != null) {
                    executeBwCommand("sync");
                    return true;
                }
                return false;
            } catch (Exception e) {
                LOGGER.error("[SY-Pass] Failed background single push", e);
                return false;
            }
        }, BW_EXECUTOR);
    }

    public static CompletableFuture<Boolean> deleteSingleItemAsync(String serverIp, String username, String knownRemoteId) {
        return deleteInternalAsync(serverIp, username, knownRemoteId, false);
    }

    public static CompletableFuture<Boolean> deleteFromBitwardenOnlyAsync(String serverIp, String username, String knownRemoteId) {
        return deleteInternalAsync(serverIp, username, knownRemoteId, true);
    }

    private static CompletableFuture<Boolean> deleteInternalAsync(String serverIp, String username, String knownRemoteId, boolean unmarkLocalOnSuccess) {
        return CompletableFuture.supplyAsync(() -> {
            if (!hasActiveSession()) return false;
            try {
                String remoteId = (knownRemoteId != null && !knownRemoteId.isBlank()) ? knownRemoteId.trim() : "";
                if (remoteId.isEmpty()) {
                    AccountData acc = PasswordManager.getPassword(serverIp, username);
                    remoteId = (acc != null) ? acc.remoteId() : "";
                }
                if (remoteId.isEmpty()) {
                    remoteId = findBitwardenItemId(serverIp, username);
                }
                if (!remoteId.isEmpty()) {
                    BwResult res = executeBwCommand("delete", "item", remoteId, "--permanent");
                    executeBwCommand("sync");
                    boolean ok = res != null && res.exitCode() == 0;
                    if (ok && unmarkLocalOnSuccess) {
                        PasswordManager.unmarkSynced(serverIp, username);
                    }
                    return ok;
                }
            } catch (Exception e) {
                LOGGER.error("[SY-Pass] Failed background Bitwarden delete", e);
            }
            return false;
        }, BW_EXECUTOR);
    }

    private static String createOrUpdateBitwardenItem(String existingId, String serverIp, String username, String password, String command) {
        byte[] stdinData = null;
        try {
            JsonObject item = new JsonObject();
            if (existingId != null && !existingId.isBlank()) {
                item.addProperty("id", existingId);
            }
            item.addProperty("type", 1);
            item.addProperty("name", "Minecraft: " + serverIp);
            item.addProperty("notes", command);

            JsonObject login = new JsonObject();
            login.addProperty("username", username);
            login.addProperty("password", password);

            JsonArray uris = new JsonArray();
            JsonObject uriObj = new JsonObject();
            uriObj.addProperty("uri", "mc://" + serverIp);
            uris.add(uriObj);
            login.add("uris", uris);

            item.add("login", login);

            stdinData = Base64.getEncoder().encode(GSON.toJson(item).getBytes(StandardCharsets.UTF_8));
            BwResult res = (existingId != null && !existingId.isBlank())
                    ? executeBwCommandWithStdin(stdinData, "edit", "item", existingId)
                    : executeBwCommandWithStdin(stdinData, "create", "item");

            if (res != null && res.exitCode() == 0) {
                String finalId = (existingId != null && !existingId.isBlank()) ? existingId : "";
                try {
                    JsonObject resObj = JsonParser.parseString(extractJson(res.output())).getAsJsonObject();
                    String parsedId = optString(resObj, "id", "");
                    if (!parsedId.isEmpty()) finalId = parsedId;
                } catch (Exception ignored) {}
                if (!finalId.isEmpty()) {
                    PasswordManager.savePassword(serverIp, username, password, command, true, finalId);
                }
                return finalId;
            } else if (res != null) {
                String out = res.output().toLowerCase(Locale.ROOT);
                if (out.contains("vault is locked") || out.contains("not logged in")) {
                    setSessionKey("", true);
                    invalidateStatusCache();
                }
            }
            return null;
        } catch (Exception e) {
            LOGGER.error("[SY-Pass] Error creating/updating item in Bitwarden", e);
            return null;
        } finally {
            if (stdinData != null) {
                Arrays.fill(stdinData, (byte) 0);
            }
        }
    }

    // =========================================================================
    // Process Execution & Parsing Helpers
    // =========================================================================

    public static String extractJson(String output) {
        if (output == null) return "";
        int startObj = output.indexOf('{');
        int endObj = output.lastIndexOf('}');
        int startArr = output.indexOf('[');
        int endArr = output.lastIndexOf(']');

        if (startObj >= 0 && endObj > startObj && (startArr < 0 || startObj < startArr)) {
            return output.substring(startObj, endObj + 1);
        }
        if (startArr >= 0 && endArr > startArr) {
            return output.substring(startArr, endArr + 1);
        }
        return output.trim();
    }

    public static String extractSessionKey(String output) {
        if (output == null) return "";
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.length() >= 40 && !trimmed.contains(" ")
                    && !trimmed.toLowerCase(Locale.ROOT).contains("error")
                    && trimmed.matches("^[A-Za-z0-9+/=]+$")) {
                return trimmed;
            }
        }
        return "";
    }

    private static String optString(JsonObject obj, String key, String fallback) {
        return (obj.has(key) && !obj.get(key).isJsonNull()) ? obj.get(key).getAsString() : fallback;
    }

    private static BwResult executeBwCommand(String... args) {
        return executeBwCommandWithEnvAndStdin(null, null, args);
    }

    private static BwResult executeBwCommandWithEnv(Map<String, String> extraEnv, String... args) {
        return executeBwCommandWithEnvAndStdin(null, extraEnv, args);
    }

    private static BwResult executeBwCommandWithStdin(byte[] stdinData, String... args) {
        return executeBwCommandWithEnvAndStdin(stdinData, null, args);
    }

    private static BwResult executeBwCommandWithEnvAndStdin(byte[] stdinData, Map<String, String> extraEnv, String... args) {
        try {
            String executable = getCliExecutable();
            if (!IS_WINDOWS && Files.exists(Path.of(executable))) {
                File f = new File(executable);
                if (!f.canExecute()) {
                    try {
                        f.setExecutable(true, true);
                        f.setReadable(true, true);
                    } catch (Exception ignored) {}
                }
            }

            String[] command = new String[args.length + 1];
            command[0] = executable;
            System.arraycopy(args, 0, command, 1, args.length);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Path configDir = getConfigDir();
            if (Files.exists(configDir)) {
                pb.directory(configDir.toFile());
            }

            String currentSession = sessionKey;
            if (currentSession != null && !currentSession.isEmpty()) {
                pb.environment().put("BW_SESSION", currentSession);
            }
            pb.environment().put("BW_NOINTERACTION", "true");
            pb.environment().remove("LD_PRELOAD");
            pb.environment().remove("LD_LIBRARY_PATH");
            if (extraEnv != null) {
                pb.environment().putAll(extraEnv);
            }

            if (!IS_WINDOWS) {
                String currentPath = pb.environment().getOrDefault("PATH", "");
                String home = System.getProperty("user.home", "");
                String extraPaths = "/usr/local/bin:/usr/bin:/bin:/opt/homebrew/bin:"
                        + home + "/.local/bin:" + home + "/.cargo/bin:" + home + "/.npm-global/bin";
                pb.environment().put("PATH", currentPath.isEmpty() ? extraPaths : currentPath + ":" + extraPaths);
            }

            Process process = pb.start();
            if (stdinData != null && stdinData.length > 0) {
                try (OutputStream os = process.getOutputStream()) {
                    os.write(stdinData);
                    os.flush();
                } catch (Exception ignored) {}
            } else {
                try {
                    process.getOutputStream().close();
                } catch (Exception ignored) {}
            }

            StringBuilder output = new StringBuilder();
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append('\n');
                    }
                } catch (Exception ignored) {}
            }, "SYPass-Process-Reader");
            readerThread.setDaemon(true);
            readerThread.start();

            boolean finished = process.waitFor(25, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            try {
                readerThread.join(2000);
            } catch (Exception ignored) {}

            int exitCode = finished ? process.exitValue() : -1;
            StringBuilder cleanOutput = new StringBuilder();
            for (String line : output.toString().trim().split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("gamemode") || trimmed.startsWith("ERROR: ld.so") || trimmed.startsWith("WARNING: ld.so")) {
                    continue;
                }
                cleanOutput.append(line).append('\n');
            }
            return new BwResult(exitCode, cleanOutput.toString().trim());
        } catch (Exception e) {
            LOGGER.error("[SY-Pass] Failed to execute BW CLI command", e);
            return new BwResult(-1, "Exception: " + e.getMessage());
        }
    }
}
