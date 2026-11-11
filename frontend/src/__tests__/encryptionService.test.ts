/**
 * Tests for the v1 half of the vault crypto (encryptionService) after WIKI4AI-197 D2.
 *
 * The shape of this suite changed with the module: `encrypt`/`decrypt` on strings are
 * gone, and what is left is the v1 key derivation plus `decryptV1Bytes`, which returns
 * bytes. Building a v1 ciphertext in these tests now goes through `cryptoApi`
 * directly — that is exactly what the deleted `encrypt` used to do, and saying so
 * keeps the dependency honest.
 */

import { describe, it, expect } from 'vitest';
import * as encryptionService from '../services/encryptionService';
import {
  deriveKey,
  decryptV1Bytes,
  bytesToBase64,
  base64ToBytes,
} from '../services/encryptionService';
import { cryptoApi } from '../services/cryptoApi';
import { deriveV1DecryptKey, hashMasterPasswordLocally, type V1DecryptKey } from '../services/vaultCrypto';
import type { EncryptedField } from '../types/vault';

/**
 * Byte comparison that survives the jsdom/Node realm split.
 *
 * `toEqual` on typed arrays also compares constructors, and here the two sides come
 * from different realms: a `TextEncoder().encode()` in the test is a jsdom
 * `Uint8Array`, while bytes handed back by WebCrypto are Node's. Same name, same
 * bytes, different constructor — so the assertion fails on values that print
 * identically. Comparing the contents sidesteps a difference that means nothing.
 */
function expectBytes(actual: Uint8Array | null | undefined, expected: Uint8Array): void {
  expect(actual).toBeInstanceOf(Uint8Array);
  expect(Array.from(actual ?? [])).toEqual(Array.from(expected));
}

const testPassword = 'test-master-password-123';
const testSalt = new Uint8Array(16).fill(42);

/** A v1-shaped field: AES-256-GCM over the plaintext bytes, random IV, no AAD. */
async function v1Field(plaintext: Uint8Array, key: Uint8Array): Promise<EncryptedField> {
  const { ciphertextWithTag, iv } = await cryptoApi.encryptBytes(plaintext, key);
  return { ciphertext: bytesToBase64(ciphertextWithTag), iv: bytesToBase64(iv) };
}

