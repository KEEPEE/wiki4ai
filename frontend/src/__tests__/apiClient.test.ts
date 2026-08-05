/**
 * Tests for apiClient - authenticated API wrapper with token refresh
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { apiGet, apiPost, apiDelete, apiPostFormData, getRedirectFromUrl } from '../services/apiClient'

// Mock fetch globally
const mockFetch = vi.fn() as any
window.fetch = mockFetch

describe('apiClient', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
  })

  describe('token attachment', () => {
    it('should attach Authorization header when token exists', async () => {
      localStorage.setItem('wiki4ai_access_token', 'test-jwt-token')

      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => ({ data: 'success' }),
      })

      await apiGet('/api/v1/test')

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/test', {
        method: 'GET',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': 'Bearer test-jwt-token',
        },
      })
    })

    it('should not attach Authorization header when no token exists', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => ({ data: 'success' }),
      })

      await apiGet('/api/v1/test')

      const callArgs = mockFetch.mock.calls[0][1]
      expect(callArgs.headers['Authorization']).toBeUndefined()
    })
  })

  describe('401 handling with token refresh', () => {
    it('should attempt token refresh on 401 and retry request', async () => {
      localStorage.setItem('wiki4ai_access_token', 'expired-token')
      localStorage.setItem('wiki4ai_refresh_token', 'valid-refresh-token')

      // First call: returns 401
      mockFetch.mockResolvedValueOnce({
        status: 401,
        ok: false,
      })

      // Second call: refresh endpoint returns new token
      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => ({ accessToken: 'new-token', refreshToken: 'new-refresh' }),
      })

      // Third call: retried original request with new token - succeeds
      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => ({ data: 'success after refresh' }),
      })

      const result = await apiGet('/api/v1/test')

      expect(result).toEqual({ data: 'success after refresh' })
      // Should have made 3 calls: original (401), refresh, retry
      expect(mockFetch.mock.calls.length).toBe(3)

      // Verify the retry used the new token
      const retryCall = mockFetch.mock.calls[2][1]
      expect(retryCall.headers['Authorization']).toBe('Bearer new-token')
    })

    it('should clear tokens on failed refresh', async () => {
      localStorage.setItem('wiki4ai_access_token', 'expired-token')
      localStorage.setItem('wiki4ai_refresh_token', 'invalid-refresh-token')

      // First call: returns 401
      mockFetch.mockResolvedValueOnce({
        status: 401,
        ok: false,
      })

      // Second call: refresh also fails
      mockFetch.mockResolvedValueOnce({
        status: 403,
        ok: false,
      })

      // The apiGet convenience method will throw because the final response is not ok
      await expect(apiGet('/api/v1/test')).rejects.toThrow()

      // Tokens should be cleared after failed refresh
      expect(localStorage.getItem('wiki4ai_access_token')).toBeNull()
      expect(localStorage.getItem('wiki4ai_refresh_token')).toBeNull()
    })
  })

  describe('convenience methods', () => {
    it('apiPost should send JSON body with correct headers', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => ({ id: 1, name: 'created' }),
      })

      const result = await apiPost('/api/v1/items', { name: 'new item' })

      expect(result).toEqual({ id: 1, name: 'created' })
      expect(mockFetch.mock.calls[0][1].method).toBe('POST')
      expect(mockFetch.mock.calls[0][1].body).toBe(JSON.stringify({ name: 'new item' }))
    })

    it('apiDelete should send DELETE request', async () => {
      mockFetch.mockResolvedValueOnce({
        status: 204,
        ok: true,
        headers: new Map([['Content-Length', '']]),
      } as any)

      await apiDelete('/api/v1/items/1')

      expect(mockFetch.mock.calls[0][1].method).toBe('DELETE')
    })

    it('apiPostFormData must NOT set a Content-Type header, so the browser can set multipart/form-data with the correct boundary', async () => {
      // Regression test: apiRequest used to unconditionally default to
      // Content-Type: application/json, which overrode the browser's
      // auto-generated multipart boundary and made the backend reject file
      // uploads (KDBX import) with "Current request is not a multipart request".
      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => [],
      })

      const formData = new FormData()
      formData.append('file', new Blob(['dummy']), 'test.kdbx')
      formData.append('password', 'secret')

      await apiPostFormData('/api/v1/vault/import/kdbx', formData)

      const callArgs = mockFetch.mock.calls[0][1]
      expect(callArgs.headers['Content-Type']).toBeUndefined()
      expect(callArgs.body).toBe(formData)
    })
  })

  describe('getRedirectFromUrl', () => {
    it('should extract redirect parameter from URL search params', () => {
      // Simulate URL with redirect param
      Object.defineProperty(window, 'location', {
        value: {
          search: '?redirect=%2Fprojects%2Fmy-project',
        },
        writable: true,
        configurable: true,
      })

      const result = getRedirectFromUrl()
      expect(result).toBe('/projects/my-project')
    })

    it('should return null when no redirect parameter exists', () => {
      Object.defineProperty(window, 'location', {
        value: {
          search: '',
        },
        writable: true,
        configurable: true,
      })

      const result = getRedirectFromUrl()
      expect(result).toBeNull()
    })
  })
})
