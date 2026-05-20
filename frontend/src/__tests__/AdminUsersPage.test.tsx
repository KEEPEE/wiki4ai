/**
 * Tests for AdminUsersPage component
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import AdminUsersPage from '../pages/AdminUsersPage'
import { AuthProvider, useAuth } from '../contexts/AuthContext'

// Mock the admin API
vi.mock('../services/adminApi', () => ({
  listUsers: vi.fn(),
  createUser: vi.fn(),
}))

const mockAdminApi = await import('../services/adminApi')

/** Create a fake JWT that won't expire for 1 hour */
function createFakeJwt(): string {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))
  const futureExp = Math.floor(Date.now() / 1000) + 3600 // 1 hour from now
  const payload = btoa(JSON.stringify({ sub: '1', exp: futureExp, iat: Date.now() / 1000 }))
  const signature = btoa('fake-signature')
  return `${header}.${payload}.${signature}`
}

/** Test wrapper that waits for AuthProvider to finish loading from localStorage */
function WaitForAuth({ children }: { children: React.ReactNode }) {
  const { isLoading } = useAuth()
  if (isLoading) return <div data-testid="auth-loading">Loading auth...</div>
  return <>{children}</>
}

function renderAdminPage(userRole: 'ADMIN' | 'USER' = 'ADMIN') {
  // Pre-set localStorage with a valid fake JWT so AuthContext doesn't clear the state
  localStorage.setItem('wiki4ai_access_token', createFakeJwt())
  localStorage.setItem('wiki4ai_refresh_token', 'fake-refresh-token')
  localStorage.setItem('wiki4ai_user_info', JSON.stringify({
    id: 1,
    username: 'test_admin',
    email: 'admin@test.com',
    role: userRole,
    createdAt: '',
  }))

  return render(
    <AuthProvider>
      <MemoryRouter initialEntries={['/admin/users']}>
        <Routes>
          <Route path="/admin/users" element={
            <WaitForAuth><AdminUsersPage /></WaitForAuth>
          } />
          <Route path="/login" element={<div data-testid="login-page">Login Page</div>} />
        </Routes>
      </MemoryRouter>
    </AuthProvider>,
  )
}

