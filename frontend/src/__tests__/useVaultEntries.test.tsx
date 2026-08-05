/**
 * Tests for useVaultEntries hook
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import React from 'react'
import type { BackendVaultEntry } from '../types/vault'
import { useVaultEntries, useSearchVaultEntries } from '../hooks/useVaultEntries'

// Mock crypto.getRandomValues for Node.js environment
Object.defineProperty(globalThis.crypto, 'getRandomValues', {
  value: (arr: Uint8Array) => {
    arr.fill(1)
    return arr
  },
  writable: true,
})

// Mock encryption service
vi.mock('../services/encryptionService', () => ({
  deriveKey: vi.fn(),
  encrypt: vi.fn(),
  decrypt: vi.fn(),
  bytesToBase64: (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes)),
  base64ToBytes: (b64: string) => Uint8Array.from(atob(b64), (c) => c.charCodeAt(0)),
}))

// Mock vaultApi module at the top level
vi.mock('../services/vaultApi', () => ({
  vaultApi: {
    getAll: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    search: vi.fn(),
  },
}))

function wrapper({ children }: { children: React.ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: 0 },
      mutations: { retry: false },
    },
  })
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
}

const mockConfig = {
  masterPassword: 'test-master-password',
  salt: new Uint8Array(16).fill(42),
}

function createMockBackendEntry(id: number, title: string): BackendVaultEntry {
  const field = { ciphertext: 'Y2lwaGVy', iv: 'aXZieXRlcw==' } // arbitrary valid base64
  return {
    id,
    title,
    url: `https://example.com/${id}`,
    groupPath: '/test',
    usernameEncrypted: field,
    passwordEncrypted: field,
    notesEncrypted: field,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  }
}

describe('useVaultEntries', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('Fetching entries', () => {
    it('should fetch and return decrypted entries on mount', async () => {
      const mockBackendEntries = [createMockBackendEntry(1, 'Entry 1'), createMockBackendEntry(2, 'Entry 2')]

      const { vaultApi } = await import('../services/vaultApi')
      const { deriveKey, decrypt } = await import('../services/encryptionService')

      // deriveKey now returns Uint8Array (raw key bytes) instead of CryptoKey
      vi.mocked(deriveKey).mockResolvedValue(new Uint8Array(32))
      vi.mocked(decrypt).mockResolvedValue(JSON.stringify('user'))

      vi.mocked(vaultApi.getAll).mockResolvedValue(mockBackendEntries)

      const { result } = renderHook(() => useVaultEntries(mockConfig), { wrapper })

      await waitFor(() => {
        expect(result.current.isLoading).toBe(false)
      }, { timeout: 5000 })

      expect(vaultApi.getAll).toHaveBeenCalled()
    })

    it('should handle decryption failures gracefully', async () => {
      const mockBackendEntries = [createMockBackendEntry(1, 'Entry 1')]

      const { vaultApi } = await import('../services/vaultApi')
      const { deriveKey, decrypt } = await import('../services/encryptionService')

      vi.mocked(deriveKey).mockResolvedValue(new Uint8Array(32))
      // decrypt now returns null on failure instead of throwing
      vi.mocked(decrypt).mockResolvedValue(null)

      vi.mocked(vaultApi.getAll).mockResolvedValue(mockBackendEntries)

      const { result } = renderHook(() => useVaultEntries(mockConfig), { wrapper })

      await waitFor(() => {
        expect(result.current.isLoading).toBe(false)
      }, { timeout: 5000 })

      // Should still return entries even with decryption failure (shows "[decryption failed]")
      expect(result.current.entries.length).toBe(1)
    })
  })

  describe('Create entry', () => {
    it('should create entry and apply optimistic update', async () => {
      const mockCreatedEntry = createMockBackendEntry(99, 'New Entry')

      const { vaultApi } = await import('../services/vaultApi')
      const { deriveKey, encrypt } = await import('../services/encryptionService')

      vi.mocked(deriveKey).mockResolvedValue(new Uint8Array(32))
      vi.mocked(encrypt).mockResolvedValue({ ciphertext: new Uint8Array([1]), iv: new Uint8Array([2]) })

      vi.mocked(vaultApi.getAll).mockResolvedValue([])
      vi.mocked(vaultApi.create).mockResolvedValue(mockCreatedEntry)

      const { result } = renderHook(() => useVaultEntries(mockConfig), { wrapper })

      await waitFor(() => expect(result.current.isLoading).toBe(false))

      const newEntry: Parameters<typeof result.current.createEntry>[0] = {
        title: 'New Entry',
        url: 'https://new.com',
        data: { username: 'user', password: 'pass' },
      }

      result.current.createEntry(newEntry)

      await waitFor(() => {
        expect(result.current.isCreating).toBe(false)
      })

      expect(vaultApi.create).toHaveBeenCalled()
    })
  })

  describe('Update entry', () => {
    it('should update entry and apply optimistic update', async () => {
      const mockBackendEntries = [createMockBackendEntry(1, 'Original')]
      const mockUpdatedEntry = createMockBackendEntry(1, 'Updated')

      const { vaultApi } = await import('../services/vaultApi')
      const { deriveKey, decrypt, encrypt } = await import('../services/encryptionService')

      vi.mocked(deriveKey).mockResolvedValue(new Uint8Array(32))
      vi.mocked(decrypt).mockResolvedValue(JSON.stringify('user'))
      vi.mocked(encrypt).mockResolvedValue({ ciphertext: new Uint8Array([1]), iv: new Uint8Array([2]) })

      vi.mocked(vaultApi.getAll).mockResolvedValue(mockBackendEntries)
      vi.mocked(vaultApi.update).mockResolvedValue(mockUpdatedEntry)

      const { result } = renderHook(() => useVaultEntries(mockConfig), { wrapper })

      await waitFor(() => expect(result.current.isLoading).toBe(false))

      result.current.updateEntry({ id: 1, updates: { title: 'Updated' } })

      await waitFor(() => {
        expect(result.current.isUpdating).toBe(false)
      })

      expect(vaultApi.update).toHaveBeenCalledWith(1, expect.any(Object))
    })
  })

  describe('Delete entry', () => {
    it('should delete entry and apply optimistic update', async () => {
      const mockBackendEntries = [createMockBackendEntry(1, 'To Delete')]

      const { vaultApi } = await import('../services/vaultApi')
      const { deriveKey, decrypt } = await import('../services/encryptionService')

      vi.mocked(deriveKey).mockResolvedValue(new Uint8Array(32))
      vi.mocked(decrypt).mockResolvedValue(JSON.stringify('user'))

      vi.mocked(vaultApi.getAll).mockResolvedValue(mockBackendEntries)
      vi.mocked(vaultApi.delete).mockResolvedValue(undefined)

      const { result } = renderHook(() => useVaultEntries(mockConfig), { wrapper })

      await waitFor(() => expect(result.current.isLoading).toBe(false))

      result.current.deleteEntry(1)

      await waitFor(() => {
        expect(result.current.isDeleting).toBe(false)
      })

      expect(vaultApi.delete).toHaveBeenCalledWith(1)
    })
  })
})

describe('useSearchVaultEntries', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('should search entries when query is provided', async () => {
    const mockResults = [createMockBackendEntry(1, 'Matching Entry')]

    const { vaultApi } = await import('../services/vaultApi')
    const { deriveKey, decrypt } = await import('../services/encryptionService')

    vi.mocked(deriveKey).mockResolvedValue(new Uint8Array(32))
    vi.mocked(decrypt).mockResolvedValue(JSON.stringify('user'))

    vi.mocked(vaultApi.search).mockResolvedValue(mockResults)

    const { result } = renderHook(
      () => useSearchVaultEntries(mockConfig, 'matching'),
      { wrapper }
    )

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false)
    }, { timeout: 5000 })

    expect(vaultApi.search).toHaveBeenCalledWith('matching', undefined)
    expect(result.current.hasSearched).toBe(true)
  })

  it('should not search when query is empty', async () => {
    const { vaultApi } = await import('../services/vaultApi')
    vi.mocked(vaultApi.search).mockResolvedValue([])

    renderHook(
      () => useSearchVaultEntries(mockConfig, ''),
      { wrapper }
    )

    expect(vaultApi.search).not.toHaveBeenCalled()
  })

  it('should trim whitespace from query', async () => {
    const mockResults = [createMockBackendEntry(1, 'Test Entry')]

    const { vaultApi } = await import('../services/vaultApi')
    const { deriveKey, decrypt } = await import('../services/encryptionService')

    vi.mocked(deriveKey).mockResolvedValue(new Uint8Array(32))
    vi.mocked(decrypt).mockResolvedValue(JSON.stringify('user'))

    vi.mocked(vaultApi.search).mockResolvedValue(mockResults)

    renderHook(
      () => useSearchVaultEntries(mockConfig, '  test  '),
      { wrapper }
    )

    expect(vaultApi.search).toHaveBeenCalledWith('test', undefined)
  })

  it('should include groupPath in search when provided', async () => {
    const mockResults = [createMockBackendEntry(1, 'Test Entry')]

    const { vaultApi } = await import('../services/vaultApi')
    const { deriveKey, decrypt } = await import('../services/encryptionService')

    vi.mocked(deriveKey).mockResolvedValue(new Uint8Array(32))
    vi.mocked(decrypt).mockResolvedValue(JSON.stringify('user'))

    vi.mocked(vaultApi.search).mockResolvedValue(mockResults)

    renderHook(
      () => useSearchVaultEntries(mockConfig, 'test', '/work'),
      { wrapper }
    )

    expect(vaultApi.search).toHaveBeenCalledWith('test', '/work')
  })
})
