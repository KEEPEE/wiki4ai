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

  it('fallback AES-256-GCM round-trips bytes on its own', async () => {
    const key = new Uint8Array(32).fill(9);
    const plaintext = new TextEncoder().encode('hello secret');

    const { ciphertextWithTag, iv } = await fallback.encryptBytes(plaintext, key);
    const decrypted = await fallback.decryptBytes(ciphertextWithTag, iv, key);

    expectBytes(decrypted, plaintext);
  });

  it('fallback and WebCrypto paths interoperate: encrypt with one, decrypt with the other', async () => {
    const key = new Uint8Array(32).fill(5);
    const plaintext = new TextEncoder().encode('cross-path secret');

    const encFallback = await fallback.encryptBytes(plaintext, key);
    expectBytes(await secure.decryptBytes(encFallback.ciphertextWithTag, encFallback.iv, key), plaintext);

    const encSecure = await secure.encryptBytes(plaintext, key);
    expectBytes(await fallback.decryptBytes(encSecure.ciphertextWithTag, encSecure.iv, key), plaintext);
  });

  it('both paths preserve non-UTF8 bytes exactly (the reason the API is bytes, not strings)', async () => {
    // 0xff is not a valid UTF-8 start byte. A string-based API has to invent a
    // replacement character here, and a migration comparing "what went in" with "what
    // came back" would then be comparing two different things.
    const key = new Uint8Array(32).fill(7);
    const plaintext = new Uint8Array([0xff, 0xfe, 0x00, 0x41, 0xc3, 0xa9]);

    for (const api of [fallback, secure]) {
      const { ciphertextWithTag, iv } = await api.encryptBytes(plaintext, key);
      expectBytes(await api.decryptBytes(ciphertextWithTag, iv, key), plaintext);
    }
  });

  it('a wrong AAD refuses to open the blob on both paths', async () => {
    // v2 seals each field against the row it belongs to. If the AAD were ignored, a
    // blob could be moved between rows or users and still decrypt, which is the
    // confusion GCM's "associated data" exists to prevent.
    const key = new Uint8Array(32).fill(11);
    const plaintext = new TextEncoder().encode('field secret');
    const aad = new TextEncoder().encode('w4ai-vault|2|7|uuid|password|key');

    for (const api of [fallback, secure]) {
      const { ciphertextWithTag, iv } = await api.encryptBytes(plaintext, key, { aad });

      expectBytes(await api.decryptBytes(ciphertextWithTag, iv, key, aad), plaintext);
      expect(await api.decryptBytes(ciphertextWithTag, iv, key)).toBeNull();
      expect(await api.decryptBytes(ciphertextWithTag, iv, key, new TextEncoder().encode('other'))).toBeNull();
    }
  });

  it('the AAD is authenticated but not secret: it does not appear in the ciphertext', async () => {
    const key = new Uint8Array(32).fill(12);
    const plaintext = new TextEncoder().encode('secret');
    const aad = new TextEncoder().encode('entry-uuid-12345');

    const { ciphertextWithTag } = await secure.encryptBytes(plaintext, key, { aad });
    const asText = new TextDecoder('utf-8', { fatal: false }).decode(ciphertextWithTag);
    expect(asText).not.toContain('entry-uuid-12345');
  });

  it('a supplied IV is used verbatim, which is how v1 shared one IV across a row', async () => {
    const key = new Uint8Array(32).fill(13);
    const iv = new Uint8Array(12).fill(3);
    const plaintext = new TextEncoder().encode('same data');

    const a = await secure.encryptBytes(plaintext, key, { iv });
    const b = await secure.encryptBytes(plaintext, key, { iv });

    expectBytes(a.iv, iv);
    expect(a.ciphertextWithTag).toEqual(b.ciphertextWithTag);
  });

  it('without a supplied IV every call gets a fresh one', async () => {
    const key = new Uint8Array(32).fill(14);
    const plaintext = new TextEncoder().encode('same data');

    const a = await secure.encryptBytes(plaintext, key);
    const b = await secure.encryptBytes(plaintext, key);

    expect(a.iv).not.toEqual(b.iv);
    expect(a.ciphertextWithTag).not.toEqual(b.ciphertextWithTag);
    expect(a.iv.length).toBe(12);
  });

  it('decryptBytes returns null for a wrong key rather than garbage', async () => {
    const plaintext = new TextEncoder().encode('secret data');
    const { ciphertextWithTag, iv } = await secure.encryptBytes(plaintext, new Uint8Array(32).fill(1));

    expect(await secure.decryptBytes(ciphertextWithTag, iv, new Uint8Array(32).fill(2))).toBeNull();
  });
});
