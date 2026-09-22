/**
 * Tests for Login page component
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import Login from '../pages/Login'
import { AuthProvider } from '../contexts/AuthContext'
import * as authApi from '../services/authApi'

// Mock the auth API
vi.mock('../services/authApi', () => ({
  login: vi.fn(),
  register: vi.fn(),
  getAuthStatus: vi.fn(),
  setup: vi.fn(),
}))

function renderLogin() {
  return render(
    <AuthProvider>
      <MemoryRouter initialEntries={['/login']}>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/register" element={<div data-testid="register-page">Register Page</div>} />
          <Route path="/setup" element={<div data-testid="setup-page">Setup Page</div>} />
          <Route path="/" element={<div data-testid="dashboard-page">Dashboard</div>} />
        </Routes>
      </MemoryRouter>
    </AuthProvider>,
  )
}

describe('Login', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    // WIKI4AI-69/70: default instance state — initialized with registration open,
    // so the register link renders like before this feature.
    vi.mocked(authApi.getAuthStatus).mockResolvedValue({
      initialized: true,
      registrationOpen: true,
    })
  })

  describe('UI rendering', () => {
    it('should render login form with username and password fields', async () => {
      renderLogin()

      await waitFor(() => {
        expect(screen.getByLabelText('Username')).toBeInTheDocument()
      })

      expect(screen.getByText('Login to Wiki4AI')).toBeInTheDocument()
      expect(screen.getByLabelText('Password')).toBeInTheDocument()
      expect(screen.getByTestId('login-submit')).toHaveTextContent('Login')
    })

    it('should show link to register page', async () => {
      renderLogin()

      await waitFor(() => {
        const registerLink = screen.getByRole('link', { name: /register here/i })
        expect(registerLink).toBeInTheDocument()
        expect(registerLink).toHaveAttribute('href', '/register')
      })
    })
  })

  describe('form submission', () => {
    it('should call login API and redirect on success', async () => {
      const mockAuthResponse = {
        accessToken: 'token',
        refreshToken: 'refresh',
        user: { id: 1, username: 'testuser', email: 'test@example.com', createdAt: '' },
      }
      vi.mocked(authApi.login).mockResolvedValue(mockAuthResponse)

      render(
        <AuthProvider>
          <MemoryRouter initialEntries={['/login']}>
            <Routes>
              <Route path="/login" element={<Login />} />
              <Route path="/" element={<div data-testid="dashboard-page">Dashboard</div>} />
            </Routes>
          </MemoryRouter>
        </AuthProvider>,
      )

      await waitFor(() => {
        expect(screen.getByTestId('login-username')).toBeInTheDocument()
      })
      const usernameInput = screen.getByTestId('login-username')
      const passwordInput = screen.getByTestId('login-password')

      await userEvent.type(usernameInput, 'testuser')
      await userEvent.type(passwordInput, 'password123')
      const form = screen.getByTestId('login-form') as HTMLFormElement
      fireEvent.submit(form)

      await waitFor(() => {
        expect(screen.getByTestId('dashboard-page')).toBeInTheDocument()
      })

      expect(authApi.login).toHaveBeenCalledWith('testuser', 'password123')
    })

    it('should display error message on failed login', async () => {
      vi.mocked(authApi.login).mockRejectedValue(new Error('Invalid username or password'))

      renderLogin()

      await waitFor(() => {
        expect(screen.getByTestId('login-username')).toBeInTheDocument()
      })
      const usernameInput = screen.getByTestId('login-username')
      const passwordInput = screen.getByTestId('login-password')
      const submitBtn = screen.getByTestId('login-submit')

      await userEvent.type(usernameInput, 'wronguser')
      await userEvent.type(passwordInput, 'wrongpass')
      await userEvent.click(submitBtn)

      await waitFor(() => {
        expect(screen.getByTestId('login-error')).toBeInTheDocument()
      })

      expect(screen.getByTestId('login-error')).toHaveTextContent('Invalid username or password')
    })

    it('should disable submit button while loading', async () => {
      // Mock a slow login
      vi.mocked(authApi.login).mockImplementation(
        () => new Promise((resolve) => setTimeout(() => resolve({ accessToken: 't', refreshToken: 'r', user: { id: 1, username: 'u', email: '', createdAt: '' } }), 200)),
      )

      renderLogin()

      await waitFor(() => {
        expect(screen.getByTestId('login-username')).toBeInTheDocument()
      })
      const usernameInput = screen.getByTestId('login-username')
      const passwordInput = screen.getByTestId('login-password')
      const submitBtn = screen.getByTestId('login-submit')

      await userEvent.type(usernameInput, 'testuser')
      await userEvent.type(passwordInput, 'password123')
      await userEvent.click(submitBtn)

      expect(screen.getByText('Logging in...')).toBeInTheDocument()
    })
  })

  describe('navigation', () => {
    it('should navigate to register page when clicking register link', async () => {
      renderLogin()

      await waitFor(() => {
        const registerLink = screen.getByRole('link', { name: /register here/i })
        expect(registerLink).toBeInTheDocument()
      })

      const registerLink = screen.getByRole('link', { name: /register here/i })
      await userEvent.click(registerLink)

      expect(screen.getByTestId('register-page')).toBeInTheDocument()
    })
  })

  describe('WIKI4AI-69/70: instance state', () => {
    it('should redirect to /setup on a fresh (uninitialized) instance', async () => {
      vi.mocked(authApi.getAuthStatus).mockResolvedValue({
        initialized: false,
        registrationOpen: true,
      })

      renderLogin()

      await waitFor(() => {
        expect(screen.getByTestId('setup-page')).toBeInTheDocument()
      })
      // The login form itself must not be shown on a fresh instance
      expect(screen.queryByTestId('login-form')).not.toBeInTheDocument()
    })

    it('should hide the register link when registration is closed', async () => {
      vi.mocked(authApi.getAuthStatus).mockResolvedValue({
        initialized: true,
        registrationOpen: false,
      })

      renderLogin()

      await waitFor(() => {
        expect(screen.getByTestId('login-form')).toBeInTheDocument()
      })
      expect(
        screen.queryByRole('link', { name: /register here/i }),
      ).not.toBeInTheDocument()
    })
  })
})
