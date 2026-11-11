/**
 * Crypto utilities with fallback for insecure contexts (HTTP).
 *
 * Web Crypto API (`crypto.subtle`) is only available in secure contexts:
 * - HTTPS
 * - localhost
 *
 * When running on plain HTTP (e.g., http://<lan-ip>:3000 - typical for
 * self-hosted deployments), we fall back to @noble/hashes and @noble/ciphers - small,
 * audited, widely-used implementations - rather than hand-rolled crypto.
 *
 * A hand-rolled SHA-256 fallback used to live here and had a bug (writing
 * the hash state into the wrong buffer) that made it return 32 zero bytes
 * for every input, which silently broke master-password verification and
 * key derivation on every non-HTTPS deployment. Do not reintroduce a
 * hand-rolled implementation - use audited libraries for anything
 * security-sensitive.
 *
 * ## Everything here works on bytes, not strings (WIKI4AI-197 D2)
 *
 * The v1 helpers used to take a string in and hand a string back, decoding the
 * plaintext with `new TextDecoder()` and returning `null` on failure. Both details
 * cost the migration its correctness:
 *
 * - A plaintext is a byte sequence, and `TextDecoder` is lossy in one direction: it
 *   replaces ill-formed sequences with U+FFFD, so bytes in and bytes out are not the
 *   same sequence. Re-encrypting the output of a decode therefore changes the data.
 *   v1 → v2 migrates the *bytes*, and only bytes can be compared byte-for-byte after
 *   the round trip to prove nothing changed.
 * - Returning `null` instead of throwing is survivable for a caller that checks, and
 *   fatal for one that does not: the previous caller substituted the literal
 *   `'[decryption failed]'`, showed it in the edit form, and saved it back as the
 *   user's password. The `null` stays (throwing across this boundary would be a
 *   bigger change than it is worth) but the type now says `Uint8Array | null`, so a
 *   caller has to decide what a failure means before it can use the value.
 */

import { sha256 as nobleSha256 } from '@noble/hashes/sha2.js';
import { pbkdf2 as noblePbkdf2 } from '@noble/hashes/pbkdf2.js';
import { gcm } from '@noble/ciphers/aes.js';

// Check if Web Crypto API is available
const hasWebCrypto = typeof crypto !== 'undefined' && typeof crypto.subtle !== 'undefined';

/** AES-GCM IV length in bytes, for both WebCrypto and the noble fallback. */
const IV_BYTES = 12;

export interface EncryptBytesResult {
  /** Ciphertext with the 16-byte GCM tag appended, as WebCrypto emits it. */
  ciphertextWithTag: Uint8Array;
  /** The IV that was used - generated when the caller did not supply one. */
  iv: Uint8Array;
}

export interface EncryptBytesOptions {
  /**
   * Reuse a specific IV. v1 needs this because the backend stores one IV per row and
   * shares it across the row's fields; v2 never passes it, precisely so that each
   * field gets its own.
   */
  iv?: Uint8Array;
  /**
   * Additional authenticated data: bound into the tag but not encrypted. v2 puts the
   * row and field identity here so a ciphertext cannot be moved between fields or
   * rows without the tag failing.
   */
  aad?: Uint8Array;
}

/**
 * Crypto interface that works in both secure and insecure contexts.
 */
export const cryptoApi = {
  async sha256(data: Uint8Array): Promise<Uint8Array> {
    if (hasWebCrypto) {
      const hashBuffer = await crypto.subtle.digest('SHA-256', data as BufferSource);
      return new Uint8Array(hashBuffer);
    }
    return nobleSha256(data);
  },

  async deriveKey(
    password: string,
    salt: Uint8Array,
    iterations: number = 100_000
  ): Promise<Uint8Array> {
    const encoder = new TextEncoder();

    if (hasWebCrypto) {
      const keyMaterial = await crypto.subtle.importKey(
        'raw',
        encoder.encode(password),
        'PBKDF2',
        false,
        ['deriveKey']
      );

      const key = await crypto.subtle.deriveKey(
        { name: 'PBKDF2', salt: salt as BufferSource, iterations, hash: 'SHA-256' },
        keyMaterial,
        { name: 'AES-GCM', length: 256 },
        true, // extractable: true - needed to export raw key bytes for use with encrypt/decrypt methods
        ['encrypt', 'decrypt']
      );

      const rawKey = await crypto.subtle.exportKey('raw', key);
      return new Uint8Array(rawKey);
    }

    return noblePbkdf2(nobleSha256, encoder.encode(password), salt, { c: iterations, dkLen: 32 });
  },

  /**
   * AES-256-GCM over raw bytes.
   *
   * The plaintext is never encoded or decoded here. Whoever holds the bytes decides
   * what they mean, which is what lets a migration prove it moved them unchanged.
   */
  async encryptBytes(
    data: Uint8Array,
    keyBytes: Uint8Array,
    options: EncryptBytesOptions = {}
  ): Promise<EncryptBytesResult> {
    const iv = options.iv ?? crypto.getRandomValues(new Uint8Array(IV_BYTES));
    const aad = options.aad;

    if (hasWebCrypto) {
      const key = await crypto.subtle.importKey(
        'raw',
        keyBytes as BufferSource,
        { name: 'AES-GCM' },
        false,
        ['encrypt']
      );

      const ciphertextBuffer = await crypto.subtle.encrypt(
        { name: 'AES-GCM', iv: iv as BufferSource, additionalData: aad as BufferSource | undefined },
        key,
        data as BufferSource
      );

      return { ciphertextWithTag: new Uint8Array(ciphertextBuffer), iv };
    }

    const ciphertextWithTag = gcm(keyBytes, iv, aad).encrypt(data);
    return { ciphertextWithTag, iv };
  },

  /**
   * AES-256-GCM decrypt, returning the plaintext bytes or `null` if the tag does not
   * verify. `null` means "these bytes are not this ciphertext under this key and
   * context" - it is a failure the caller must handle, never a value to display.
   */
  async decryptBytes(
    ciphertextWithTag: Uint8Array,
    iv: Uint8Array,
    keyBytes: Uint8Array,
    aad?: Uint8Array
  ): Promise<Uint8Array | null> {
    if (hasWebCrypto) {
      const key = await crypto.subtle.importKey(
        'raw',
        keyBytes as BufferSource,
        { name: 'AES-GCM' },
        false,
        ['decrypt']
      );

      try {
        const plaintextBuffer = await crypto.subtle.decrypt(
          { name: 'AES-GCM', iv, additionalData: aad } as AesGcmParams,
          key,
          ciphertextWithTag.slice() as BufferSource
        );
        return new Uint8Array(plaintextBuffer);
      } catch {
        return null;
      }
    }

    try {
      return gcm(keyBytes, iv, aad).decrypt(ciphertextWithTag);
    } catch {
      return null;
    }
  },

  get isSecureContext(): boolean {
    return hasWebCrypto;
  }
};
