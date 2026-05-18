/**
 * Tests for documentApi service
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { documentApi } from '../services/documentApi'

beforeEach(() => {
  vi.restoreAllMocks()
})

describe('documentApi', () => {
  describe('getByProject', () => {
    it('should return documents array on success', async () => {
      const mockDocs = [
        { id: 1, title: 'Doc 1', content: '', createdAt: '', updatedAt: '' },
        { id: 2, title: 'Doc 2', content: '', createdAt: '', updatedAt: '' },
      ]

      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(mockDocs),
      })

      const result = await documentApi.getByProject('test-project')

      expect(fetch).toHaveBeenCalledWith('/api/v1/projects/test-project/documents')
      expect(result).toEqual(mockDocs)
    })

    it('should throw error on failed request', async () => {
      global.fetch = vi.fn().mockResolvedValue({
        ok: false,
        statusText: 'Not Found',
      })

      await expect(documentApi.getByProject('nonexistent')).rejects.toThrow(/Failed to fetch documents/)
    })
  })

  describe('create', () => {
    it('should create document and return response', async () => {
      const data = { title: 'New Doc', content: '# Hello' }
      const created = { id: 1, ...data, createdAt: '', updatedAt: '' }

      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(created),
      })

      const result = await documentApi.create('test-project', data)

      expect(fetch).toHaveBeenCalledWith('/api/v1/projects/test-project/documents', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
      })
      expect(result).toEqual(created)
    })
  })

  describe('get', () => {
    it('should return document on success', async () => {
      const mockDoc = { id: 1, title: 'My Doc', content: '# Content', createdAt: '', updatedAt: '' }

      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(mockDoc),
      })

      const result = await documentApi.get('test-project', 'my-doc')

      expect(fetch).toHaveBeenCalledWith('/api/v1/projects/test-project/documents/my-doc')
      expect(result).toEqual(mockDoc)
    })
  })

  describe('getContent', () => {
    it('should return document content with wiki links', async () => {
      const mockContent = {
        title: 'My Doc',
        content: '# Hello [[World]]',
        wikiLinks: ['world'],
      }

      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(mockContent),
      })

      const result = await documentApi.getContent('test-project', 'my-doc')

      expect(fetch).toHaveBeenCalledWith('/api/v1/projects/test-project/documents/my-doc/content')
      expect(result).toEqual(mockContent)
    })
  })

  describe('update', () => {
    it('should update document and return response', async () => {
      const data = { title: 'Updated Title', content: '# Updated' }
      const updated = { id: 1, ...data, createdAt: '', updatedAt: '' }

      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(updated),
      })

      const result = await documentApi.update('test-project', 'my-doc', data)

      expect(fetch).toHaveBeenCalledWith('/api/v1/projects/test-project/documents/my-doc', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
      })
      expect(result).toEqual(updated)
    })
  })

  describe('delete', () => {
    it('should delete document successfully', async () => {
      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        status: 204,
      })

      await expect(documentApi.delete('test-project', 'my-doc')).resolves.toBeUndefined()

      expect(fetch).toHaveBeenCalledWith('/api/v1/projects/test-project/documents/my-doc', { method: 'DELETE' })
    })

    it('should handle 204 response without error', async () => {
      global.fetch = vi.fn().mockResolvedValue({
        ok: false,
        status: 204,
      })

      // Should not throw for 204
      await expect(documentApi.delete('test-project', 'my-doc')).resolves.toBeUndefined()
    })

    it('should throw error on delete failure', async () => {
      global.fetch = vi.fn().mockResolvedValue({
        ok: false,
        statusText: 'Not Found',
      })

      await expect(documentApi.delete('test-project', 'nonexistent')).rejects.toThrow(/Failed to delete document/)
    })
  })
})
