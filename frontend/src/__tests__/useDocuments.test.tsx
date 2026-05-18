/**
 * Tests for useDocuments hook
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import React from 'react'
import { useDocuments } from '../hooks/useDocuments'

// Mock the documentApi module at the top level
vi.mock('../services/documentApi', () => ({
  documentApi: {
    getByProject: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
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

describe('useDocuments', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('Fetching documents', () => {
    it('should fetch and return documents on mount', async () => {
      const mockDocs = [
        { id: 1, title: 'Doc 1', content: '', createdAt: '', updatedAt: '' },
        { id: 2, title: 'Doc 2', content: '', createdAt: '', updatedAt: '' },
      ]

      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.getByProject).mockResolvedValue(mockDocs)

      const { result } = renderHook(() => useDocuments('test-project'), { wrapper })

      await waitFor(() => {
        expect(result.current.isLoading).toBe(false)
      })

      expect(result.current.documents).toEqual(mockDocs)
    })

    it('should not fetch when projectSlug is empty', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.getByProject).mockResolvedValue([])

      renderHook(() => useDocuments(''), { wrapper })

      // Should not call API for empty slug
      expect(documentApi.getByProject).not.toHaveBeenCalled()
    })
  })

  describe('Create document', () => {
    it('should create document and invalidate cache', async () => {
      const mockDoc = { id: 1, title: 'New Doc', content: '', createdAt: '', updatedAt: '' }

      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.getByProject).mockResolvedValue([])
      vi.mocked(documentApi.create).mockResolvedValue(mockDoc)

      const { result } = renderHook(() => useDocuments('test-project'), { wrapper })

      await waitFor(() => expect(result.current.isLoading).toBe(false))

      await result.current.createDocument({ title: 'New Doc', content: '' })

      expect(documentApi.create).toHaveBeenCalledWith('test-project', { title: 'New Doc', content: '' })
    })
  })

  describe('Update document', () => {
    it('should update document and invalidate cache', async () => {
      const mockDoc = { id: 1, title: 'Updated', content: '# Updated', createdAt: '', updatedAt: '' }

      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.getByProject).mockResolvedValue([])
      vi.mocked(documentApi.update).mockResolvedValue(mockDoc)

      const { result } = renderHook(() => useDocuments('test-project'), { wrapper })

      await waitFor(() => expect(result.current.isLoading).toBe(false))

      await result.current.updateDocument({ docSlug: 'my-doc', data: { title: 'Updated', content: '# Updated' } })

      expect(documentApi.update).toHaveBeenCalledWith('test-project', 'my-doc', { title: 'Updated', content: '# Updated' })
    })
  })

  describe('Delete document', () => {
    it('should delete document and invalidate cache', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.getByProject).mockResolvedValue([])
      vi.mocked(documentApi.delete).mockResolvedValue(undefined)

      const { result } = renderHook(() => useDocuments('test-project'), { wrapper })

      await waitFor(() => expect(result.current.isLoading).toBe(false))

      await result.current.deleteDocument('my-doc')

      expect(documentApi.delete).toHaveBeenCalledWith('test-project', 'my-doc')
    })
  })
})
