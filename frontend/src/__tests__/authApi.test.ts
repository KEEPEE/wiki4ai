/**
 * Tests for authApi service
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { login, register } from '../services/authApi'

// Mock fetch globally
const mockFetch = vi.fn() as any
window.fetch = mockFetch

describe('authApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('login', () => {
    it('should return auth response on successful login', async () => {
      const mockResponse = {
        accessToken: 'test-access-token',
        refreshToken: 'test-refresh-token',
        user: { id: 1, username: 'testuser', email: 'test@example.com', createdAt: '2026-01-01T00:00:00' },
      }

      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => mockResponse,
      })

      const result = await login('testuser', 'password123')

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: 'testuser', password: 'password123' }),
      })
      expect(result).toEqual(mockResponse)
    })

    it('should throw error with message on failed login', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: false,
        status: 401,
        json: async () => ({ error: 'Invalid username or password' }),
      })

      await expect(login('testuser', 'wrongpassword')).rejects.toThrow('Invalid username or password')
    })

    it('should throw generic error when response has no error body', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: false,
        statusText: 'Internal Server Error',
        json: async () => { throw new Error('No JSON'); },
      })

      await expect(login('testuser', 'password')).rejects.toThrow()
    })
  })

  describe('register', () => {
    it('should return user info on successful registration', async () => {
      const mockResponse = {
        id: 2,
        username: 'newuser',
        email: 'new@example.com',
        createdAt: '2026-05-20T00:00:00',
      }

      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => mockResponse,
      })

      const result = await register('newuser', 'new@example.com', 'password123')

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/auth/register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: 'newuser', email: 'new@example.com', password: 'password123' }),
      })
      expect(result).toEqual(mockResponse)
    })

    it('should throw error on duplicate username/email', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: false,
        status: 409,
        json: async () => ({ error: 'Username already exists' }),
      })

      await expect(register('existing', 'test@example.com', 'password123')).rejects.toThrow('Username already exists')
    })
  })
})
