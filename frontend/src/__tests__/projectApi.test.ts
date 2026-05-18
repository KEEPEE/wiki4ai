/**
 * Tests for projectApi service
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { projectApi } from '../services/projectApi'

// Mock fetch globally
const mockFetch = vi.fn() as any
window.fetch = mockFetch

describe('projectApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
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

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/projects')
      expect(result).toEqual(mockResponse)
    })

    it('should throw error when API call fails', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: false,
        status: 500,
        json: async () => ({ message: 'Server error' }),
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

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/projects/by-id/1')
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

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/projects', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: 'New Project' }),
      })
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

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/projects/by-id/1', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: 'Updated Project' }),
      })
      expect(result).toEqual(mockResponse)
    })
  })

  describe('deleteProject', () => {
    it('should delete project via DELETE request with by-id route', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => ({}),
      })

      await projectApi.deleteProject(1)

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/projects/by-id/1', {
        method: 'DELETE',
      })
    })
  })
})