describe('AdminUsersPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
  })

  describe('access control', () => {
    it('should show access denied for non-admin users', async () => {
      renderAdminPage('USER')

      await waitFor(() => {
        expect(screen.queryByTestId('auth-loading')).not.toBeInTheDocument()
      }, { timeout: 3000 })

      await waitFor(() => {
        expect(screen.getByTestId('access-denied')).toBeInTheDocument()
      }, { timeout: 3000 })

      expect(screen.getByText('403 - Access Denied')).toBeInTheDocument()
    })

    it('should render admin page for admin users', async () => {
      vi.mocked(mockAdminApi.listUsers).mockResolvedValue({
        content: [], totalElements: 0, totalPages: 0, number: 0, size: 20,
      })

      renderAdminPage('ADMIN')

      await waitFor(() => {
        expect(screen.queryByTestId('auth-loading')).not.toBeInTheDocument()
      }, { timeout: 3000 })

      await waitFor(() => {
        expect(screen.getByText('User Management')).toBeInTheDocument()
      }, { timeout: 3000 })
    })
  })

  describe('users list', () => {
    const mockUsersList = [
      { id: 1, username: 'admin_one', email: 'admin1@test.com', role: 'ADMIN' as const, createdAt: '2026-01-01T00:00:00' },
      { id: 2, username: 'user_two', email: 'user2@test.com', role: 'USER' as const, createdAt: '2026-02-01T00:00:00' },
    ]

    it('should render users table with usernames and roles', async () => {
      vi.mocked(mockAdminApi.listUsers).mockResolvedValue({
        content: mockUsersList, totalElements: 2, totalPages: 1, number: 0, size: 20,
      })

      renderAdminPage('ADMIN')

      await waitFor(() => {
        expect(screen.queryByTestId('auth-loading')).not.toBeInTheDocument()
      }, { timeout: 3000 })

      await waitFor(() => {
        expect(screen.getByTestId('users-table')).toBeInTheDocument()
      }, { timeout: 3000 })

      expect(screen.getByText('admin_one')).toBeInTheDocument()
      expect(screen.getByText('user_two')).toBeInTheDocument()
    })

    it('should show user count in section title', async () => {
      vi.mocked(mockAdminApi.listUsers).mockResolvedValue({
        content: mockUsersList, totalElements: 2, totalPages: 1, number: 0, size: 20,
      })

      renderAdminPage('ADMIN')

      await waitFor(() => {
        expect(screen.queryByTestId('auth-loading')).not.toBeInTheDocument()
      }, { timeout: 3000 })

      await waitFor(() => {
        expect(screen.getByText('All Users (2)')).toBeInTheDocument()
      }, { timeout: 3000 })
    })

    it('should show error when user list fetch fails', async () => {
      vi.mocked(mockAdminApi.listUsers).mockRejectedValue(new Error('Network error'))

      renderAdminPage('ADMIN')

      await waitFor(() => {
        expect(screen.queryByTestId('auth-loading')).not.toBeInTheDocument()
      }, { timeout: 3000 })

      await waitFor(() => {
        expect(screen.getByTestId('load-error')).toBeInTheDocument()
      }, { timeout: 3000 })

      expect(screen.getByTestId('load-error')).toHaveTextContent('Network error')
    })
  })

  describe('create user', () => {
    it('should render create form with all fields', async () => {
      vi.mocked(mockAdminApi.listUsers).mockResolvedValue({
        content: [], totalElements: 0, totalPages: 0, number: 0, size: 20,
      })

      renderAdminPage('ADMIN')

      await waitFor(() => {
        expect(screen.queryByTestId('auth-loading')).not.toBeInTheDocument()
      }, { timeout: 3000 })

      await waitFor(() => {
        expect(screen.getByTestId('create-username')).toBeInTheDocument()
      }, { timeout: 3000 })

      expect(screen.getByTestId('create-email')).toBeInTheDocument()
      expect(screen.getByTestId('create-password')).toBeInTheDocument()
      expect(screen.getByTestId('create-role')).toBeInTheDocument()
    })

    it('should call createUser API with all fields including role', async () => {
      vi.mocked(mockAdminApi.listUsers).mockResolvedValue({
        content: [], totalElements: 0, totalPages: 0, number: 0, size: 20,
      })
      vi.mocked(mockAdminApi.createUser).mockResolvedValue({
        id: 3, username: 'newuser', email: 'new@test.com', role: 'USER', createdAt: '',
      })

      renderAdminPage('ADMIN')

      await waitFor(() => {
        expect(screen.queryByTestId('auth-loading')).not.toBeInTheDocument()
      }, { timeout: 3000 })

      const usernameInput = screen.getByTestId('create-username')
      const emailInput = screen.getByTestId('create-email')
      const passwordInput = screen.getByTestId('create-password')
      const roleSelect = screen.getByTestId('create-role') as HTMLSelectElement
      const submitBtn = screen.getByTestId('create-user-btn')

      await userEvent.type(usernameInput, 'newuser')
      await userEvent.type(emailInput, 'new@test.com')
      await userEvent.type(passwordInput, 'securepass123')
      await userEvent.selectOptions(roleSelect, 'ADMIN')
      await userEvent.click(submitBtn)

      await waitFor(() => {
        expect(mockAdminApi.createUser).toHaveBeenCalledWith(
          expect.objectContaining({
            username: 'newuser',
            email: 'new@test.com',
            password: 'securepass123',
            role: 'ADMIN',
          }),
        )
      })
    })

    it('should show success message and reload users after creation', async () => {
      vi.mocked(mockAdminApi.listUsers)
        .mockResolvedValueOnce({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 })
        .mockResolvedValueOnce({
          content: [{ id: 3, username: 'newuser', email: 'new@test.com', role: 'USER', createdAt: '' }],
          totalElements: 1, totalPages: 1, number: 0, size: 20,
        })

      vi.mocked(mockAdminApi.createUser).mockResolvedValue({
        id: 3, username: 'newuser', email: 'new@test.com', role: 'USER', createdAt: '',
      })

      renderAdminPage('ADMIN')

      await waitFor(() => {
        expect(screen.queryByTestId('auth-loading')).not.toBeInTheDocument()
      }, { timeout: 3000 })

      const usernameInput = screen.getByTestId('create-username')
      const emailInput = screen.getByTestId('create-email')
      const passwordInput = screen.getByTestId('create-password')
      const submitBtn = screen.getByTestId('create-user-btn')

      await userEvent.type(usernameInput, 'newuser')
      await userEvent.type(emailInput, 'new@test.com')
      await userEvent.type(passwordInput, 'securepass123')
      await userEvent.click(submitBtn)

      await waitFor(() => {
        expect(screen.getByTestId('create-success')).toHaveTextContent('User created successfully')
      })

      // Verify list was reloaded (called twice: initial + after create)
      expect(mockAdminApi.listUsers).toHaveBeenCalledTimes(2)
    })

    it('should show error message on creation failure', async () => {
      vi.mocked(mockAdminApi.listUsers).mockResolvedValue({
        content: [], totalElements: 0, totalPages: 0, number: 0, size: 20,
      })
      vi.mocked(mockAdminApi.createUser).mockRejectedValue(new Error('Username is already taken'))

      renderAdminPage('ADMIN')

      await waitFor(() => {
        expect(screen.queryByTestId('auth-loading')).not.toBeInTheDocument()
      }, { timeout: 3000 })

      const usernameInput = screen.getByTestId('create-username')
      const emailInput = screen.getByTestId('create-email')
      const passwordInput = screen.getByTestId('create-password')
      const submitBtn = screen.getByTestId('create-user-btn')

      await userEvent.type(usernameInput, 'existing_user')
      await userEvent.type(emailInput, 'exist@test.com')
      await userEvent.type(passwordInput, 'securepass123')
      await userEvent.click(submitBtn)

      await waitFor(() => {
        expect(screen.getByTestId('create-error')).toHaveTextContent('Username is already taken')
      })
    })
  })
})
