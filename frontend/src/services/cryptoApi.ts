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
 */

import { sha256 as nobleSha256 } from '@noble/hashes/sha2.js';
import { pbkdf2 as noblePbkdf2 } from '@noble/hashes/pbkdf2.js';
import { gcm } from '@noble/ciphers/aes.js';

// Check if Web Crypto API is available
const hasWebCrypto = typeof crypto !== 'undefined' && typeof crypto.subtle !== 'undefined';

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

  async encrypt(data: string, keyBytes: Uint8Array, providedIv?: Uint8Array): Promise<{ ciphertextWithTag: Uint8Array; iv: Uint8Array }> {
    const encoder = new TextEncoder();
    const plaintext = encoder.encode(data);
    const iv = providedIv ?? crypto.getRandomValues(new Uint8Array(12));

    if (hasWebCrypto) {
      const key = await crypto.subtle.importKey(
        'raw',
        keyBytes as BufferSource,
        { name: 'AES-GCM' },
        false,
        ['encrypt']
      );

      const ciphertextBuffer = await crypto.subtle.encrypt(
        { name: 'AES-GCM', iv: iv as BufferSource },
        key,
        plaintext
      );

      return { ciphertextWithTag: new Uint8Array(ciphertextBuffer), iv };
    }

    const ciphertextWithTag = gcm(keyBytes, iv).encrypt(plaintext);
    return { ciphertextWithTag, iv };
  },

  async decrypt(ciphertextWithTag: Uint8Array, iv: Uint8Array, keyBytes: Uint8Array): Promise<string | null> {
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
          { name: 'AES-GCM', iv } as AesGcmParams,
          key,
          ciphertextWithTag.slice() as BufferSource
        );
        return new TextDecoder().decode(plaintextBuffer);
      } catch {
        return null;
      }
    }

    try {
      const plaintext = gcm(keyBytes, iv).decrypt(ciphertextWithTag);
      return new TextDecoder().decode(plaintext);
    } catch {
      return null;
    }
  },

  get isSecureContext(): boolean {
    return hasWebCrypto;
  }
};
