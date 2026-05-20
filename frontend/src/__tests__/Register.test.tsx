/**
 * Tests for Register page component
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import Register from '../pages/Register'
import { AuthProvider } from '../contexts/AuthContext'
import * as authApi from '../services/authApi'

// Mock the auth API
vi.mock('../services/authApi', () => ({
  login: vi.fn(),
  register: vi.fn(),
}))

function renderRegister() {
  return render(
    <AuthProvider>
      <MemoryRouter initialEntries={['/register']}>
        <Routes>
          <Route path="/register" element={<Register />} />
          <Route path="/login" element={<div data-testid="login-page">Login Page</div>} />
        </Routes>
      </MemoryRouter>
    </AuthProvider>,
  )
}

describe('Register', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
  })

  describe('UI rendering', () => {
    it('should render registration form with all fields', async () => {
      renderRegister()

      await waitFor(() => {
        expect(screen.getByLabelText('Username')).toBeInTheDocument()
      })

      expect(screen.getByText('Create Account')).toBeInTheDocument()
      expect(screen.getByLabelText('Email')).toBeInTheDocument()
      expect(screen.getByLabelText('Password')).toBeInTheDocument()
      expect(screen.getByTestId('register-submit')).toHaveTextContent('Register')
    })

    it('should show link to login page', async () => {
      renderRegister()

      await waitFor(() => {
        const loginLink = screen.getByRole('link', { name: /login here/i })
        expect(loginLink).toBeInTheDocument()
        expect(loginLink).toHaveAttribute('href', '/login')
      })
    })
  })

  describe('client-side validation', () => {
    it('should show error for username shorter than 3 characters', async () => {
      renderRegister()

      const usernameInput = screen.getByTestId('register-username')
      const passwordInput = screen.getByTestId('register-password')

      await userEvent.type(usernameInput, 'ab') // too short
      await userEvent.type(screen.getByLabelText('Email'), 'test@example.com')
      await userEvent.type(passwordInput, 'password123')
      const form = screen.getByTestId('register-form') as HTMLFormElement
      fireEvent.submit(form)

      expect(screen.getByTestId('register-username-error')).toBeInTheDocument()
      expect(screen.getByTestId('register-username-error')).toHaveTextContent(/at least 3 characters/i)
    })

    it('should show error for invalid email format', async () => {
      renderRegister()

      const usernameInput = screen.getByTestId('register-username')
      const emailInput = screen.getByLabelText('Email')
      const passwordInput = screen.getByTestId('register-password')

      await userEvent.type(usernameInput, 'testuser')
      await userEvent.type(emailInput, 'not-an-email') // invalid format
      await userEvent.type(passwordInput, 'password123')
      const form = screen.getByTestId('register-form') as HTMLFormElement
      fireEvent.submit(form)

      expect(screen.getByTestId('register-email-error')).toBeInTheDocument()
      expect(screen.getByTestId('register-email-error')).toHaveTextContent(/valid email/i)
    })

    it('should show error for password shorter than 8 characters', async () => {
      renderRegister()

      const usernameInput = screen.getByTestId('register-username')
      const emailInput = screen.getByLabelText('Email')
      const passwordInput = screen.getByTestId('register-password')

      await userEvent.type(usernameInput, 'testuser')
      await userEvent.type(emailInput, 'test@example.com')
      await userEvent.type(passwordInput, 'short') // too short
      const form = screen.getByTestId('register-form') as HTMLFormElement
      fireEvent.submit(form)

      expect(screen.getByTestId('register-password-error')).toBeInTheDocument()
      expect(screen.getByTestId('register-password-error')).toHaveTextContent(/at least 8 characters/i)
    })

    it('should show all validation errors at once', async () => {
      renderRegister()

      const usernameInput = screen.getByTestId('register-username')
      const emailInput = screen.getByLabelText('Email')
      const passwordInput = screen.getByTestId('register-password')

      await userEvent.type(usernameInput, 'ab') // too short
      await userEvent.type(emailInput, 'invalid') // invalid format
      await userEvent.type(passwordInput, 'short') // too short
      const form = screen.getByTestId('register-form') as HTMLFormElement
      fireEvent.submit(form)

      expect(screen.getByTestId('register-username-error')).toBeInTheDocument()
      expect(screen.getByTestId('register-email-error')).toBeInTheDocument()
      expect(screen.getByTestId('register-password-error')).toBeInTheDocument()
    })
  })

  describe('form submission', () => {
    it('should call register API and redirect to login on success', async () => {
      vi.mocked(authApi.register).mockResolvedValue({
        id: 2,
        username: 'newuser',
        email: 'new@example.com',
        createdAt: '',
      })

      renderRegister()

      const usernameInput = screen.getByTestId('register-username')
      const emailInput = screen.getByLabelText('Email')
      const passwordInput = screen.getByTestId('register-password')

      await userEvent.type(usernameInput, 'newuser')
      await userEvent.type(emailInput, 'new@example.com')
      await userEvent.type(passwordInput, 'password123')
      const form = screen.getByTestId('register-form') as HTMLFormElement
      fireEvent.submit(form)

      await waitFor(() => {
        expect(screen.getByTestId('login-page')).toBeInTheDocument()
      })

      expect(authApi.register).toHaveBeenCalledWith('newuser', 'new@example.com', 'password123')
    })

    it('should display server error on failed registration', async () => {
      vi.mocked(authApi.register).mockRejectedValue(new Error('Username already exists'))

      renderRegister()

      const usernameInput = screen.getByTestId('register-username')
      const emailInput = screen.getByLabelText('Email')
      const passwordInput = screen.getByTestId('register-password')

      await userEvent.type(usernameInput, 'existinguser')
      await userEvent.type(emailInput, 'new@example.com')
      await userEvent.type(passwordInput, 'password123')
      const form = screen.getByTestId('register-form') as HTMLFormElement
      fireEvent.submit(form)

      await waitFor(() => {
        expect(screen.getByTestId('register-server-error')).toBeInTheDocument()
      })

      expect(screen.getByTestId('register-server-error')).toHaveTextContent('Username already exists')
    })
  })

  describe('navigation', () => {
    it('should navigate to login page when clicking login link', async () => {
      renderRegister()

      await waitFor(() => {
        const loginLink = screen.getByRole('link', { name: /login here/i })
        expect(loginLink).toBeInTheDocument()
      })

      const loginLink = screen.getByRole('link', { name: /login here/i })
      await userEvent.click(loginLink)

      expect(screen.getByTestId('login-page')).toBeInTheDocument()
    })
  })
})
