/**
 * WIKI4AI-200 (PER-1): the React Query cache must not survive a change of identity.
 *
 * Why this is a WIKI4AI-200 test and not general hygiene: before this story
 * `GET /api/v1/projects` returned the same body for every caller, so a cached list was
 * harmless no matter who was signed in. Now the pins and the positions in that response
 * are the CALLER's own, which means the same URL legitimately returns a different body
 * for a different user — and the query key is only `['projects', {sort, q, visibility}]`
 * (see `projectsQueryKey` in `hooks/useProjects.ts`). Nothing in the key distinguishes
 * two users, so the cache cannot tell them apart either. With `staleTime` at five
 * minutes, signing in could otherwise serve the dashboard of whoever was here before.
 *
 * The cache is cleared rather than keyed by user id on purpose: an identity change also
 * invalidates documents, permissions, integrity reports and everything else that was
 * fetched under the old token, and enumerating those keys here would be a list that
 * silently rots.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { AuthProvider, useAuth } from '../contexts/AuthContext'
import { queryClient } from '../utils/queryClient'
import * as authApi from '../services/authApi'

vi.mock('../services/authApi', () => ({
  login: vi.fn(),
  register: vi.fn(),
  getAuthStatus: vi.fn().mockResolvedValue({ initialized: true, registrationOpen: false }),
  setup: vi.fn(),
  getMe: vi.fn().mockResolvedValue({
    id: 1, username: 'someone', email: 'someone@test.local', createdAt: '',
  }),
}))

/** The exact key `useProjects` uses — copied, not imported, so a key change breaks here. */
const PROJECTS_KEY = ['projects', { sort: 'created_desc', q: null, visibility: null }]
const SEED = [{ slug: 'belongs-to-the-previous-user', pinned: true, sortOrder: 0 }]

function createFakeJwt(): string {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))
  const futureExp = Math.floor(Date.now() / 1000) + 3600
  const payload = btoa(JSON.stringify({ sub: '1', exp: futureExp, iat: Date.now() / 1000 }))
  return `${header}.${payload}.${btoa('fake-signature')}`
}

// AuthProvider inside the QueryClientProvider, mirroring main.tsx: the cache is the
// outer concern and auth is the consumer that has to reach it.
function wrapper({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>{children}</AuthProvider>
    </QueryClientProvider>
  )
}

describe('AuthContext and the React Query cache', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    queryClient.clear()
    vi.mocked(authApi.getAuthStatus).mockResolvedValue({
      initialized: true, registrationOpen: false,
    })
    vi.mocked(authApi.getMe).mockResolvedValue({
      id: 1, username: 'someone', email: 'someone@test.local', createdAt: '',
    })
  })

  afterEach(() => {
    queryClient.clear()
    vi.unstubAllGlobals()
  })

  async function renderSignedIn() {
    vi.mocked(authApi.login).mockResolvedValue({
      accessToken: createFakeJwt(),
      refreshToken: 'refresh-token',
      user: { id: 1, username: 'someone', email: 'someone@test.local', createdAt: '' },
    })
    const hook = renderHook(() => useAuth(), { wrapper })
    await waitFor(() => expect(hook.result.current.isLoading).toBe(false))
    await act(async () => {
      await hook.result.current.login('someone', 'pass')
    })
    return hook
  }

  it('a login drops the cached projects list', async () => {
    // Something fetched before the login — as an anonymous visitor, or by the account
    // this one replaced.
    queryClient.setQueryData(PROJECTS_KEY, SEED)
    expect(queryClient.getQueryData(PROJECTS_KEY)).toEqual(SEED)

    await renderSignedIn()

    expect(queryClient.getQueryData(PROJECTS_KEY))
      .toBeUndefined()
  })

  it('a logout drops the cached projects list', async () => {
    const hook = await renderSignedIn()

    // The list this account fetched, now sitting in the cache.
    queryClient.setQueryData(PROJECTS_KEY, SEED)

    act(() => {
      hook.result.current.logout()
    })

    // The next account must not inherit this account's pins and positions.
    expect(queryClient.getQueryData(PROJECTS_KEY)).toBeUndefined()
    expect(hook.result.current.isAuthenticated).toBe(false)
  })

  it('a token refresh keeps the cache, because the identity did not change', async () => {
    const hook = await renderSignedIn()
    queryClient.setQueryData(PROJECTS_KEY, SEED)

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ accessToken: createFakeJwt(), refreshToken: 'rotated' }),
    }))

    await act(async () => {
      await hook.result.current.refreshTokenAction()
    })

    // A refresh is the same user; clearing here would refetch the whole app every time
    // the access token rotates.
    expect(queryClient.getQueryData(PROJECTS_KEY)).toEqual(SEED)
  })

  it('a failed refresh clears the cache, because it logs the user out', async () => {
    const hook = await renderSignedIn()
    queryClient.setQueryData(PROJECTS_KEY, SEED)

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, json: async () => ({}) }))

    await act(async () => {
      await hook.result.current.refreshTokenAction()
    })

    expect(queryClient.getQueryData(PROJECTS_KEY)).toBeUndefined()
    expect(hook.result.current.isAuthenticated).toBe(false)
  })
})
