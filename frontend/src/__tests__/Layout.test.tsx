/**
 * Tests for Layout component
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import Layout from '../components/Layout'
import { AuthProvider } from '../contexts/AuthContext'
import * as apiClient from '../services/apiClient'
import i18n from '../i18n'

// WIKI4AI-85: the user menu persists language via PUT /auth/me (apiClient).
vi.mock('../services/apiClient', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
  getRedirectFromUrl: vi.fn(() => null),
}))

/** Build a syntactically valid JWT with an `exp` in the future. */
function makeFakeJwt(expSeconds: number): string {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).replace(/=+$/, '')
  const payload = btoa(JSON.stringify({ sub: 'keepee', exp: expSeconds })).replace(/=+$/, '')
  return `${header}.${payload}.fakesig`
}

/** Seed localStorage so AuthProvider restores an authenticated session. */
function seedAuthenticatedSession(role: 'ADMIN' | 'USER' = 'ADMIN') {
  const futureExp = Math.floor(Date.now() / 1000) + 3600
  localStorage.setItem('wiki4ai_access_token', makeFakeJwt(futureExp))
  localStorage.setItem('wiki4ai_refresh_token', 'fake-refresh')
  localStorage.setItem(
    'wiki4ai_user_info',
    JSON.stringify({ id: 1, username: 'keepee', email: 'keepee@example.com', role }),
  )
}

