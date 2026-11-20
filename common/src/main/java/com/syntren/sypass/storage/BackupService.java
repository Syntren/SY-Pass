package com.syntren.sypass.storage;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.syntren.sypass.SYPassCommon;
import com.syntren.sypass.security.VaultCrypto;

import javax.crypto.SecretKey;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Handles plain and PBKDF2/AES-GCM encrypted JSON backup creation and restoration.
 */
public final class BackupService {

    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    @FunctionalInterface
    public interface LocalDecryptor {
        String decrypt(String ciphertext) throws Exception;
    }

    public record ParseOutcome(int errorCode, List<CsvCodec.ImportedEntry> entries) {
        public boolean isSuccess() {
            return errorCode == 0 && entries != null;
        }
    }

    private BackupService() {}

    public static String exportBackup(Path backupsDir, Map<String, Map<String, AccountData>> data, String backupPassword, Gson gson) {
        try {
            if (!Files.exists(backupsDir)) {
                Files.createDirectories(backupsDir);
            }
            String timestamp = LocalDateTime.now().format(FILE_TIMESTAMP);
            Path backupFile = backupsDir.resolve("sypass-backup-" + timestamp + ".json");

            JsonObject servers = serializeServers(data);
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.addProperty("timestamp", timestamp);
            root.add("servers", servers);

            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("version", 1);
            wrapper.addProperty("timestamp", timestamp);

            if (backupPassword != null && !backupPassword.isBlank()) {
                byte[] salt = VaultCrypto.randomBytes(VaultCrypto.SALT_LENGTH);
                byte[] iv = VaultCrypto.randomBytes(VaultCrypto.GCM_IV_LENGTH);
                char[] passChars = backupPassword.trim().toCharArray();
                byte[] plainBytes = gson.toJson(root).getBytes(StandardCharsets.UTF_8);
                try {
                    SecretKey derivedKey = VaultCrypto.deriveKey(passChars, salt, VaultCrypto.DEFAULT_PBKDF2_ITERATIONS);
                    byte[] cipherBytes = VaultCrypto.encryptGcmBytes(plainBytes, derivedKey, iv);

                    wrapper.addProperty("mode", "pbkdf2");
                    wrapper.addProperty("kdf", "PBKDF2WithHmacSHA256");
                    wrapper.addProperty("iterations", VaultCrypto.DEFAULT_PBKDF2_ITERATIONS);
                    wrapper.addProperty("salt", Base64.getEncoder().encodeToString(salt));
                    wrapper.addProperty("iv", Base64.getEncoder().encodeToString(iv));
                    wrapper.addProperty("ciphertext", Base64.getEncoder().encodeToString(cipherBytes));
                } finally {
                    Arrays.fill(passChars, '\0');
                    Arrays.fill(plainBytes, (byte) 0);
                }
            } else {
                wrapper.addProperty("mode", "plain");
                wrapper.add("servers", servers);
            }

            VaultCrypto.writeSecureFile(backupFile, gson.toJson(wrapper).getBytes(StandardCharsets.UTF_8));
            return backupFile.getFileName().toString();
        } catch (Exception e) {
            SYPassCommon.LOGGER.error("[SY-Pass] Failed to export backup", e);
            return null;
        }
    }

