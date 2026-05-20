/**
 * Tests for documentApi service (uses authenticated apiClient)
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { documentApi } from '../services/documentApi'

// Mock fetch globally
const mockFetch = vi.fn() as any
window.fetch = mockFetch

describe('documentApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // Set a token so apiClient attaches Authorization header
    localStorage.setItem('wiki4ai_access_token', 'test-token')
  })

  describe('getByProject', () => {
    it('should return documents from paginated API response', async () => {
      const mockDocuments = [
        { id: 1, title: 'Doc 1', content: '', projectId: 1, createdAt: '', updatedAt: '' },
      ]
      // Backend returns Spring Data Page object with .content array
      const paginatedResponse = {
        content: mockDocuments,
        pageable: { pageNumber: 0, pageSize: 50 },
        totalElements: 1,
        totalPages: 1,
      }

      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => paginatedResponse,
      })

      const result = await documentApi.getByProject('test-project')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/projects/test-project/documents?page=0&size=50',
        expect.objectContaining({ method: 'GET' }),
      )
      expect(result).toEqual(mockDocuments)
    })

    it('should handle plain array response (backward compatibility)', async () => {
      const mockResponse = [
        { id: 1, title: 'Doc 1', content: '', projectId: 1, createdAt: '', updatedAt: '' },
      ]

      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => mockResponse,
      })

      const result = await documentApi.getByProject('test-project')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/projects/test-project/documents?page=0&size=50',
        expect.objectContaining({ method: 'GET' }),
      )
      expect(result).toEqual(mockResponse)
    })

    it('should throw error when API call fails', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
      })

      await expect(documentApi.getByProject('test-project')).rejects.toThrow()
    })
  })

  describe('create', () => {
    it('should create document via POST request', async () => {
      const mockResponse = { id: 1, title: 'New Doc', content: '', projectId: 1 }

      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => mockResponse,
      })

      const result = await documentApi.create('test-project', { title: 'New Doc' })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/projects/test-project/documents',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ title: 'New Doc' }),
        }),
      )
      expect(result).toEqual(mockResponse)
    })
  })

  describe('update', () => {
    it('should update document via PUT request', async () => {
      const mockResponse = { id: 1, title: 'Updated', content: '# Updated' }

      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => mockResponse,
      })

      const result = await documentApi.update('test-project', 'my-doc', { title: 'Updated' })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/projects/test-project/documents/my-doc',
        expect.objectContaining({
          method: 'PUT',
          body: JSON.stringify({ title: 'Updated' }),
        }),
      )
      expect(result).toEqual(mockResponse)
    })
  })

  describe('delete', () => {
    it('should delete document via DELETE request', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: true,
        status: 204,
        headers: { get: () => null },
      })

      await documentApi.delete('test-project', 'my-doc')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/projects/test-project/documents/my-doc',
        expect.objectContaining({ method: 'DELETE' }),
      )
    })
  })

  describe('getContent', () => {
    it('should return full document content', async () => {
      const mockResponse = { title: 'My Doc', content: '# Hello World', wikiLinks: [] }

      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => mockResponse,
      })

      const result = await documentApi.getContent('test-project', 'my-doc')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/projects/test-project/documents/my-doc/content',
        expect.objectContaining({ method: 'GET' }),
      )
      expect(result).toEqual(mockResponse)
    })
  })
})