function renderLayout(route = '/') {
  return render(
    <AuthProvider>
      <MemoryRouter initialEntries={[route]}>
        <Routes>
          <Route path="/" element={<Layout />}>
            <Route index element={<div>Main Content</div>} />
            <Route path="/search" element={<div data-testid="search-page-stub">Search Page</div>} />
            <Route path="/profile" element={<div data-testid="profile-page-stub">Profile Page</div>} />
            <Route path="/vault" element={<div data-testid="vault-page-stub">Vault Page</div>} />
            <Route path="/admin/users" element={<div data-testid="admin-page-stub">Admin Page</div>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </AuthProvider>,
  )
}

describe('Layout', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('should render main content area without sidebar', async () => {
    render(
      <AuthProvider>
        <MemoryRouter>
          <Routes>
            <Route path="/" element={<Layout />}>
              <Route index element={<div>Main Content</div>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </AuthProvider>,
    )

    await waitFor(() => {
      expect(screen.getByText('Main Content')).toBeInTheDocument()
    })
  })

  it('should render children in main content area', async () => {
    render(
      <AuthProvider>
        <MemoryRouter>
          <Routes>
            <Route path="/" element={<Layout />}>
              <Route index element={<div>Test Child</div>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </AuthProvider>,
    )

    await waitFor(() => {
      expect(screen.getByText('Test Child')).toBeInTheDocument()
    })
  })

  it('should show login/register links when not authenticated', async () => {
    render(
      <AuthProvider>
        <MemoryRouter>
          <Routes>
            <Route path="/" element={<Layout />}>
              <Route index element={<div>Content</div>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </AuthProvider>,
    )

    await waitFor(() => {
      expect(screen.getByTestId('nav-login')).toBeInTheDocument()
      expect(screen.getByTestId('nav-register')).toBeInTheDocument()
    })
  })

  describe('Global search bar (WIKI4AI-61)', () => {
    it('should render the global search input when authenticated', async () => {
      seedAuthenticatedSession()
      renderLayout()

      await waitFor(() => {
        expect(screen.getByTestId('global-search-input')).toBeInTheDocument()
      })
      // WIKI4AI-73: EN default catalog
      expect(screen.getByTestId('global-search-input')).toHaveAttribute('placeholder', 'Search the wiki...')
    })

    it('should not render the global search input when anonymous (login-only endpoint)', async () => {
      renderLayout()

      await waitFor(() => {
        expect(screen.getByTestId('nav-login')).toBeInTheDocument()
      })
      expect(screen.queryByTestId('global-search-input')).not.toBeInTheDocument()
    })

    it('should navigate to /search?q=... when a query of at least 2 chars is submitted', async () => {
      const user = userEvent.setup()
      seedAuthenticatedSession()
      renderLayout()

      const input = await screen.findByTestId('global-search-input')
      await user.type(input, 'embedding')
      await user.click(input) // focus
      await user.keyboard('{Enter}')

      await waitFor(() => {
        expect(screen.getByTestId('search-page-stub')).toBeInTheDocument()
      })
    })

    it('should stay on the current page when submitting a 1-character query', async () => {
      const user = userEvent.setup()
      seedAuthenticatedSession()
      renderLayout()

      const input = await screen.findByTestId('global-search-input')
      await user.type(input, 'x')
      await user.keyboard('{Enter}')

      expect(screen.queryByTestId('search-page-stub')).not.toBeInTheDocument()
    })
  })

  describe('User menu (WIKI4AI-85)', () => {
    beforeEach(() => {
      vi.clearAllMocks()
    })

    afterEach(async () => {
      // The language toggle test mutates the shared i18n instance — always
      // leave it on EN for the rest of the suite.
      await i18n.changeLanguage('en')
    })

    async function openUserMenu(user = userEvent.setup()) {
      await user.click(screen.getByTestId('user-menu-button'))
      await waitFor(() => {
        expect(screen.getByTestId('user-menu-panel')).toBeInTheDocument()
      }, { timeout: 3000 })
    }

    it('should render an initials avatar trigger instead of the old auth badges', async () => {
      seedAuthenticatedSession()
      renderLayout()

      const avatar = await screen.findByTestId('user-menu-avatar')
      expect(avatar).toHaveTextContent('K') // "keepee" → "K"

      // The old debug-styled cluster is gone.
      expect(screen.queryByTestId('nav-vault')).not.toBeInTheDocument()
      expect(screen.queryByTestId('nav-admin-users')).not.toBeInTheDocument()
      expect(screen.queryByTestId('nav-profile')).not.toBeInTheDocument()
      expect(screen.queryByTestId('logout-button')).not.toBeInTheDocument()

      // Panel is closed initially.
      expect(screen.queryByTestId('user-menu-panel')).not.toBeInTheDocument()
    })

    it('should open a dropdown with Profile, Vault, Admin (ADMIN user), Language and Logout', async () => {
      const user = userEvent.setup()
      seedAuthenticatedSession('ADMIN')
      renderLayout()

      await openUserMenu(user)

      expect(screen.getByTestId('user-menu-profile')).toHaveTextContent('Profile')
      expect(screen.getByTestId('user-menu-vault')).toHaveTextContent('Vault')
      expect(screen.getByTestId('user-menu-admin')).toHaveTextContent('Admin')
      expect(screen.getByTestId('user-menu-language-row')).toBeInTheDocument()
      expect(screen.getByTestId('user-menu-logout')).toHaveTextContent('Logout')
    })

    it('should hide the Admin item for non-admin users (role gating)', async () => {
      const user = userEvent.setup()
      seedAuthenticatedSession('USER')
      renderLayout()

      await openUserMenu(user)

      expect(screen.getByTestId('user-menu-profile')).toBeInTheDocument()
      expect(screen.queryByTestId('user-menu-admin')).not.toBeInTheDocument()
    })

    it('should navigate to /profile, /vault and /admin/users from the menu', async () => {
      const user = userEvent.setup()
      seedAuthenticatedSession('ADMIN')
      renderLayout()

      await openUserMenu(user)
      await user.click(screen.getByTestId('user-menu-profile'))
      expect(await screen.findByTestId('profile-page-stub')).toBeInTheDocument()

      // Re-open and go to Vault
      await openUserMenu(user)
      await user.click(screen.getByTestId('user-menu-vault'))
      expect(await screen.findByTestId('vault-page-stub')).toBeInTheDocument()

      // Re-open and go to Admin
      await openUserMenu(user)
      await user.click(screen.getByTestId('user-menu-admin'))
      expect(await screen.findByTestId('admin-page-stub')).toBeInTheDocument()
    })

    it('should switch language from the menu via PUT /auth/me (SK → EN round trip)', async () => {
      const user = userEvent.setup()
      vi.mocked(apiClient.apiPut).mockResolvedValue({} as never)
      seedAuthenticatedSession()
      renderLayout()

      await openUserMenu(user)

      // Switch to SK — the UI switches instantly and persists via the API.
      await user.click(screen.getByTestId('user-menu-lang-sk'))
      await waitFor(() => {
        expect(apiClient.apiPut).toHaveBeenCalledWith(
          expect.stringContaining('/auth/me'),
          { language: 'sk' },
        )
      })
      // i18n applied: the search placeholder is now Slovak.
      await waitFor(() => {
        expect(screen.getByTestId('global-search-input')).toHaveAttribute('placeholder', 'Hľadať vo wiki...')
      })

      // Switch back to EN — leave the app in English.
      await user.click(screen.getByTestId('user-menu-lang-en'))
      await waitFor(() => {
        expect(apiClient.apiPut).toHaveBeenCalledWith(
          expect.stringContaining('/auth/me'),
          { language: 'en' },
        )
      })
      await waitFor(() => {
        expect(screen.getByTestId('global-search-input')).toHaveAttribute('placeholder', 'Search the wiki...')
      })

      // The menu stays open across the toggle so the change is visible in place.
      expect(screen.getByTestId('user-menu-panel')).toBeInTheDocument()
    })

    it('should close on Escape and return focus to the trigger', async () => {
      const user = userEvent.setup()
      seedAuthenticatedSession()
      renderLayout()

      await openUserMenu(user)
      await waitFor(() => {
        expect(document.activeElement).toBe(screen.getByTestId('user-menu-profile'))
      })

      await user.keyboard('{Escape}')

      expect(screen.queryByTestId('user-menu-panel')).not.toBeInTheDocument()
      expect(document.activeElement).toBe(screen.getByTestId('user-menu-button'))
    })

    it('should close on click outside', async () => {
      const user = userEvent.setup()
      seedAuthenticatedSession()
      renderLayout()

      await openUserMenu(user)
      await user.click(screen.getByText('Main Content'))

      expect(screen.queryByTestId('user-menu-panel')).not.toBeInTheDocument()
    })

    it('should support keyboard navigation: Enter opens, arrows move, Enter activates', async () => {
      const user = userEvent.setup()
      seedAuthenticatedSession('ADMIN')
      renderLayout()

      // Focus the trigger and open with Enter (keyboard-only path).
      screen.getByTestId('user-menu-button').focus()
      await user.keyboard('{Enter}')

      // First item receives focus on open.
      await waitFor(() => {
        expect(document.activeElement).toBe(screen.getByTestId('user-menu-profile'))
      })

      // ArrowDown → Vault, Enter activates it.
      await user.keyboard('{ArrowDown}')
      expect(document.activeElement).toBe(screen.getByTestId('user-menu-vault'))
      await user.keyboard('{Enter}')
      expect(await screen.findByTestId('vault-page-stub')).toBeInTheDocument()
    })
  })
})
