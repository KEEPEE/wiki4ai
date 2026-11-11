/**
 * Tests for useVaultEntries after WIKI4AI-197 D2.
 *
 * The crypto is real here. The hook no longer derives any key — it is handed one —
 * so what is left to exercise is AES-GCM per field, which is cheap, and the
 * decisions that matter: which version a row is read as, what a write puts on the
 * wire, and what happens to a row that will not open.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import React from 'react';
import type { BackendVaultEntry, VaultEntryData } from '../types/vault';
import { useVaultEntries, useSearchVaultEntries } from '../hooks/useVaultEntries';
import { cryptoApi } from '../services/cryptoApi';
import { bytesToBase64 } from '../services/encryptionService';
import { buildV2Aad, concat, type V1DecryptKey, type V2Key } from '../services/vaultCrypto';
import type { VaultKeys } from '../contexts/VaultContext';

const V2_KEY = new Uint8Array(32).fill(31) as V2Key;
const V1_KEY = new Uint8Array(32).fill(30) as V1DecryptKey;
const KEY_ID = 'e'.repeat(64);
const USER_ID = 7;

const KEYS: VaultKeys = { userId: USER_ID, keyId: KEY_ID, v2Key: V2_KEY, v1Keys: [V1_KEY] };

vi.mock('../services/vaultApi', () => ({
  vaultApi: {
    getAll: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    search: vi.fn(),
  },
}));

function wrapper({ children }: { children: React.ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: 0 },
      mutations: { retry: false },
    },
  });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

/** A row whose secrets are sealed the v1 way. */
async function v1BackendEntry(
  id: number,
  values: { password?: string; username?: string; notes?: string } = {}
): Promise<BackendVaultEntry> {
  const seal = async (value: string) => {
    const { ciphertextWithTag, iv } = await cryptoApi.encryptBytes(
      new TextEncoder().encode(JSON.stringify(value)),
      V1_KEY
    );
    return { ciphertext: bytesToBase64(ciphertextWithTag), iv: bytesToBase64(iv) };
  };

  return {
    id,
    title: `Entry ${id}`,
    url: `https://example.com/${id}`,
    groupPath: '/test',
    usernameEncrypted: values.username ? await seal(values.username) : null,
    passwordEncrypted: await seal(values.password ?? 'pw'),
    notesEncrypted: values.notes ? await seal(values.notes) : null,
    cryptoVersion: 1,
    entryUuid: `00000000-0000-4000-8000-${String(id).padStart(12, '0')}`,
    keyId: null,
    usernameV2: null,
    passwordV2: null,
    notesV2: null,
    v1Digest: null,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  };
}

/** A row whose secrets are sealed the v2 way. */
async function v2BackendEntry(
  id: number,
  values: { password?: string; username?: string; notes?: string } = {}
): Promise<BackendVaultEntry> {
  const entryUuid = `00000000-0000-4000-8000-${String(id).padStart(12, '0')}`;
  const seal = async (field: 'username' | 'password' | 'notes', value: string) => {
    const { ciphertextWithTag, iv } = await cryptoApi.encryptBytes(
      new TextEncoder().encode(JSON.stringify(value)),
      V2_KEY,
      { aad: buildV2Aad({ userId: USER_ID, entryUuid, field, keyId: KEY_ID }) }
    );
    return bytesToBase64(concat(iv, ciphertextWithTag));
  };

  return {
    id,
    title: `Entry ${id}`,
    url: `https://example.com/${id}`,
    groupPath: '/test',
    usernameEncrypted: null,
    passwordEncrypted: null,
    notesEncrypted: null,
    cryptoVersion: 2,
    entryUuid,
    keyId: KEY_ID,
    usernameV2: values.username ? await seal('username', values.username) : null,
    passwordV2: await seal('password', values.password ?? 'pw'),
    notesV2: values.notes ? await seal('notes', values.notes) : null,
    v1Digest: null,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  };
}

/** Ciphertext no key will open. */
function junkField() {
  return {
    ciphertext: bytesToBase64(new Uint8Array(20).fill(8)),
    iv: bytesToBase64(new Uint8Array(12).fill(6)),
  };
}

