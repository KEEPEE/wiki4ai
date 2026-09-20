/**
 * Tests for Layout component
 */

import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import Layout from '../components/Layout'
import { AuthProvider } from '../contexts/AuthContext'

/** Build a syntactically valid JWT with an `exp` in the future. */
function makeFakeJwt(expSeconds: number): string {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).replace(/=+$/, '')
  const payload = btoa(JSON.stringify({ sub: 'keepee', exp: expSeconds })).replace(/=+$/, '')
  return `${header}.${payload}.fakesig`
}

/** Seed localStorage so AuthProvider restores an authenticated session. */
function seedAuthenticatedSession() {
  const futureExp = Math.floor(Date.now() / 1000) + 3600
  localStorage.setItem('wiki4ai_access_token', makeFakeJwt(futureExp))
  localStorage.setItem('wiki4ai_refresh_token', 'fake-refresh')
  localStorage.setItem(
    'wiki4ai_user_info',
    JSON.stringify({ id: 1, username: 'keepee', email: 'keepee@example.com', role: 'ADMIN' }),
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
      expect(screen.getByTestId('global-search-input')).toHaveAttribute('placeholder', 'Hľadať vo wiki...')
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
})
