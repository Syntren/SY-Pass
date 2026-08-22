package com.syntren.sypass.storage;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class BitwardenManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("SYPass");
    private static final Gson GSON = new Gson();
    private static String sessionKey = "";
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("sypass");
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    private static final Path LOCAL_CLI_PATH = CONFIG_DIR.resolve(IS_WINDOWS ? "bw.exe" : "bw");

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

    public record BwStatusInfo(boolean isInstalled, String cliPath, String status, String userEmail, String serverUrl) {
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
        sessionKey = key != null ? key.trim() : "";
    }

    public static String getSessionKey() {
        return sessionKey;
    }

    public static boolean hasActiveSession() {
        return sessionKey != null && !sessionKey.isBlank();
    }

    /**
     * Знаходить шлях до виконуваного файлу Bitwarden CLI (bw).
     */
    public static String getCliExecutable() {
        // 1. Локальний файл у config/sypass/
        if (Files.exists(LOCAL_CLI_PATH) && Files.isExecutable(LOCAL_CLI_PATH)) {
            return LOCAL_CLI_PATH.toAbsolutePath().toString();
        }

        // 2. Типові шляхи у системі
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

        // Пошук у NVM директоріях (Linux/macOS)
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

        // 3. Стандартний виклик з PATH
        return IS_WINDOWS ? "bw.cmd" : "bw";
    }

    /**
     * Перевіряє чи доступний Bitwarden CLI.
     */
    public static boolean isCliInstalled() {
        try {
            BwResult res = executeBwCommand("--version");
            return res != null && res.exitCode() == 0 && !res.output().isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Отримує статус Bitwarden CLI (bw status).
     */
    public static BwStatusInfo getStatusInfo() {
        String cliPath = getCliExecutable();
        try {
            BwResult result = executeBwCommand("status");
            if (result == null || result.exitCode() != 0 || result.output().isBlank()) {
                return new BwStatusInfo(false, cliPath, "not_found", "", "");
            }

            JsonElement parsed = JsonParser.parseString(result.output());
            if (parsed.isJsonObject()) {
                JsonObject obj = parsed.getAsJsonObject();
                String status = obj.has("status") && !obj.get("status").isJsonNull() ? obj.get("status").getAsString() : "unknown";
                String userEmail = obj.has("userEmail") && !obj.get("userEmail").isJsonNull() ? obj.get("userEmail").getAsString() : "";
                String serverUrl = obj.has("serverUrl") && !obj.get("serverUrl").isJsonNull() ? obj.get("serverUrl").getAsString() : "";
                return new BwStatusInfo(true, cliPath, status, userEmail, serverUrl);
            }
        } catch (Exception e) {
            LOGGER.debug("[SYPass] Error querying bw status", e);
        }
        return new BwStatusInfo(false, cliPath, "error", "", "");
    }

    /**
     * Встановлює кастомну адресу сервера (Self-hosted або EU Vault).
     */
    public static boolean setServerUrl(String url) {
        if (url == null || url.isBlank()) return false;
        BwResult result = executeBwCommand("config", "server", url.trim());
        return result != null && result.exitCode() == 0;
    }

    /**
     * Авторизація у Bitwarden за логіном/паролем або розблокування сховища.
     * method: "1" = Email, "0" = Authenticator (TOTP), "2" = Duo, "3" = YubiKey
     */
    public static BwLoginResponse login(String email, String password, String otp, String method) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return new BwLoginResponse(LoginStatus.ERROR, "Введіть Email та майстер-пароль!", "");
        }

        email = email.trim();
        password = password.trim();
        String selectedMethod = (method != null && !method.isBlank()) ? method : "1"; // За замовчуванням Email (1)

        // 1. Перевіряємо статус CLI
        BwStatusInfo statusInfo = getStatusInfo();
        if (!statusInfo.isInstalled()) {
            return new BwLoginResponse(LoginStatus.CLI_NOT_FOUND,
                    "Bitwarden CLI (bw) не знайдено в системі! Встановіть 'npm i -g @bitwarden/cli' або завантажте у config/sypass/bw", "");
        }

        try {
            // 2. Якщо вже залогінені під цим email і сховище заблоковане -> робимо unlock
            if (statusInfo.isLocked() && (statusInfo.userEmail().equalsIgnoreCase(email) || statusInfo.userEmail().isEmpty())) {
                LOGGER.info("[SYPass] Vault is locked. Attempting unlock for {}", email);
                BwResult unlockResult = executeBwCommand("unlock", password, "--raw");
                if (unlockResult != null && unlockResult.exitCode() == 0 && isValidSessionKey(unlockResult.output())) {
                    sessionKey = unlockResult.output().trim();
                    LOGGER.info("[SYPass] Unlock successful!");
                    return new BwLoginResponse(LoginStatus.SUCCESS, "Сховище успішно розблоковано!", sessionKey);
                } else if (unlockResult != null && unlockResult.output().toLowerCase().contains("invalid")) {
                    return new BwLoginResponse(LoginStatus.INVALID_PASSWORD, "Невірний майстер-пароль!", "");
                }
            }

            // Якщо залогінені під іншим акаунтом -> спочатку logout
            if (statusInfo.isAuthenticated() && !statusInfo.userEmail().equalsIgnoreCase(email)) {
                LOGGER.info("[SYPass] Logging out previous account: {}", statusInfo.userEmail());
                executeBwCommand("logout");
            }

            // 3. Формуємо команду login
            List<String> args = new ArrayList<>();
            args.add("login");
            args.add(email);
            args.add(password);
            args.add("--method");
            args.add(selectedMethod);

            if (otp != null && !otp.isBlank()) {
                args.add("--code");
                args.add(otp.trim());
            }
            args.add("--raw");

            LOGGER.info("[SYPass] Initiating login for user: {}", email);
            BwResult result = executeBwCommand(args.toArray(new String[0]));
            if (result == null) {
                return new BwLoginResponse(LoginStatus.ERROR, "Не вдалося запустити команду Bitwarden CLI", "");
            }

            String output = result.output().trim();
            String lowerOutput = output.toLowerCase();

            // Перевірка на необхідність 2FA
            if (lowerOutput.contains("two-step") || lowerOutput.contains("code is required") ||
                lowerOutput.contains("verification") || lowerOutput.contains("two-factor") ||
                lowerOutput.contains("2fa") || lowerOutput.contains("code:")) {
                LOGGER.info("[SYPass] Login status: NEED_OTP");
                String promptMsg = selectedMethod.equals("1")
                        ? "Код підтвердження надіслано на ваш Email! Введіть його нижче:"
                        : "Введіть 2FA код з додатку автентифікації:";
                return new BwLoginResponse(LoginStatus.NEED_OTP, promptMsg, "");
            }

            // Перевірка якщо вже залогінені
            if (lowerOutput.contains("already logged in")) {
                BwResult unlockRes = executeBwCommand("unlock", password, "--raw");
                if (unlockRes != null && unlockRes.exitCode() == 0 && isValidSessionKey(unlockRes.output())) {
                    sessionKey = unlockRes.output().trim();
                    return new BwLoginResponse(LoginStatus.SUCCESS, "Успішний вхід та розблокування!", sessionKey);
                } else {
                    return new BwLoginResponse(LoginStatus.INVALID_PASSWORD, "Невірний майстер-пароль для розблокування!", "");
                }
            }

            // Перевірка невірного паролю / коду
            if (lowerOutput.contains("invalid") || result.exitCode() != 0) {
                if (otp != null && !otp.isBlank() && (lowerOutput.contains("code") || lowerOutput.contains("token") || lowerOutput.contains("two-step"))) {
                    return new BwLoginResponse(LoginStatus.INVALID_OTP, "Невірний 2FA код або метод!", "");
                }
                if (lowerOutput.contains("username or password") || lowerOutput.contains("master password") || lowerOutput.contains("credentials")) {
                    return new BwLoginResponse(LoginStatus.INVALID_PASSWORD, "Невірний Email або майстер-пароль!", "");
                }
                String cleanError = output.replace("\n", " ").trim();
                return new BwLoginResponse(LoginStatus.ERROR, cleanError.isEmpty() ? "Помилка авторизації (код " + result.exitCode() + ")" : cleanError, "");
            }

            // Успішний вхід
            if (result.exitCode() == 0 && isValidSessionKey(output)) {
                sessionKey = output;
                LOGGER.info("[SYPass] Login SUCCESS!");
                return new BwLoginResponse(LoginStatus.SUCCESS, "Успішна авторизація!", sessionKey);
            }

        } catch (Exception e) {
            LOGGER.error("[SYPass] Exception during login", e);
            return new BwLoginResponse(LoginStatus.ERROR, "Помилка: " + e.getMessage(), "");
        }

        return new BwLoginResponse(LoginStatus.ERROR, "Невідома відповідь від Bitwarden", "");
    }

    /**
     * Авторизація за допомогою API Key (Client ID + Client Secret) та наступне розблокування.
     */
    public static BwLoginResponse loginWithApiKey(String clientId, String clientSecret, String masterPassword) {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank() || masterPassword == null || masterPassword.isBlank()) {
            return new BwLoginResponse(LoginStatus.ERROR, "Заповніть Client ID, Client Secret та майстер-пароль!", "");
        }

        try {
            Map<String, String> env = new HashMap<>();
            env.put("BW_CLIENTID", clientId.trim());
            env.put("BW_CLIENTSECRET", clientSecret.trim());

            BwResult loginRes = executeBwCommandWithEnv(env, "login", "--apikey");
            if (loginRes == null || (loginRes.exitCode() != 0 && !loginRes.output().toLowerCase().contains("already logged in"))) {
                String err = loginRes != null ? loginRes.output().trim() : "Помилка запуску";
                return new BwLoginResponse(LoginStatus.ERROR, "Помилка API Key: " + err, "");
            }

            BwResult unlockRes = executeBwCommand("unlock", masterPassword.trim(), "--raw");
            if (unlockRes != null && unlockRes.exitCode() == 0 && isValidSessionKey(unlockRes.output())) {
                sessionKey = unlockRes.output().trim();
                return new BwLoginResponse(LoginStatus.SUCCESS, "Успішний вхід за API ключем!", sessionKey);
            } else {
                return new BwLoginResponse(LoginStatus.INVALID_PASSWORD, "Невірний майстер-пароль для розблокування!", "");
            }
        } catch (Exception e) {
            LOGGER.error("[SYPass] Error logging in with API key", e);
            return new BwLoginResponse(LoginStatus.ERROR, "Помилка: " + e.getMessage(), "");
        }
    }

    /**
     * Перевірка чи схожий рядок на session key (base64 токен без пробілів і помилок).
     */
    private static boolean isValidSessionKey(String output) {
        if (output == null) return false;
        String trimmed = output.trim();
        return !trimmed.isEmpty() && !trimmed.contains(" ") && !trimmed.contains("\n") && !trimmed.toLowerCase().contains("error");
    }

    /**
     * Отримання паролів з Bitwarden до локального сховища.
     */
    public static BwSyncResult pullFromBitwarden() {
        if (!hasActiveSession()) {
            return new BwSyncResult(false, 0, "Сховище заблоковане або відсутня активна сесія!");
        }

        try {
            // Синхронізуємо локальний кеш Bitwarden CLI
            executeBwCommand("sync");

            // Отримуємо повний список записів сховища
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

            for (int i = 0; i < items.size(); i++) {
                JsonObject item = items.get(i).getAsJsonObject();
                String name = item.has("name") && !item.get("name").isJsonNull() ? item.get("name").getAsString() : "";

                if (item.has("login") && !item.get("login").isJsonNull()) {
                    JsonObject login = item.getAsJsonObject("login");
                    String username = login.has("username") && !login.get("username").isJsonNull() ? login.get("username").getAsString() : "";
                    String password = login.has("password") && !login.get("password").isJsonNull() ? login.get("password").getAsString() : "";
                    String command = item.has("notes") && !item.get("notes").isJsonNull() ? item.get("notes").getAsString() : "/login";

                    String serverIp = "";

                    // Шукаємо URI з mc://
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

                    // Fallback: якщо URI не знайдено, перевіряємо чи назва починається з "Minecraft: " або "MC: "
                    if (serverIp.isEmpty() && (name.startsWith("Minecraft: ") || name.startsWith("MC: "))) {
                        serverIp = name.replace("Minecraft: ", "").replace("MC: ", "").trim();
                    }

                    if (!serverIp.isEmpty() && !username.isEmpty() && !password.isEmpty()) {
                        PasswordManager.savePassword(serverIp, username, password, command);
                        importedCount++;
                    }
                }
            }

            return new BwSyncResult(true, importedCount, "Успішно імпортовано " + importedCount + " паролів!");
        } catch (Exception e) {
            LOGGER.error("[SYPass] Error pulling from Bitwarden", e);
            return new BwSyncResult(false, 0, "Помилка імпорту: " + e.getMessage());
        }
    }

    /**
     * Відправлення локальних паролів до Bitwarden (створення або оновлення).
     */
    public static BwSyncResult pushToBitwarden() {
        if (!hasActiveSession()) {
            return new BwSyncResult(false, 0, "Сховище заблоковане або відсутня активна сесія!");
        }

        try {
            executeBwCommand("sync");

            // Отримуємо існуючі елементи для уникнення дублювання
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
                    String existingId = existingItemIds.get(key);

                    boolean success = createOrUpdateBitwardenItem(existingId, serverIp, username, data.password(), data.command());
                    if (success) {
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
            return res != null && res.exitCode() == 0;
        } catch (Exception e) {
            LOGGER.error("[SYPass] Error creating/updating item in Bitwarden", e);
            return false;
        }
    }

    public static void logout() {
        try {
            executeBwCommand("logout");
        } catch (Exception ignored) {}
        sessionKey = "";
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
            if (extraEnv != null) {
                pb.environment().putAll(extraEnv);
            }

            // Додаємо типові системні шляхи до PATH якщо потрібно
            String currentPath = pb.environment().getOrDefault("PATH", "");
            if (!IS_WINDOWS) {
                String home = System.getProperty("user.home", "");
                String extraPaths = "/usr/local/bin:/usr/bin:/bin:" + home + "/.local/bin:" + home + "/.cargo/bin:" + home + "/.npm-global/bin";
                pb.environment().put("PATH", currentPath.isEmpty() ? extraPaths : currentPath + ":" + extraPaths);
            }

            Process process = pb.start();

            // Закриваємо stdin, щоб процес не зависав на інтерактивних запитах
            try {
                process.getOutputStream().close();
            } catch (Exception ignored) {}

            StringBuilder output = new StringBuilder();
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                        String lower = line.toLowerCase();
                        // Якщо CLI запитує 2FA код на stdin, не чекаємо вічно
                        if (lower.contains("two-step") || lower.contains("verification") || lower.contains("two-factor") || lower.contains("2fa") || lower.contains("code:")) {
                            try { Thread.sleep(200); } catch (Exception ignored) {}
                            break;
                        }
                    }
                } catch (Exception ignored) {}
            });
            readerThread.setDaemon(true);
            readerThread.start();

            // Таймаут 10 секунд на виконання команди
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            try {
                readerThread.join(1000);
            } catch (Exception ignored) {}

            int exitCode = finished ? process.exitValue() : -1;
            return new BwResult(exitCode, output.toString().trim());
        } catch (Exception e) {
            LOGGER.debug("[SYPass] Failed to execute BW CLI command", e);
            return new BwResult(-1, "Exception: " + e.getMessage());
        }
    }
}