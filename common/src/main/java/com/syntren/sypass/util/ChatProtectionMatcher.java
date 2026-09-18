package com.syntren.sypass.util;

import com.syntren.sypass.config.SYPassConfig;
import com.syntren.sypass.storage.PasswordManager;

import java.util.*;

/**
 * High-performance multi-pattern matcher based on the Aho-Corasick algorithm.
 * Enables O(N) linear-time password leak scanning where N is the message length,
 * completely independent of the number of passwords in the vault.
 */
public class ChatProtectionMatcher {

    private static class Node {
        final Map<Character, Node> children = new HashMap<>();
        Node fail = null;
        boolean isEndOfWord = false;
    }

    private static class Automaton {
        final Node root = new Node();
        final Set<String> shortPasswords = new HashSet<>();
        int minLength = Integer.MAX_VALUE;
        boolean hasPatterns = false;

        void addWord(char[] word) {
            if (word == null || word.length == 0) return;
            int start = 0;
            while (start < word.length && Character.isWhitespace(word[start])) start++;
            int end = word.length;
            while (end > start && Character.isWhitespace(word[end - 1])) end--;
            int len = end - start;
            if (len < 3) {
                if (len > 0) {
                    shortPasswords.add(new String(word, start, len));
                }
                return;
            }

            hasPatterns = true;
            if (len < minLength) {
                minLength = len;
            }

            Node curr = root;
            for (int i = start; i < end; i++) {
                char c = word[i];
                curr = curr.children.computeIfAbsent(c, k -> new Node());
            }
            curr.isEndOfWord = true;
        }

        void buildFailureLinks() {
            Queue<Node> queue = new ArrayDeque<>();
            for (Node child : root.children.values()) {
                child.fail = root;
                queue.add(child);
            }

            while (!queue.isEmpty()) {
                Node curr = queue.poll();
                for (Map.Entry<Character, Node> entry : curr.children.entrySet()) {
                    char c = entry.getKey();
                    Node child = entry.getValue();

                    Node f = curr.fail;
                    while (f != null && !f.children.containsKey(c)) {
                        f = f.fail;
                    }
                    child.fail = (f != null) ? f.children.get(c) : root;

                    if (child.fail != null && child.fail.isEndOfWord) {
                        child.isEndOfWord = true;
                    }
                    queue.add(child);
                }
            }
        }

        boolean containsAny(String text) {
            if (text == null || text.isEmpty()) return false;

            if (!shortPasswords.isEmpty() && shortPasswords.contains(text.trim())) {
                return true;
            }

            if (!hasPatterns || text.length() < minLength) {
                return false;
            }

            Node curr = root;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                while (curr != null && !curr.children.containsKey(c)) {
                    curr = curr.fail;
                }
                if (curr == null) {
                    curr = root;
                    continue;
                }
                curr = curr.children.get(c);
                if (curr.isEndOfWord) {
                    return true;
                }
            }
            return false;
        }
    }

    private static volatile Automaton allServersCache = null;
    private static volatile String cachedServerIp = null;
    private static volatile Automaton currentServerCache = null;

    public static synchronized void invalidateCache() {
        allServersCache = null;
        cachedServerIp = null;
        currentServerCache = null;
    }

    public static boolean checkMessageLeaks(String message, String currentServerAddress, SYPassConfig.ChatProtectionScope scope) {
        if (message == null || message.isBlank()) return false;

        if (scope == SYPassConfig.ChatProtectionScope.ALL_SERVERS) {
            Automaton auto = allServersCache;
            if (auto == null) {
                synchronized (ChatProtectionMatcher.class) {
                    if (allServersCache == null) {
                        Automaton newAuto = new Automaton();
                        PasswordManager.populateProtectionAutomaton(newAuto::addWord, null);
                        newAuto.buildFailureLinks();
                        allServersCache = newAuto;
                    }
                    auto = allServersCache;
                }
            }
            return auto.containsAny(message);
        } else {
            if (currentServerAddress == null || currentServerAddress.isBlank()) {
                return false;
            }
            String norm = PasswordManager.normalizeServerAddress(currentServerAddress);
            Automaton auto = currentServerCache;
            if (auto == null || !norm.equalsIgnoreCase(cachedServerIp)) {
                synchronized (ChatProtectionMatcher.class) {
                    if (currentServerCache == null || !norm.equalsIgnoreCase(cachedServerIp)) {
                        Automaton newAuto = new Automaton();
                        PasswordManager.populateProtectionAutomaton(newAuto::addWord, norm);
                        newAuto.buildFailureLinks();
                        currentServerCache = newAuto;
                        cachedServerIp = norm;
                    }
                    auto = currentServerCache;
                }
            }
            return auto.containsAny(message);
        }
    }

    public static boolean isAuthCommand(String command, String serverAddress) {
        if (command == null || command.isBlank()) return false;
        String clean = command.trim();
        if (clean.startsWith("/")) {
            clean = clean.substring(1).trim();
        }
        String lower = clean.toLowerCase(Locale.ROOT);

        // 1. Standard authentication and registration commands
        if (lower.startsWith("login ") || lower.equals("login") ||
            lower.startsWith("l ") || lower.equals("l") ||
            lower.startsWith("register ") || lower.equals("register") ||
            lower.startsWith("reg ") || lower.equals("reg") ||
            lower.startsWith("auth ") || lower.equals("auth") ||
            lower.startsWith("changepassword ") || lower.startsWith("changepass ") ||
            lower.startsWith("cp ") || lower.startsWith("unregister ")) {
            return true;
        }

        // 2. Custom registration template command
        String regTemplate = SYPassConfig.getRegisterCommandTemplate();
        if (regTemplate != null && !regTemplate.isBlank()) {
            String tmplClean = regTemplate.trim();
            if (tmplClean.startsWith("/")) tmplClean = tmplClean.substring(1).trim();
            int spaceIdx = tmplClean.indexOf(' ');
            String tmplPrefix = (spaceIdx > 0 ? tmplClean.substring(0, spaceIdx) : tmplClean).toLowerCase(Locale.ROOT);
            if (!tmplPrefix.isEmpty() && (lower.startsWith(tmplPrefix + " ") || lower.equals(tmplPrefix))) {
                return true;
            }
        }

        // 3. User's configured custom login command for this server
        if (serverAddress != null && !serverAddress.isBlank()) {
            try {
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                if (client != null && client.getUser() != null) {
                    String username = client.getUser().getName();
                    if (username != null && !username.isBlank()) {
                        PasswordManager.AccountData acc = PasswordManager.getPassword(serverAddress, username);
                        if (acc != null && acc.command() != null) {
                            String customCmd = acc.command().trim();
                            if (customCmd.startsWith("/")) customCmd = customCmd.substring(1).trim();
                            int spaceIdx = customCmd.indexOf(' ');
                            String customPrefix = (spaceIdx > 0 ? customCmd.substring(0, spaceIdx) : customCmd).toLowerCase(Locale.ROOT);
                            if (!customPrefix.isEmpty() && (lower.startsWith(customPrefix + " ") || lower.equals(customPrefix))) {
                                return true;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
                // Ignore in headless / test environment
            }
        }

        return false;
    }
}
