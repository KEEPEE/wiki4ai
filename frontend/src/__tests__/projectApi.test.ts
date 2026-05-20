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
})
