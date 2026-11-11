/**
 * Tests for projectApi service (uses authenticated apiClient)
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { projectApi } from '../services/projectApi'

// Mock fetch globally
const mockFetch = vi.fn() as any
window.fetch = mockFetch

describe('projectApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // Set a token so apiClient attaches Authorization header
    localStorage.setItem('wiki4ai_access_token', 'test-token')
  })

  describe('getAllProjects', () => {
    it('should return projects from API', async () => {
      const mockResponse = [
        { id: 1, name: 'Project 1', slug: 'project-1', description: null, documentCount: 3, createdAt: '', updatedAt: '' },
      ]

      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => mockResponse,
      })

      const result = await projectApi.getAllProjects()

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/projects',
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

      await expect(projectApi.getAllProjects()).rejects.toThrow()
    })
  })

  describe('getProjectById', () => {
    it('should return project from API using by-id route', async () => {
      const mockResponse = { id: 1, name: 'Project 1', slug: 'project-1', description: null, documentCount: 3, createdAt: '', updatedAt: '' }

      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => mockResponse,
      })

      const result = await projectApi.getProjectById(1)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/projects/by-id/1',
        expect.objectContaining({ method: 'GET' }),
      )
      expect(result).toEqual(mockResponse)
    })
  })

  describe('getProjectBySlug', () => {
    it('should fetch project via the by-slug route', async () => {
      const mockResponse = { id: 1, name: 'Project 1', slug: 'project-1' }

      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => mockResponse,
      })

      const result = await projectApi.getProjectBySlug('project-1')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/projects/project-1',
        expect.objectContaining({ method: 'GET' }),
      )
      expect(result).toEqual(mockResponse)
    })
  })

  describe('getTree', () => {
    it('should fetch the nested subproject tree for a project (WIKI4AI-30)', async () => {
      const mockResponse = {
        id: 1, name: 'Root', slug: 'root', parentSlug: null, depth: 1,
        hasChildren: true, documentCount: 2,
        children: [
          { id: 2, name: 'Child', slug: 'child', parentSlug: 'root', depth: 2, hasChildren: false, documentCount: 0, children: [] },
        ],
      }

      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => mockResponse,
      })

      const result = await projectApi.getTree('root')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/projects/root/tree',
        expect.objectContaining({ method: 'GET' }),
      )
      expect(result.children[0].slug).toBe('child')
    })

    it('should reject when the project is not found (404)', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: false,
        status: 404,
        statusText: 'Not Found',
      })

      await expect(projectApi.getTree('unknown')).rejects.toThrow()
    })
  })

  describe('createProject', () => {
    it('should create project via POST request', async () => {
      const mockResponse = { id: 1, name: 'New Project', slug: 'new-project' }

      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => mockResponse,
      })

      const result = await projectApi.createProject({ name: 'New Project' })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/projects',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ name: 'New Project' }),
        }),
      )
      expect(result).toEqual(mockResponse)
    })
  })

  describe('updateProject', () => {
    it('should update project via PUT request with by-id route', async () => {
      const mockResponse = { id: 1, name: 'Updated Project', slug: 'updated-project' }

      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => mockResponse,
      })

      const result = await projectApi.updateProject(1, { name: 'Updated Project' })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/projects/by-id/1',
        expect.objectContaining({
          method: 'PUT',
          body: JSON.stringify({ name: 'Updated Project' }),
        }),
      )
      expect(result).toEqual(mockResponse)
    })
  })

  describe('deleteProject', () => {
    it('should delete project via DELETE request with by-id route', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: true,
        status: 204,
        headers: { get: () => null },
      })

      await projectApi.deleteProject(1)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/projects/by-id/1',
        expect.objectContaining({ method: 'DELETE' }),
      )
    })
  })

  // WIKI4AI-186: pin endpoints (story 184). POST, no body, slug in the path;
  // the response carries the new `pinned`/`sortOrder` (sortOrder is opaque and
  // may be negative — the UI must take it from here, not from a refetch).
  describe('pinProject / unpinProject', () => {
    it('POSTs /projects/{slug}/pin and returns the pinned project', async () => {
      const updated = {
        id: 1, name: 'Glass Admin', slug: 'glass-admin', documentCount: 2,
        createdAt: '', updatedAt: '', pinned: true, sortOrder: -1,
      }
      mockFetch.mockResolvedValueOnce({ ok: true, json: async () => updated })

      const result = await projectApi.pinProject('glass-admin')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/projects/glass-admin/pin',
        expect.objectContaining({ method: 'POST' }),
      )
      expect(result.pinned).toBe(true)
      expect(result.sortOrder).toBe(-1)
    })

    it('POSTs /projects/{slug}/unpin and returns the unpinned project', async () => {
      const updated = {
        id: 1, name: 'Glass Admin', slug: 'glass-admin', documentCount: 2,
        createdAt: '', updatedAt: '', pinned: false, sortOrder: 4,
      }
      mockFetch.mockResolvedValueOnce({ ok: true, json: async () => updated })

      const result = await projectApi.unpinProject('glass-admin')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/projects/glass-admin/unpin',
        expect.objectContaining({ method: 'POST' }),
      )
      expect(result.pinned).toBe(false)
    })

    it('rejects with a 403-prefixed message when MANAGE is missing', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: false,
        status: 403,
        statusText: 'Forbidden',
        json: async () => ({ message: 'Chýba MANAGE oprávnenie' }),
      })

      // The UI keys its "no permission" Toast off this exact shape.
      await expect(projectApi.pinProject('glass-admin')).rejects.toThrow(/^403: /)
    })
  })

  // ── WIKI4AI-187: sort/filter parameters + reorder ─────────────────────────
  describe('getAllProjects with sort and filters (WIKI4AI-187)', () => {
    it('sends only the parameters that are set', async () => {
      mockFetch.mockResolvedValueOnce({ ok: true, json: async () => [] })

      await projectApi.getAllProjects({ sort: 'manual', q: 'Ordering', visibility: 'public' })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/projects?sort=manual&q=Ordering&visibility=public',
        expect.objectContaining({ method: 'GET' }),
      )
    })

    it('omits an empty search instead of asking for q=', async () => {
      mockFetch.mockResolvedValueOnce({ ok: true, json: async () => [] })

      await projectApi.getAllProjects({ sort: 'name_asc', q: '   ' })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/projects?sort=name_asc',
        expect.objectContaining({ method: 'GET' }),
      )
    })
  })

  describe('reorderProject (WIKI4AI-187)', () => {
    it('POSTs the slug anchor body to the reorder endpoint', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => ({ id: 1, slug: 'beta', sortOrder: 0 }),
      })

      await projectApi.reorderProject('beta', { beforeSlug: 'alpha' })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/projects/beta/reorder',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ beforeSlug: 'alpha' }),
        }),
      )
    })

    it('rejects with a 403-prefixed message when MANAGE is missing', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: false,
        status: 403,
        statusText: 'Forbidden',
        json: async () => ({ message: 'Chýba MANAGE oprávnenie' }),
      })

      await expect(projectApi.reorderProject('beta', { position: 0 })).rejects.toThrow(/^403: /)
    })
  })
})
