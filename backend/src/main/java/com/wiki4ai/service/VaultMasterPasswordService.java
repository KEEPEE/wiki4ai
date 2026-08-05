package com.wiki4ai.service;

import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.InvalidParameterSpecException;
import java.util.Arrays;
import java.util.Base64;

@Service
public class VaultMasterPasswordService {

    private static final int PBKDF2_ITERATIONS = 100_000;
    private static final int SALT_LENGTH = 16;
    private static final int KEY_LENGTH = 256;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    /**
     * Hash a master password using PBKDF2.
     * Returns format: base64(salt):base64(hash)
     */
    public String hashPassword(String masterPassword) {
        try {
            byte[] salt = generateSalt();
            byte[] hash = deriveKey(masterPassword, salt);

            return Base64.getEncoder().encodeToString(salt) + ":" +
                   Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash master password", e);
        }
    }

    /**
     * Verify a master password against a stored hash.
     */
    public boolean verifyPassword(String masterPassword, String storedHash) {
        if (storedHash == null || !storedHash.contains(":")) {
            return false;
        }

        try {
            String[] parts = storedHash.split(":", 2);
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] expectedHash = Base64.getDecoder().decode(parts[1]);
            byte[] actualHash = deriveKey(masterPassword, salt);

            return Arrays.equals(actualHash, expectedHash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to verify master password", e);
        }
    }

    /**
     * Check if user has a vault master password set.
     */
    public boolean isPasswordSet(String storedHash) {
        return storedHash != null && !storedHash.isBlank();
    }

    private byte[] generateSalt() {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_LENGTH];
        random.nextBytes(salt);
        return salt;
    }

    private byte[] deriveKey(String password, byte[] salt) throws NoSuchAlgorithmException, InvalidKeySpecException, InvalidParameterSpecException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
        PBEKeySpec spec = new PBEKeySpec(
            password.toCharArray(),
            salt,
            PBKDF2_ITERATIONS,
            KEY_LENGTH
        );

        try {
            return factory.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }
}
