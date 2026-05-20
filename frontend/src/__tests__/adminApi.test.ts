/**
 * Tests for adminApi service
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { listUsers, createUser, updateUserRole, deleteUser } from '../services/adminApi'

// Mock the apiClient functions
vi.mock('../services/apiClient', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
}))

// Import mocked module after mocking
const { apiGet, apiPost, apiPut, apiDelete } = await import('../services/apiClient')

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

  describe('updateUserRole', () => {
    it('should call PUT /api/v1/admin/users/{id}/role with new role', async () => {
      const mockUpdated = { id: 2, username: 'user_two', email: 'u@test.com', role: 'ADMIN' as const, createdAt: '' }
      vi.mocked(apiPut).mockResolvedValue(mockUpdated)

      const result = await updateUserRole(2, { role: 'ADMIN' })

      expect(apiPut).toHaveBeenCalledWith('/api/v1/admin/users/2/role', { role: 'ADMIN' })
      expect(result).toEqual(mockUpdated)
    })

    it('should change role to USER correctly', async () => {
      vi.mocked(apiPut).mockResolvedValue({ id: 1, username: 'admin_one', email: 'a@test.com', role: 'USER' as const, createdAt: '' })

      await updateUserRole(1, { role: 'USER' })

      expect(apiPut).toHaveBeenCalledWith('/api/v1/admin/users/1/role', { role: 'USER' })
    })
  })

  describe('deleteUser', () => {
    it('should call DELETE /api/v1/admin/users/{id}', async () => {
      vi.mocked(apiDelete).mockResolvedValue(undefined)

      await deleteUser(5)

      expect(apiDelete).toHaveBeenCalledWith('/api/v1/admin/users/5')
    })

    it('should return void on success', async () => {
      vi.mocked(apiDelete).mockResolvedValue(undefined)

      const result = await deleteUser(3)

      expect(result).toBeUndefined()
    })
  })
})
