/**
 * Regression tests for cryptoApi's insecure-context (non-WebCrypto) fallback
 * path - the one actually used by every current wiki4ai deployment, since
 * they're all served over plain HTTP and crypto.subtle is unavailable
 * outside secure contexts.
 *
 * A previous hand-rolled SHA-256 fallback silently returned 32 zero bytes
 * for every input (wrote its result into the wrong buffer), which broke
 * vault master-password verification and key derivation without ever
 * failing a test - the existing encryptionService tests all ran under
 * Vitest/Node, where `crypto.subtle` IS available, so they only ever
 * exercised the WebCrypto branch and never touched the fallback.
 *
 * These tests force the fallback branch explicitly and check it against
 * known-correct SHA-256 test vectors (generated once via Node's crypto
 * module - the same algorithm the Python MCP server's hashlib/cryptography
 * calls implement) and cross-check it against the WebCrypto branch for
 * PBKDF2/AES-GCM, so a regression here fails loudly instead of silently.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

function toHex(bytes: Uint8Array): string {
  return Array.from(bytes).map((b) => b.toString(16).padStart(2, '0')).join('');
}

describe('cryptoApi insecure-context fallback', () => {
  let fallback: typeof import('../services/cryptoApi').cryptoApi;
  let secure: typeof import('../services/cryptoApi').cryptoApi;

  beforeEach(async () => {
    const realSubtle = globalThis.crypto.subtle;

    vi.resetModules();
    Object.defineProperty(globalThis.crypto, 'subtle', { value: undefined, configurable: true });
    ({ cryptoApi: fallback } = await import('../services/cryptoApi'));

    Object.defineProperty(globalThis.crypto, 'subtle', { value: realSubtle, configurable: true });
    vi.resetModules();
    ({ cryptoApi: secure } = await import('../services/cryptoApi'));
  });

  it('confirms the two paths under test are actually different (fallback vs WebCrypto)', () => {
    expect(fallback.isSecureContext).toBe(false);
    expect(secure.isSecureContext).toBe(true);
  });

  it('fallback sha256 matches known-correct SHA-256 test vectors', async () => {
    const enc = new TextEncoder();
    // Generated via `createHash('sha256').update(input, 'utf8').digest('hex')` in Node.
    const vectors: [string, string][] = [
      ['', 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'],
      ['abc', 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'],
      ['some-test-password-1', 'd8949c1e386971040dd4e50c3e2fc5cb0efff9f8b2eb57d9f89431cc6c441c65'],
      ['a'.repeat(80), '0f45e858fbc4176cdf4e411f88281edefc390ae5afe7df0f44cd9297f0a64580'],
      ['Príklad — Unicode 🚀', 'c14a887f96d7dc866544c6c2b80ce4501a4e049c3a6512ce6a8704cd60b5f4f8'],
    ];

    for (const [input, expected] of vectors) {
      const got = await fallback.sha256(enc.encode(input));
      expect(toHex(got)).toBe(expected);
    }
  });

  it('fallback sha256 never returns an all-zero digest (the exact regression this suite exists for)', async () => {
    const got = await fallback.sha256(new TextEncoder().encode('anything'));
    expect(toHex(got)).not.toBe('0'.repeat(64));
  });

  it('fallback and WebCrypto paths produce identical derived keys (PBKDF2) for the same password+salt', async () => {
    const salt = new Uint8Array(16).fill(3);
    const k1 = await fallback.deriveKey('same-password', salt);
    const k2 = await secure.deriveKey('same-password', salt);
    expect(toHex(k1)).toBe(toHex(k2));
    expect(k1.length).toBe(32); // AES-256 key size
  });

  it('fallback and WebCrypto paths produce identical derived keys at the real production iteration count (100k)', async () => {
    const salt = new Uint8Array(16).fill(1);
    const k1 = await fallback.deriveKey('unrelated-test-hash-value', salt);
    const k2 = await secure.deriveKey('unrelated-test-hash-value', salt);
    expect(toHex(k1)).toBe(toHex(k2));
  });

  it('fallback AES-256-GCM round-trips correctly on its own', async () => {
    const key = new Uint8Array(32).fill(9);
    const { ciphertextWithTag, iv } = await fallback.encrypt('hello secret', key);
    const decrypted = await fallback.decrypt(ciphertextWithTag, iv, key);
    expect(decrypted).toBe('hello secret');
  });

  it('fallback and WebCrypto paths interoperate: encrypt with one, decrypt with the other', async () => {
    const key = new Uint8Array(32).fill(5);

    const encFallback = await fallback.encrypt('cross-path secret A', key);
    const decBySecure = await secure.decrypt(encFallback.ciphertextWithTag, encFallback.iv, key);
    expect(decBySecure).toBe('cross-path secret A');

    const encSecure = await secure.encrypt('cross-path secret B', key);
    const decByFallback = await fallback.decrypt(encSecure.ciphertextWithTag, encSecure.iv, key);
    expect(decByFallback).toBe('cross-path secret B');
  });
});
