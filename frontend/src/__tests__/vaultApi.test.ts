/**
 * Tests for vaultApi service (uses authenticated apiClient)
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { vaultApi } from '../services/vaultApi'

// Mock fetch globally
const mockFetch = vi.fn() as any
window.fetch = mockFetch

describe('vaultApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('wiki4ai_access_token', 'test-token')
  })

  describe('getAll', () => {
    it('should return all vault entries for the user', async () => {
      const mockEntries = [
        {
          id: 1,
          title: 'Entry 1',
          url: 'https://example.com',
          groupPath: '/work',
          usernameEncrypted: [1, 2, 3],
          passwordEncrypted: [4, 5, 6],
          notesEncrypted: [7, 8, 9],
          iv: [10, 11, 12],
        },
      ]

      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => mockEntries,
      })

      const result = await vaultApi.getAll()

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/vault/entries',
        expect.objectContaining({ method: 'GET' }),
      )
      expect(result).toEqual(mockEntries)
    })

    it('should return empty array when no entries exist', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => [],
      })

      const result = await vaultApi.getAll()

      expect(result).toEqual([])
    })

    it('should throw error when API call fails', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
      })

      await expect(vaultApi.getAll()).rejects.toThrow()
    })
  })

  describe('create', () => {
    it('should create entry via POST request with encrypted data', async () => {
      const mockResponse = {
        id: 1,
        title: 'New Entry',
        url: 'https://new.com',
        groupPath: '/personal',
        usernameEncrypted: [1],
        passwordEncrypted: [2],
        notesEncrypted: null,
        iv: [3],
      }

      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => mockResponse,
      })

      const result = await vaultApi.create({
        title: 'New Entry',
        url: 'https://new.com',
        groupPath: '/personal',
        usernameEncrypted: new Uint8Array([1]),
        passwordEncrypted: new Uint8Array([2]),
        notesEncrypted: null,
        iv: new Uint8Array([3]),
      })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/vault/entries',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({
            title: 'New Entry',
            url: 'https://new.com',
            groupPath: '/personal',
            usernameEncrypted: [1],
            passwordEncrypted: [2],
            notesEncrypted: null,
            iv: [3],
          }),
        }),
      )
      expect(result).toEqual(mockResponse)
    })

    it('should convert Uint8Array to number arrays in request body', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => ({ id: 1, title: 'Test' }),
      })

      await vaultApi.create({
        title: 'Test',
        usernameEncrypted: new Uint8Array([255, 0, 128]),
        passwordEncrypted: new Uint8Array([10, 20]),
        notesEncrypted: new Uint8Array([30]),
        iv: new Uint8Array([40]),
      })

      const callArgs = mockFetch.mock.calls[0][1] as { body: string }
      const body = JSON.parse(callArgs.body)

      expect(body.usernameEncrypted).toEqual([255, 0, 128])
      expect(body.passwordEncrypted).toEqual([10, 20])
      expect(body.notesEncrypted).toEqual([30])
      expect(body.iv).toEqual([40])
    })

    it('should handle optional fields correctly', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => ({ id: 1 }),
      })

      await vaultApi.create({
        title: 'Minimal Entry',
        usernameEncrypted: new Uint8Array([1]),
        passwordEncrypted: new Uint8Array([2]),
        notesEncrypted: null,
        iv: new Uint8Array([3]),
      })

      const callArgs = mockFetch.mock.calls[0][1] as { body: string }
      const body = JSON.parse(callArgs.body)

      expect(body.url).toBeUndefined()
      expect(body.groupPath).toBeUndefined()
    })
  })

  describe('update', () => {
    it('should update entry via PUT request', async () => {
      const mockResponse = { id: 1, title: 'Updated Entry' }

      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => mockResponse,
      })

      const result = await vaultApi.update(1, {
        title: 'Updated Entry',
        passwordEncrypted: new Uint8Array([5]),
        iv: new Uint8Array([6]),
      })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/vault/entries/1',
        expect.objectContaining({
          method: 'PUT',
          body: JSON.stringify({
            title: 'Updated Entry',
            passwordEncrypted: [5],
            iv: [6],
          }),
        }),
      )
      expect(result).toEqual(mockResponse)
    })

    it('should only include provided fields in update request', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => ({ id: 1 }),
      })

      await vaultApi.update(1, { title: 'New Title' })

      const callArgs = mockFetch.mock.calls[0][1] as { body: string }
      const body = JSON.parse(callArgs.body)

      expect(body).toEqual({ title: 'New Title' })
    })
  })

  describe('delete', () => {
    it('should delete entry via DELETE request', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: true,
        status: 204,
        headers: { get: () => null },
      })

      await vaultApi.delete(1)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/vault/entries/1',
        expect.objectContaining({ method: 'DELETE' }),
      )
    })
  })

  describe('search', () => {
    it('should search entries by query string', async () => {
      const mockResults = [
        { id: 1, title: 'Matching Entry', usernameEncrypted: [], passwordEncrypted: [], iv: [] },
      ]

      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => mockResults,
      })

      const result = await vaultApi.search('matching')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/vault/search?q=matching',
        expect.objectContaining({ method: 'GET' }),
      )
      expect(result).toEqual(mockResults)
    })

    it('should include groupPath parameter when provided', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => [],
      })

      await vaultApi.search('test', '/work')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/vault/search?q=test&groupPath=%2Fwork',
        expect.objectContaining({ method: 'GET' }),
      )
    })

    it('should encode special characters in query', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => [],
      })

      await vaultApi.search('test & more')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/vault/search?q=test+%26+more',
        expect.objectContaining({ method: 'GET' }),
      )
    })
  })
})
