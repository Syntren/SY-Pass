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

        void addWord(String word) {
            if (word == null || word.isBlank()) return;
            String clean = word.trim();
            // Short dummy passwords (< 3 chars) are handled via exact match to avoid false positives
            if (clean.length() < 3) {
                shortPasswords.add(clean);
                return;
            }

            hasPatterns = true;
            if (clean.length() < minLength) {
                minLength = clean.length();
            }

            Node curr = root;
            for (int i = 0; i < clean.length(); i++) {
                char c = clean.charAt(i);
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

                    // If failure node is end-of-word, inherit match flag
                    if (child.fail != null && child.fail.isEndOfWord) {
                        child.isEndOfWord = true;
                    }
                    queue.add(child);
                }
            }
        }

        boolean containsAny(String text) {
            if (text == null || text.isEmpty()) return false;

            // 1. Exact match check for short passwords
            if (!shortPasswords.isEmpty() && shortPasswords.contains(text.trim())) {
                return true;
            }

            if (!hasPatterns || text.length() < minLength) {
                return false;
            }

            // 2. Aho-Corasick linear scan: O(length of text)
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
                        for (Map<String, PasswordManager.AccountData> accs : PasswordManager.getAllData().values()) {
                            for (PasswordManager.AccountData acc : accs.values()) {
                                newAuto.addWord(acc.password());
                            }
                        }
                        newAuto.buildFailureLinks();
                        allServersCache = newAuto;
                    }
                    auto = allServersCache;
                }
            }
            return auto.containsAny(message);
        } else {
            // Option B: CURRENT_SERVER
            if (currentServerAddress == null || currentServerAddress.isBlank()) {
                return false;
            }
            String norm = PasswordManager.normalizeServerAddress(currentServerAddress);
            Automaton auto = currentServerCache;
            if (auto == null || !norm.equalsIgnoreCase(cachedServerIp)) {
                synchronized (ChatProtectionMatcher.class) {
                    if (currentServerCache == null || !norm.equalsIgnoreCase(cachedServerIp)) {
                        Automaton newAuto = new Automaton();
                        Map<String, PasswordManager.AccountData> accs = PasswordManager.getServerAccounts(norm);
                        for (PasswordManager.AccountData acc : accs.values()) {
                            newAuto.addWord(acc.password());
                        }
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
}
