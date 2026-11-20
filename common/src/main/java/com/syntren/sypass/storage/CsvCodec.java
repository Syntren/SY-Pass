package com.syntren.sypass.storage;

import com.syntren.sypass.SYPassCommon;
import com.syntren.sypass.security.VaultCrypto;
import com.syntren.sypass.util.ServerAddressUtil;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Universal CSV importer/exporter supporting Bitwarden, KeePass 1.x/2.x, 1Password, and Generic/Browser CSV formats.
 */
public final class CsvCodec {

    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    public record ImportedEntry(
            String serverIp,
            String username,
            char[] password,
            String command,
            boolean isSynced,
            String remoteId,
            boolean isFavorite,
            long lastUsed
    ) {
        public void wipe() {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    private CsvCodec() {}

    public static String exportBitwardenCsv(Path backupsDir, Map<String, Map<String, AccountData>> data) {
        try {
            if (!Files.exists(backupsDir)) {
                Files.createDirectories(backupsDir);
            }
            String timestamp = LocalDateTime.now().format(FILE_TIMESTAMP);
            Path exportFile = backupsDir.resolve("sypass-export-bitwarden-" + timestamp + ".csv");

            StringBuilder csv = new StringBuilder();
            csv.append("folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp\n");

            for (Map.Entry<String, Map<String, AccountData>> sEntry : data.entrySet()) {
                String serverIp = sEntry.getKey();
                for (Map.Entry<String, AccountData> aEntry : sEntry.getValue().entrySet()) {
                    String username = aEntry.getKey();
                    AccountData acc = aEntry.getValue();

                    csv.append(escapeField("Minecraft")).append(',')
                       .append(acc.isFavorite() ? '1' : '0').append(',')
                       .append("login,")
                       .append(escapeField(serverIp)).append(',')
                       .append(escapeField(acc.command())).append(',')
                       .append(',')
                       .append("0,")
                       .append(escapeField(serverIp)).append(',')
                       .append(escapeField(username)).append(',')
                       .append(escapeField(acc.getPasswordAsString())).append(',')
                       .append('\n');
                }
            }

            byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
            try {
                VaultCrypto.writeSecureFile(exportFile, bytes);
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
            return exportFile.getFileName().toString();
        } catch (Exception e) {
            SYPassCommon.LOGGER.error("[SY-Pass] Failed to export Bitwarden CSV", e);
            return null;
        }
    }

    /**
     * Parses a CSV file into a list of {@link ImportedEntry} objects.
     * Returns {@code null} if the file cannot be read or lacks a password column.
     */
    public static List<ImportedEntry> parseCsvFile(File file) {
        if (file == null || !file.exists()) return null;
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            if (lines.isEmpty()) return Collections.emptyList();

            // Strip UTF-8 BOM if present
            String firstLine = lines.get(0);
            if (!firstLine.isEmpty() && firstLine.charAt(0) == '\uFEFF') {
                firstLine = firstLine.substring(1);
            }

            List<String> header = parseLine(firstLine);
            Map<String, Integer> colIndex = new HashMap<>();
            for (int i = 0; i < header.size(); i++) {
                colIndex.put(header.get(i).trim().toLowerCase(Locale.ROOT), i);
            }

            int nameIdx = getColumnIndex(colIndex, "name", "title", "server", "serverip", "host", "hostname", "address", "website", "web site", "url", "login_uri", "group", "account");
            int uriIdx = getColumnIndex(colIndex, "login_uri", "url", "website", "web site", "hostname", "server", "address");
            int userIdx = getColumnIndex(colIndex, "login_username", "username", "login name", "user", "login", "email", "account");
            int passIdx = getColumnIndex(colIndex, "login_password", "password", "pass", "code", "secret");
            int notesIdx = getColumnIndex(colIndex, "notes", "comment", "comments", "command", "cmd", "description");
            int favIdx = getColumnIndex(colIndex, "favorite", "fav", "starred");

            if (passIdx == -1) {
                return null;
            }

            List<ImportedEntry> entries = new ArrayList<>();
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;
                List<String> cols = parseLine(line);

                String server = getCol(cols, nameIdx);
                if (server.isEmpty()) {
                    server = getCol(cols, uriIdx);
                }
                server = ServerAddressUtil.stripSchemeAndPath(server);

                String username = getCol(cols, userIdx);
                String password = getCol(cols, passIdx);
                String notes = getCol(cols, notesIdx);
                String favVal = getCol(cols, favIdx);
                boolean favorite = "1".equals(favVal) || "true".equalsIgnoreCase(favVal);

                if (server.isEmpty() || username.isEmpty() || password.isEmpty()) {
                    continue;
                }

                String cmd = "/login";
                if (notes.startsWith("/")) {
                    int firstSpace = notes.indexOf(' ');
                    cmd = firstSpace > 0 ? notes.substring(0, firstSpace) : notes;
                }

                entries.add(new ImportedEntry(server, username, password.toCharArray(), cmd, false, "", favorite, 0L));
            }
            return entries;
        } catch (Exception e) {
            SYPassCommon.LOGGER.error("[SY-Pass] Failed to import CSV file {}", file.getName(), e);
            return null;
        }
    }

    public static String escapeField(String field) {
        if (field == null) return "";
        if (field.contains(",") || field.contains("\"") || field.contains("\n") || field.contains("\r")) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }

    public static List<String> parseLine(String line) {
        List<String> result = new ArrayList<>();
        if (line == null || line.trim().isEmpty()) return result;

        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
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

    private static String getCol(List<String> cols, int idx) {
        return (idx >= 0 && idx < cols.size()) ? cols.get(idx).trim() : "";
    }

    private static int getColumnIndex(Map<String, Integer> colIndex, String... candidates) {
        for (String c : candidates) {
            Integer idx = colIndex.get(c.toLowerCase(Locale.ROOT));
            if (idx != null) {
                return idx;
            }
        }
        return -1;
    }
}
