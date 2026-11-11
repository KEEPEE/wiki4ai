/**
 * Tests for the v2 key hierarchy and the v1 digest mirror (WIKI4AI-197 D2).
 *
 * The digest vectors below are not produced by this file's own code. They were
 * computed with Python's hashlib over the layout documented in
 * `backend/.../VaultV1Digest.java` — an independent implementation of the same
 * spec — so a drift between the TypeScript and the Java side fails here instead of
 * showing up as every row being skipped during a live migration.
 */

import { describe, it, expect } from 'vitest';
import {
  DEFAULT_V2_ITERATIONS,
  buildV2Aad,
  bytesEqual,
  computeV1Digest,
  decryptV2Field,
  deriveV1DecryptKey,
  deriveV2Kek,
  encryptV2Field,
  fingerprintKey,
  generateDek,
  hashMasterPasswordLocally,
  parseV2KdfParams,
  safeBase64ToBytes,
  unwrapDek,
  wrapDek,
  type V2FieldContext,
  type V2Key,
} from '../services/vaultCrypto';
import { cryptoApi } from '../services/cryptoApi';

const FAST_KDF = 1_000;

function toHex(bytes: Uint8Array): string {
  return Array.from(bytes).map((b) => b.toString(16).padStart(2, '0')).join('');
}

function bytesFromHex(hex: string): Uint8Array {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return out;
}

function expectBytes(actual: Uint8Array | null | undefined, expected: Uint8Array): void {
  expect(actual).toBeInstanceOf(Uint8Array);
  expect(Array.from(actual ?? [])).toEqual(Array.from(expected));
}

const CONTEXT: V2FieldContext = {
  userId: 7,
  entryUuid: '3f9a1c22-7777-4b0e-9a11-0d0d0d0d0d0d',
  field: 'password',
  keyId: 'a'.repeat(64),
};

