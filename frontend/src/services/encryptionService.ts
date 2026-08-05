/**
 * Client-side encryption utility using Web Crypto API with fallback for insecure contexts.
 *
 * Provides PBKDF2 key derivation and AES-256-GCM encrypt/decrypt operations
 * for securing vault data in the browser. Works on both HTTPS and HTTP.
 */

import { cryptoApi } from './cryptoApi';
import type { EncryptedVaultEntry } from '../types/vault';

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
 * Derive an encryption key from a master password using PBKDF2.
 * Returns raw key bytes (Uint8Array) for use with cryptoApi.
 *
 * @param masterPassword - The user's master password
 * @param salt - Random salt bytes (should be at least 16 bytes)
 * @returns Raw encryption key bytes suitable for AES-GCM operations
 */
export async function deriveKey(
  masterPassword: string,
  salt: Uint8Array,
): Promise<Uint8Array> {
  return cryptoApi.deriveKey(masterPassword, salt, PBKDF2_ITERATIONS);
}

/**
 * Encrypt plaintext data using AES-256-GCM.
 * Generates a new random IV for each encryption operation.
 *
 * @param data - The plaintext string to encrypt
 * @param keyBytes - The raw encryption key bytes from deriveKey()
 * @param iv - Optional explicit IV to use instead of generating a random one
 *   (the backend persists a single IV per vault entry, shared across its fields)
 * @returns EncryptedVaultEntry containing ciphertext (with tag) and iv
 */
export async function encrypt(
  data: string,
  keyBytes: Uint8Array,
  iv?: Uint8Array,
): Promise<EncryptedVaultEntry> {
  const result = await cryptoApi.encrypt(data, keyBytes, iv);

  return {
    ciphertext: result.ciphertextWithTag, // Tag is appended to ciphertext (Web Crypto API compatible)
    iv: result.iv,
  };
}

/**
 * Decrypt AES-256-GCM encrypted data.
 *
 * @param ciphertext - The encrypted data bytes (with authentication tag appended)
 * @param iv - The initialization vector used during encryption
 * @param keyBytes - The raw encryption key bytes derived with the same password and salt
 * @returns The decrypted plaintext string, or null if decryption fails
 */
export async function decrypt(
  ciphertext: Uint8Array,
  iv: Uint8Array,
  keyBytes: Uint8Array,
): Promise<string | null> {
  return cryptoApi.decrypt(ciphertext, iv, keyBytes);
}
