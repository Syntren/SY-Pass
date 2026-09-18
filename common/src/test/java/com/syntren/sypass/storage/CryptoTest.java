package com.syntren.sypass.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class CryptoTest {

    @Test
    @DisplayName("PBKDF2WithHmacSHA256 produces repeatable 256-bit key with same salt")
    void testPbkdf2Derivation() throws Exception {
        char[] masterPass = "MyMasterPassword123!".toCharArray();
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);

        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec1 = new PBEKeySpec(masterPass, salt, 100000, 256);
        byte[] keyBytes1 = factory.generateSecret(spec1).getEncoded();

        PBEKeySpec spec2 = new PBEKeySpec(masterPass, salt, 100000, 256);
        byte[] keyBytes2 = factory.generateSecret(spec2).getEncoded();

        assertArrayEquals(keyBytes1, keyBytes2);
        assertEquals(32, keyBytes1.length, "Key must be 256 bits (32 bytes)");
    }

    @Test
    @DisplayName("AES-GCM encrypt and decrypt roundtrip preserves plaintext and integrity")
    void testAesGcmRoundtrip() throws Exception {
        byte[] rawKey = new byte[32];
        new SecureRandom().nextBytes(rawKey);
        SecretKey key = new SecretKeySpec(rawKey, "AES");

        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);

        String plaintext = "{\"testServer\":{\"user\":{\"password\":\"securePwd123\"}}}";

        Cipher encryptCipher = Cipher.getInstance("AES/GCM/NoPadding");
        encryptCipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] cipherText = encryptCipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        Cipher decryptCipher = Cipher.getInstance("AES/GCM/NoPadding");
        decryptCipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] decrypted = decryptCipher.doFinal(cipherText);

        assertEquals(plaintext, new String(decrypted, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("AES-GCM rejects tampered ciphertext or authentication tag")
    void testAesGcmTamperDetection() throws Exception {
        byte[] rawKey = new byte[32];
        new SecureRandom().nextBytes(rawKey);
        SecretKey key = new SecretKeySpec(rawKey, "AES");

        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);

        Cipher encryptCipher = Cipher.getInstance("AES/GCM/NoPadding");
        encryptCipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] cipherText = encryptCipher.doFinal("secret-data".getBytes(StandardCharsets.UTF_8));

        // Tamper with one byte in the ciphertext
        cipherText[0] ^= 0x55;

        Cipher decryptCipher = Cipher.getInstance("AES/GCM/NoPadding");
        decryptCipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        assertThrows(Exception.class, () -> decryptCipher.doFinal(cipherText), "Tampered ciphertext must fail authentication tag check");
    }

    @Test
    @DisplayName("Normalize server address removes trailing port 25565, trailing dots, and trims/lowercases")
    void testNormalizeServerAddress() {
        assertEquals("mc.hypixel.net", PasswordManager.normalizeServerAddress("MC.HYPIXEL.NET:25565"));
        assertEquals("play.example.com", PasswordManager.normalizeServerAddress("  Play.Example.Com.  "));
        assertEquals("127.0.0.1:25566", PasswordManager.normalizeServerAddress("127.0.0.1:25566"));
        assertEquals("", PasswordManager.normalizeServerAddress(null));
    }
}
