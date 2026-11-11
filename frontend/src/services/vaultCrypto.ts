/**
 * The vault v2 key hierarchy and wire formats (WIKI4AI-197, phase D2).
 *
 * ## Why this module exists separately from cryptoApi and encryptionService
 *
 * `cryptoApi` is a transport: AES-GCM, PBKDF2, SHA-256, with a fallback for plain-HTTP
 * deployments. `encryptionService` is v1's shape — a string in, a string out, one IV
 * per row — and in D2 it keeps only the parts of v1 that are still true (reading it).
 * This module is v2's shape: a key hierarchy, an AAD, and a per-field IV.
 *
 * ## The key hierarchy
 *
 *     master password ──PBKDF2(server salt, 600k)──▶ KEK ──AES-GCM──▶ wraps DEK
 *                                                        DEK ──▶ every entry field
 *
 * The DEK is what encrypts the data, and it is random. The password never encrypts
 * data directly, so changing the master password means re-wrapping 32 bytes rather
 * than re-encrypting the vault.
 *
 * The KEK is derived from the **raw password**. That single choice is the difference
 * between v2 and v1: v1 derived its key from `h = SHA-256(password)` and sent `h` to
 * the server, so the server — which also stores the salt — could derive the same key
 * and read the whole vault. v2 never sends `h`, and the server never sees anything
 * from which the KEK can be recomputed.
 *
 * ## Two key types that cannot be confused
 *
 * `V1DecryptKey` decrypts and nothing else; `V2Key` reads and writes. The distinction
 * is only in the type system, but that is where the mistake is made: a v1 key used to
 * encrypt produces a v1 row in a vault whose owner now reads v2, which is exactly the
 * corruption the D1 database trigger exists to catch.
 */

import { cryptoApi } from './cryptoApi';
import { base64ToBytes, bytesToBase64 } from './encryptionService';

/** v1's work factor. Kept because existing rows were derived with it, not as a target. */
const V1_KDF_ITERATIONS = 100_000;

/** Fallback if the server ever omits `kdf`; the server's own value wins. */
export const DEFAULT_V2_ITERATIONS = 600_000;

const DEK_BYTES = 32;
const IV_BYTES = 12;

/** Domain separator for the v2 AAD, so this layout cannot collide with any other. */
const AAD_DOMAIN = 'w4ai-vault';
const AAD_VERSION = '2';

export type VaultField = 'username' | 'password' | 'notes';

declare const brandSymbol: unique symbol;

/** A type that carries a nominal tag on top of a structural one. */
type Branded<T, Tag extends string> = T & { readonly [brandSymbol]: Tag };

/**
 * The v1 key. Derivable from `h` plus the v1 salt, and able only to open v1 rows —
 * there is no v2 write path that accepts it, and no v1 write path left to accept it.
 */
export type V1DecryptKey = Branded<Uint8Array, 'V1DecryptKey'>;

/** The v2 data-encryption key. The only key a D2 client writes with. */
export type V2Key = Branded<Uint8Array, 'V2Key'>;

function asV1Key(bytes: Uint8Array): V1DecryptKey {
  return bytes as V1DecryptKey;
}

/** The one place the v2 brand is asserted, so callers never cast raw bytes by hand. */
export function asV2Key(bytes: Uint8Array): V2Key {
  return bytes as V2Key;
}

const encoder = new TextEncoder();

/**
 * `h = hex(SHA-256(UTF-8(password)))`, the value v1 was built on.
 *
 * Local-only in D2: it is what unlocks existing v1 rows, and it must not go to the
 * server, because a server holding `h` and the salt can derive the v1 key.
 */
