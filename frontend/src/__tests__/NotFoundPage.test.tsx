/**
 * WIKI4AI-108 — an unmatched URL must render a real page, never a blank screen.
 *
 * Measured cause on dev `.219` (real browser, `page.goto('/admin')`):
 *   [WARNING] No routes matched location "/admin"
 *   bodyTextLength 0 · 16 DOM elements · no headings
 * No exception was thrown, so `ErrorBoundary` (WIKI4AI-27/38) correctly stayed out:
 * there was no error to catch. The router simply matched nothing and committed an
 * empty tree. Two defects, both covered here:
 *   A  no catch-all route existed → every typo / broken link / stale bookmark was
 *      a silent empty screen.
 *   B  `/admin` was not a route at all — only `/admin/users` and friends existed.
 *
 * These tests drive the REAL route table of `src/App.tsx` — not a copy of it — by
 * pointing `window.history` at a path and rendering `<App />`. That is what makes
 * them fail when the catch-all or the `/admin` redirect is deleted from App.tsx.
 * A test that built its own `<Routes>` would keep passing with the bug restored.
 *
 * No network: every service the App tree can reach on these routes is mocked, and
 * the nav marker's polling hook is replaced so no `refetchInterval` timer outlives
 * the test (the WIKI4AI-175/179 teardown-flake class).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import App from '../App'
import i18n from '../i18n'

const BOGUS_PATH = '/totally-made-up-path-xyz'
const ACCESS_TOKEN_KEY = 'wiki4ai_access_token'
const REFRESH_TOKEN_KEY = 'wiki4ai_refresh_token'
const USER_INFO_KEY = 'wiki4ai_user_info'

// ── Network boundary mocks ───────────────────────────────────────────────────
// AuthProvider probes GET /auth/status and re-syncs GET /auth/me on mount.
vi.mock('../services/authApi', () => ({
  login: vi.fn(),
  register: vi.fn(),
  setup: vi.fn(),
  getAuthStatus: vi.fn(async () => ({ initialized: true, registrationOpen: false })),
  getMe: vi.fn(async () => ({
    id: 1,
    username: 'test_admin',
    email: 'admin@test.com',
    role: 'ADMIN',
    language: 'en',
    createdAt: '',
  })),
}))

// AdminUsersPage (the target of the /admin redirect) reads the admin API.
vi.mock('../services/adminApi', () => ({
  listUsers: vi.fn(async () => ({
    content: [
      { id: 2, username: 'member', email: 'member@test.com', role: 'USER', createdAt: '' },
    ],
    totalElements: 1,
    totalPages: 1,
    number: 0,
    size: 20,
  })),
  createUser: vi.fn(),
  updateUserRole: vi.fn(),
  deleteUser: vi.fn(),
}))

// Layout → UserMenu → the unseen-events marker polls the board every 10 s.
// Replaced wholesale: this suite is about routing, and a live polling timer would
// outlive teardown — exactly the flake WIKI4AI-179 removed elsewhere.
vi.mock('../hooks/useUnseenEvents', () => ({
  useUnseenEvents: () => ({
    count: 0,
    source: 'unknown',
    known: false,
    isMarking: false,
    actorId: null,
    markSeen: vi.fn(async () => undefined),
  }),
  UNSEEN_REFETCH_INTERVAL_MS: 10_000,
  UNSEEN_SCAN_REFETCH_INTERVAL_MS: 30_000,
  maxEventId: undefined,
}))

/** Fake JWT whose `exp` is one hour in the future, so AuthContext keeps the session. */
function createFakeJwt(): string {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).replace(/=+$/, '')
  const payload = btoa(
    JSON.stringify({ sub: '1', exp: Math.floor(Date.now() / 1000) + 3600 }),
  ).replace(/=+$/, '')
  return `${header}.${payload}.fake-signature`
}

function seedSession(role: 'ADMIN' | 'USER') {
  localStorage.setItem(ACCESS_TOKEN_KEY, createFakeJwt())
  localStorage.setItem(REFRESH_TOKEN_KEY, 'fake-refresh-token')
  localStorage.setItem(
    USER_INFO_KEY,
    JSON.stringify({
      id: 1,
      username: role === 'ADMIN' ? 'test_admin' : 'test_member',
      email: `${role === 'ADMIN' ? 'admin' : 'member'}@test.com`,
      role,
      language: 'en',
      createdAt: '',
    }),
  )
}

