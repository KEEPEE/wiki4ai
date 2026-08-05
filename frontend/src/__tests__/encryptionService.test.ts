/**
 * Tests for EncryptionService using Web Crypto API with fallback for insecure contexts.
 */

import { describe, it, expect } from 'vitest';
import { deriveKey, encrypt, decrypt } from '../services/encryptionService';

describe('EncryptionService', () => {
  const testPassword = 'test-master-password-123';
  const testSalt = new Uint8Array(16).fill(42);

  describe('deriveKey', () => {
    it('should derive a key from password and salt', async () => {
      const key = await deriveKey(testPassword, testSalt);
      expect(key).toBeDefined();
      expect(key).toBeInstanceOf(Uint8Array);
      expect(key.length).toBe(32); // AES-256 key size in bytes
    });

    it('should produce different keys for different passwords with same salt', async () => {
      const key1 = await deriveKey('password-one', testSalt);
      const key2 = await deriveKey('password-two', testSalt);

      expect(key1).not.toEqual(key2);

      const testData = 'same plaintext';
      const encrypted1 = await encrypt(testData, key1);
      const decrypted = await decrypt(encrypted1.ciphertext, encrypted1.iv, key2);

      // Decryption with wrong key should return null (authentication failure)
      expect(decrypted).toBeNull();
    });

    it('should produce different keys for same password with different salt', async () => {
      const salt1 = new Uint8Array(16).fill(1);
      const salt2 = new Uint8Array(16).fill(2);

      const key1 = await deriveKey(testPassword, salt1);
      const key2 = await deriveKey(testPassword, salt2);

      expect(key1).not.toEqual(key2);

      const testData = 'same plaintext';
      const encrypted1 = await encrypt(testData, key1);
      const decrypted = await decrypt(encrypted1.ciphertext, encrypted1.iv, key2);

      // Decryption with wrong key should return null (authentication failure)
      expect(decrypted).toBeNull();
    });
  });

  describe('encrypt', () => {
    it('should return ciphertext and iv', async () => {
      const key = await deriveKey(testPassword, testSalt);
      const result = await encrypt('hello world', key);

      expect(result.ciphertext).toBeInstanceOf(Uint8Array);
      expect(result.iv).toBeInstanceOf(Uint8Array);
      expect(result.ciphertext.length).toBeGreaterThan(0);
      expect(result.iv.length).toBe(12);
    });

    it('should generate a new IV for each encryption', async () => {
      const key = await deriveKey(testPassword, testSalt);
      const enc1 = await encrypt('same data', key);
      const enc2 = await encrypt('same data', key);

      expect(enc1.iv).not.toEqual(enc2.iv);
    });

    it('should produce different ciphertext for same plaintext (due to new IV)', async () => {
      const key = await deriveKey(testPassword, testSalt);
      const enc1 = await encrypt('same data', key);
      const enc2 = await encrypt('same data', key);

      expect(enc1.ciphertext).not.toEqual(enc2.ciphertext);
    });
  });

  describe('decrypt', () => {
    it('should decrypt encrypted data back to original (round-trip)', async () => {
      const key = await deriveKey(testPassword, testSalt);
      const original = 'Hello, World!';
      const encrypted = await encrypt(original, key);
      const decrypted = await decrypt(encrypted.ciphertext, encrypted.iv, key);

      expect(decrypted).toBe(original);
    });

    it('should handle empty string', async () => {
      const key = await deriveKey(testPassword, testSalt);
      const original = '';
      const encrypted = await encrypt(original, key);
      const decrypted = await decrypt(encrypted.ciphertext, encrypted.iv, key);

      expect(decrypted).toBe(original);
    });

    it('should handle unicode characters', async () => {
      const key = await deriveKey(testPassword, testSalt);
      const original = 'Príklad — Test Článok 🚀';
      const encrypted = await encrypt(original, key);
      const decrypted = await decrypt(encrypted.ciphertext, encrypted.iv, key);

      expect(decrypted).toBe(original);
    });

    it('should handle long text', async () => {
      const key = await deriveKey(testPassword, testSalt);
      const original = 'A'.repeat(10_000);
      const encrypted = await encrypt(original, key);
      const decrypted = await decrypt(encrypted.ciphertext, encrypted.iv, key);

      expect(decrypted).toBe(original);
    });

    it('should return null with wrong IV', async () => {
      const key = await deriveKey(testPassword, testSalt);
      const original = 'secret data';
      const encrypted = await encrypt(original, key);
      const wrongIv = new Uint8Array(12).fill(0);

      // Decryption with wrong IV should return null (authentication failure)
      const result = await decrypt(encrypted.ciphertext, wrongIv, key);
      expect(result).toBeNull();
    });

    it('should return null with wrong ciphertext', async () => {
      const key = await deriveKey(testPassword, testSalt);
      const original = 'secret data';
      const encrypted = await encrypt(original, key);
      const wrongCiphertext = new Uint8Array(encrypted.ciphertext.length).fill(0);

      // Decryption with wrong ciphertext should return null (authentication failure)
      const result = await decrypt(wrongCiphertext, encrypted.iv, key);
      expect(result).toBeNull();
    });

    it('should return null with wrong key', async () => {
      const key1 = await deriveKey(testPassword, testSalt);
      const key2 = await deriveKey('wrong-password', testSalt);

      const original = 'secret data';
      const encrypted = await encrypt(original, key1);

      // Decryption with wrong key should return null (authentication failure)
      const result = await decrypt(encrypted.ciphertext, encrypted.iv, key2);
      expect(result).toBeNull();
    });
  });

  describe('full round-trip scenarios', () => {
    it('encrypt → decrypt with JSON payload', async () => {
      const key = await deriveKey(testPassword, testSalt);
      const original = JSON.stringify({ id: 1, title: 'Test', content: 'Hello' });
      const encrypted = await encrypt(original, key);
      const decrypted = await decrypt(encrypted.ciphertext, encrypted.iv, key);

      expect(decrypted).toBe(original);
    });

    it('same password+salt can encrypt and decrypt multiple times', async () => {
      const key1 = await deriveKey(testPassword, testSalt);
      const key2 = await deriveKey(testPassword, testSalt);

      // Same password + salt should produce same key bytes
      expect(key1).toEqual(key2);

      const data1 = 'message one';
      const data2 = 'message two';

      const enc1 = await encrypt(data1, key1);
      const dec1 = await decrypt(enc1.ciphertext, enc1.iv, key2);

      const enc2 = await encrypt(data2, key2);
      const dec2 = await decrypt(enc2.ciphertext, enc2.iv, key1);

      expect(dec1).toBe(data1);
      expect(dec2).toBe(data2);
    });
  });
});