describe('useVaultEntries (v2)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('reading', () => {
    it('decrypts a v1 row', async () => {
      const { vaultApi } = await import('../services/vaultApi');
      vi.mocked(vaultApi.getAll).mockResolvedValue([
        await v1BackendEntry(1, { password: 'v1-heslo', username: 'user', notes: 'pozn' }),
      ]);

      const { result } = renderHook(() => useVaultEntries(KEYS), { wrapper });

      await waitFor(() => expect(result.current.isLoading).toBe(false));

      expect(result.current.entries[0].data).toEqual({
        password: 'v1-heslo',
        username: 'user',
        notes: 'pozn',
      });
      expect(result.current.entries[0].isReadable).toBe(true);
    });

    it('decrypts a v2 row', async () => {
      const { vaultApi } = await import('../services/vaultApi');
      vi.mocked(vaultApi.getAll).mockResolvedValue([
        await v2BackendEntry(2, { password: 'v2-heslo 🚀', notes: 'Príklad' }),
      ]);

      const { result } = renderHook(() => useVaultEntries(KEYS), { wrapper });

      await waitFor(() => expect(result.current.isLoading).toBe(false));

      expect(result.current.entries[0].data.password).toBe('v2-heslo 🚀');
      expect(result.current.entries[0].data.notes).toBe('Príklad');
      expect(result.current.entries[0].isReadable).toBe(true);
    });

    it('handles a vault that mixes both versions', async () => {
      const { vaultApi } = await import('../services/vaultApi');
      vi.mocked(vaultApi.getAll).mockResolvedValue([
        await v1BackendEntry(1, { password: 'old' }),
        await v2BackendEntry(2, { password: 'new' }),
      ]);

      const { result } = renderHook(() => useVaultEntries(KEYS), { wrapper });

      await waitFor(() => expect(result.current.isLoading).toBe(false));

      expect(result.current.entries.map((entry) => entry.data.password)).toEqual(['old', 'new']);
    });

    it('marks a row that will not open as unreadable, with no sentinel in the password', async () => {
      const { vaultApi } = await import('../services/vaultApi');
      const row = await v1BackendEntry(3, { password: 'x' });
      row.passwordEncrypted = junkField();
      vi.mocked(vaultApi.getAll).mockResolvedValue([row]);

      const { result } = renderHook(() => useVaultEntries(KEYS), { wrapper });

      await waitFor(() => expect(result.current.isLoading).toBe(false));

      expect(result.current.entries[0].isReadable).toBe(false);
      expect(result.current.entries[0].data.password).toBe('');
      // The literal that used to be written here became the user's password on save.
      expect(JSON.stringify(result.current.entries)).not.toContain('decryption failed');
    });

    it('marks a v2 row whose blob belongs to another row as unreadable', async () => {
      const { vaultApi } = await import('../services/vaultApi');
      const row = await v2BackendEntry(4, { password: 'secret' });
      row.entryUuid = '11111111-1111-4111-8111-111111111111';
      vi.mocked(vaultApi.getAll).mockResolvedValue([row]);

      const { result } = renderHook(() => useVaultEntries(KEYS), { wrapper });

      await waitFor(() => expect(result.current.isLoading).toBe(false));

      expect(result.current.entries[0].isReadable).toBe(false);
    });

    it('does not query at all while the vault is locked', async () => {
      const { vaultApi } = await import('../services/vaultApi');

      const { result } = renderHook(() => useVaultEntries(null), { wrapper });

      await waitFor(() => expect(result.current.isLoading).toBe(false));

      expect(vaultApi.getAll).not.toHaveBeenCalled();
      expect(result.current.entries).toEqual([]);
    });
  });

  describe('writing', () => {
    it('creates a row under v2 with its own uuid and no v1 fields', async () => {
      const { vaultApi } = await import('../services/vaultApi');
      vi.mocked(vaultApi.getAll).mockResolvedValue([]);
      vi.mocked(vaultApi.create).mockResolvedValue({
        id: 99,
        title: 'New',
        cryptoVersion: 2,
      } as never);

      const { result } = renderHook(() => useVaultEntries(KEYS), { wrapper });
      await waitFor(() => expect(result.current.isLoading).toBe(false));

      const data: VaultEntryData = { password: 'nové heslo', username: 'úřad', notes: '🚀' };
      await act(async () => {
        await result.current.createEntry({ title: 'New', url: 'https://x.test', data });
      });

      const dto = vi.mocked(vaultApi.create).mock.calls[0][0];
      expect(dto.cryptoVersion).toBe(2);
      expect(dto.keyId).toBe(KEY_ID);
      expect(dto.entryUuid).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
      expect(dto.passwordV2).toBeTruthy();
      expect(dto.usernameV2).toBeTruthy();
      expect(dto.notesV2).toBeTruthy();
      // A v2 create must not leave a v1 copy behind.
      expect(dto.passwordEncrypted).toBeUndefined();
      expect(dto.usernameEncrypted).toBeUndefined();
      expect(dto.notesEncrypted).toBeUndefined();
    });

    it('round-trips what it just wrote, under the uuid it sent', async () => {
      const { vaultApi } = await import('../services/vaultApi');
      vi.mocked(vaultApi.getAll).mockResolvedValue([]);
      vi.mocked(vaultApi.create).mockResolvedValue({ id: 100 } as never);

      const { result } = renderHook(() => useVaultEntries(KEYS), { wrapper });
      await waitFor(() => expect(result.current.isLoading).toBe(false));

      await act(async () => {
        await result.current.createEntry({ title: 'New', data: { password: 'overené' } });
      });

      const dto = vi.mocked(vaultApi.create).mock.calls[0][0];
      const packed = Uint8Array.from(atob(dto.passwordV2!), (c) => c.charCodeAt(0));
      const { cryptoApi: api } = await import('../services/cryptoApi');
      const opened = await api.decryptBytes(
        packed.slice(12),
        packed.slice(0, 12),
        V2_KEY,
        buildV2Aad({ userId: USER_ID, entryUuid: dto.entryUuid!, field: 'password', keyId: KEY_ID })
      );

      expect(opened).not.toBeNull();
      expect(JSON.parse(new TextDecoder().decode(opened!))).toBe('overené');
    });

    it('updates a row with the uuid the server already has', async () => {
      // entry_uuid is not updatable, so encrypting with a fresh one would produce a
      // blob the row cannot open.
      const { vaultApi } = await import('../services/vaultApi');
      const row = await v1BackendEntry(5, { password: 'old' });
      vi.mocked(vaultApi.getAll).mockResolvedValue([row]);
      vi.mocked(vaultApi.update).mockResolvedValue({ id: 5 } as never);

      const { result } = renderHook(() => useVaultEntries(KEYS), { wrapper });
      await waitFor(() => expect(result.current.isLoading).toBe(false));

      await act(async () => {
        await result.current.updateEntry({ id: 5, updates: { title: 'renamed', data: { password: 'new' } } });
      });

      const dto = vi.mocked(vaultApi.update).mock.calls[0][1];
      expect(dto.entryUuid).toBe(row.entryUuid);
      expect(dto.cryptoVersion).toBe(2);
      expect(dto.passwordV2).toBeTruthy();
      expect(dto.title).toBe('renamed');
    });

    it('refuses a metadata-only update instead of clearing the secrets', async () => {
      // v1 treated an omitted field as "set to NULL", so a title-only PUT wiped the
      // password ciphertext. The backend now refuses it; the hook refuses first.
      const { vaultApi } = await import('../services/vaultApi');
      vi.mocked(vaultApi.getAll).mockResolvedValue([await v1BackendEntry(6, {})]);

      const { result } = renderHook(() => useVaultEntries(KEYS), { wrapper });
      await waitFor(() => expect(result.current.isLoading).toBe(false));

      await expect(
        result.current.updateEntry({ id: 6, updates: { title: 'only the title' } })
      ).rejects.toThrow(/must carry the entry data/);

      expect(vaultApi.update).not.toHaveBeenCalled();
    });

    it('deletes a row', async () => {
      const { vaultApi } = await import('../services/vaultApi');
      vi.mocked(vaultApi.getAll).mockResolvedValue([await v1BackendEntry(7, {})]);
      vi.mocked(vaultApi.delete).mockResolvedValue(undefined as never);

      const { result } = renderHook(() => useVaultEntries(KEYS), { wrapper });
      await waitFor(() => expect(result.current.isLoading).toBe(false));
      expect(result.current.entries).toHaveLength(1);

      await act(async () => {
        await result.current.deleteEntry(7);
      });

      expect(vaultApi.delete).toHaveBeenCalledWith(7);
    });
  });

  describe('search', () => {
    it('decrypts search results the same way as the list', async () => {
      const { vaultApi } = await import('../services/vaultApi');
      vi.mocked(vaultApi.search).mockResolvedValue([await v2BackendEntry(8, { password: 'hľadane' })]);

      const { result } = renderHook(() => useSearchVaultEntries('hľadane', undefined, KEYS), { wrapper });

      await waitFor(() => expect(result.current.isLoading).toBe(false));

      expect(result.current.searchResults[0].data.password).toBe('hľadane');
      expect(result.current.searchResults[0].isReadable).toBe(true);
    });

    it('does not search while locked', async () => {
      const { vaultApi } = await import('../services/vaultApi');

      const { result } = renderHook(() => useSearchVaultEntries('query', undefined, null), { wrapper });

      await waitFor(() => expect(result.current.isLoading).toBe(false));
      expect(vaultApi.search).not.toHaveBeenCalled();
    });
  });
});
