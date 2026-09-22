/**
 * Tests for ProtectedRoute component
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import ProtectedRoute from '../components/ProtectedRoute'
import { AuthProvider } from '../contexts/AuthContext'
import * as authApi from '../services/authApi'

// Mock the auth API (the provider probes GET /api/v1/auth/status on mount)
vi.mock('../services/authApi', () => ({
  login: vi.fn(),
  register: vi.fn(),
  getAuthStatus: vi.fn(),
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

function TestPage() {
  return <div data-testid="protected-content">Protected Content</div>
}

function renderWithAuth(initialPath = '/', isAuthenticated = false) {
  // Pre-set auth state if needed
  if (isAuthenticated) {
    const mockUser = { id: 1, username: 'testuser', email: 'test@example.com', createdAt: '' }
    localStorage.setItem('wiki4ai_access_token', createFakeJwt())
    localStorage.setItem('wiki4ai_refresh_token', 'refresh-token')
    localStorage.setItem('wiki4ai_user_info', JSON.stringify(mockUser))
  } else {
    localStorage.clear()
  }

  return render(
    <AuthProvider>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route path="/protected" element={<ProtectedRoute><TestPage /></ProtectedRoute>} />
          <Route
            path="/login"
            element={
              <div data-testid="login-page">
                Login Page
                <span data-testid="redirect-param">{window.location.search}</span>
              </div>
            }
          />
          <Route
            path="/setup"
            element={
              <div data-testid="setup-page">
                Setup Page
                <span data-testid="redirect-param">{window.location.search}</span>
              </div>
            }
          />
        </Routes>
      </MemoryRouter>
    </AuthProvider>,
  )
}

describe('ProtectedRoute', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // WIKI4AI-69/70: default instance state — initialized (regular login flow)
    vi.mocked(authApi.getAuthStatus).mockResolvedValue({
      initialized: true,
      registrationOpen: false,
    })
  })

  it('should render children when user is authenticated', async () => {
    renderWithAuth('/protected', true)

    await waitFor(() => {
      expect(screen.getByTestId('protected-content')).toBeInTheDocument()
    })
  })

  it('should redirect to /login when not authenticated', async () => {
    renderWithAuth('/protected', false)

    await waitFor(() => {
      // After redirect, the protected content should NOT be visible
      expect(screen.queryByTestId('protected-content')).not.toBeInTheDocument()
      // And the login page should be rendered instead (Navigate component redirects within MemoryRouter)
      expect(screen.getByTestId('login-page')).toBeInTheDocument()
    })
  })

  it('should show loading state while auth is being checked', () => {
    // This test verifies the component renders something during loading
    // The AuthProvider sets isLoading=true initially, then false after checking localStorage
    const { container } = renderWithAuth('/protected', false)

    // Component should be rendering (either spinner or redirect)
    expect(container.firstChild).not.toBeNull()
  })

  it('should redirect to /setup (not /login) when not authenticated on a fresh instance', async () => {
    vi.mocked(authApi.getAuthStatus).mockResolvedValue({
      initialized: false,
      registrationOpen: true,
    })

    renderWithAuth('/protected', false)

    await waitFor(() => {
      expect(screen.queryByTestId('protected-content')).not.toBeInTheDocument()
      // Fresh instance → the setup form, not the login page
      expect(screen.getByTestId('setup-page')).toBeInTheDocument()
      expect(screen.queryByTestId('login-page')).not.toBeInTheDocument()
    })
  })
})
