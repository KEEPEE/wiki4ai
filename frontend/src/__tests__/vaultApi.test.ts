/**
 * Tests for vaultApi service (uses authenticated apiClient)
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { vaultApi } from '../services/vaultApi'

// Mock fetch globally
const mockFetch = vi.fn() as any
window.fetch = mockFetch

const mockField = { ciphertext: 'Y2lwaGVy', iv: 'aXZieXRlcw==' }

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
          usernameEncrypted: mockField,
          passwordEncrypted: mockField,
          notesEncrypted: mockField,
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
        usernameEncrypted: mockField,
        passwordEncrypted: mockField,
        notesEncrypted: null,
      }

      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => mockResponse,
      })

      const result = await vaultApi.create({
        title: 'New Entry',
        url: 'https://new.com',
        groupPath: '/personal',
        usernameEncrypted: mockField,
        passwordEncrypted: mockField,
      })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/vault/entries',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({
            title: 'New Entry',
            url: 'https://new.com',
            groupPath: '/personal',
            usernameEncrypted: mockField,
            passwordEncrypted: mockField,
          }),
        }),
      )
      expect(result).toEqual(mockResponse)
    })

    it('should send EncryptedField (Base64 ciphertext/iv) objects, not raw byte arrays', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => ({ id: 1, title: 'Test' }),
      })

      await vaultApi.create({
        title: 'Test',
        usernameEncrypted: mockField,
        passwordEncrypted: mockField,
        notesEncrypted: mockField,
      })

      const callArgs = mockFetch.mock.calls[0][1] as { body: string }
      const body = JSON.parse(callArgs.body)

      expect(body.usernameEncrypted).toEqual(mockField)
      expect(body.passwordEncrypted).toEqual(mockField)
      expect(body.notesEncrypted).toEqual(mockField)
      expect(typeof body.passwordEncrypted.ciphertext).toBe('string')
      expect(typeof body.passwordEncrypted.iv).toBe('string')
    })

    it('should handle optional fields correctly', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => ({ id: 1 }),
      })

      await vaultApi.create({
        title: 'Minimal Entry',
        passwordEncrypted: mockField,
      })

      const callArgs = mockFetch.mock.calls[0][1] as { body: string }
      const body = JSON.parse(callArgs.body)

      expect(body.url).toBeUndefined()
      expect(body.groupPath).toBeUndefined()
      expect(body.usernameEncrypted).toBeUndefined()
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
        passwordEncrypted: mockField,
      })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/vault/entries/1',
        expect.objectContaining({
          method: 'PUT',
          body: JSON.stringify({
            title: 'Updated Entry',
            passwordEncrypted: mockField,
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
        { id: 1, title: 'Matching Entry', usernameEncrypted: null, passwordEncrypted: mockField, notesEncrypted: null },
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

  describe('getExportEntries', () => {
    it('should return all encrypted entries for export', async () => {
      const mockEntries = [
        {
          id: 1,
          title: 'Entry 1',
          url: 'https://example.com',
          groupPath: '/work',
          usernameEncrypted: mockField,
          passwordEncrypted: mockField,
          notesEncrypted: mockField,
        },
      ]

      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => mockEntries,
      })

      const result = await vaultApi.getExportEntries()

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/vault/export',
        expect.objectContaining({ method: 'GET' }),
      )
      expect(result).toEqual(mockEntries)
    })

    it('should return empty array when no entries exist', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => [],
      })

      const result = await vaultApi.getExportEntries()

      expect(result).toEqual([])
    })

    it('should throw error when API call fails', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
      })

      await expect(vaultApi.getExportEntries()).rejects.toThrow()
    })
  })
})
