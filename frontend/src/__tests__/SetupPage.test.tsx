/**
 * WIKI4AI-69: tests for the first-run SetupPage component.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import SetupPage from '../pages/SetupPage'
import { AuthProvider } from '../contexts/AuthContext'
import * as authApi from '../services/authApi'

// Mock the auth API
vi.mock('../services/authApi', () => ({
  login: vi.fn(),
  register: vi.fn(),
  getAuthStatus: vi.fn(),
  setup: vi.fn(),
}))

function renderSetup() {
  return render(
    <AuthProvider>
      <MemoryRouter initialEntries={['/setup']}>
        <Routes>
          <Route path="/setup" element={<SetupPage />} />
          <Route path="/login" element={<div data-testid="login-page">Login Page</div>} />
          <Route path="/" element={<div data-testid="dashboard-page">Dashboard</div>} />
        </Routes>
      </MemoryRouter>
    </AuthProvider>,
  )
}

describe('SetupPage (WIKI4AI-69)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    // Fresh instance by default — the setup form is shown.
    vi.mocked(authApi.getAuthStatus).mockResolvedValue({
      initialized: false,
      registrationOpen: true,
    })
  })

  describe('UI rendering', () => {
    it('should render the first-run setup form on a fresh instance', async () => {
      renderSetup()

      await waitFor(() => {
        expect(screen.getByTestId('setup-form')).toBeInTheDocument()
      })

      expect(screen.getByText('First-time setup')).toBeInTheDocument()
      expect(screen.getByTestId('setup-subtitle')).toHaveTextContent(/administrator/i)
      expect(screen.getByTestId('setup-username')).toBeInTheDocument()
      expect(screen.getByTestId('setup-password')).toBeInTheDocument()
      expect(screen.getByTestId('setup-confirm')).toBeInTheDocument()
      expect(screen.getByTestId('setup-submit')).toHaveTextContent('Create admin account')
    })

    it('should redirect to /login when the instance is already initialized', async () => {
      vi.mocked(authApi.getAuthStatus).mockResolvedValue({
        initialized: true,
        registrationOpen: false,
      })

      renderSetup()

      await waitFor(() => {
        expect(screen.getByTestId('login-page')).toBeInTheDocument()
      })
      expect(screen.queryByTestId('setup-form')).not.toBeInTheDocument()
    })
  })

  describe('client-side validation', () => {
    it('should show error for username shorter than 2 characters', async () => {
      renderSetup()
      await waitFor(() => expect(screen.getByTestId('setup-form')).toBeInTheDocument())

      await userEvent.type(screen.getByTestId('setup-username'), 'a') // too short
      await userEvent.type(screen.getByTestId('setup-password'), 'password123')
      await userEvent.type(screen.getByTestId('setup-confirm'), 'password123')
      fireEvent.submit(screen.getByTestId('setup-form') as HTMLFormElement)

      expect(screen.getByTestId('setup-username-error')).toHaveTextContent(/at least 2 characters/i)
    })

    it('should show error for password shorter than 8 characters', async () => {
      renderSetup()
      await waitFor(() => expect(screen.getByTestId('setup-form')).toBeInTheDocument())

      await userEvent.type(screen.getByTestId('setup-username'), 'adminuser')
      await userEvent.type(screen.getByTestId('setup-password'), 'short')
      await userEvent.type(screen.getByTestId('setup-confirm'), 'short')
      fireEvent.submit(screen.getByTestId('setup-form') as HTMLFormElement)

      expect(screen.getByTestId('setup-password-error')).toHaveTextContent(/at least 8 characters/i)
    })

    it('should show error when passwords do not match', async () => {
      renderSetup()
      await waitFor(() => expect(screen.getByTestId('setup-form')).toBeInTheDocument())

      await userEvent.type(screen.getByTestId('setup-username'), 'adminuser')
      await userEvent.type(screen.getByTestId('setup-password'), 'password123')
      await userEvent.type(screen.getByTestId('setup-confirm'), 'different123')
      fireEvent.submit(screen.getByTestId('setup-form') as HTMLFormElement)

      expect(screen.getByTestId('setup-confirm-error')).toHaveTextContent(/do not match/i)
    })
  })

  describe('form submission', () => {
    it('should call setup API and redirect to /login on success', async () => {
      vi.mocked(authApi.setup).mockResolvedValue({
        id: 1,
        username: 'adminuser',
        email: 'adminuser@localhost',
        role: 'ADMIN',
        createdAt: '',
      })

      renderSetup()
      await waitFor(() => expect(screen.getByTestId('setup-form')).toBeInTheDocument())

      await userEvent.type(screen.getByTestId('setup-username'), 'adminuser')
      await userEvent.type(screen.getByTestId('setup-password'), 'password123')
      await userEvent.type(screen.getByTestId('setup-confirm'), 'password123')
      fireEvent.submit(screen.getByTestId('setup-form') as HTMLFormElement)

      await waitFor(() => {
        expect(screen.getByTestId('login-page')).toBeInTheDocument()
      })
      expect(authApi.setup).toHaveBeenCalledWith('adminuser', 'password123')
    })

    it('should display server error when setup is rejected (already initialized)', async () => {
      vi.mocked(authApi.setup).mockRejectedValue(new Error('Setup already completed'))

      renderSetup()
      await waitFor(() => expect(screen.getByTestId('setup-form')).toBeInTheDocument())

      await userEvent.type(screen.getByTestId('setup-username'), 'adminuser')
      await userEvent.type(screen.getByTestId('setup-password'), 'password123')
      await userEvent.type(screen.getByTestId('setup-confirm'), 'password123')
      fireEvent.submit(screen.getByTestId('setup-form') as HTMLFormElement)

      await waitFor(() => {
        expect(screen.getByTestId('setup-error')).toBeInTheDocument()
      })
      expect(screen.getByTestId('setup-error')).toHaveTextContent('Setup already completed')
    })
  })
})