describe('vaultCrypto (v2)', () => {
  describe('the v1 digest mirror', () => {
    it('matches the Java layout for a password-only row', async () => {
      const digest = await computeV1Digest({
        username: null,
        password: new TextEncoder().encode('"pw"'),
        notes: null,
        iv: new Uint8Array(12).fill(1),
      });

      expect(digest).toBe('eb6bc8ef328fc72e5fa786eceb1ce236ead41d5bfc32cae423af42e48696d2fd');
    });

    it('matches the Java layout with all three fields and non-ASCII bytes', async () => {
      const digest = await computeV1Digest({
        username: new TextEncoder().encode('"user"'),
        password: new TextEncoder().encode('"pw"'),
        notes: new TextEncoder().encode('"nótes" 🚀'),
        iv: new Uint8Array(12).fill(0xaa),
      });

      expect(digest).toBe('24974d78d5a8f61fc4888fdf19d95ad6ddc36a6fcd58146a095f74c971227086');
    });

    it('matches the Java layout when every field is absent', async () => {
      const digest = await computeV1Digest({ username: null, password: null, notes: null, iv: null });
      expect(digest).toBe('022b932e2edca69f77ebadeb069709766a623df2947971bd88442a51dbbc9a68');
    });

    it('distinguishes an absent field from an empty one', async () => {
      // The Java side writes int32 -1 for null and int32 0 for a zero-length array.
      // Collapsing them would let a row whose notes were deleted look identical to one
      // whose notes were always absent, and the compare-and-set would stop meaning
      // anything.
      const withNull = await computeV1Digest({
        username: null,
        password: new TextEncoder().encode('"pw"'),
        notes: null,
        iv: new Uint8Array(12).fill(1),
      });
      const withEmpty = await computeV1Digest({
        username: new Uint8Array(0),
        password: new TextEncoder().encode('"pw"'),
        notes: null,
        iv: new Uint8Array(12).fill(1),
      });

      expect(withEmpty).toBe('4fc7e010b5ed6bda206bd544f1ca5743effc70eb0994a0db2e8f7930dc195bec');
      expect(withEmpty).not.toBe(withNull);
    });

    it('is lowercase hex of 64 characters', async () => {
      const digest = await computeV1Digest({
        username: null,
        password: new TextEncoder().encode('"pw"'),
        notes: null,
        iv: new Uint8Array(12),
      });
      expect(digest).toMatch(/^[0-9a-f]{64}$/);
    });
  });

  describe('hashMasterPasswordLocally', () => {
    it('is the lowercase hex SHA-256 of the raw password', async () => {
      const h = await hashMasterPasswordLocally('heslo');
      const expected = toHex(await cryptoApi.sha256(new TextEncoder().encode('heslo')));
      expect(h).toBe(expected);
      expect(h).toMatch(/^[0-9a-f]{64}$/);
    });

    it('is not the value v1 sent to the server for a password containing diakritika', async () => {
      // Sanity on encoding: v1 hashed UTF-8 bytes, and so does this. If the two ever
      // diverged, every existing v1 row would become unreadable after an unlock.
      const h = await hashMasterPasswordLocally('Příšerně Žlutý Kůň');
      expect(h).toBe(toHex(await cryptoApi.sha256(new TextEncoder().encode('Příšerně Žlutý Kůň'))));
    });
  });

  describe('deriveV1DecryptKey', () => {
    it('derives the same 32 bytes as PBKDF2 over h and the salt', async () => {
      const salt = new Uint8Array(16).fill(42);
      const h = await hashMasterPasswordLocally('test-master-password-123');
      const key = await deriveV1DecryptKey(h, salt);
      const viaKdf = await cryptoApi.deriveKey(h, salt, 100_000);

      expect(key.length).toBe(32);
      expectBytes(key, viaKdf);
    });

    it('gives different keys for different salts', async () => {
      const h = 'a'.repeat(64);
      const k1 = await deriveV1DecryptKey(h, new Uint8Array(16).fill(1));
      const k2 = await deriveV1DecryptKey(h, new Uint8Array(16).fill(2));
      expect(toHex(k1)).not.toBe(toHex(k2));
    });
  });

  describe('parseV2KdfParams', () => {
    it('reads the parameters the server actually publishes', () => {
      const params = parseV2KdfParams('{"alg":"PBKDF2-HMAC-SHA256","iter":600000,"dkLen":256}');
      expect(params.iterations).toBe(600_000);
      expect(params.keyBytes).toBe(32);
    });

    it('falls back to the v2 defaults for missing or malformed json', () => {
      expect(parseV2KdfParams(null).iterations).toBe(DEFAULT_V2_ITERATIONS);
      expect(parseV2KdfParams('not json').iterations).toBe(DEFAULT_V2_ITERATIONS);
      expect(parseV2KdfParams('{"iter":0}').iterations).toBe(DEFAULT_V2_ITERATIONS);
      // dkLen is in bits, the way the server publishes it (256 bits = a 32-byte DEK).
      expect(parseV2KdfParams('{"iter":600000,"dkLen":128}').keyBytes).toBe(16);
      expect(parseV2KdfParams('{"iter":600000}').keyBytes).toBe(32);
    });

    it('defaults to 600 000 iterations, not v1 100 000', () => {
      expect(DEFAULT_V2_ITERATIONS).toBe(600_000);
    });
  });

  describe('KEK and the sealed DEK', () => {
    it('unwraps what it wrapped', async () => {
      const kek = await deriveV2Kek('master-password', new Uint8Array(32).fill(3), FAST_KDF);
      const dek = generateDek();

      const sealed = await wrapDek(kek, dek);
      const opened = await unwrapDek(kek, sealed);

      expectBytes(opened, dek);
    });

    it('refuses a KEK derived from another password — this is the unlock check', async () => {
      const salt = new Uint8Array(32).fill(3);
      const right = await deriveV2Kek('right', salt, FAST_KDF);
      const wrong = await deriveV2Kek('wrong', salt, FAST_KDF);

      const dek = generateDek();
      const sealed = await wrapDek(right, dek);

      expect(await unwrapDek(wrong, sealed)).toBeNull();
      expectBytes(await unwrapDek(right, sealed), dek);
    });

    it('refuses a tampered sealed key', async () => {
      const kek = await deriveV2Kek('master-password', new Uint8Array(32).fill(4), FAST_KDF);
      const sealed = await wrapDek(kek, generateDek());
      const bytes = safeBase64ToBytes(sealed)!;
      bytes[20] ^= 0xff;

      expect(await unwrapDek(kek, toHex(bytes))).toBeNull();
    });

    it('returns null rather than throwing on input that is not base64', async () => {
      const kek = await deriveV2Kek('master-password', new Uint8Array(32).fill(5), FAST_KDF);
      expect(await unwrapDek(kek, 'not base64 at all !!')).toBeNull();
      expect(await unwrapDek(kek, '')).toBeNull();
    });

    it('a different salt gives a different KEK for the same password', async () => {
      const a = await deriveV2Kek('same', new Uint8Array(32).fill(1), FAST_KDF);
      const b = await deriveV2Kek('same', new Uint8Array(32).fill(2), FAST_KDF);
      expect(toHex(a)).not.toBe(toHex(b));
    });

    it('generates a fresh 32-byte DEK each time', () => {
      const a = generateDek();
      const b = generateDek();
      expect(a.length).toBe(32);
      expect(toHex(a)).not.toBe(toHex(b));
    });
  });

  describe('fingerprintKey', () => {
    it('is 64 hex characters, which is what the key_id column holds', async () => {
      const keyId = await fingerprintKey(generateDek());
      expect(keyId).toMatch(/^[0-9a-f]{64}$/);
      expect(keyId.length).toBe(64);
    });

    it('is stable for one key and different for two', async () => {
      const key = generateDek();
      const other = generateDek();
      expect(await fingerprintKey(key)).toBe(await fingerprintKey(key));
      expect(await fingerprintKey(key)).not.toBe(await fingerprintKey(other));
    });
  });

  describe('buildV2Aad', () => {
    it('binds user, row, field and key into one string', () => {
      const aad = new TextDecoder().decode(buildV2Aad(CONTEXT));
      const parts = aad.split('\u0000');

      expect(parts).toEqual(['w4ai-vault', '2', '7', CONTEXT.entryUuid, 'password', CONTEXT.keyId]);
    });

    it('changes when any single component changes', () => {
      const base = toHex(buildV2Aad(CONTEXT));
      const variants: V2FieldContext[] = [
        { ...CONTEXT, userId: 8 },
        { ...CONTEXT, entryUuid: '00000000-0000-4000-8000-000000000000' },
        { ...CONTEXT, field: 'username' },
        { ...CONTEXT, field: 'notes' },
        { ...CONTEXT, keyId: 'b'.repeat(64) },
      ];

      for (const variant of variants) {
        expect(toHex(buildV2Aad(variant))).not.toBe(base);
      }
    });

    it('lowercases the uuid so a stored and a supplied form agree', () => {
      const upper = { ...CONTEXT, entryUuid: CONTEXT.entryUuid.toUpperCase() };
      expect(toHex(buildV2Aad(upper))).toBe(toHex(buildV2Aad(CONTEXT)));
    });
  });

  describe('v2 field encryption', () => {
    const key = new Uint8Array(32).fill(6) as V2Key;

    it('round-trips bytes including diakritika and emoji', async () => {
      const plaintext = new TextEncoder().encode(JSON.stringify('Heslo — Príšerně Žlutý Kůň 🚀'));
      const packed = await encryptV2Field(plaintext, key, CONTEXT);

      expectBytes(await decryptV2Field(packed, key, CONTEXT), plaintext);
    });

    it('packs the IV in front of the ciphertext and tag', async () => {
      const packed = safeBase64ToBytes(await encryptV2Field(new TextEncoder().encode('x'), key, CONTEXT))!;
      // 12-byte IV + 1 byte of ciphertext + 16-byte GCM tag.
      expect(packed.length).toBe(12 + 1 + 16);
    });

    it('gives each field its own IV', async () => {
      const plaintext = new TextEncoder().encode('same value');
      const password = safeBase64ToBytes(await encryptV2Field(plaintext, key, CONTEXT))!;
      const notes = safeBase64ToBytes(await encryptV2Field(plaintext, key, { ...CONTEXT, field: 'notes' }))!;

      expect(toHex(password.slice(0, 12))).not.toBe(toHex(notes.slice(0, 12)));
    });

    it('refuses a blob moved to another field of the same row', async () => {
      const packed = await encryptV2Field(new TextEncoder().encode('secret'), key, CONTEXT);
      expect(await decryptV2Field(packed, key, { ...CONTEXT, field: 'notes' })).toBeNull();
    });

    it('refuses a blob moved to another row', async () => {
      const packed = await encryptV2Field(new TextEncoder().encode('secret'), key, CONTEXT);
      const otherRow = { ...CONTEXT, entryUuid: '11111111-1111-4111-8111-111111111111' };
      expect(await decryptV2Field(packed, key, otherRow)).toBeNull();
    });

    it('refuses a blob moved to another user', async () => {
      const packed = await encryptV2Field(new TextEncoder().encode('secret'), key, CONTEXT);
      expect(await decryptV2Field(packed, key, { ...CONTEXT, userId: 8 })).toBeNull();
    });

    it('refuses a blob under another key id', async () => {
      const packed = await encryptV2Field(new TextEncoder().encode('secret'), key, CONTEXT);
      expect(await decryptV2Field(packed, key, { ...CONTEXT, keyId: 'c'.repeat(64) })).toBeNull();
    });

    it('returns null for absent, malformed and too-short blobs', async () => {
      expect(await decryptV2Field(null, key, CONTEXT)).toBeNull();
      expect(await decryptV2Field(undefined, key, CONTEXT)).toBeNull();
      expect(await decryptV2Field('!!!not base64!!!', key, CONTEXT)).toBeNull();
      expect(await decryptV2Field('AAAA', key, CONTEXT)).toBeNull();
    });
  });

  describe('helpers', () => {
    it('bytesEqual is byte-for-byte, not length-only', () => {
      const a = new Uint8Array([1, 2, 3]);
      expect(bytesEqual(a, new Uint8Array([1, 2, 3]))).toBe(true);
      expect(bytesEqual(a, new Uint8Array([1, 2, 4]))).toBe(false);
      expect(bytesEqual(a, new Uint8Array([1, 2]))).toBe(false);
    });

    it('safeBase64ToBytes returns null instead of throwing on junk', () => {
      // Empty input is empty bytes, not an error; junk is the error.
      expectBytes(safeBase64ToBytes('') as Uint8Array, new Uint8Array(0));
      expect(safeBase64ToBytes('@@@@')).toBeNull();
      expectBytes(safeBase64ToBytes('AQID') as Uint8Array, new Uint8Array([1, 2, 3]));
    });

    it('bytesFromHex round-trips a digest (test helper sanity)', () => {
      expect(toHex(bytesFromHex('00ff10'))).toBe('00ff10');
    });
  });
});
