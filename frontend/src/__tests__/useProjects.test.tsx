/**
 * Tests for useProjects hook
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import React from 'react'
import { useProjects } from '../hooks/useProjects'

// Mock the projectApi module at the top level
vi.mock('../services/projectApi', () => ({
  projectApi: {
    getAllProjects: vi.fn(),
    createProject: vi.fn(),
    updateProject: vi.fn(),
    deleteProject: vi.fn(),
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

describe('useProjects', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('Fetching projects', () => {
    it('should fetch and return projects on mount', async () => {
      const mockProjects = [
        { id: 1, name: 'Project 1', slug: 'project-1', description: null, documentCount: 3, createdAt: '', updatedAt: '' },
      ]

      const { projectApi } = await import('../services/projectApi')
      vi.mocked(projectApi.getAllProjects).mockResolvedValue(mockProjects)

      const { result } = renderHook(() => useProjects(), { wrapper })

      await waitFor(() => {
        expect(result.current.isLoading).toBe(false)
      })

      expect(result.current.projects).toEqual(mockProjects)
    })

    it('should return empty array while loading', async () => {
      const { projectApi } = await import('../services/projectApi')
      vi.mocked(projectApi.getAllProjects).mockImplementation(() => 
        new Promise(resolve => setTimeout(() => resolve([]), 100))
      )

      const { result } = renderHook(() => useProjects(), { wrapper })

      expect(result.current.isLoading).toBe(true)
      expect(result.current.projects).toEqual([])
    })
  })

  describe('Create project', () => {
    it('should create project and invalidate cache', async () => {
      const mockProject = { id: 1, name: 'New Project', slug: 'new-project', description: null, documentCount: 0, createdAt: '', updatedAt: '' }

      const { projectApi } = await import('../services/projectApi')
      vi.mocked(projectApi.getAllProjects).mockResolvedValue([])
      vi.mocked(projectApi.createProject).mockResolvedValue(mockProject)

      const { result } = renderHook(() => useProjects(), { wrapper })

      await waitFor(() => expect(result.current.isLoading).toBe(false))

      await result.current.createProject({ name: 'New Project', description: 'Test' })

      expect(projectApi.createProject).toHaveBeenCalledWith({ name: 'New Project', description: 'Test' })
    })
  })

  describe('Delete project', () => {
    it('should delete project successfully', async () => {
      const { projectApi } = await import('../services/projectApi')
      vi.mocked(projectApi.getAllProjects).mockResolvedValue([])
      vi.mocked(projectApi.deleteProject).mockResolvedValue(undefined)

      const { result } = renderHook(() => useProjects(), { wrapper })

      await waitFor(() => expect(result.current.isLoading).toBe(false))

      await result.current.deleteProject(1)

      expect(projectApi.deleteProject).toHaveBeenCalledWith(1)
    })
  })
})
