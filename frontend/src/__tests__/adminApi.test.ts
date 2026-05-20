/**
 * Tests for adminApi service
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { listUsers, createUser } from '../services/adminApi'

// Mock the apiClient functions
vi.mock('../services/apiClient', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
}))

// Import mocked module after mocking
const { apiGet, apiPost } = await import('../services/apiClient')

describe('adminApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('listUsers', () => {
    it('should call GET /api/v1/admin/users with pagination params', async () => {
      const mockResponse = {
        content: [
          { id: 1, username: 'admin', email: 'a@test.com', role: 'ADMIN' as const, createdAt: '2026-01-01' },
        ],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 20,
      }
      vi.mocked(apiGet).mockResolvedValue(mockResponse)

      const result = await listUsers(0, 20)

      expect(apiGet).toHaveBeenCalledWith('/api/v1/admin/users?page=0&size=20')
      expect(result).toEqual(mockResponse)
    })

    it('should include search param when provided', async () => {
      vi.mocked(apiGet).mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 })

      await listUsers(0, 10, 'john')

      expect(apiGet).toHaveBeenCalledWith('/api/v1/admin/users?page=0&size=10&search=john')
    })
  })

  describe('createUser', () => {
    it('should call POST /api/v1/admin/users with user data', async () => {
      const mockCreated = { id: 3, username: 'newuser', email: 'new@test.com', role: 'USER' as const, createdAt: '' }
      vi.mocked(apiPost).mockResolvedValue(mockCreated)

      const result = await createUser({
        username: 'newuser',
        email: 'new@test.com',
        password: 'password123',
        role: 'USER',
      })

      expect(apiPost).toHaveBeenCalledWith('/api/v1/admin/users', {
        username: 'newuser',
        email: 'new@test.com',
        password: 'password123',
        role: 'USER',
      })
      expect(result).toEqual(mockCreated)
    })

    it('should pass ADMIN role correctly', async () => {
      vi.mocked(apiPost).mockResolvedValue({ id: 4, username: 'newadmin', email: 'a@test.com', role: 'ADMIN' as const, createdAt: '' })

      await createUser({
        username: 'newadmin',
        email: 'a@test.com',
        password: 'password123',
        role: 'ADMIN',
      })

      expect(apiPost).toHaveBeenCalledWith(
        '/api/v1/admin/users',
        expect.objectContaining({ role: 'ADMIN' }),
      )
    })
  })
})
