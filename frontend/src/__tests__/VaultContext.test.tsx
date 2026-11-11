/**
 * Tests for VaultContext — the v2 unlock/setup state machine (WIKI4AI-197 D2).
 *
 * These tests drive the real crypto rather than mocking it: the point of the new
 * flow is that the password is verified by opening the sealed DEK, and a mocked
 * `unwrapDek` would assert nothing about that. `fetch` is mocked instead, which
 * also lets the suite inspect what actually left the browser — including the
 * requirement that neither the password nor SHA-256(password) ever does.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import type { ReactNode } from 'react';
import { VaultProvider, useVault } from '../contexts/VaultContext';
import { cryptoApi } from '../services/cryptoApi';
import { bytesToBase64 } from '../services/encryptionService';
import {
  deriveV1DecryptKey,
  deriveV2Kek,
  fingerprintKey,
  generateDek,
  hashMasterPasswordLocally,
  wrapDek,
} from '../services/vaultCrypto';
import type { BackendVaultEntry } from '../types/vault';

const VAULT_SALT_KEY = 'wiki4ai_vault_salt';
const TOKEN_KEY = 'wiki4ai_access_token';

// Fast on purpose: the work factor is the server's to choose, and these tests read
// it from the kdf blob rather than hardcoding 600 000.
const KDF_ITERATIONS = 1_000;
const KDF = JSON.stringify({ alg: 'PBKDF2-HMAC-SHA256', iter: KDF_ITERATIONS, dkLen: 256 });
const V2_SALT = new Uint8Array(32).fill(3);
const V2_SALT_B64 = bytesToBase64(V2_SALT);
const V1_SALT = new Uint8Array(16).fill(4);
const V1_SALT_B64 = bytesToBase64(V1_SALT);

interface FetchCall {
  url: string;
  method: string;
  body?: Record<string, unknown>;
}

interface Scenario {
  initialized?: boolean;
  wrappedKey?: string | null;
  keyId?: string | null;
  /** Returned by GET /vault/v2/params after the first call (the init-key 409 path). */
  paramsAfterConflict?: { wrappedKey: string; keyId: string };
  entries?: BackendVaultEntry[];
  initKeyConflict?: boolean;
  v1Salt?: string | null;
  migrateStatus?: number;
}

function makeFetch(scenario: Scenario) {
  const calls: FetchCall[] = [];
  let paramsCalls = 0;

  const ok = (data: unknown, status = 200) => ({
    ok: status < 400,
    status,
    statusText: 'OK',
    json: async () => data,
  });

  const mock = vi.fn(async (input: string, options?: RequestInit) => {
    const url = String(input);
    const method = (options?.method ?? 'GET').toUpperCase();
    const body =
      typeof options?.body === 'string' ? (JSON.parse(options.body) as Record<string, unknown>) : undefined;
    calls.push({ url, method, body });

    if (url.includes('/vault/v2/params')) {
      paramsCalls += 1;
      if (scenario.initKeyConflict && scenario.paramsAfterConflict && paramsCalls > 1) {
        return ok({
          initialized: true,
          salt: V2_SALT_B64,
          kdf: KDF,
          wrappedKey: scenario.paramsAfterConflict.wrappedKey,
          keyId: scenario.paramsAfterConflict.keyId,
        });
      }
      return ok({
        initialized: !!scenario.initialized,
        salt: V2_SALT_B64,
        kdf: KDF,
        wrappedKey: scenario.initialized ? scenario.wrappedKey ?? null : null,
        keyId: scenario.initialized ? scenario.keyId ?? null : null,
      });
    }

    if (url.includes('/auth/me')) return ok({ id: 7, username: 'tester', role: 'USER' });

    if (url.endsWith('/vault/entries') && method === 'GET') {
      return ok(scenario.entries ?? []);
    }

    if (url.includes('/vault/master-password/salt')) {
      return scenario.v1Salt ? ok({ salt: scenario.v1Salt }) : ok({ message: 'not set' }, 404);
    }

    if (url.includes('/vault/v2/init-salt')) {
      return ok({ initialized: false, salt: V2_SALT_B64, kdf: KDF, wrappedKey: null, keyId: null });
    }

    if (url.includes('/vault/v2/init-key')) {
      if (scenario.initKeyConflict) {
        return {
          ok: false,
          status: 409,
          statusText: 'Conflict',
          json: async () => ({ message: 'vault key already initialised' }),
        };
      }
      return ok({
        initialized: true,
        salt: V2_SALT_B64,
        kdf: KDF,
        wrappedKey: body?.wrappedKey,
        keyId: body?.keyId,
      });
    }

    if (url.includes('/vault/v2/migrate')) {
      const entries = (body?.entries ?? []) as { id: number }[];
      return ok({ migrated: entries.length, ids: entries.map((entry) => entry.id) }, scenario.migrateStatus ?? 200);
    }

    throw new Error(`unexpected fetch: ${method} ${url}`);
  });

  return { mock, calls };
}

