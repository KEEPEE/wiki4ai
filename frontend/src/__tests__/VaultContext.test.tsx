/**
 * Tests for VaultContext - vault unlock/setup/salt-recovery state machine.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import type { ReactNode } from 'react'
import { VaultProvider, useVault } from '../contexts/VaultContext'

const VAULT_SALT_KEY = 'wiki4ai_vault_salt'
const TOKEN_KEY = 'wiki4ai_access_token'

vi.mock('../services/encryptionService', () => ({
  deriveKey: vi.fn().mockResolvedValue(new Uint8Array(32)),
}))

Object.defineProperty(globalThis.crypto, 'getRandomValues', {
  value: (arr: Uint8Array) => {
    arr.fill(7)
    return arr
  },
  writable: true,
})

// eslint-disable-next-line @typescript-eslint/no-explicit-any -- matches the mockFetch pattern used in vaultApi.test.ts
const mockFetch = vi.fn() as any
window.fetch = mockFetch

function wrapper({ children }: { children: ReactNode }) {
  return <VaultProvider>{children}</VaultProvider>
}

describe('VaultContext', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    localStorage.setItem(TOKEN_KEY, 'test-jwt')
  })

  describe('checkStatus', () => {
    it('should report hasMasterPasswordSet=false when unauthenticated', async () => {
      localStorage.removeItem(TOKEN_KEY)
      const { result } = renderHook(() => useVault(), { wrapper })

      await act(async () => {
        await result.current.checkStatus()
      })

      expect(result.current.hasMasterPasswordSet).toBe(false)
    })

    it('should reflect the backend status response', async () => {
      mockFetch.mockResolvedValueOnce({ ok: true, json: async () => true })
      const { result } = renderHook(() => useVault(), { wrapper })

      await act(async () => {
        await result.current.checkStatus()
      })

      expect(result.current.hasMasterPasswordSet).toBe(true)
    })
  })

  describe('setupVault', () => {
    it('should generate a salt, sync it to the backend, and unlock', async () => {
      mockFetch.mockResolvedValueOnce({ ok: true })
      const { result } = renderHook(() => useVault(), { wrapper })

      await act(async () => {
        await result.current.setupVault('master-hash')
      })

      expect(result.current.isUnlocked).toBe(true)
      expect(result.current.hasMasterPasswordSet).toBe(true)

      const [, options] = mockFetch.mock.calls[0]
      const body = JSON.parse(options.body)
      expect(body.masterPasswordHash).toBe('master-hash')
      expect(typeof body.salt).toBe('string')
      expect(localStorage.getItem(VAULT_SALT_KEY)).toBe(body.salt)
    })
  })

  describe('unlock', () => {
    it('should unlock directly when the salt is already in localStorage', async () => {
      localStorage.setItem(VAULT_SALT_KEY, btoa('existing-salt-bytes'))
      mockFetch.mockResolvedValueOnce({ ok: true }) // verify

      const { result } = renderHook(() => useVault(), { wrapper })

      await act(async () => {
        await result.current.unlock('master-hash')
      })

      expect(result.current.isUnlocked).toBe(true)
      expect(result.current.needsReinit).toBe(false)
      // Only the verify call - no need to fetch salt from backend.
      expect(mockFetch).toHaveBeenCalledTimes(1)
    })

    it('should recover the salt from the backend when missing locally, without reinit', async () => {
      // No salt in localStorage (new browser/device).
      mockFetch
        .mockResolvedValueOnce({ ok: true }) // verify
        .mockResolvedValueOnce({ ok: true, json: async () => ({ salt: 'cmVjb3ZlcmVkLXNhbHQ=' }) }) // GET salt

      const { result } = renderHook(() => useVault(), { wrapper })

      await act(async () => {
        await result.current.unlock('master-hash')
      })

      expect(result.current.isUnlocked).toBe(true)
      expect(result.current.needsReinit).toBe(false)
      expect(localStorage.getItem(VAULT_SALT_KEY)).toBe('cmVjb3ZlcmVkLXNhbHQ=')
    })

    it('should fall back to needsReinit when no salt exists locally or on the backend', async () => {
      mockFetch
        .mockResolvedValueOnce({ ok: true }) // verify
        .mockResolvedValueOnce({ ok: false, status: 404 }) // GET salt - not found

      const { result } = renderHook(() => useVault(), { wrapper })

      await act(async () => {
        await result.current.unlock('master-hash')
      })

      expect(result.current.isUnlocked).toBe(false)
      expect(result.current.needsReinit).toBe(true)
      expect(result.current.error).toBeTruthy()
    })

    it('should set an error and stay locked on incorrect master password', async () => {
      mockFetch.mockResolvedValueOnce({ ok: false, status: 401 }) // verify fails

      const { result } = renderHook(() => useVault(), { wrapper })

      await act(async () => {
        await result.current.unlock('wrong-hash')
      })

      expect(result.current.isUnlocked).toBe(false)
      expect(result.current.error).toBeTruthy()
    })
  })

  describe('lock', () => {
    it('should reset unlocked state and config', async () => {
      localStorage.setItem(VAULT_SALT_KEY, btoa('existing-salt-bytes'))
      mockFetch.mockResolvedValueOnce({ ok: true })

      const { result } = renderHook(() => useVault(), { wrapper })

      await act(async () => {
        await result.current.unlock('master-hash')
      })
      expect(result.current.isUnlocked).toBe(true)

      act(() => {
        result.current.lock()
      })

      await waitFor(() => {
        expect(result.current.isUnlocked).toBe(false)
        expect(result.current.config).toBeNull()
        expect(result.current.keyBytes).toBeNull()
      })
    })
  })
})
