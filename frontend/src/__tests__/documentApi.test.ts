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

  describe('searchGlobal (WIKI4AI-61)', () => {
    it('should call the global search endpoint with keyword and default limit', async () => {
      const mockHits = [
        { id: 1, title: 'Doc', slug: 'doc', projectId: 1, projectSlug: 'p', projectName: 'P', score: 0.03, updatedAt: '', excerpt: 'x' },
      ]

      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => mockHits,
      })

      const result = await documentApi.searchGlobal('embedding')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/search/documents?keyword=embedding&limit=20',
        expect.objectContaining({ method: 'GET' }),
      )
      expect(result).toEqual(mockHits)
    })

    it('should URL-encode the keyword and pass a custom limit', async () => {
      mockFetch.mockResolvedValueOnce({ ok: true, json: async () => [] })

      await documentApi.searchGlobal('hello world', 5)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/search/documents?keyword=hello%20world&limit=5',
        expect.objectContaining({ method: 'GET' }),
      )
    })
  })

  // WIKI4AI-186: pin endpoints (story 184). POST, no body. Neither call bumps
  // the document `version`, so an open editor is not pushed into a 409.
  describe('pinDocument / unpinDocument', () => {
    it('POSTs /projects/{slug}/documents/{docSlug}/pin', async () => {
      const updated = {
        id: 2, title: 'Readme', slug: 'readme', projectId: 1,
        createdAt: '', updatedAt: '', version: 3, pinned: true, sortOrder: -1,
      }
      mockFetch.mockResolvedValueOnce({ ok: true, json: async () => updated })

      const result = await documentApi.pinDocument('ordering', 'readme')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/projects/ordering/documents/readme/pin',
        expect.objectContaining({ method: 'POST' }),
      )
      expect(result.pinned).toBe(true)
      // Pinning must not change the optimistic-locking version.
      expect((result as unknown as typeof updated).version).toBe(3)
    })

    it('POSTs /projects/{slug}/documents/{docSlug}/unpin', async () => {
      const updated = {
        id: 2, title: 'Readme', slug: 'readme', projectId: 1,
        createdAt: '', updatedAt: '', version: 3, pinned: false, sortOrder: 2,
      }
      mockFetch.mockResolvedValueOnce({ ok: true, json: async () => updated })

      const result = await documentApi.unpinDocument('ordering', 'readme')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/projects/ordering/documents/readme/unpin',
        expect.objectContaining({ method: 'POST' }),
      )
      expect(result.pinned).toBe(false)
    })

    it('rejects with a 403-prefixed message when UPDATE is missing', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: false,
        status: 403,
        statusText: 'Forbidden',
        json: async () => ({ message: 'Chýba UPDATE oprávnenie' }),
      })

      await expect(documentApi.pinDocument('ordering', 'readme')).rejects.toThrow(/^403: /)
    })
  })

  // ── WIKI4AI-187: sort/filter parameters + reorder ─────────────────────────
  describe('document list query (WIKI4AI-187)', () => {
    it('appends sort and q to the paginated request', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => ({ content: [], totalElements: 0 }),
      })

      await documentApi.getByProject('ordering', { sort: 'manual', q: 'guide' })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/projects/ordering/documents?page=0&size=50&sort=manual&q=guide',
        expect.objectContaining({ method: 'GET' }),
      )
    })

    it('getByProjectPage asks for the backend maximum and keeps the total', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => ({ content: [{ id: 1, slug: 'a' }], totalElements: 137 }),
      })

      const page = await documentApi.getByProjectPage('ordering', { sort: 'manual' })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/projects/ordering/documents?page=0&size=100&sort=manual',
        expect.objectContaining({ method: 'GET' }),
      )
      // The total is what lets the UI admit that only part of the project can
      // be dragged.
      expect(page).toEqual({ items: [{ id: 1, slug: 'a' }], total: 137 })
    })
  })

  describe('reorderDocument (WIKI4AI-187)', () => {
    it('POSTs the slug anchor body to the document reorder endpoint', async () => {
      mockFetch.mockResolvedValueOnce({ ok: true, json: async () => ({ id: 1, slug: 'b' }) })

      await documentApi.reorderDocument('ordering', 'b', { afterSlug: 'c' })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/projects/ordering/documents/b/reorder',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ afterSlug: 'c' }),
        }),
      )
    })

    it('rejects with a 403-prefixed message when UPDATE is missing', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: false,
        status: 403,
        statusText: 'Forbidden',
        json: async () => ({ message: 'Chýba UPDATE oprávnenie' }),
      })

      await expect(documentApi.reorderDocument('ordering', 'b', { position: 2 })).rejects.toThrow(/^403: /)
    })
  })
})