/**
 * Hard navigation: set the browser URL first, then mount the app. This is the
 * F5 / pasted-URL path, not a client-side click.
 */
function hardNavigate(path: string) {
  window.history.replaceState({}, '', path)
  return render(<App />)
}

/** The Not Found page rendered — heading, explanation and a way out. */
function expectNotFoundPage(path: string) {
  const title = screen.getByTestId('not-found-title')
  expect(title).toHaveTextContent(/not found/i)
  // A visible explanation, not just a heading.
  expect(screen.getByTestId('not-found-body').textContent?.trim().length).toBeGreaterThan(0)
  // The address the router was actually asked for — read from the router.
  expect(screen.getByTestId('not-found-path-value')).toHaveTextContent(path)
  // A way home.
  expect(screen.getByTestId('not-found-home-link')).toHaveAttribute('href', '/')
}

describe('WIKI4AI-108: unmatched routes render a page, /admin resolves', () => {
  beforeEach(async () => {
    localStorage.clear()
    sessionStorage.clear()
    await i18n.changeLanguage('en')
  })

  afterEach(() => {
    // Leave the browser where the suite started so each test's hard navigation
    // is the only thing that decides which route matches.
    window.history.replaceState({}, '', '/')
  })

  describe('defect A — catch-all route', () => {
    it('renders a real Not Found page for an unmatched path (anonymous)', async () => {
      const { container } = hardNavigate(BOGUS_PATH)

      // Measured on dev `.219`: 16 DOM elements, ZERO headings, zero route text.
      // `container` also holds the global splash, so the discriminating claims are
      // these two: a heading exists, and the route area says "not found".
      expect(container.querySelectorAll('h1').length).toBeGreaterThan(0)
      expect(container.textContent).not.toBe('')
      expect(container.textContent).toMatch(/not found/i)

      await screen.findByTestId('not-found-title')
      expectNotFoundPage(BOGUS_PATH)

      // The router must not silently bounce the visitor somewhere else — the
      // explanation has to be about the address they actually asked for.
      expect(window.location.pathname).toBe(BOGUS_PATH)

      // The page fetches nothing, so it may not claim a status code the server
      // never sent (an unmatched SPA route is served by index.html with 200).
      expect(container.textContent).not.toMatch(/\b404\b/)
    })

    it('renders the Not Found page for a signed-in user too (no login bounce)', async () => {
      seedSession('ADMIN')
      hardNavigate(BOGUS_PATH)

      await screen.findByTestId('not-found-title')
      expectNotFoundPage(BOGUS_PATH)
      expect(window.location.pathname).toBe(BOGUS_PATH)
      expect(screen.queryByTestId('login-form')).not.toBeInTheDocument()
    })

    it('catches a wrong sub-path of a real route as well', async () => {
      hardNavigate('/admin/does-not-exist')

      await screen.findByTestId('not-found-title')
      expectNotFoundPage('/admin/does-not-exist')
    })

    it('does not swallow real routes: /login still renders the login form', async () => {
      hardNavigate('/login')

      expect(await screen.findByTestId('login-form')).toBeInTheDocument()
      expect(screen.queryByTestId('not-found-title')).not.toBeInTheDocument()
    })
  })

  describe('defect B — /admin is not a route, /admin/users is', () => {
    it('signed-in ADMIN landing on /admin ends up on the admin page', async () => {
      seedSession('ADMIN')
      hardNavigate('/admin')

      // The admin page rendered — not a blank screen, not the Not Found page.
      expect(await screen.findByTestId('users-list-section')).toBeInTheDocument()
      expect(screen.queryByTestId('not-found-title')).not.toBeInTheDocument()
      expect(window.location.pathname).toBe('/admin/users')
    })

    it('anonymous /admin is redirected too, never left unmatched', async () => {
      hardNavigate('/admin')

      // `/admin` matched and redirected BEFORE auth was consulted — the URL moved
      // to the admin page. Had the route been missing, the URL would still be
      // '/admin' and the catch-all would be on screen.
      expect(window.location.pathname).toBe('/admin/users')
      expect(screen.queryByTestId('not-found-title')).not.toBeInTheDocument()

      // ProtectedRoute then asks for a session, and its ?redirect= names the admin
      // page — proof the chain ran through /admin/users, not through the catch-all.
      expect(await screen.findByTestId('login-form')).toBeInTheDocument()
      expect(window.location.pathname).toBe('/login')
      expect(decodeURIComponent(window.location.search)).toContain('/admin/users')
    })
  })
})
