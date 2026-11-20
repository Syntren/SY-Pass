package com.syntren.sypass.util;

import java.util.Locale;

/**
 * Utilities for normalizing, stripping, and masking Minecraft server addresses.
 */
public final class ServerAddressUtil {

    private ServerAddressUtil() {}

    public static String normalize(String address) {
        if (address == null) return "";
        String clean = address.trim().toLowerCase(Locale.ROOT);
        if (clean.endsWith(":25565")) {
            clean = clean.substring(0, clean.length() - 6);
        }
        while (clean.endsWith(".")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }

    public static String stripSchemeAndPath(String raw) {
        if (raw == null) return "";
        String server = raw.trim();
        String lower = server.toLowerCase(Locale.ROOT);
        if (lower.startsWith("mc://")) {
            server = server.substring(5);
        } else if (lower.startsWith("https://")) {
            server = server.substring(8);
        } else if (lower.startsWith("http://")) {
            server = server.substring(7);
        }
        int slashIdx = server.indexOf('/');
        if (slashIdx >= 0) {
            server = server.substring(0, slashIdx);
        }
        return server.trim();
    }

    public static String mask(String serverAddress) {
        if (serverAddress == null || serverAddress.isEmpty()) return "";
        return "*".repeat(serverAddress.length());
    }
}
