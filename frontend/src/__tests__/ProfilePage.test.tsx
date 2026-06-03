/**
 * Tests for ProfilePage component
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import ProfilePage from '../pages/ProfilePage'
import { AuthProvider } from '../contexts/AuthContext'
import * as apiClient from '../services/apiClient'

// Mock the API client
vi.mock('../services/apiClient', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
  getRedirectFromUrl: vi.fn(() => null),
}))

const mockProfile = {
  id: 1,
  username: 'testuser',
  email: 'test@example.com',
  role: 'USER',
  createdAt: '2026-01-01T00:00:00',
}

function renderProfilePage() {
  return render(
    <AuthProvider>
      <MemoryRouter initialEntries={['/profile']}>
        <Routes>
          <Route path="/profile" element={<ProfilePage />} />
          <Route path="/login" element={<div data-testid="login-page">Login Page</div>} />
        </Routes>
      </MemoryRouter>
    </AuthProvider>,
  )
}

describe('ProfilePage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
  })

  describe('profile display', () => {
    it('should render profile information (username, email, role)', async () => {
      vi.mocked(apiClient.apiGet).mockResolvedValue(mockProfile)

      renderProfilePage()

      await waitFor(() => {
        expect(screen.getByTestId('profile-username')).toHaveTextContent('testuser')
      })

      expect(screen.getByTestId('profile-email')).toHaveTextContent('test@example.com')
      expect(screen.getByTestId('profile-role')).toHaveTextContent('USER')
    })

    it('should show loading state while fetching profile', async () => {
      // Slow response
      vi.mocked(apiClient.apiGet).mockImplementation(
        () => new Promise((resolve) => setTimeout(() => resolve(mockProfile), 200)),
      )

      renderProfilePage()

      expect(screen.getByText('Loading profile...')).toBeInTheDocument()
    })

    it('should show error when profile fetch fails', async () => {
      vi.mocked(apiClient.apiGet).mockRejectedValue(new Error('Network error'))

      renderProfilePage()

      await waitFor(() => {
        expect(screen.getByTestId('update-error')).toHaveTextContent('Failed to load profile')
      })
    })
  })

  describe('edit profile', () => {
    it('should render edit form with username, email, and password fields', async () => {
      vi.mocked(apiClient.apiGet).mockResolvedValue(mockProfile)

      renderProfilePage()

      await waitFor(() => {
        expect(screen.getByTestId('edit-username')).toBeInTheDocument()
      })

      expect(screen.getByTestId('edit-email')).toBeInTheDocument()
      expect(screen.getByTestId('edit-current-password')).toBeInTheDocument()
      expect(screen.getByTestId('edit-new-password')).toBeInTheDocument()
      expect(screen.getByTestId('update-profile-btn')).toHaveTextContent('Save Changes')
    })

    it('should send update request and show success message', async () => {
      vi.mocked(apiClient.apiGet).mockResolvedValue(mockProfile)
      const updatedProfile = { ...mockProfile, email: 'newemail@example.com' }
      vi.mocked(apiClient.apiPut).mockResolvedValue(updatedProfile)

      renderProfilePage()

      await waitFor(() => {
        expect(screen.getByTestId('profile-email')).toHaveTextContent('test@example.com')
      })

      const emailInput = screen.getByTestId('edit-email')
      await userEvent.clear(emailInput)
      await userEvent.type(emailInput, 'newemail@example.com')

      const submitBtn = screen.getByTestId('update-profile-btn')
      await userEvent.click(submitBtn)

      await waitFor(() => {
        expect(screen.getByTestId('update-success')).toHaveTextContent('Profile updated successfully')
      })

      expect(apiClient.apiPut).toHaveBeenCalledWith(
        expect.stringContaining('/auth/me'),
        expect.objectContaining({ email: 'newemail@example.com' }),
      )
    })

    it('should show error message on update failure', async () => {
      vi.mocked(apiClient.apiGet).mockResolvedValue(mockProfile)
      vi.mocked(apiClient.apiPut).mockRejectedValue(new Error('Username is already taken'))

      renderProfilePage()

      await waitFor(() => {
        expect(screen.getByTestId('edit-username')).toBeInTheDocument()
      })

      const usernameInput = screen.getByTestId('edit-username')
      await userEvent.clear(usernameInput)
      await userEvent.type(usernameInput, 'taken_user')

      const submitBtn = screen.getByTestId('update-profile-btn')
      await userEvent.click(submitBtn)

      await waitFor(() => {
        expect(screen.getByTestId('update-error')).toHaveTextContent('Username is already taken')
      })
    })

    it('should disable button while updating', async () => {
      vi.mocked(apiClient.apiGet).mockResolvedValue(mockProfile)
      vi.mocked(apiClient.apiPut).mockImplementation(
        () => new Promise((resolve) => setTimeout(() => resolve(mockProfile), 200)),
      )

      renderProfilePage()

      await waitFor(() => {
        expect(screen.getByTestId('edit-username')).toBeInTheDocument()
      })

      const submitBtn = screen.getByTestId('update-profile-btn')
      await userEvent.click(submitBtn)

      expect(submitBtn).toBeDisabled()
      expect(screen.getByText('Saving...')).toBeInTheDocument()
    })
  })

  describe('API token generation', () => {
    it('should render generate token button and section description', async () => {
      vi.mocked(apiClient.apiGet).mockResolvedValue(mockProfile)

      renderProfilePage()

      await waitFor(() => {
        expect(screen.getByTestId('generate-token-btn')).toBeInTheDocument()
      })

      expect(screen.getByTestId('generate-token-btn')).toHaveTextContent('Generate New Token')
    })

    it('should call API and display token on generate', async () => {
      vi.mocked(apiClient.apiGet).mockResolvedValue(mockProfile)
      vi.mocked(apiClient.apiPost).mockResolvedValue({ accessToken: 'eyJhbGciOiJIUzI1NiJ9.faketoken' })

      renderProfilePage()

      await waitFor(() => {
        expect(screen.getByTestId('generate-token-btn')).toBeInTheDocument()
      })

      const generateBtn = screen.getByTestId('generate-token-btn')
      await userEvent.click(generateBtn)

      await waitFor(() => {
        expect(screen.getByTestId('legacy-token-display')).toBeInTheDocument()
      })

      expect(apiClient.apiPost).toHaveBeenCalledWith(
        expect.stringContaining('/auth/token'),
        undefined,
      )
    })

    it('should show error on token generation failure', async () => {
      vi.mocked(apiClient.apiGet).mockResolvedValue(mockProfile)
      vi.mocked(apiClient.apiPost).mockRejectedValue(new Error('Authentication failed'))

      renderProfilePage()

      await waitFor(() => {
        expect(screen.getByTestId('generate-token-btn')).toBeInTheDocument()
      })

      const generateBtn = screen.getByTestId('generate-token-btn')
      await userEvent.click(generateBtn)

      await waitFor(() => {
        expect(screen.getByTestId('update-error')).toHaveTextContent('Failed to generate token')
      })
    })
  })

  describe('logout', () => {
    it('should render logout button in danger zone section', async () => {
      vi.mocked(apiClient.apiGet).mockResolvedValue(mockProfile)

      renderProfilePage()

      await waitFor(() => {
        expect(screen.getByTestId('profile-logout-btn')).toBeInTheDocument()
      })

      expect(screen.getByTestId('profile-logout-btn')).toHaveTextContent('Logout')
    })

    it('should clear auth and redirect to login on logout', async () => {
      vi.mocked(apiClient.apiGet).mockResolvedValue(mockProfile)

      render(
        <AuthProvider>
          <MemoryRouter initialEntries={['/profile']}>
            <Routes>
              <Route path="/profile" element={<ProfilePage />} />
              <Route path="/login" element={<div data-testid="login-page">Login Page</div>} />
            </Routes>
          </MemoryRouter>
        </AuthProvider>,
      )

      await waitFor(() => {
        expect(screen.getByTestId('profile-logout-btn')).toBeInTheDocument()
      })

      const logoutBtn = screen.getByTestId('profile-logout-btn')
      await userEvent.click(logoutBtn)

      // After logout, localStorage should be cleared and we redirect to login
      expect(localStorage.getItem('wiki4ai_access_token')).toBeNull()
    })
  })

  describe('copy token', () => {
    it('should copy legacy token to clipboard and show confirmation', async () => {
      const mockClipboard = {
        writeText: vi.fn().mockResolvedValue(undefined),
      }
      Object.assign(navigator, { clipboard: mockClipboard })

      vi.mocked(apiClient.apiGet).mockResolvedValue(mockProfile)
      vi.mocked(apiClient.apiPost).mockResolvedValue({ accessToken: 'test-token-value' })

      renderProfilePage()

      await waitFor(() => {
        expect(screen.getByTestId('generate-token-btn')).toBeInTheDocument()
      })

      // Generate token first (legacy quick generate flow)
      const generateBtn = screen.getByTestId('generate-token-btn')
      await userEvent.click(generateBtn)

      await waitFor(() => {
        expect(screen.getByTestId('legacy-token-display')).toBeInTheDocument()
      })

      // Click copy button on legacy display
      const copyBtn = screen.getByTestId('copy-token-btn')
      await userEvent.click(copyBtn)

      expect(mockClipboard.writeText).toHaveBeenCalledWith('test-token-value')
      expect(copyBtn).toHaveTextContent('✓ Copied!')
    })
  })
})
