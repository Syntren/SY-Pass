package com.syntren.sypass.storage;

import com.syntren.sypass.security.VaultCrypto;
import com.syntren.sypass.util.ServerAddressUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;

import static org.junit.jupiter.api.Assertions.*;

class CryptoTest {

    @Test
    @DisplayName("PBKDF2WithHmacSHA256 produces repeatable 256-bit key with same salt")
    void testPbkdf2Derivation() throws Exception {
        byte[] salt = VaultCrypto.randomBytes(VaultCrypto.SALT_LENGTH);
        SecretKey key1 = VaultCrypto.deriveKey("MyMasterPassword123!".toCharArray(), salt, VaultCrypto.DEFAULT_PBKDF2_ITERATIONS);
        SecretKey key2 = VaultCrypto.deriveKey("MyMasterPassword123!".toCharArray(), salt, VaultCrypto.DEFAULT_PBKDF2_ITERATIONS);

        assertArrayEquals(key1.getEncoded(), key2.getEncoded());
        assertEquals(32, key1.getEncoded().length, "Key must be 256 bits (32 bytes)");
    }

    @Test
    @DisplayName("VaultCrypto AES-256-GCM encrypt and decrypt roundtrip preserves plaintext")
    void testAesGcmRoundtrip() throws Exception {
        SecretKey key = VaultCrypto.generateAes256Key();
        String plaintext = "{\"testServer\":{\"user\":{\"password\":\"securePwd123\"}}}";

        String encrypted = VaultCrypto.encryptGcm(plaintext, key);
        assertTrue(encrypted.startsWith(VaultCrypto.GCM_PREFIX));
        assertEquals(plaintext, VaultCrypto.decryptGcm(encrypted, key));
    }

    @Test
    @DisplayName("VaultCrypto AES-GCM rejects tampered ciphertext")
    void testAesGcmTamperDetection() throws Exception {
        SecretKey key = VaultCrypto.generateAes256Key();
        String encrypted = VaultCrypto.encryptGcm("secret-data", key);
        char last = encrypted.charAt(encrypted.length() - 2);
        char flipped = (last == 'A') ? 'B' : 'A';
        String tampered = encrypted.substring(0, encrypted.length() - 2) + flipped + encrypted.charAt(encrypted.length() - 1);

        assertThrows(Exception.class, () -> VaultCrypto.decryptGcm(tampered, key));
    }

    @Test
    @DisplayName("ServerAddressUtil normalizes port 25565, trailing dots, scheme, and casing")
    void testNormalizeServerAddress() {
        assertEquals("mc.hypixel.net", ServerAddressUtil.normalize("MC.HYPIXEL.NET:25565"));
        assertEquals("play.example.com", ServerAddressUtil.normalize("  Play.Example.Com.  "));
        assertEquals("127.0.0.1:25566", ServerAddressUtil.normalize("127.0.0.1:25566"));
        assertEquals("", ServerAddressUtil.normalize(null));
        assertEquals("play.example.com", ServerAddressUtil.stripSchemeAndPath("mc://play.example.com/lobby"));
        assertEquals("mc.hypixel.net", PasswordManager.normalizeServerAddress("MC.HYPIXEL.NET:25565"));
    }

    @Test
    @DisplayName("ServerAddressUtil masks server address with asterisks")
    void testMaskServerAddress() {
        assertEquals("********************", ServerAddressUtil.mask("mc.hypixel.net:25565"));
        assertEquals("*************", ServerAddressUtil.mask("192.168.1.100"));
        assertEquals("*********", ServerAddressUtil.mask("localhost"));
        assertEquals("", ServerAddressUtil.mask(null));
        assertEquals("", ServerAddressUtil.mask(""));
    }
}
