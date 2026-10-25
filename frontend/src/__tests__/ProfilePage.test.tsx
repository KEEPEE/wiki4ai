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

  describe('WIKI4AI-85: language + logout moved to the top-nav user menu', () => {
    it('should no longer render a language section on this page', async () => {
      vi.mocked(apiClient.apiGet).mockResolvedValue(mockProfile)

      renderProfilePage()

      await waitFor(() => {
        expect(screen.getByTestId('profile-username')).toBeInTheDocument()
      })

      // Language switch now lives in the top-nav user menu (UserMenu.tsx).
      expect(screen.queryByTestId('language-section')).not.toBeInTheDocument()
      expect(screen.queryByTestId('language-select')).not.toBeInTheDocument()
    })

    it('should no longer render a duplicate logout button', async () => {
      vi.mocked(apiClient.apiGet).mockResolvedValue(mockProfile)

      renderProfilePage()

      await waitFor(() => {
        expect(screen.getByTestId('profile-username')).toBeInTheDocument()
      })

      // Logout now lives in the top-nav user menu (single location).
      expect(screen.queryByTestId('profile-logout-btn')).not.toBeInTheDocument()
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

  // ── WIKI4AI-138: legacy deprecation banner + tokens on /api/v1/tokens ──────
  describe('WIKI4AI-138: token list, banner, delete and rotation on /api/v1/tokens', () => {
    /** A hashed (revocable) token WITHOUT an expiry — must not be flagged legacy. */
    const hashedToken = {
      id: 7,
      name: 'CI Token',
      prefix: 'abc12345',
      scopes: ['read', 'write'],
      actorId: null,
      expiresAt: null,
      revokedAt: null,
      lastUsedAt: null,
      createdAt: '2026-01-01T00:00:00',
      hashed: true,
    }

    /** A legacy clear-text JWT: hashed === false, infinite lifetime. */
    const legacyToken = {
      id: 8,
      name: 'Old MCP Token',
      prefix: null,
      scopes: [],
      actorId: null,
      expiresAt: null,
      revokedAt: null,
      lastUsedAt: null,
      createdAt: '2025-01-01T00:00:00',
      hashed: false,
    }

    /** Route apiGet so /auth/me returns the profile and /tokens returns the list. */
    function stubTokenList(tokens: unknown[]) {
      vi.mocked(apiClient.apiGet).mockImplementation(((url: string) =>
        Promise.resolve(url.endsWith('/tokens') ? tokens : mockProfile)) as never)
    }

    it('should read the token list from GET /api/v1/tokens, not from /auth/tokens', async () => {
      stubTokenList([hashedToken])

      renderProfilePage()

      await waitFor(() => {
        expect(screen.getByText('CI Token')).toBeInTheDocument()
      })

      const calledUrls = vi.mocked(apiClient.apiGet).mock.calls.map(([url]) => url)
      expect(calledUrls).toContain('/api/v1/tokens')
      expect(calledUrls.some((url) => url.includes('/auth/tokens'))).toBe(false)
    })

    it('should show the deprecation banner for a legacy token (hashed: false)', async () => {
      stubTokenList([legacyToken])

      renderProfilePage()

      await waitFor(() => {
        expect(screen.getByTestId('legacy-token-banner-8')).toBeInTheDocument()
      })

      const banner = screen.getByTestId('legacy-token-banner-8')
      expect(banner).toHaveTextContent('Legacy token')
      expect(banner).toHaveTextContent('cannot be revoked and never expires')
      // The banner is a visible alert, not a hidden hint.
      expect(banner).toBeVisible()
      expect(banner).toHaveAttribute('role', 'alert')
    })

    it('should NOT show the banner for a hashed token that has no expiry', async () => {
      // hashed: true AND expiresAt: null — if the banner were driven by a missing
      // expiresAt this token would be wrongly flagged as legacy.
      stubTokenList([{ ...hashedToken, expiresAt: null }])

      renderProfilePage()

      await waitFor(() => {
        expect(screen.getByText('CI Token')).toBeInTheDocument()
      })

      expect(screen.queryByTestId('legacy-token-banner-7')).not.toBeInTheDocument()
      expect(screen.queryByTestId('legacy-token-banner-7')).toBeNull()
    })

    it('should show the banner for a legacy token that DOES have an expiry', async () => {
      // The flag, not the expiry, decides the banner.
      stubTokenList([{ ...legacyToken, id: 9, name: 'Legacy With Expiry', expiresAt: '2030-01-01T00:00:00' }])

      renderProfilePage()

      await waitFor(() => {
        expect(screen.getByTestId('legacy-token-banner-9')).toBeInTheDocument()
      })
      expect(screen.getByText('Legacy With Expiry')).toBeInTheDocument()
    })

    it('should not offer rotation for a legacy token', async () => {
      stubTokenList([legacyToken])

      renderProfilePage()

      await waitFor(() => {
        expect(screen.getByText('Old MCP Token')).toBeInTheDocument()
      })

      expect(screen.queryByTestId('rotate-token-8')).not.toBeInTheDocument()
    })

    it('should delete through DELETE /api/v1/tokens/{id}, not /auth/token/{id}', async () => {
      stubTokenList([hashedToken])
      vi.mocked(apiClient.apiDelete).mockResolvedValue(undefined as never)

      renderProfilePage()

      await waitFor(() => {
        expect(screen.getByTestId('delete-token-7')).toBeInTheDocument()
      })

      await userEvent.click(screen.getByTestId('delete-token-7'))

      await waitFor(() => {
        expect(apiClient.apiDelete).toHaveBeenCalledWith('/api/v1/tokens/7')
      })

      const deleteUrls = vi.mocked(apiClient.apiDelete).mock.calls.map(([url]) => url)
      expect(deleteUrls.some((url) => url.includes('/auth/token/'))).toBe(false)
    })

    it('should rotate through POST /api/v1/tokens/{id}/rotate and show the new plaintext once', async () => {
      stubTokenList([hashedToken])
      vi.mocked(apiClient.apiPost).mockResolvedValue({
        token: 'w4a_ROTATED_PLAINTEXT_0123456789abcdef',
        metadata: { ...hashedToken, id: 11, prefix: 'rotated1' },
      } as never)

      renderProfilePage()

      await waitFor(() => {
        expect(screen.getByTestId('rotate-token-7')).toBeInTheDocument()
      })

      await userEvent.click(screen.getByTestId('rotate-token-7'))

      await waitFor(() => {
        expect(screen.getByTestId('rotated-token-display')).toBeInTheDocument()
      })

      expect(apiClient.apiPost).toHaveBeenCalledWith('/api/v1/tokens/7/rotate')

      const display = screen.getByTestId('rotated-token-display')
      expect(display).toHaveTextContent('w4a_ROTATED_PLAINTEXT_0123456789abcdef')
      // The user is told the old token is dead.
      expect(display).toHaveTextContent('The old token has stopped working')
      expect(screen.getByTestId('copy-rotated-token-btn')).toBeInTheDocument()
    })

    it('should show an error (not crash) when rotation fails with 400 on a legacy token', async () => {
      stubTokenList([hashedToken])
      vi.mocked(apiClient.apiPost).mockRejectedValue(
        new Error('400: Legacy token cannot be rotated') as never,
      )

      renderProfilePage()

      await waitFor(() => {
        expect(screen.getByTestId('rotate-token-7')).toBeInTheDocument()
      })

      await userEvent.click(screen.getByTestId('rotate-token-7'))

      await waitFor(() => {
        expect(screen.getByTestId('update-error')).toBeInTheDocument()
      })

      expect(screen.getByTestId('update-error')).toHaveTextContent('Failed to rotate the token')
      expect(screen.getByTestId('update-error')).toHaveTextContent('400')
      // No crash: the rest of the page is still rendered and usable.
      expect(screen.getByTestId('profile-username')).toHaveTextContent('testuser')
      expect(screen.getByTestId('rotate-token-7')).toBeEnabled()
      expect(screen.queryByTestId('rotated-token-display')).not.toBeInTheDocument()
    })

    it('should create a token through POST /api/v1/tokens with scopes as an array', async () => {
      stubTokenList([])
      vi.mocked(apiClient.apiPost).mockResolvedValue({
        token: 'w4a_CREATED_PLAINTEXT_0123456789abcdef',
        metadata: { ...hashedToken, id: 12 },
      } as never)

      renderProfilePage()

      await waitFor(() => {
        expect(screen.getByTestId('create-named-token-btn')).toBeInTheDocument()
      })

      await userEvent.type(screen.getByTestId('api-token-name'), 'CI/CD Pipeline')
      await userEvent.click(screen.getByTestId('create-named-token-btn'))

      await waitFor(() => {
        expect(apiClient.apiPost).toHaveBeenCalledWith(
          '/api/v1/tokens',
          expect.objectContaining({ name: 'CI/CD Pipeline' }),
        )
      })

      const createCall = vi
        .mocked(apiClient.apiPost)
        .mock.calls.find(([url]) => url === '/api/v1/tokens')
      expect(createCall).toBeDefined()
      const body = createCall![1] as { name: string; scopes: unknown }
      // A string here would be a 500 JSON parse error on the backend.
      expect(Array.isArray(body.scopes)).toBe(true)
      expect(body.scopes).toEqual(expect.arrayContaining(['read', 'write']))

      // The legacy named endpoint is no longer used by the UI.
      const postUrls = vi.mocked(apiClient.apiPost).mock.calls.map(([url]) => url)
      expect(postUrls.some((url) => url.includes('/auth/token/named'))).toBe(false)
    })

    it('should send the scopes the user unchecked as an array too (never a string)', async () => {
      stubTokenList([])
      vi.mocked(apiClient.apiPost).mockResolvedValue({
        token: 'w4a_CREATED_PLAINTEXT_0123456789abcdef',
        metadata: { ...hashedToken, id: 13 },
      } as never)

      renderProfilePage()

      await waitFor(() => {
        expect(screen.getByTestId('token-scope-write')).toBeInTheDocument()
      })

      await userEvent.click(screen.getByTestId('token-scope-write'))
      await userEvent.type(screen.getByTestId('api-token-name'), 'Read Only')
      await userEvent.click(screen.getByTestId('create-named-token-btn'))

      await waitFor(() => {
        expect(apiClient.apiPost).toHaveBeenCalledWith(
          '/api/v1/tokens',
          expect.objectContaining({ name: 'Read Only' }),
        )
      })

      const body = vi
        .mocked(apiClient.apiPost)
        .mock.calls.find(([url]) => url === '/api/v1/tokens')![1] as { scopes: unknown }
      expect(Array.isArray(body.scopes)).toBe(true)
      expect(body.scopes).toEqual(['read'])
    })

    it('should mark a revoked token in the list', async () => {
      stubTokenList([{ ...hashedToken, revokedAt: '2026-02-01T00:00:00' }])

      renderProfilePage()

      await waitFor(() => {
        expect(screen.getByTestId('token-revoked-7')).toBeInTheDocument()
      })

      // A revoked token cannot be rotated again (the backend answers 409).
      expect(screen.queryByTestId('rotate-token-7')).not.toBeInTheDocument()
    })
  })
})
