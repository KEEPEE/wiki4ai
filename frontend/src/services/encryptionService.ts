/**
 * Client-side encryption utility using Web Crypto API.
 *
 * Provides PBKDF2 key derivation and AES-256-GCM encrypt/decrypt operations
 * for securing vault data in the browser.
 */

import type { EncryptedVaultEntry } from '../types/vault';

const PBKDF2_ITERATIONS = 100_000;
const HASH_ALGORITHM = 'SHA-256';
const AES_KEY_LENGTH = 256;
const IV_LENGTH = 12;

/**
 * Derive an encryption key from a master password using PBKDF2.
 *
 * @param masterPassword - The user's master password
 * @param salt - Random salt bytes (should be at least 16 bytes)
 * @returns A CryptoKey suitable for AES-GCM operations
 */
export async function deriveKey(
  masterPassword: string,
  salt: Uint8Array,
): Promise<CryptoKey> {
  const encoder = new TextEncoder();
  const passwordBuffer = encoder.encode(masterPassword);

  const keyMaterial = await crypto.subtle.importKey(
    'raw',
    passwordBuffer,
    'PBKDF2',
    false,
    ['deriveKey'],
  );

  return crypto.subtle.deriveKey(
    {
      name: 'PBKDF2',
      salt: new Uint8Array(salt),
      iterations: PBKDF2_ITERATIONS,
      hash: HASH_ALGORITHM,
    },
    keyMaterial,
    { name: 'AES-GCM', length: AES_KEY_LENGTH },
    false,
    ['encrypt', 'decrypt'],
  );
}

/**
 * Encrypt plaintext data using AES-256-GCM.
 * Generates a new random IV for each encryption operation.
 *
 * @param data - The plaintext string to encrypt
 * @param key - The CryptoKey derived from deriveKey()
 * @returns EncryptedVaultEntry containing ciphertext and iv
 */
export async function encrypt(
  data: string,
  key: CryptoKey,
): Promise<EncryptedVaultEntry> {
  const encoder = new TextEncoder();
  const plaintext = encoder.encode(data);
  const iv = crypto.getRandomValues(new Uint8Array(IV_LENGTH));

  const ciphertextBuffer = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv },
    key,
    plaintext,
  );

  return {
    ciphertext: new Uint8Array(ciphertextBuffer),
    iv,
  };
}

/**
 * Decrypt AES-256-GCM encrypted data.
 *
 * @param ciphertext - The encrypted data bytes
 * @param iv - The initialization vector used during encryption
 * @param key - The CryptoKey derived from deriveKey() with the same password and salt
 * @returns The decrypted plaintext string
 */
export async function decrypt(
  ciphertext: Uint8Array,
  iv: Uint8Array,
  key: CryptoKey,
): Promise<string> {
  const plaintextBuffer = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv } as AesGcmParams,
    key,
    ciphertext.slice() as unknown as BufferSource,
  );

  return new TextDecoder().decode(plaintextBuffer);
}
