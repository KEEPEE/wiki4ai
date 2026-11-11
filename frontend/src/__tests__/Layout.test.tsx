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

      // ArrowDown → Calendar (WIKI4AI-97), again → Vault, Enter activates it.
      await user.keyboard('{ArrowDown}')
      expect(document.activeElement).toBe(screen.getByTestId('user-menu-calendar'))
      await user.keyboard('{ArrowDown}')
      expect(document.activeElement).toBe(screen.getByTestId('user-menu-vault'))
      await user.keyboard('{Enter}')
      expect(await screen.findByTestId('vault-page-stub')).toBeInTheDocument()
    })
  })
})

/**
 * WIKI4AI-195 (DIZ-4 items 4, 8, 11) — prístupnosť shellu.
 *
 * Pred týmto krokom: skip link NEEXISTOVAL vôbec (2.4.1 Bypass Blocks),
 * Escape mobilný sidebar nezatvoril, off-canvas sidebar bol tabovateľný
 * aj keď bol mimo obrazovky (2.4.3), logo odkaz nemal prístupný názov a
 * dekoratívá lupa sa volala ako nepomenovaný grafický prvok.
 */
describe('Layout — shell a11y (WIKI4AI-195, DIZ-4 items 4, 8, 11)', () => {
  beforeEach(() => {
    localStorage.clear()
    seedAuthenticatedSession()
  })

  afterEach(() => {
    localStorage.clear()
    vi.unstubAllGlobals()
  })

  it('skip link je PRVÝ focusovateľný prvok a ukazuje na existujúci #main-content', async () => {
    renderLayout()
    const skip = await screen.findByTestId('skip-link')
    const focusable = Array.from(
      document.querySelectorAll<HTMLElement>('a[href], button:not([disabled]), input, [tabindex]'),
    )
    expect(focusable[0], 'skip link musí byť prvý, inak neobíde navigáciu').toBe(skip)
    expect(skip.getAttribute('href')).toBe('#main-content')
    expect(document.getElementById('main-content')).not.toBeNull()
  })

  /* jsdom NEMÁ fragment navigáciu — klik na href="#…" v ňom nepresunie
     focus, na rozdiel od prehliadača. Testuje sa preto mechanizmus, nie
     následok: cieľ existuje a je focusovateľný. Bez tabIndex=-1 by
     prehliadač presunul len scroll a 2.4.1 by splnené nebolo. */
  it('cieľ skip linku existuje a je focusovateľný (tabIndex=-1)', async () => {
    renderLayout()
    const skip = await screen.findByTestId('skip-link')
    const target = document.getElementById('main-content')
    expect(target).not.toBeNull()
    expect(target).toHaveAttribute('tabindex', '-1')
    target?.focus()
    expect(document.activeElement).toBe(target)
    expect(skip.getAttribute('href')).toBe('#' + target?.id)
  })

  it('Escape zavrie mobilný sidebar', async () => {
    const user = userEvent.setup()
    renderLayout()
    const toggle = await screen.findByTestId('nav-menu-toggle')
    await user.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    await user.keyboard('{Escape}')
    await waitFor(() => expect(toggle).toHaveAttribute('aria-expanded', 'false'))
  })

  it('off-canvas sidebar je inert, kým nie je otvorený (WCAG 2.4.3)', async () => {
    const narrow = {
      matches: true,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    } as unknown as MediaQueryList
    vi.spyOn(window, 'matchMedia').mockImplementation(() => narrow)

    renderLayout()
    const aside = await screen.findByRole('complementary', { name: /wiki/i })
    expect(aside, 'úzke okno + zatvorený sidebar musí byť inert').toHaveAttribute('inert')

    await userEvent.click(await screen.findByTestId('nav-menu-toggle'))
    await waitFor(() =>
      expect(screen.getByRole('complementary', { name: /wiki/i })).not.toHaveAttribute('inert'),
    )
  })

  it('logo odkaz má prístupný názov — SVG sama ho nedá (DIZ-4 item 11)', async () => {
    renderLayout()
    const logo = await screen.findByTestId('nav-logo')
    /* Obsahuje len <WikiLogo/> (SVG), teda bez aria-label by mal prázdny
       názov a čítačka by vyhlásila nepomenovaný link. */
    expect(logo.textContent, 'odkaz obsahuje len SVG').toBe('')
    /* Pristupny nazov odkazu bez textoveho childa prichadza vyhradne z
     * aria-label — testuje sa priamo, bez dom-accessibility-api. */
    expect(logo.getAttribute('aria-label')).toMatch(/Wiki4AI/)
    expect(i18n.t('layout.nav.homeAria')).toMatch(/Wiki4AI/)
  })

  it('dekoratívna lupa vyhľadávania je aria-hidden', async () => {
    renderLayout()
    const form = await screen.findByTestId('global-search-form')
    expect(form.querySelector('svg')).toHaveAttribute('aria-hidden', 'true')
  })
})
