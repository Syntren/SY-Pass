package com.syntren.sypass.storage;

import java.util.Arrays;

/**
 * Immutable metadata + mutable char[] password buffer for a stored Minecraft server account.
 * Call {@link #wipe()} when evicting from memory.
 */
public record AccountData(
        char[] password,
        String command,
        boolean isSynced,
        String remoteId,
        boolean isFavorite,
        long lastUsed
) {
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
        } else {
            command = command.trim();
            if (!command.startsWith("/")) {
                command = "/" + command;
            }
        }
        remoteId = (remoteId != null) ? remoteId : "";
    }

    public char[] getPasswordCopy() {
        return password.clone();
    }

    public String getPasswordAsString() {
        return new String(password);
    }

    public AccountData sanitized() {
        return new AccountData(new char[0], command, isSynced, remoteId, isFavorite, lastUsed);
    }

    public AccountData withFavorite(boolean favorite) {
        return new AccountData(password, command, isSynced, remoteId, favorite, lastUsed);
    }

    public AccountData withLastUsed(long timestampMs) {
        return new AccountData(password, command, isSynced, remoteId, isFavorite, timestampMs);
    }

    public AccountData withoutSync() {
        return new AccountData(password, command, false, "", isFavorite, lastUsed);
    }

    public void wipe() {
        Arrays.fill(password, '\0');
    }
}
