package com.syntren.sypass.storage;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class BitwardenManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("SYPass");
    private static final Gson GSON = new Gson();
    private static String sessionKey = "";
    public static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("sypass");
    public static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    public static final boolean IS_MAC = System.getProperty("os.name").toLowerCase().contains("mac") || System.getProperty("os.name").toLowerCase().contains("darwin");
    public static final Path LOCAL_CLI_PATH = CONFIG_DIR.resolve(IS_WINDOWS ? "bw.exe" : "bw");

    private static BwStatusInfo cachedStatus = null;
    private static long lastStatusQueryMs = 0;
    private static final long STATUS_CACHE_TTL_MS = 10000;

    public interface DownloadProgressListener {
        void onProgress(float progress, long downloadedBytes, long totalBytes, String statusText);
        void onSuccess();
        void onError(String errorMessage);
    }

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
        lastStatusQueryMs = 0;
    }

    public static boolean isLocalCliInstalled() {
        return Files.exists(LOCAL_CLI_PATH) && (IS_WINDOWS || Files.isExecutable(LOCAL_CLI_PATH));
    }

    public static boolean deleteLocalCli() {
        logout();
        invalidateStatusCache();
        try {
            return Files.deleteIfExists(LOCAL_CLI_PATH);
        } catch (Exception e) {
            LOGGER.error("[SYPass] Failed to delete local CLI", e);
            return false;
        }
    }

    public static String getDownloadUrlForCurrentPlatform() {
        if (IS_WINDOWS) {
            return "https://bitwarden.com/download/?app=cli&platform=windows";
        } else if (IS_MAC) {
            return "https://bitwarden.com/download/?app=cli&platform=macos";
        } else {
            return "https://bitwarden.com/download/?app=cli&platform=linux";
        }
    }

    public static void downloadAndInstallCliAsync(DownloadProgressListener listener) {
        new Thread(() -> {
            File tempZip = CONFIG_DIR.resolve("bw_temp.zip").toFile();
            try {
                if (!Files.exists(CONFIG_DIR)) {
                    Files.createDirectories(CONFIG_DIR);
                }

                String downloadUrl = getDownloadUrlForCurrentPlatform();
                listener.onProgress(0.05f, 0, -1, "З'єднання з сервером Bitwarden...");

                URL url = URI.create(downloadUrl).toURL();
                HttpURLConnection connection = null;
                int redirects = 0;
                while (redirects < 6) {
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(30000);
                    connection.setInstanceFollowRedirects(true);
                    int status = connection.getResponseCode();
                    if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM || status == 307 || status == 308) {
                        String newUrl = connection.getHeaderField("Location");
                        url = URI.create(newUrl).toURL();
                        redirects++;
                    } else {
                        break;
                    }
                }

                if (connection == null) {
                    throw new IOException("Не вдалося встановити HTTP-з'єднання з " + downloadUrl);
                }

                long totalBytes = connection.getContentLengthLong();
                long downloadedBytes = 0;

                try (InputStream in = new BufferedInputStream(connection.getInputStream(), 65536);
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(tempZip), 65536)) {
                    byte[] buffer = new byte[65536];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        downloadedBytes += bytesRead;
                        float progress = totalBytes > 0 ? (float) downloadedBytes / totalBytes : 0.5f;
                        listener.onProgress(Math.min(0.90f, progress), downloadedBytes, totalBytes, "Завантаження архіву Bitwarden CLI...");
                    }
                    out.flush();
                }

                listener.onProgress(0.92f, downloadedBytes, totalBytes, "Розпакування архіву...");

                boolean extracted = false;
                try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(tempZip), 65536))) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        String name = entry.getName();
                        if (name.equals("bw") || name.equals("bw.exe") || name.endsWith("/bw") || name.endsWith("/bw.exe")) {
                            try (BufferedOutputStream fos = new BufferedOutputStream(new FileOutputStream(LOCAL_CLI_PATH.toFile()), 65536)) {
                                byte[] buf = new byte[65536];
                                int len;
                                while ((len = zis.read(buf)) != -1) {
                                    fos.write(buf, 0, len);
                                }
                                fos.flush();
                            }
                            extracted = true;
                            break;
                        }
                    }
                }

                tempZip.delete();

                if (!extracted) {
                    listener.onError("Не знайдено виконуваний файл 'bw' всередині завантаженого архіву.");
                    return;
                }

                if (!IS_WINDOWS) {
                    File cliFile = LOCAL_CLI_PATH.toFile();
                    cliFile.setExecutable(true, false);
                    cliFile.setReadable(true, false);
                    cliFile.setWritable(true, true);
                    try {
                        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxr-xr-x");
                        Files.setPosixFilePermissions(LOCAL_CLI_PATH, perms);
                    } catch (Exception ignored) {}
                }

                invalidateStatusCache();
                listener.onProgress(0.98f, downloadedBytes, totalBytes, "Перевірка запуску CLI...");

                if (isCliInstalled()) {
                    listener.onProgress(1.0f, downloadedBytes, totalBytes, "Успішно встановлено!");
                    listener.onSuccess();
                } else {
                    listener.onError("Файл завантажено, але не вдалося запустити Bitwarden CLI.");
                }

            } catch (Exception e) {
                LOGGER.error("[SYPass] Error downloading Bitwarden CLI", e);
                tempZip.delete();
                listener.onError("Помилка завантаження: " + e.getMessage());
            }
        }).start();
    }

    public static String getCliExecutable() {
        if (isLocalCliInstalled()) {
            return LOCAL_CLI_PATH.toAbsolutePath().toString();
        }

        String userHome = System.getProperty("user.home", "");
        List<Path> candidatePaths = new ArrayList<>();

        if (IS_WINDOWS) {
            String appData = System.getenv("APPDATA");
            String localAppData = System.getenv("LOCALAPPDATA");
            String programFiles = System.getenv("ProgramFiles");
            if (appData != null) candidatePaths.add(Path.of(appData, "npm", "bw.cmd"));
            if (localAppData != null) candidatePaths.add(Path.of(localAppData, "npm", "bw.cmd"));
            if (programFiles != null) candidatePaths.add(Path.of(programFiles, "Bitwarden CLI", "bw.exe"));
        } else {
            candidatePaths.add(Path.of("/usr/local/bin/bw"));
            candidatePaths.add(Path.of("/usr/bin/bw"));
            candidatePaths.add(Path.of("/bin/bw"));
            if (!userHome.isBlank()) {
                candidatePaths.add(Path.of(userHome, ".local/bin/bw"));
                candidatePaths.add(Path.of(userHome, ".cargo/bin/bw"));
                candidatePaths.add(Path.of(userHome, ".npm-global/bin/bw"));
            }
            candidatePaths.add(Path.of("/opt/homebrew/bin/bw"));
        }

        for (Path path : candidatePaths) {
            if (Files.exists(path) && Files.isExecutable(path)) {
                return path.toAbsolutePath().toString();
            }
        }

        if (!IS_WINDOWS && !userHome.isBlank()) {
            Path nvmDir = Path.of(userHome, ".nvm/versions/node");
            if (Files.exists(nvmDir) && Files.isDirectory(nvmDir)) {
                try {
                    File[] nodeVersions = nvmDir.toFile().listFiles();
                    if (nodeVersions != null) {
                        for (File nodeVer : nodeVersions) {
                            Path bwInNode = nodeVer.toPath().resolve("bin/bw");
                            if (Files.exists(bwInNode) && Files.isExecutable(bwInNode)) {
                                return bwInNode.toAbsolutePath().toString();
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        return IS_WINDOWS ? "bw.cmd" : "bw";
    }

    public static boolean isCliInstalled() {
        if (isLocalCliInstalled()) return true;
        if (cachedStatus != null) return cachedStatus.isInstalled();
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
        try {
            BwResult result = executeBwCommand("status");
            if (result == null || result.exitCode() != 0 || result.output().isBlank()) {
                cachedStatus = new BwStatusInfo(false, local, cliPath, "not_found", "", "");
            } else {
                JsonElement parsed = JsonParser.parseString(result.output());
                if (parsed.isJsonObject()) {
                    JsonObject obj = parsed.getAsJsonObject();
                    String status = obj.has("status") && !obj.get("status").isJsonNull() ? obj.get("status").getAsString() : "unknown";
                    String userEmail = obj.has("userEmail") && !obj.get("userEmail").isJsonNull() ? obj.get("userEmail").getAsString() : "";
                    String serverUrl = obj.has("serverUrl") && !obj.get("serverUrl").isJsonNull() ? obj.get("serverUrl").getAsString() : "";
                    cachedStatus = new BwStatusInfo(true, local, cliPath, status, userEmail, serverUrl);
                } else {
                    cachedStatus = new BwStatusInfo(false, local, cliPath, "error", "", "");
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[SYPass] Error querying bw status", e);
            cachedStatus = new BwStatusInfo(false, local, cliPath, "error", "", "");
        }

        lastStatusQueryMs = now;
        return cachedStatus;
    }

    public static boolean configureServer(String serverUrl) {
        try {
            String target = (serverUrl != null && !serverUrl.isBlank()) ? serverUrl.trim() : "https://vault.bitwarden.com";
            BwResult res = executeBwCommand("config", "server", target);
            invalidateStatusCache();
            return res != null && res.exitCode() == 0;
        } catch (Exception e) {
            LOGGER.error("[SYPass] Failed to configure server URL", e);
            return false;
        }
    }

    public static BwLoginResponse login(String email, String password, String otp) {
        return login(email, password, otp, null);
    }

    public static BwLoginResponse login(String email, String password, String otp, String method) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return new BwLoginResponse(LoginStatus.ERROR, "Введіть Email та майстер-пароль!", "");
        }

        email = email.trim();
        password = password.trim();

        BwStatusInfo statusInfo = getStatusInfo();
        if (!statusInfo.isInstalled()) {
            return new BwLoginResponse(LoginStatus.CLI_NOT_FOUND, "Bitwarden CLI (bw) не знайдено в системі!", "");
        }

        try {
            String customServer = com.syntren.sypass.config.SYPassConfig.getCustomServerUrl();
            if (!customServer.isEmpty()) {
                configureServer(customServer);
            }

            if (statusInfo.isLocked() && statusInfo.userEmail().equalsIgnoreCase(email)) {
                LOGGER.info("[SYPass] Attempting secure unlock for {}", email);
                Map<String, String> unlockEnv = Map.of("BW_PASSWORD", password);
                BwResult unlockResult = executeBwCommandWithEnv(unlockEnv, "unlock", "--passwordenv", "BW_PASSWORD", "--raw");
                if (unlockResult != null && unlockResult.exitCode() == 0 && isValidSessionKey(unlockResult.output())) {
                    setSessionKey(unlockResult.output().trim());
                    return new BwLoginResponse(LoginStatus.SUCCESS, "Сховище успішно розблоковано!", sessionKey);
                }
                LOGGER.info("[SYPass] Unlock failed. Resetting session to perform fresh login...");
                executeBwCommand("logout");
            } else if (statusInfo.isAuthenticated() && !statusInfo.userEmail().equalsIgnoreCase(email)) {
                executeBwCommand("logout");
            }

            if (otp != null && !otp.isBlank() && (method == null || method.isBlank())) {
                // If method is not explicitly given, try Authenticator app (0) first
                BwLoginResponse res = performLogin(email, password, otp, "0");
                if (res.isSuccess()) {
                    return res;
                }
                // If method 0 failed with invalid method or provider not supported, fallback to Email (1)
                String errMsg = res.message().toLowerCase();
                if (errMsg.contains("invalid two-step") || errMsg.contains("no provider") || errMsg.contains("method")) {
                    BwLoginResponse resEmail = performLogin(email, password, otp, "1");
                    if (resEmail.isSuccess()) {
                        return resEmail;
                    }
                }
                return res;
            }

            return performLogin(email, password, otp, method);

        } catch (Exception e) {
            LOGGER.error("[SYPass] Exception during login", e);
            return new BwLoginResponse(LoginStatus.ERROR, "Помилка: " + e.getMessage(), "");
        }
    }

    public static BwLoginResponse sendEmail2faCode(String email, String password) {
        return performLogin(email, password, null, "1");
    }

    private static BwLoginResponse performLogin(String email, String password, String otp, String method) {
        boolean hasOtp = (otp != null && !otp.isBlank());

        List<String> args = new ArrayList<>();
        args.add("login");
        args.add(email);
        args.add("--passwordenv");
        args.add("BW_PASSWORD");

        if (method != null && !method.isBlank()) {
            args.add("--method");
            args.add(method.trim());
        }

        if (hasOtp) {
            args.add("--code");
            args.add(otp.trim());
        }
        args.add("--raw");

        LOGGER.info("[SYPass] Sending secure login command for: {} (hasOtp={}, method={})", email, hasOtp, method);
        Map<String, String> env = new HashMap<>();
        env.put("BW_PASSWORD", password);
        BwResult result = executeBwCommandWithEnv(env, args.toArray(new String[0]));
        if (result == null) {
            return new BwLoginResponse(LoginStatus.ERROR, "Не вдалося запустити Bitwarden CLI", "");
        }

        String output = result.output().trim();
        String lowerOutput = output.toLowerCase();

        if (lowerOutput.contains("already logged in")) {
            executeBwCommand("logout");
            return performLogin(email, password, otp, method);
        }

        // 1. Success check
        if (result.exitCode() == 0 && isValidSessionKey(output)) {
            setSessionKey(output);
            return new BwLoginResponse(LoginStatus.SUCCESS, "Успішна авторизація!", sessionKey);
        }

        // 2. If OTP was submitted, this was a verification attempt and MUST NOT loop back to NEED_OTP
        if (hasOtp) {
            LOGGER.warn("[SYPass] OTP verification failed: {}", output);
            if (lowerOutput.contains("invalid") || lowerOutput.contains("code") || lowerOutput.contains("token")
                    || lowerOutput.contains("fail") || lowerOutput.contains("incorrect")) {
                return new BwLoginResponse(LoginStatus.INVALID_OTP, "Невірний або застарілий 2FA код! Перевірте код або виберіть інший метод.", "");
            }
            return new BwLoginResponse(LoginStatus.ERROR, output.replace("\n", " ").trim(), "");
        }

        // 3. Initial login without OTP: check if 2FA is needed
        if (lowerOutput.contains("two-step") || lowerOutput.contains("code is required") ||
                lowerOutput.contains("verification") || lowerOutput.contains("two-factor") ||
                lowerOutput.contains("2fa") || lowerOutput.contains("code:") ||
                lowerOutput.contains("selectedprovider") || lowerOutput.contains("provider")) {
            String promptMsg = lowerOutput.contains("email")
                    ? "Код підтвердження надіслано на Email! Введіть його нижче:"
                    : "Введіть 2FA код підтвердження (з додатка Authenticator або пошти):";
            return new BwLoginResponse(LoginStatus.NEED_OTP, promptMsg, "");
        }

        // 4. Invalid credentials check
        if (result.exitCode() != 0 || lowerOutput.contains("invalid")) {
            if (lowerOutput.contains("username or password") || lowerOutput.contains("master password") || lowerOutput.contains("credentials")) {
                return new BwLoginResponse(LoginStatus.INVALID_PASSWORD, "Невірний Email або майстер-пароль!", "");
            }
            return new BwLoginResponse(LoginStatus.ERROR, output.replace("\n", " ").trim(), "");
        }

        return new BwLoginResponse(LoginStatus.ERROR, "Неочікувана відповідь від Bitwarden: " + output, "");
    }

    public static BwLoginResponse loginWithApiKey(String clientId, String clientSecret, String masterPassword) {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank() || masterPassword == null || masterPassword.isBlank()) {
            return new BwLoginResponse(LoginStatus.ERROR, "Заповніть Client ID, Client Secret та майстер-пароль!", "");
        }

        try {
            String customServer = com.syntren.sypass.config.SYPassConfig.getCustomServerUrl();
            if (!customServer.isEmpty()) {
                configureServer(customServer);
            }

            executeBwCommand("logout");

            Map<String, String> env = new HashMap<>();
            env.put("BW_CLIENTID", clientId.trim());
            env.put("BW_CLIENTSECRET", clientSecret.trim());

            BwResult loginRes = executeBwCommandWithEnv(env, "login", "--apikey");
            if (loginRes == null || loginRes.exitCode() != 0) {
                String err = loginRes != null ? loginRes.output().trim() : "Помилка запуску";
                return new BwLoginResponse(LoginStatus.ERROR, "Помилка API Key: " + err, "");
            }

            Map<String, String> unlockEnv = Map.of("BW_PASSWORD", masterPassword.trim());
            BwResult unlockRes = executeBwCommandWithEnv(unlockEnv, "unlock", "--passwordenv", "BW_PASSWORD", "--raw");
            if (unlockRes != null && unlockRes.exitCode() == 0 && isValidSessionKey(unlockRes.output())) {
                setSessionKey(unlockRes.output().trim());
                return new BwLoginResponse(LoginStatus.SUCCESS, "Успішний вхід за API ключем!", sessionKey);
            } else {
                return new BwLoginResponse(LoginStatus.INVALID_PASSWORD, "Невірний майстер-пароль для розблокування!", "");
            }
        } catch (Exception e) {
            LOGGER.error("[SYPass] Error logging in with API key", e);
            return new BwLoginResponse(LoginStatus.ERROR, "Помилка: " + e.getMessage(), "");
        }
    }

    private static boolean isValidSessionKey(String output) {
        if (output == null) return false;
        String trimmed = output.trim();
        return !trimmed.isEmpty() && !trimmed.contains(" ") && !trimmed.contains("\n") && !trimmed.toLowerCase().contains("error");
    }

    public static BwSyncResult pullFromBitwarden() {
        if (!hasActiveSession()) {
            return new BwSyncResult(false, 0, "Сховище заблоковане або відсутня активна сесія!");
        }

        try {
            executeBwCommand("sync");

            BwResult result = executeBwCommand("list", "items");
            if (result == null || result.exitCode() != 0 || result.output().isBlank()) {
                String err = result != null ? result.output().trim() : "Немає відповіді від CLI";
                return new BwSyncResult(false, 0, "Не вдалося отримати записи: " + err);
            }

            JsonElement element = JsonParser.parseString(result.output());
            if (!element.isJsonArray()) {
                return new BwSyncResult(false, 0, "Некоректний формат відповіді від Bitwarden");
            }

            JsonArray items = element.getAsJsonArray();
            int importedCount = 0;
            Set<String> remoteKeys = new HashSet<>();

            for (int i = 0; i < items.size(); i++) {
                JsonObject item = items.get(i).getAsJsonObject();
                String id = item.has("id") && !item.get("id").isJsonNull() ? item.get("id").getAsString() : "";
                String name = item.has("name") && !item.get("name").isJsonNull() ? item.get("name").getAsString() : "";

                if (item.has("login") && !item.get("login").isJsonNull()) {
                    JsonObject login = item.getAsJsonObject("login");
                    String username = login.has("username") && !login.get("username").isJsonNull() ? login.get("username").getAsString() : "";
                    String password = login.has("password") && !login.get("password").isJsonNull() ? login.get("password").getAsString() : "";
                    String command = item.has("notes") && !item.get("notes").isJsonNull() ? item.get("notes").getAsString() : "/login";

                    String serverIp = "";

                    if (login.has("uris") && login.get("uris").isJsonArray()) {
                        JsonArray uris = login.getAsJsonArray("uris");
                        for (int u = 0; u < uris.size(); u++) {
                            JsonObject uObj = uris.get(u).getAsJsonObject();
                            if (uObj.has("uri") && !uObj.get("uri").isJsonNull()) {
                                String uriVal = uObj.get("uri").getAsString().trim();
                                if (uriVal.startsWith("mc://")) {
                                    serverIp = uriVal.replace("mc://", "");
                                    break;
                                }
                            }
                        }
                    }

                    if (serverIp.isEmpty() && (name.startsWith("Minecraft: ") || name.startsWith("MC: "))) {
                        serverIp = name.replace("Minecraft: ", "").replace("MC: ", "").trim();
                    }

                    if (!serverIp.isEmpty() && !username.isEmpty() && !password.isEmpty()) {
                        String key = serverIp.toLowerCase() + "|" + username.toLowerCase();
                        if (!remoteKeys.contains(key)) {
                            importedCount++;
                        }
                        PasswordManager.savePassword(serverIp, username, password, command, true, id);
                        remoteKeys.add(key);
                    }
                }
            }

            // Звірка (Reconciliation): скидаємо прапорець isSynced для записів, яких більше немає у хмарі
            Map<String, Map<String, PasswordManager.AccountData>> allData = PasswordManager.getAllData();
            for (Map.Entry<String, Map<String, PasswordManager.AccountData>> sEntry : allData.entrySet()) {
                String sIp = sEntry.getKey();
                for (Map.Entry<String, PasswordManager.AccountData> aEntry : sEntry.getValue().entrySet()) {
                    String uName = aEntry.getKey();
                    PasswordManager.AccountData acc = aEntry.getValue();
                    String key = sIp.toLowerCase() + "|" + uName.toLowerCase();
                    if (acc.isSynced() && !remoteKeys.contains(key)) {
                        PasswordManager.savePassword(sIp, uName, acc.password(), acc.command(), false, "");
                    }
                }
            }

            return new BwSyncResult(true, importedCount, "Успішно імпортовано " + importedCount + " паролів!");
        } catch (Exception e) {
            LOGGER.error("[SYPass] Error pulling from Bitwarden", e);
            return new BwSyncResult(false, 0, "Помилка імпорту: " + e.getMessage());
        }
    }

    public static BwSyncResult pushToBitwarden() {
        if (!hasActiveSession()) {
            return new BwSyncResult(false, 0, "Сховище заблоковане або відсутня активна сесія!");
        }

        try {
            executeBwCommand("sync");

            Map<String, String> existingItemIds = new HashMap<>();
            BwResult listResult = executeBwCommand("list", "items");
            if (listResult != null && listResult.exitCode() == 0 && !listResult.output().isBlank()) {
                try {
                    JsonElement parsed = JsonParser.parseString(listResult.output());
                    if (parsed.isJsonArray()) {
                        for (JsonElement elem : parsed.getAsJsonArray()) {
                            JsonObject itemObj = elem.getAsJsonObject();
                            String id = itemObj.has("id") ? itemObj.get("id").getAsString() : "";
                            String name = itemObj.has("name") ? itemObj.get("name").getAsString() : "";
                            String user = "";
                            String sIp = "";

                            if (itemObj.has("login") && !itemObj.get("login").isJsonNull()) {
                                JsonObject loginObj = itemObj.getAsJsonObject("login");
                                user = loginObj.has("username") ? loginObj.get("username").getAsString() : "";
                                if (loginObj.has("uris") && loginObj.get("uris").isJsonArray()) {
                                    for (JsonElement uElem : loginObj.getAsJsonArray("uris")) {
                                        String u = uElem.getAsJsonObject().get("uri").getAsString();
                                        if (u.startsWith("mc://")) {
                                            sIp = u.replace("mc://", "");
                                            break;
                                        }
                                    }
                                }
                            }
                            if (sIp.isEmpty() && name.startsWith("Minecraft: ")) {
                                sIp = name.replace("Minecraft: ", "");
                            }

                            if (!id.isEmpty() && !sIp.isEmpty() && !user.isEmpty()) {
                                existingItemIds.put(sIp.toLowerCase() + "|" + user.toLowerCase(), id);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            Map<String, Map<String, PasswordManager.AccountData>> allData = PasswordManager.getAllData();
            int created = 0;
            int updated = 0;

            for (Map.Entry<String, Map<String, PasswordManager.AccountData>> serverEntry : allData.entrySet()) {
                String serverIp = serverEntry.getKey();
                for (Map.Entry<String, PasswordManager.AccountData> accEntry : serverEntry.getValue().entrySet()) {
                    String username = accEntry.getKey();
                    PasswordManager.AccountData data = accEntry.getValue();

                    String key = serverIp.toLowerCase() + "|" + username.toLowerCase();
                    String existingId = data.remoteId().isEmpty() ? existingItemIds.get(key) : data.remoteId();

                    boolean success = createOrUpdateBitwardenItem(existingId, serverIp, username, data.password(), data.command());
                    if (success) {
                        PasswordManager.savePassword(serverIp, username, data.password(), data.command(), true, existingId != null ? existingId : "");
                        if (existingId != null) updated++;
                        else created++;
                    }
                }
            }

            executeBwCommand("sync");
            return new BwSyncResult(true, created + updated, "Створено: " + created + ", Оновлено: " + updated);
        } catch (Exception e) {
            LOGGER.error("[SYPass] Error pushing to Bitwarden", e);
            return new BwSyncResult(false, 0, "Помилка вивантаження: " + e.getMessage());
        }
    }

    public static String findBitwardenItemId(String serverIp, String username) {
        try {
            BwResult listResult = executeBwCommand("list", "items");
            if (listResult != null && listResult.exitCode() == 0 && !listResult.output().isBlank()) {
                JsonElement parsed = JsonParser.parseString(listResult.output());
                if (parsed.isJsonArray()) {
                    for (JsonElement elem : parsed.getAsJsonArray()) {
                        JsonObject itemObj = elem.getAsJsonObject();
                        String id = itemObj.has("id") && !itemObj.get("id").isJsonNull() ? itemObj.get("id").getAsString() : "";
                        String name = itemObj.has("name") && !itemObj.get("name").isJsonNull() ? itemObj.get("name").getAsString() : "";
                        String user = "";
                        String sIp = "";

                        if (itemObj.has("login") && !itemObj.get("login").isJsonNull()) {
                            JsonObject loginObj = itemObj.getAsJsonObject("login");
                            user = loginObj.has("username") && !loginObj.get("username").isJsonNull() ? loginObj.get("username").getAsString() : "";
                            if (loginObj.has("uris") && loginObj.get("uris").isJsonArray()) {
                                for (JsonElement uElem : loginObj.getAsJsonArray("uris")) {
                                    JsonObject uObj = uElem.getAsJsonObject();
                                    if (uObj.has("uri") && !uObj.get("uri").isJsonNull()) {
                                        String u = uObj.get("uri").getAsString();
                                        if (u.startsWith("mc://")) {
                                            sIp = u.replace("mc://", "");
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                        if (sIp.isEmpty() && name.startsWith("Minecraft: ")) {
                            sIp = name.replace("Minecraft: ", "");
                        }

                        if (!id.isEmpty() && sIp.equalsIgnoreCase(serverIp) && user.equalsIgnoreCase(username)) {
                            return id;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    public static CompletableFuture<Boolean> pushSingleItemAsync(String serverIp, String username, String password, String command) {
        return CompletableFuture.supplyAsync(() -> {
            if (!hasActiveSession()) return false;
            try {
                PasswordManager.AccountData acc = PasswordManager.getPassword(serverIp, username);
                String remoteId = acc != null ? acc.remoteId() : "";
                if (remoteId == null || remoteId.isBlank()) {
                    remoteId = findBitwardenItemId(serverIp, username);
                }
                return createOrUpdateBitwardenItem(remoteId, serverIp, username, password, command);
            } catch (Exception e) {
                LOGGER.error("[SYPass] Failed background single push", e);
                return false;
            }
        });
    }

    public static CompletableFuture<Boolean> deleteSingleItemAsync(String serverIp, String username) {
        return deleteSingleItemAsync(serverIp, username, null);
    }

    public static CompletableFuture<Boolean> deleteSingleItemAsync(String serverIp, String username, String knownRemoteId) {
        return CompletableFuture.supplyAsync(() -> {
            if (!hasActiveSession()) return false;
            try {
                String remoteId = (knownRemoteId != null && !knownRemoteId.isBlank()) ? knownRemoteId.trim() : "";
                if (remoteId.isEmpty()) {
                    PasswordManager.AccountData acc = PasswordManager.getPassword(serverIp, username);
                    remoteId = acc != null ? acc.remoteId() : "";
                }
                if (remoteId.isEmpty()) {
                    remoteId = findBitwardenItemId(serverIp, username);
                }
                if (!remoteId.isEmpty()) {
                    BwResult res = executeBwCommand("delete", "item", remoteId, "--permanent");
                    executeBwCommand("sync");
                    return res != null && res.exitCode() == 0;
                }
            } catch (Exception e) {
                LOGGER.error("[SYPass] Failed background single delete", e);
            }
            return false;
        });
    }

    public static CompletableFuture<Boolean> deleteFromBitwardenOnlyAsync(String serverIp, String username, String knownRemoteId) {
        return CompletableFuture.supplyAsync(() -> {
            if (!hasActiveSession()) return false;
            try {
                String remoteId = (knownRemoteId != null && !knownRemoteId.isBlank()) ? knownRemoteId.trim() : "";
                PasswordManager.AccountData acc = PasswordManager.getPassword(serverIp, username);
                if (remoteId.isEmpty() && acc != null) {
                    remoteId = acc.remoteId();
                }
                if (remoteId.isEmpty()) {
                    remoteId = findBitwardenItemId(serverIp, username);
                }
                if (!remoteId.isEmpty()) {
                    BwResult res = executeBwCommand("delete", "item", remoteId, "--permanent");
                    executeBwCommand("sync");
                    if (res != null && res.exitCode() == 0) {
                        PasswordManager.unmarkSynced(serverIp, username);
                        return true;
                    }
                }
            } catch (Exception e) {
                LOGGER.error("[SYPass] Failed background single delete from Bitwarden", e);
            }
            return false;
        });
    }

    private static boolean createOrUpdateBitwardenItem(String existingId, String serverIp, String username, String password, String command) {
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

            String encodedJson = Base64.getEncoder().encodeToString(GSON.toJson(item).getBytes(StandardCharsets.UTF_8));
            BwResult res;
            if (existingId != null && !existingId.isBlank()) {
                res = executeBwCommand("edit", "item", existingId, encodedJson);
            } else {
                res = executeBwCommand("create", "item", encodedJson);
            }

            if (res != null && res.exitCode() == 0) {
                try {
                    JsonObject resObj = JsonParser.parseString(res.output()).getAsJsonObject();
                    if (resObj.has("id") && !resObj.get("id").isJsonNull()) {
                        String newId = resObj.get("id").getAsString();
                        PasswordManager.savePassword(serverIp, username, password, command, true, newId);
                    }
                } catch (Exception ignored) {}
                return true;
            }
            return false;
        } catch (Exception e) {
            LOGGER.error("[SYPass] Error creating/updating item in Bitwarden", e);
            return false;
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

    private static BwResult executeBwCommand(String... args) {
        return executeBwCommandWithEnv(null, args);
    }

    private static BwResult executeBwCommandWithEnv(Map<String, String> extraEnv, String... args) {
        try {
            String executable = getCliExecutable();
            String[] command = new String[args.length + 1];
            command[0] = executable;
            System.arraycopy(args, 0, command, 1, args.length);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            if (sessionKey != null && !sessionKey.isEmpty()) {
                pb.environment().put("BW_SESSION", sessionKey);
            }
            pb.environment().put("BW_NOINTERACTION", "true");
            if (extraEnv != null) {
                pb.environment().putAll(extraEnv);
            }

            String currentPath = pb.environment().getOrDefault("PATH", "");
            if (!IS_WINDOWS) {
                String home = System.getProperty("user.home", "");
                String extraPaths = "/usr/local/bin:/usr/bin:/bin:" + home + "/.local/bin:" + home + "/.cargo/bin:" + home + "/.npm-global/bin";
                pb.environment().put("PATH", currentPath.isEmpty() ? extraPaths : currentPath + ":" + extraPaths);
            }

            Process process = pb.start();

            try {
                process.getOutputStream().close();
            } catch (Exception ignored) {}

            StringBuilder output = new StringBuilder();
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                } catch (Exception ignored) {}
            });
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
            return new BwResult(exitCode, output.toString().trim());
        } catch (Exception e) {
            LOGGER.debug("[SYPass] Failed to execute BW CLI command", e);
            return new BwResult(-1, "Exception: " + e.getMessage());
        }
    }
}