/**
 * Tests for projectApi service
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { projectApi } from '../services/projectApi'

// Mock fetch globally
beforeEach(() => {
  vi.restoreAllMocks()
})

describe('projectApi', () => {
  describe('getAllProjects', () => {
    it('should return projects array on success', async () => {
      const mockProjects = [
        { id: 1, name: 'Project 1', slug: 'project-1', description: null, documentCount: 5, createdAt: '', updatedAt: '' },
      ]

      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(mockProjects),
      })

      const result = await projectApi.getAllProjects()

      expect(fetch).toHaveBeenCalledWith('/api/v1/projects')
      expect(result).toEqual(mockProjects)
    })

    it('should throw error on failed request', async () => {
      global.fetch = vi.fn().mockResolvedValue({
        ok: false,
        statusText: 'Internal Server Error',
      })

      await expect(projectApi.getAllProjects()).rejects.toThrow(/Failed to fetch projects/)
    })
  })

  describe('getProjectById', () => {
    it('should return project on success', async () => {
      const mockProject = { id: 1, name: 'Test', slug: 'test', description: null, documentCount: 0, createdAt: '', updatedAt: '' }

      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(mockProject),
      })

      const result = await projectApi.getProjectById(1)

      expect(fetch).toHaveBeenCalledWith('/api/v1/projects/1')
      expect(result).toEqual(mockProject)
    })
  })

  describe('getProjectBySlug', () => {
    it('should return project by slug on success', async () => {
      const mockProject = { id: 1, name: 'Test', slug: 'test-project', description: null, documentCount: 0, createdAt: '', updatedAt: '' }

      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(mockProject),
      })

      const result = await projectApi.getProjectBySlug('test-project')

      expect(fetch).toHaveBeenCalledWith('/api/v1/projects/slug/test-project')
      expect(result).toEqual(mockProject)
    })
  })

  describe('createProject', () => {
    it('should create project and return response', async () => {
      const dto = { name: 'New Project', description: 'Test' }
      const created = { id: 1, ...dto, slug: 'new-project', documentCount: 0, createdAt: '', updatedAt: '' }

      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(created),
      })

      const result = await projectApi.createProject(dto)

      expect(fetch).toHaveBeenCalledWith('/api/v1/projects', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(dto),
      })
      expect(result).toEqual(created)
    })

    it('should throw error on creation failure', async () => {
      global.fetch = vi.fn().mockResolvedValue({
        ok: false,
        statusText: 'Bad Request',
      })

      await expect(projectApi.createProject({ name: 'Test' })).rejects.toThrow(/Failed to create project/)
    })
  })

  describe('updateProject', () => {
    it('should update project and return response', async () => {
      const dto = { name: 'Updated Name', description: 'Updated desc' }
      const updated = { id: 1, ...dto, slug: 'test', documentCount: 0, createdAt: '', updatedAt: '' }

      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(updated),
      })

      const result = await projectApi.updateProject(1, dto)

      expect(fetch).toHaveBeenCalledWith('/api/v1/projects/1', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(dto),
      })
      expect(result).toEqual(updated)
    })
  })

  describe('deleteProject', () => {
    it('should delete project successfully', async () => {
      global.fetch = vi.fn().mockResolvedValue({
        ok: true,
      })

      await expect(projectApi.deleteProject(1)).resolves.toBeUndefined()

      expect(fetch).toHaveBeenCalledWith('/api/v1/projects/1', { method: 'DELETE' })
    })

    it('should throw error on delete failure', async () => {
      global.fetch = vi.fn().mockResolvedValue({
        ok: false,
        statusText: 'Not Found',
      })

      await expect(projectApi.deleteProject(999)).rejects.toThrow(/Failed to delete project/)
    })
  })
})
