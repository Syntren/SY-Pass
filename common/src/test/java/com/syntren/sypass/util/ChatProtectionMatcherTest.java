package com.syntren.sypass.util;

import com.syntren.sypass.config.SYPassConfig;
import com.syntren.sypass.storage.PasswordManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatProtectionMatcherTest {

    @BeforeEach
    void setUp() {
        ChatProtectionMatcher.invalidateCache();
        PasswordManager.savePassword("hypixel.net", "Player1", "TopSecretPass999", "/login");
        PasswordManager.savePassword("mineplex.com", "Player1", "MineplexVault123", "/login");
    }

    @AfterEach
    void tearDown() {
        PasswordManager.removePassword("hypixel.net", "Player1");
        PasswordManager.removePassword("mineplex.com", "Player1");
        ChatProtectionMatcher.invalidateCache();
    }

    @Test
    @DisplayName("Block message containing exact current server password")
    void testCurrentServerLeakDetected() {
        boolean leaks = ChatProtectionMatcher.checkMessageLeaks(
                "Hey guys, my password is TopSecretPass999 on this server",
                "hypixel.net",
                SYPassConfig.ChatProtectionScope.CURRENT_SERVER
        );
        assertTrue(leaks, "Message containing hypixel password should be blocked on hypixel");
    }

    @Test
    @DisplayName("Allow message on current server if password belongs to another server in CURRENT_SERVER mode")
    void testCurrentServerScopeIsolation() {
        boolean leaks = ChatProtectionMatcher.checkMessageLeaks(
                "My other pass is MineplexVault123",
                "hypixel.net",
                SYPassConfig.ChatProtectionScope.CURRENT_SERVER
        );
        assertFalse(leaks, "Mineplex password should not trigger leak detection on Hypixel under CURRENT_SERVER scope");
    }

    @Test
    @DisplayName("Block any saved password regardless of server in ALL_SERVERS scope")
    void testAllServersScopeLeakDetected() {
        boolean leaks = ChatProtectionMatcher.checkMessageLeaks(
                "Accidentally typing MineplexVault123 here",
                "hypixel.net",
                SYPassConfig.ChatProtectionScope.ALL_SERVERS
        );
        assertTrue(leaks, "Mineplex password must be blocked everywhere under ALL_SERVERS scope");
    }

    @Test
    @DisplayName("Normal chat messages without saved passwords are not blocked")
    void testNormalMessageNotBlocked() {
        boolean leaks = ChatProtectionMatcher.checkMessageLeaks(
                "Hello everyone! Nice to meet you in game.",
                "hypixel.net",
                SYPassConfig.ChatProtectionScope.ALL_SERVERS
        );
        assertFalse(leaks, "Benign chat messages must not be blocked");
    }

    @Test
    @DisplayName("Standard authentication and registration commands are recognized as auth commands")
    void testRecognizedAuthCommands() {
        assertTrue(ChatProtectionMatcher.isAuthCommand("login TopSecretPass999", "hypixel.net"));
        assertTrue(ChatProtectionMatcher.isAuthCommand("/login TopSecretPass999", "hypixel.net"));
        assertTrue(ChatProtectionMatcher.isAuthCommand("l TopSecretPass999", "hypixel.net"));
        assertTrue(ChatProtectionMatcher.isAuthCommand("/l TopSecretPass999", "hypixel.net"));
        assertTrue(ChatProtectionMatcher.isAuthCommand("register pass pass", "hypixel.net"));
        assertTrue(ChatProtectionMatcher.isAuthCommand("/reg pass", "hypixel.net"));
        assertTrue(ChatProtectionMatcher.isAuthCommand("auth pass", "hypixel.net"));
        assertTrue(ChatProtectionMatcher.isAuthCommand("/changepassword old new", "hypixel.net"));
    }

    @Test
    @DisplayName("Non-authentication commands like /msg or /say are not recognized as auth commands")
    void testNonAuthCommands() {
        assertFalse(ChatProtectionMatcher.isAuthCommand("msg friend TopSecretPass999", "hypixel.net"));
        assertFalse(ChatProtectionMatcher.isAuthCommand("/tell friend TopSecretPass999", "hypixel.net"));
        assertFalse(ChatProtectionMatcher.isAuthCommand("/w friend TopSecretPass999", "hypixel.net"));
        assertFalse(ChatProtectionMatcher.isAuthCommand("say TopSecretPass999", "hypixel.net"));
        assertFalse(ChatProtectionMatcher.isAuthCommand(null, "hypixel.net"));
        assertFalse(ChatProtectionMatcher.isAuthCommand("", "hypixel.net"));
    }
}
