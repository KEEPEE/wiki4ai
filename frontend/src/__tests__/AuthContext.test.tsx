/**
 * Tests for AuthContext
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AuthProvider, useAuth } from '../contexts/AuthContext'
import * as authApi from '../services/authApi'

// Mock the auth API
vi.mock('../services/authApi', () => ({
  login: vi.fn(),
  register: vi.fn(),
  getAuthStatus: vi.fn().mockResolvedValue({ initialized: true, registrationOpen: false }),
  setup: vi.fn(),
}))

/** Create a fake JWT that won't expire for 1 hour */
function createFakeJwt(): string {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))
  const futureExp = Math.floor(Date.now() / 1000) + 3600 // 1 hour from now
  const payload = btoa(JSON.stringify({ sub: '1', exp: futureExp, iat: Date.now() / 1000 }))
  const signature = btoa('fake-signature')
  return `${header}.${payload}.${signature}`
}

/** Test component that consumes AuthContext */
function TestComponent() {
  const { user, isAuthenticated, isLoading, login, logout } = useAuth()
  return (
    <div>
      <span data-testid="is-authenticated">{String(isAuthenticated)}</span>
      <span data-testid="user-username">{user?.username ?? 'null'}</span>
      <span data-testid="loading-state">{String(isLoading)}</span>
      <button onClick={() => login('test', 'pass')} data-testid="login-btn">Login</button>
      <button onClick={logout} data-testid="logout-btn">Logout</button>
    </div>
  )
}

describe('AuthContext', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // Clear localStorage before each test
    localStorage.clear()
  })

  describe('initial state', () => {
    it('should start with unauthenticated state when no tokens in storage', async () => {
      render(
        <AuthProvider>
          <TestComponent />
        </AuthProvider>,
      )

      await waitFor(() => {
        expect(screen.getByTestId('loading-state')).toHaveTextContent('false')
      })

      expect(screen.getByTestId('is-authenticated')).toHaveTextContent('false')
      expect(screen.getByTestId('user-username')).toHaveTextContent('null')
    })

    it('should restore auth state from localStorage on mount', async () => {
      // Pre-populate localStorage with valid tokens (fake JWT that won't expire)
      const mockUser = { id: 1, username: 'stored_user', email: 'test@example.com', createdAt: '' }
      localStorage.setItem('wiki4ai_access_token', createFakeJwt())
      localStorage.setItem('wiki4ai_refresh_token', 'refresh-token')
      localStorage.setItem('wiki4ai_user_info', JSON.stringify(mockUser))

      render(
        <AuthProvider>
          <TestComponent />
        </AuthProvider>,
      )

      await waitFor(() => {
        expect(screen.getByTestId('loading-state')).toHaveTextContent('false')
      })

      expect(screen.getByTestId('is-authenticated')).toHaveTextContent('true')
      expect(screen.getByTestId('user-username')).toHaveTextContent('stored_user')
    })
  })

  describe('login', () => {
    it('should authenticate user on successful login', async () => {
      const mockAuthResponse = {
        accessToken: createFakeJwt(),
        refreshToken: 'new-refresh-token',
        user: { id: 1, username: 'testuser', email: 'test@example.com', createdAt: '' },
      }
      vi.mocked(authApi.login).mockResolvedValue(mockAuthResponse)

      render(
        <AuthProvider>
          <TestComponent />
        </AuthProvider>,
      )

      // Wait for initial loading to complete
      await waitFor(() => {
        expect(screen.getByTestId('loading-state')).toHaveTextContent('false')
      })

      const loginBtn = screen.getByTestId('login-btn')
      await userEvent.click(loginBtn)

      await waitFor(() => {
        expect(screen.getByTestId('is-authenticated')).toHaveTextContent('true')
      })

      expect(screen.getByTestId('user-username')).toHaveTextContent('testuser')
      expect(authApi.login).toHaveBeenCalledWith('test', 'pass')
    })

    it('should throw error on failed login', async () => {
      vi.mocked(authApi.login).mockRejectedValue(new Error('Invalid credentials'))

      // Component that catches and displays errors
      function ErrorTestComponent() {
        const { login, isAuthenticated } = useAuth()
        return (
          <div>
            <span data-testid="is-authenticated">{String(isAuthenticated)}</span>
            <button onClick={async () => { try { await login('test', 'pass') } catch (e: any) { document.getElementById('error-display')!.textContent = e.message } }} data-testid="login-btn">Login</button>
            <span id="error-display" data-testid="error-display"></span>
          </div>
        )
      }

      render(
        <AuthProvider>
          <ErrorTestComponent />
        </AuthProvider>,
      )

      await waitFor(() => {
        expect(screen.getByTestId('is-authenticated')).toBeInTheDocument()
      })

      const loginBtn = screen.getByTestId('login-btn')
      await userEvent.click(loginBtn)

      await waitFor(() => {
        expect(screen.getByTestId('error-display')).toHaveTextContent('Invalid credentials')
      })
    })
  })

  describe('logout', () => {
    it('should clear auth state on logout', async () => {
      // Pre-populate localStorage with valid tokens
      const mockUser = { id: 1, username: 'testuser', email: 'test@example.com', createdAt: '' }
      localStorage.setItem('wiki4ai_access_token', createFakeJwt())
      localStorage.setItem('wiki4ai_refresh_token', 'refresh')
      localStorage.setItem('wiki4ai_user_info', JSON.stringify(mockUser))

      render(
        <AuthProvider>
          <TestComponent />
        </AuthProvider>,
      )

      await waitFor(() => {
        expect(screen.getByTestId('is-authenticated')).toHaveTextContent('true')
      })

      const logoutBtn = screen.getByTestId('logout-btn')
      await userEvent.click(logoutBtn)

      expect(screen.getByTestId('is-authenticated')).toHaveTextContent('false')
      expect(screen.getByTestId('user-username')).toHaveTextContent('null')
      expect(localStorage.getItem('wiki4ai_access_token')).toBeNull()
    })
  })

  describe('useAuth outside provider', () => {
    it('should throw error when used outside AuthProvider', () => {
      const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})

      expect(() => {
        render(<TestComponent />)
      }).toThrow('useAuth must be used within an AuthProvider')

      consoleError.mockRestore()
    })
  })
})