describe('EncryptionService (v1 read path)', () => {
  describe('deriveKey', () => {
    it('derives a 32-byte AES-256 key from password and salt', async () => {
      const key = await deriveKey(testPassword, testSalt);
      expect(key).toBeInstanceOf(Uint8Array);
      expect(key.length).toBe(32);
    });

    it('produces different keys for different passwords with the same salt', async () => {
      const key1 = await deriveKey('password-one', testSalt);
      const key2 = await deriveKey('password-two', testSalt);

      expect(key1).not.toEqual(key2);

      const field = await v1Field(new TextEncoder().encode('same plaintext'), key1);
      expect(await decryptV1Bytes(field, key2 as V1DecryptKey)).toBeNull();
    });

    it('produces different keys for the same password with different salts', async () => {
      const key1 = await deriveKey(testPassword, new Uint8Array(16).fill(1));
      const key2 = await deriveKey(testPassword, new Uint8Array(16).fill(2));

      expect(key1).not.toEqual(key2);

      const field = await v1Field(new TextEncoder().encode('same plaintext'), key1);
      expect(await decryptV1Bytes(field, key2 as V1DecryptKey)).toBeNull();
    });

    it('is deterministic: the same password and salt give the same bytes', async () => {
      const key1 = await deriveKey(testPassword, testSalt);
      const key2 = await deriveKey(testPassword, testSalt);
      expect(key1).toEqual(key2);
    });
  });

  describe('decryptV1Bytes', () => {
    it('returns the plaintext bytes it was given', async () => {
      const key = await deriveKey(testPassword, testSalt);
      const plaintext = new TextEncoder().encode('Hello, World!');
      const field = await v1Field(plaintext, key);

      const decrypted = await decryptV1Bytes(field, key as V1DecryptKey);

      expect(decrypted).toBeInstanceOf(Uint8Array);
      expectBytes(decrypted, plaintext);
    });

    it('round-trips the JSON text v1 actually stored', async () => {
      const key = await deriveKey(testPassword, testSalt);
      const plaintext = new TextEncoder().encode(JSON.stringify('p@ssw0rd'));
      const field = await v1Field(plaintext, key);

      const decrypted = await decryptV1Bytes(field, key as V1DecryptKey);
      expect(decrypted).not.toBeNull();
      expect(JSON.parse(new TextDecoder().decode(decrypted!))).toBe('p@ssw0rd');
    });

    it('preserves diakritika and emoji byte for byte', async () => {
      // The reason this module returns bytes at all: the migration compares what it
      // read with what it writes, and a string round trip is where that comparison
      // would quietly go wrong on exactly these characters.
      const key = await deriveKey(testPassword, testSalt);
      const plaintext = new TextEncoder().encode('Príklad — Článok 🚀');
      const field = await v1Field(plaintext, key);

      const decrypted = await decryptV1Bytes(field, key as V1DecryptKey);
      expectBytes(decrypted, plaintext);
    });

    it('handles an empty plaintext', async () => {
      const key = await deriveKey(testPassword, testSalt);
      const field = await v1Field(new Uint8Array(0), key);

      const decrypted = await decryptV1Bytes(field, key as V1DecryptKey);
      expectBytes(decrypted, new Uint8Array(0));
    });

    it('handles a long plaintext', async () => {
      const key = await deriveKey(testPassword, testSalt);
      const plaintext = new TextEncoder().encode('A'.repeat(10_000));
      const field = await v1Field(plaintext, key);

      expectBytes(await decryptV1Bytes(field, key as V1DecryptKey), plaintext);
    });

    it('returns null for a wrong IV rather than a placeholder', async () => {
      const key = await deriveKey(testPassword, testSalt);
      const field = await v1Field(new TextEncoder().encode('secret data'), key);
      const tampered: EncryptedField = { ...field, iv: bytesToBase64(new Uint8Array(12)) };

      expect(await decryptV1Bytes(tampered, key as V1DecryptKey)).toBeNull();
    });

    it('returns null for tampered ciphertext', async () => {
      const key = await deriveKey(testPassword, testSalt);
      const field = await v1Field(new TextEncoder().encode('secret data'), key);
      const bytes = base64ToBytes(field.ciphertext);
      bytes[0] ^= 0xff;
      const tampered: EncryptedField = { ...field, ciphertext: bytesToBase64(bytes) };

      expect(await decryptV1Bytes(tampered, key as V1DecryptKey)).toBeNull();
    });

    it('returns null with a key derived from another password', async () => {
      const key1 = await deriveKey(testPassword, testSalt);
      const key2 = await deriveKey('wrong-password', testSalt);
      const field = await v1Field(new TextEncoder().encode('secret data'), key1);

      expect(await decryptV1Bytes(field, key2 as V1DecryptKey)).toBeNull();
    });

    it('returns null for a missing or incomplete field', async () => {
      const key = await deriveKey(testPassword, testSalt);

      expect(await decryptV1Bytes(null, key as V1DecryptKey)).toBeNull();
      expect(await decryptV1Bytes(undefined, key as V1DecryptKey)).toBeNull();
      expect(await decryptV1Bytes({ ciphertext: '', iv: '' }, key as V1DecryptKey)).toBeNull();
    });

    it('opens a field written through the v1 key derivation (h + salt)', async () => {
      // The path the real rows came from: h = SHA-256(password), K1 = PBKDF2(h, salt).
      const h = await hashMasterPasswordLocally(testPassword);
      const key = await deriveV1DecryptKey(h, testSalt);
      const plaintext = new TextEncoder().encode(JSON.stringify('réel'));
      const field = await v1Field(plaintext, key);

      expectBytes(await decryptV1Bytes(field, key), plaintext);
    });
  });

  describe('v1 encryption is gone (WIKI4AI-197 D2)', () => {
    it('the module exports no encrypt at runtime', () => {
      expect('encrypt' in encryptionService).toBe(false);
      expect((encryptionService as Record<string, unknown>).encrypt).toBeUndefined();
    });

    it('the module exports no string-returning decrypt either', () => {
      expect((encryptionService as Record<string, unknown>).decrypt).toBeUndefined();
    });

    it('naming encrypt in source is a compile error, not a runtime surprise', () => {
      // @ts-expect-error 'encrypt' was deleted from this module in D2. If this line
      // ever typechecks, the v1 write path is back and a client can leave a vault
      // half-migrated again.
      const v1Encrypt = encryptionService.encrypt;
      expect(v1Encrypt).toBeUndefined();
    });
  });

  describe('base64 helpers', () => {
    it('round-trips arbitrary bytes including non-ASCII', () => {
      const bytes = new Uint8Array([0, 1, 2, 253, 254, 255, 128, 64, 32]);
      expect(base64ToBytes(bytesToBase64(bytes))).toEqual(bytes);
    });

    it('round-trips an empty array', () => {
      expect(base64ToBytes(bytesToBase64(new Uint8Array(0)))).toEqual(new Uint8Array(0));
    });
  });
});