    public static ParseOutcome parseBackupFile(File file, String backupPassword, Gson gson, LocalDecryptor localDecryptor) {
        if (file == null || !file.exists()) {
            return new ParseOutcome(-1, null);
        }
        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            JsonObject wrapper = gson.fromJson(content, JsonObject.class);
            if (wrapper == null) {
                return new ParseOutcome(-2, null);
            }

            JsonObject servers = extractServersObject(wrapper, backupPassword, localDecryptor);
            if (servers == null) {
                return new ParseOutcome(-2, null);
            }

            List<CsvCodec.ImportedEntry> entries = new ArrayList<>();
            for (String serverIp : servers.keySet()) {
                JsonObject accs = servers.getAsJsonObject(serverIp);
                for (String user : accs.keySet()) {
                    JsonObject acc = accs.getAsJsonObject(user);
                    if (!acc.has("password") || acc.get("password").isJsonNull()) continue;
                    char[] pass = acc.get("password").getAsString().toCharArray();
                    String cmd = acc.has("command") && !acc.get("command").isJsonNull() ? acc.get("command").getAsString() : "/login";
                    boolean synced = acc.has("isSynced") && acc.get("isSynced").getAsBoolean();
                    String remoteId = acc.has("remoteId") && !acc.get("remoteId").isJsonNull() ? acc.get("remoteId").getAsString() : "";
                    boolean favorite = acc.has("isFavorite") && acc.get("isFavorite").getAsBoolean();
                    long lastUsed = acc.has("lastUsed") ? acc.get("lastUsed").getAsLong() : 0L;
                    entries.add(new CsvCodec.ImportedEntry(serverIp, user, pass, cmd, synced, remoteId, favorite, lastUsed));
                }
            }
            return new ParseOutcome(0, entries);
        } catch (MissingPasswordException e) {
            return new ParseOutcome(-3, null);
        } catch (WrongPasswordException e) {
            return new ParseOutcome(-4, null);
        } catch (LocalKeyMismatchException e) {
            return new ParseOutcome(-5, null);
        } catch (Exception e) {
            SYPassCommon.LOGGER.error("[SY-Pass] Failed to import backup file {}", file.getName(), e);
            return new ParseOutcome(-2, null);
        }
    }

    public static File findLatestFile(Path dir, String extension) {
        if (!Files.exists(dir)) return null;
        String lowerExt = extension.toLowerCase(Locale.ROOT);
        File[] files = dir.toFile().listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(lowerExt));
        if (files == null || files.length == 0) return null;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return files[0];
    }

    private static JsonObject extractServersObject(JsonObject wrapper, String backupPassword, LocalDecryptor localDecryptor)
            throws MissingPasswordException, WrongPasswordException, LocalKeyMismatchException {
        String mode = wrapper.has("mode") && !wrapper.get("mode").isJsonNull()
                ? wrapper.get("mode").getAsString()
                : "";

        if ("plain".equalsIgnoreCase(mode)) {
            return wrapper.has("servers") ? wrapper.getAsJsonObject("servers") : null;
        }
        if ("pbkdf2".equalsIgnoreCase(mode)) {
            if (backupPassword == null || backupPassword.isBlank()) {
                throw new MissingPasswordException();
            }
            char[] passChars = backupPassword.trim().toCharArray();
            try {
                byte[] salt = Base64.getDecoder().decode(wrapper.get("salt").getAsString());
                byte[] iv = Base64.getDecoder().decode(wrapper.get("iv").getAsString());
                byte[] cipherBytes = Base64.getDecoder().decode(wrapper.get("ciphertext").getAsString());
                int iterations = wrapper.has("iterations") ? wrapper.get("iterations").getAsInt() : VaultCrypto.DEFAULT_PBKDF2_ITERATIONS;

                SecretKey derivedKey = VaultCrypto.deriveKey(passChars, salt, iterations);
                byte[] plainBytes = VaultCrypto.decryptGcmBytes(cipherBytes, derivedKey, iv);
                String decryptedJson = new String(plainBytes, StandardCharsets.UTF_8);
                Arrays.fill(plainBytes, (byte) 0);

                JsonObject root = JsonParser.parseString(decryptedJson).getAsJsonObject();
                return root.has("servers") ? root.getAsJsonObject("servers") : null;
            } catch (GeneralSecurityException e) {
                throw new WrongPasswordException();
            } finally {
                Arrays.fill(passChars, '\0');
            }
        }
        if (wrapper.has("encrypted_backup") || "local_key".equalsIgnoreCase(mode)) {
            String encryptedStr = wrapper.has("ciphertext")
                    ? wrapper.get("ciphertext").getAsString()
                    : wrapper.get("encrypted_backup").getAsString();
            try {
                String decryptedJson = localDecryptor.decrypt(encryptedStr);
                JsonObject root = JsonParser.parseString(decryptedJson).getAsJsonObject();
                return root.has("servers") ? root.getAsJsonObject("servers") : null;
            } catch (Exception e) {
                throw new LocalKeyMismatchException();
            }
        }
        if (wrapper.has("servers")) {
            return wrapper.getAsJsonObject("servers");
        }
        return null;
    }

    private static JsonObject serializeServers(Map<String, Map<String, AccountData>> data) {
        JsonObject servers = new JsonObject();
        for (Map.Entry<String, Map<String, AccountData>> sEntry : data.entrySet()) {
            JsonObject accs = new JsonObject();
            for (Map.Entry<String, AccountData> aEntry : sEntry.getValue().entrySet()) {
                AccountData d = aEntry.getValue();
                JsonObject acc = new JsonObject();
                acc.addProperty("password", d.getPasswordAsString());
                acc.addProperty("command", d.command());
                acc.addProperty("isSynced", d.isSynced());
                acc.addProperty("remoteId", d.remoteId());
                acc.addProperty("isFavorite", d.isFavorite());
                acc.addProperty("lastUsed", d.lastUsed());
                accs.add(aEntry.getKey(), acc);
            }
            servers.add(sEntry.getKey(), accs);
        }
        return servers;
    }

    private static final class MissingPasswordException extends Exception {}
    private static final class WrongPasswordException extends Exception {}
    private static final class LocalKeyMismatchException extends Exception {}
}