export async function hashMasterPasswordLocally(rawPassword: string): Promise<string> {
  const digest = await cryptoApi.sha256(encoder.encode(rawPassword));
  return Array.from(digest)
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

/** The v1 key K1 = PBKDF2(h, vault_salt, 100k, 32 B). Decrypt-only. */
export async function deriveV1DecryptKey(h: string, salt: Uint8Array): Promise<V1DecryptKey> {
  const bytes = await cryptoApi.deriveKey(h, salt, V1_KDF_ITERATIONS);
  return asV1Key(bytes);
}

export interface V2KdfParams {
  iterations: number;
  keyBytes: number;
}

/**
 * Read the KDF parameters the server stores next to the salt.
 *
 * The client does not decide the work factor; the server does, so raising it later
 * does not strand a client that shipped with a hardcoded number. An unparseable value
 * falls back to the known default rather than failing an unlock.
 */
export function parseV2KdfParams(kdfJson: string | null | undefined): V2KdfParams {
  const fallback: V2KdfParams = { iterations: DEFAULT_V2_ITERATIONS, keyBytes: DEK_BYTES };
  if (!kdfJson) return fallback;
  try {
    const parsed = JSON.parse(kdfJson) as { iter?: unknown; dkLen?: unknown };
    const iterations = typeof parsed.iter === 'number' && parsed.iter > 0 ? parsed.iter : fallback.iterations;
    const bits = typeof parsed.dkLen === 'number' && parsed.dkLen > 0 ? parsed.dkLen : DEK_BYTES * 8;
    return { iterations, keyBytes: Math.floor(bits / 8) };
  } catch {
    return fallback;
  }
}

/** KEK = PBKDF2(raw password, server salt, iterations). Never sent anywhere. */
export async function deriveV2Kek(
  rawPassword: string,
  salt: Uint8Array,
  iterations: number = DEFAULT_V2_ITERATIONS
): Promise<Uint8Array> {
  return cryptoApi.deriveKey(rawPassword, salt, iterations);
}

/** A fresh random DEK. */
export function generateDek(): Uint8Array {
  return crypto.getRandomValues(new Uint8Array(DEK_BYTES));
}

/**
 * Seal the DEK under the KEK: `iv(12) || ciphertext || tag`, Base64.
 *
 * No AAD here, unlike the entry fields: this blob is the only thing the KEK ever
 * encrypts, it lives in its own column, and it never shares a key with anything.
 */
export async function wrapDek(kek: Uint8Array, dek: Uint8Array): Promise<string> {
  const { ciphertextWithTag, iv } = await cryptoApi.encryptBytes(dek, kek);
  return bytesToBase64(concat(iv, ciphertextWithTag));
}

/**
 * Open the sealed DEK.
 *
 * `null` is the password check. There is no separate verifier to ask: if the KEK
 * derived from this password opens the wrapped key, the GCM tag says the password is
 * right, and if it does not, nothing else in the vault will decrypt either.
 */
export async function unwrapDek(kek: Uint8Array, wrappedBase64: string): Promise<Uint8Array | null> {
  const packed = safeBase64ToBytes(wrappedBase64);
  if (!packed || packed.length <= IV_BYTES) return null;
  const iv = packed.slice(0, IV_BYTES);
  const ciphertextWithTag = packed.slice(IV_BYTES);
  const dek = await cryptoApi.decryptBytes(ciphertextWithTag, iv, kek);
  return dek && dek.length === DEK_BYTES ? dek : null;
}

/**
 * Fingerprint of a key: hex SHA-256, 64 characters — the limit the server's column
 * and validation allow, and no truncation to argue about.
 *
 * Not a secret. It lets the server refuse a row sealed under a key the vault does not
 * hold without being able to open anything.
 */
export async function fingerprintKey(key: Uint8Array): Promise<string> {
  const digest = await cryptoApi.sha256(key);
  return Array.from(digest)
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

/** Everything the v2 AAD binds a ciphertext to. */
export interface V2FieldContext {
  userId: number;
  entryUuid: string;
  field: VaultField;
  keyId: string;
}

/**
 * AAD = `"w4ai-vault" | 2 | user_id | entry_uuid | field | key_id`, NUL-separated.
 *
 * Each component is drawn from an alphabet that cannot contain NUL (hex, a decimal
 * integer, a lowercase uuid, a fixed field name), so the separators are unambiguous.
 *
 * What this buys: the same ciphertext cannot be replayed into another field of the
 * same row (`field`), into another row (`entry_uuid`), onto another user's row
 * (`user_id`), or under another key (`key_id`). Without it, moving a password blob
 * from one entry to another is invisible to GCM — the tag still verifies, and the
 * user silently gets someone else's password.
 */
export function buildV2Aad(context: V2FieldContext): Uint8Array {
  const parts = [
    AAD_DOMAIN,
    AAD_VERSION,
    String(context.userId),
    context.entryUuid.toLowerCase(),
    context.field,
    context.keyId,
  ];
  return encoder.encode(parts.join('\u0000'));
}

/** Encrypt one field under the v2 key, with its own IV and its own AAD. */
export async function encryptV2Field(
  plaintext: Uint8Array,
  key: V2Key,
  context: V2FieldContext
): Promise<string> {
  const { ciphertextWithTag, iv } = await cryptoApi.encryptBytes(plaintext, key, {
    aad: buildV2Aad(context),
  });
  return bytesToBase64(concat(iv, ciphertextWithTag));
}

/**
 * Open one v2 field. `null` covers absent, unparseable, and tag-mismatch alike: from
 * the reader's side all three mean "this field has no readable value right now".
 */
export async function decryptV2Field(
  packedBase64: string | null | undefined,
  key: V2Key,
  context: V2FieldContext
): Promise<Uint8Array | null> {
  if (!packedBase64) return null;
  const packed = safeBase64ToBytes(packedBase64);
  if (!packed || packed.length <= IV_BYTES) return null;
  return cryptoApi.decryptBytes(
    packed.slice(IV_BYTES),
    packed.slice(0, IV_BYTES),
    key,
    buildV2Aad(context)
  );
}

/** Byte-for-byte equality, for the migration's round-trip proof. */
export function bytesEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

export function concat(a: Uint8Array, b: Uint8Array): Uint8Array {
  const out = new Uint8Array(a.length + b.length);
  out.set(a, 0);
  out.set(b, a.length);
  return out;
}

/** Base64 that a hostile or merely old server might not have produced. */
export function safeBase64ToBytes(value: string): Uint8Array | null {
  try {
    return base64ToBytes(value);
  } catch {
    return null;
  }
}

// ==================== v1 digest (compare-and-set token) ====================

/**
 * The domain separator used by the server's `VaultV1Digest`. The layout below is
 * mirrored from `VaultV1Digest.java` and the two must not drift: a client that
 * digests differently gets every row it offers to migrate refused with 409.
 */
const V1_DIGEST_DOMAIN = 'w4ai-vault-v1';

function int32Bytes(value: number): Uint8Array {
  const buffer = new ArrayBuffer(4);
  new DataView(buffer).setInt32(0, value, false);
  return new Uint8Array(buffer);
}

/**
 * SHA-256 over a row's v1 bytes: `domain | 0x00 | (len|int32 | bytes)` for
 * username, password, notes and iv in that order, with a missing field contributing
 * `0xFFFFFFFF` and no bytes.
 *
 * This is the token the migrate endpoint compares against the row it locks, so it is
 * a compare-and-set on the v1 bytes themselves. Length-prefixed rather than
 * delimiter-separated because every input is arbitrary bytes: a separator can occur
 * inside a ciphertext by chance, a length cannot.
 */
export async function computeV1Digest(fields: {
  username: Uint8Array | null;
  password: Uint8Array | null;
  notes: Uint8Array | null;
  iv: Uint8Array | null;
}): Promise<string> {
  const chunks: Uint8Array[] = [encoder.encode(V1_DIGEST_DOMAIN), new Uint8Array([0])];

  for (const field of [fields.username, fields.password, fields.notes, fields.iv]) {
    if (field === null) {
      chunks.push(int32Bytes(-1));
    } else {
      chunks.push(int32Bytes(field.length), field);
    }
  }

  const digest = await cryptoApi.sha256(concatAll(chunks));
  return Array.from(digest)
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

function concatAll(chunks: Uint8Array[]): Uint8Array {
  const total = chunks.reduce((sum, chunk) => sum + chunk.length, 0);
  const out = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.length;
  }
  return out;
}
