/**
 * Client-side encryption utilities for the vault, with a fallback for insecure
 * contexts (plain HTTP), where `crypto.subtle` is unavailable.
 *
 * ## What this module is in WIKI4AI-197 D2
 *
 * The v1 half of the vault: base64 for the wire format, the v1 key derivation, and
 * reading a v1 field. There is deliberately **no `encrypt` here any more.** v1
 * encryption used to live here, and deleting it is the load-bearing change of the
 * migration: a client that cannot produce v1 bytes cannot leave a vault half-migrated
 * by accident, no matter which code path it takes. Reading v1 stays, because the
 * existing rows are still in that format and only v1's shape can open them.
 *
 * v2 — the key hierarchy, the AAD, the per-field IV — is in `vaultCrypto.ts`.
 */

import { cryptoApi } from './cryptoApi';
import type { EncryptedField } from '../types/vault';
import type { V1DecryptKey } from './vaultCrypto';

const PBKDF2_ITERATIONS = 100_000;

/** Encode raw bytes as a Base64 string (matches backend's Base64-encoded EncryptedField). */
export function bytesToBase64(bytes: Uint8Array): string {
  let binary = '';
  for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
  return btoa(binary);
}

/** Decode a Base64 string back into raw bytes. */
export function base64ToBytes(base64: string): Uint8Array {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

/**
 * Derive the v1 key K1 = PBKDF2-SHA256(h, vault_salt, 100 000, 32 B).
 *
 * The `masterPassword` argument is `h`, the SHA-256 of the master password — that is
 * what v1 was built with, so it is what opens existing rows. It is not what v2 uses:
 * v2 derives from the raw password (see `vaultCrypto.deriveV2Kek`), which is the whole
 * point of the migration.
 */
export async function deriveKey(
  masterPassword: string,
  salt: Uint8Array,
): Promise<Uint8Array> {
  return cryptoApi.deriveKey(masterPassword, salt, PBKDF2_ITERATIONS);
}

/**
 * Open one v1 field and hand back its plaintext **bytes**.
 *
 * Bytes rather than a string, because the migration has to compare what it read with
 * what it writes, and a `TextDecoder` round trip is not byte-preserving for input
 * that is not well-formed UTF-8. Whoever needs text can decode it; whoever needs
 * bytes still has them.
 *
 * `null` means the tag did not verify — wrong key, wrong IV, or a field written under
 * a different key. It never means "empty", and it must never be turned into a
 * placeholder value: the previous version of this code substituted the literal
 * `'[decryption failed]'`, the edit form prefilled it, and saving wrote it out as the
 * user's password.
 */
export async function decryptV1Bytes(
  field: EncryptedField | null | undefined,
  key: V1DecryptKey,
): Promise<Uint8Array | null> {
  if (!field?.ciphertext || !field.iv) return null;
  return cryptoApi.decryptBytes(
    base64ToBytes(field.ciphertext),
    base64ToBytes(field.iv),
    key,
  );
}
