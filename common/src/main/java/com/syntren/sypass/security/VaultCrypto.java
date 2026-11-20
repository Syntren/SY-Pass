package com.syntren.sypass.security;

import com.syntren.sypass.SYPassCommon;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;

/**
 * Cryptographic primitives (AES-256-GCM, PBKDF2-HMAC-SHA256) and hardened atomic file I/O.
 */
public final class VaultCrypto {

    public static final String GCM_PREFIX = "gcm:";
    public static final int GCM_IV_LENGTH = 12;
    public static final int GCM_TAG_LENGTH_BITS = 128;
    public static final int DEFAULT_PBKDF2_ITERATIONS = 100_000;
    public static final int SALT_LENGTH = 16;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Set<PosixFilePermission> OWNER_RW = PosixFilePermissions.fromString("rw-------");

    private VaultCrypto() {}

    public static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        SECURE_RANDOM.nextBytes(bytes);
        return bytes;
    }

    public static SecretKey generateAes256Key() throws GeneralSecurityException {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256, SECURE_RANDOM);
        return keyGen.generateKey();
    }

    public static SecretKey deriveKey(char[] password, byte[] salt, int iterations) throws GeneralSecurityException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        byte[] encoded = null;
        try {
            encoded = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(encoded, "AES");
        } finally {
            spec.clearPassword();
            if (encoded != null) {
                Arrays.fill(encoded, (byte) 0);
            }
        }
    }

    public static byte[] encryptGcmBytes(byte[] plaintext, SecretKey key, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
        return cipher.doFinal(plaintext);
    }

    public static byte[] decryptGcmBytes(byte[] ciphertext, SecretKey key, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
        return cipher.doFinal(ciphertext);
    }

    public static String encryptGcm(byte[] plaintext, SecretKey key) throws GeneralSecurityException {
        byte[] iv = randomBytes(GCM_IV_LENGTH);
        byte[] encrypted = encryptGcmBytes(plaintext, key, iv);

        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

        return GCM_PREFIX + Base64.getEncoder().encodeToString(combined);
    }

    public static String encryptGcm(String plaintext, SecretKey key) throws GeneralSecurityException {
        byte[] bytes = plaintext.getBytes(StandardCharsets.UTF_8);
        try {
            return encryptGcm(bytes, key);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    public static byte[] decryptGcmToBytes(String payload, SecretKey key) throws GeneralSecurityException {
        if (payload == null || !payload.startsWith(GCM_PREFIX)) {
            throw new IllegalArgumentException("Payload does not have expected AES-GCM prefix");
        }
        byte[] combined = Base64.getDecoder().decode(payload.substring(GCM_PREFIX.length()).trim());
        if (combined.length <= GCM_IV_LENGTH) {
            throw new IllegalArgumentException("Invalid encrypted payload size");
        }
        byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
        byte[] cipherText = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);
        return decryptGcmBytes(cipherText, key, iv);
    }

    public static String decryptGcm(String payload, SecretKey key) throws GeneralSecurityException {
        byte[] decrypted = decryptGcmToBytes(payload, key);
        try {
            return new String(decrypted, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(decrypted, (byte) 0);
        }
    }

    /**
     * Decrypts legacy AES-ECB payloads for transparent migration to AES-256-GCM.
     */
    public static String decryptLegacyEcb(String payload, SecretKey key) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.DECRYPT_MODE, key);
        byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(payload));
        try {
            return new String(decrypted, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(decrypted, (byte) 0);
        }
    }

    public static void enforceSecurePermissions(Path path) {
        if (path == null || !Files.exists(path)) return;
        try {
            Files.setPosixFilePermissions(path, OWNER_RW);
        } catch (UnsupportedOperationException ignored) {
            File file = path.toFile();
            file.setReadable(false, false);
            file.setReadable(true, true);
            file.setWritable(false, false);
            file.setWritable(true, true);
        } catch (Exception e) {
            SYPassCommon.LOGGER.warn("[SY-Pass] Failed to enforce strict permissions on {}: {}", path.getFileName(), e.getMessage());
        }
    }

    public static void writeSecureFile(Path path, byte[] data) throws IOException {
        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        Path tempFile = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(tempFile, data);
        enforceSecurePermissions(tempFile);
        try {
            Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING);
        }
        enforceSecurePermissions(path);
    }
}