function wrapper({ children }: { children: ReactNode }) {
  return <VaultProvider>{children}</VaultProvider>;
}

/** A v1 row readable with the key derived from `password`. */
async function v1Row(id: number, password: string): Promise<BackendVaultEntry> {
  const h = await hashMasterPasswordLocally(password);
  const key = await deriveV1DecryptKey(h, V1_SALT);
  const sealed = await cryptoApi.encryptBytes(new TextEncoder().encode(JSON.stringify('s3cret')), key);

  return {
    id,
    title: `entry ${id}`,
    url: undefined,
    groupPath: undefined,
    usernameEncrypted: null,
    passwordEncrypted: {
      ciphertext: bytesToBase64(sealed.ciphertextWithTag),
      iv: bytesToBase64(sealed.iv),
    },
    notesEncrypted: null,
    cryptoVersion: 1,
    entryUuid: '00000000-0000-4000-8000-000000000001',
    keyId: null,
    usernameV2: null,
    passwordV2: null,
    notesV2: null,
    v1Digest: null,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  };
}

describe('VaultContext (v2)', () => {
  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem(TOKEN_KEY, 'test-jwt');
  });

  describe('checkStatus', () => {
    it('reports hasMasterPasswordSet=false when unauthenticated', async () => {
      localStorage.removeItem(TOKEN_KEY);
      const { result } = renderHook(() => useVault(), { wrapper });

      await act(async () => {
        await result.current.checkStatus();
      });

      expect(result.current.hasMasterPasswordSet).toBe(false);
    });

    it('reflects the backend status response', async () => {
      window.fetch = vi.fn() as unknown as typeof fetch;
      (window.fetch as ReturnType<typeof vi.fn>).mockResolvedValue({ ok: true, status: 200, statusText: 'OK', json: async () => true });
      const { result } = renderHook(() => useVault(), { wrapper });

      await act(async () => {
        await result.current.checkStatus();
      });

      expect(result.current.hasMasterPasswordSet).toBe(true);
    });
  });

  describe('setupVault on an account with no key yet', () => {
    it('initialises v2 and unlocks', async () => {
      const { mock, calls } = makeFetch({ initialized: false });
      window.fetch = mock as unknown as typeof fetch;
      const { result } = renderHook(() => useVault(), { wrapper });

      await act(async () => {
        await result.current.setupVault('correct-horse-battery');
      });

      expect(result.current.isUnlocked).toBe(true);
      expect(result.current.hasMasterPasswordSet).toBe(true);
      expect(result.current.error).toBeNull();
      expect(result.current.keys?.userId).toBe(7);
      expect(result.current.keys?.keyId).toMatch(/^[0-9a-f]{64}$/);

      expect(calls.some((call) => call.url.includes('/vault/v2/init-salt'))).toBe(true);
      const initKey = calls.find((call) => call.url.includes('/vault/v2/init-key'));
      expect(initKey?.method).toBe('POST');
      expect(typeof initKey?.body?.wrappedKey).toBe('string');
      expect(initKey?.body?.keyId).toBe(result.current.keys?.keyId);
    });

    it('seals a key the server cannot open: the request carries no password and no hash', async () => {
      const { mock, calls } = makeFetch({ initialized: false });
      window.fetch = mock as unknown as typeof fetch;
      const { result } = renderHook(() => useVault(), { wrapper });

      await act(async () => {
        await result.current.setupVault('correct-horse-battery');
      });

      const h = await hashMasterPasswordLocally('correct-horse-battery');
      const wire = JSON.stringify(calls);

      expect(wire).not.toContain(h);
      expect(wire).not.toContain('correct-horse-battery');
      expect(wire).not.toContain('masterPasswordHash');
      expect(wire).not.toContain('password');
    });

    it('a vault with no entries is initialised without a password check', async () => {
      // Nothing exists to protect, so there is nothing to decrypt first.
      const { mock } = makeFetch({ initialized: false, entries: [] });
      window.fetch = mock as unknown as typeof fetch;
      const { result } = renderHook(() => useVault(), { wrapper });

      await act(async () => {
        await result.current.setupVault('any-password-at-all');
      });

      expect(result.current.isUnlocked).toBe(true);
    });
  });

  describe('unlock of a vault that already has a key', () => {
    it('unwraps the stored DEK and reports the matching key id', async () => {
      const dek = generateDek();
      const kek = await deriveV2Kek('correct-horse', V2_SALT, KDF_ITERATIONS);
      const wrappedKey = await wrapDek(kek, dek);
      const keyId = await fingerprintKey(dek);

      const { mock } = makeFetch({ initialized: true, wrappedKey, keyId });
      window.fetch = mock as unknown as typeof fetch;
      const { result } = renderHook(() => useVault(), { wrapper });

      await act(async () => {
        await result.current.unlock('correct-horse');
      });

      expect(result.current.isUnlocked).toBe(true);
      expect(result.current.keys?.keyId).toBe(keyId);
      expect(Array.from(result.current.keys?.v2Key ?? [])).toEqual(Array.from(dek));
    });

    it('a wrong password stays locked and says so, without asking the server', async () => {
      const kek = await deriveV2Kek('correct-horse', V2_SALT, KDF_ITERATIONS);
      const wrappedKey = await wrapDek(kek, generateDek());

      const { mock, calls } = makeFetch({ initialized: true, wrappedKey, keyId: 'a'.repeat(64) });
      window.fetch = mock as unknown as typeof fetch;
      const { result } = renderHook(() => useVault(), { wrapper });

      await act(async () => {
        await result.current.unlock('wrong-horse');
      });

      expect(result.current.isUnlocked).toBe(false);
      expect(result.current.keys).toBeNull();
      expect(result.current.error).toBe('Incorrect master password');
      // The GCM tag on the sealed DEK is the check; /master-password/verify is gone.
      expect(calls.some((call) => call.url.includes('master-password/verify'))).toBe(false);
    });

    it('does not initialise v2 when the password cannot open the existing v1 rows', async () => {
      const entries = [await v1Row(1, 'right-password')];
      const { mock, calls } = makeFetch({ initialized: false, entries, v1Salt: V1_SALT_B64 });
      window.fetch = mock as unknown as typeof fetch;
      const { result } = renderHook(() => useVault(), { wrapper });

      await act(async () => {
        await result.current.unlock('wrong-password');
      });

      expect(result.current.isUnlocked).toBe(false);
      expect(result.current.error).toBe('Incorrect master password');
      expect(calls.some((call) => call.url.includes('/vault/v2/init-key'))).toBe(false);
    });

    it('initialises and migrates when the password does open them', async () => {
      const entries = [await v1Row(1, 'right-password')];
      const { mock, calls } = makeFetch({ initialized: false, entries, v1Salt: V1_SALT_B64 });
      window.fetch = mock as unknown as typeof fetch;
      const { result } = renderHook(() => useVault(), { wrapper });

      await act(async () => {
        await result.current.unlock('right-password');
      });

      expect(result.current.isUnlocked).toBe(true);
      const migrate = calls.find((call) => call.url.includes('/vault/v2/migrate'));
      expect(migrate?.method).toBe('POST');
      expect((migrate?.body?.entries as unknown[]) ?? []).toHaveLength(1);
      expect(result.current.migrationReport?.migrated).toBe(1);
    });

    it('recovers when another browser initialised the key first', async () => {
      // init-key answers 409; the key that won is the one this browser must open.
      const dek = generateDek();
      const kek = await deriveV2Kek('shared-password', V2_SALT, KDF_ITERATIONS);
      const wrappedKey = await wrapDek(kek, dek);
      const keyId = await fingerprintKey(dek);

      const { mock, calls } = makeFetch({
        initialized: false,
        initKeyConflict: true,
        paramsAfterConflict: { wrappedKey, keyId },
      });
      window.fetch = mock as unknown as typeof fetch;
      const { result } = renderHook(() => useVault(), { wrapper });

      await act(async () => {
        await result.current.unlock('shared-password');
      });

      expect(result.current.isUnlocked).toBe(true);
      expect(result.current.keys?.keyId).toBe(keyId);
      expect(calls.filter((call) => call.url.includes('/vault/v2/params'))).toHaveLength(2);
    });

    it('reports a mismatched key id instead of writing rows nobody can read', async () => {
      const kek = await deriveV2Kek('correct-horse', V2_SALT, KDF_ITERATIONS);
      const wrappedKey = await wrapDek(kek, generateDek());

      const { mock } = makeFetch({ initialized: true, wrappedKey, keyId: 'deadbeef' });
      window.fetch = mock as unknown as typeof fetch;
      const { result } = renderHook(() => useVault(), { wrapper });

      await act(async () => {
        await result.current.unlock('correct-horse');
      });

      expect(result.current.isUnlocked).toBe(false);
      expect(result.current.error).toContain('does not match the key stored on the server');
    });
  });

  describe('what travels to the server', () => {
    it('never sends the master password hash, and never calls the v1 verify or set endpoints', async () => {
      const entries = [await v1Row(1, 'right-password'), await v1Row(2, 'right-password')];
      const { mock, calls } = makeFetch({ initialized: false, entries, v1Salt: V1_SALT_B64 });
      window.fetch = mock as unknown as typeof fetch;
      const { result } = renderHook(() => useVault(), { wrapper });

      await act(async () => {
        await result.current.unlock('right-password');
      });

      expect(result.current.isUnlocked).toBe(true);

      const h = await hashMasterPasswordLocally('right-password');
      const wire = JSON.stringify(
        calls.map((call) => ({ url: call.url, body: call.body }))
      );

      expect(wire).not.toContain(h);
      expect(wire).not.toContain('right-password');
      expect(calls.some((call) => call.url.includes('master-password/verify'))).toBe(false);
      expect(calls.some((call) => call.url.includes('master-password/set'))).toBe(false);

      // The v1 salt is still fetched — it is what opens the old rows — but a salt is
      // not a secret the client has to keep.
      expect(calls.some((call) => call.url.includes('master-password/salt'))).toBe(true);
    });
  });

  describe('lock', () => {
    it('drops the keys', async () => {
      const dek = generateDek();
      const kek = await deriveV2Kek('correct-horse', V2_SALT, KDF_ITERATIONS);
      const wrappedKey = await wrapDek(kek, dek);

      window.fetch = makeFetch({ initialized: true, wrappedKey, keyId: await fingerprintKey(dek) }).mock as unknown as typeof fetch;
      const { result } = renderHook(() => useVault(), { wrapper });

      await act(async () => {
        await result.current.unlock('correct-horse');
      });
      expect(result.current.isUnlocked).toBe(true);

      act(() => {
        result.current.lock();
      });

      await waitFor(() => {
        expect(result.current.isUnlocked).toBe(false);
        expect(result.current.keys).toBeNull();
        expect(result.current.migrationReport).toBeNull();
      });
    });
  });

  describe('needsReinit', () => {
    it('is no longer triggered by a browser that has never seen a salt', async () => {
      // v1 needed a local salt copy to derive its key, so a new browser was stuck.
      // v2 reads the salt from the server, so there is nothing to be missing.
      localStorage.removeItem(VAULT_SALT_KEY);
      const { mock } = makeFetch({ initialized: false, entries: [] });
      window.fetch = mock as unknown as typeof fetch;
      const { result } = renderHook(() => useVault(), { wrapper });

      await act(async () => {
        await result.current.unlock('brand-new-password');
      });

      expect(result.current.needsReinit).toBe(false);
      expect(result.current.isUnlocked).toBe(true);
    });
  });
});
