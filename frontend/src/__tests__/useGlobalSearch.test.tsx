/**
 * Tests for the useGlobalSearch hook (WIKI4AI-61).
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import React from 'react'
import { useGlobalSearch } from '../hooks/useGlobalSearch'

// Mock the documentApi module at the top level
vi.mock('../services/documentApi', () => ({
  documentApi: {
    searchGlobal: vi.fn(),
  },
}))

function wrapper({ children }: { children: React.ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: 0 },
    },
  })
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
}

describe('useGlobalSearch (WIKI4AI-61)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('should fetch and return global search results for a valid keyword', async () => {
    const mockResults = [
      { id: 1, title: 'Doc', slug: 'doc', projectId: 1, projectSlug: 'p', projectName: 'P', score: 0.03, updatedAt: '', excerpt: 'x' },
    ]
    const { documentApi } = await import('../services/documentApi')
    vi.mocked(documentApi.searchGlobal).mockResolvedValue(mockResults)

    const { result } = renderHook(() => useGlobalSearch('embedding'), { wrapper })

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false)
    })

    expect(documentApi.searchGlobal).toHaveBeenCalledWith('embedding', 20)
    expect(result.current.results).toEqual(mockResults)
    expect(result.current.hasSearched).toBe(true)
  })

  it('should trim the keyword before querying', async () => {
    const { documentApi } = await import('../services/documentApi')
    vi.mocked(documentApi.searchGlobal).mockResolvedValue([])

    renderHook(() => useGlobalSearch('  embedding  '), { wrapper })

    await waitFor(() => {
      expect(documentApi.searchGlobal).toHaveBeenCalledWith('embedding', 20)
    })
  })

  it('should not fetch when the trimmed keyword is shorter than 2 characters', async () => {
    const { documentApi } = await import('../services/documentApi')
    vi.mocked(documentApi.searchGlobal).mockResolvedValue([])

    const { result } = renderHook(() => useGlobalSearch('x'), { wrapper })

    // Give the query a chance to (incorrectly) fire.
    await new Promise((r) => setTimeout(r, 50))
    expect(documentApi.searchGlobal).not.toHaveBeenCalled()
    expect(result.current.hasSearched).toBe(false)
    expect(result.current.results).toEqual([])
  })

  it('should pass a custom limit to the API', async () => {
    const { documentApi } = await import('../services/documentApi')
    vi.mocked(documentApi.searchGlobal).mockResolvedValue([])

    renderHook(() => useGlobalSearch('embedding', 5), { wrapper })

    await waitFor(() => {
      expect(documentApi.searchGlobal).toHaveBeenCalledWith('embedding', 5)
    })
  })
})
