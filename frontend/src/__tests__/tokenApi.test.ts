/**
 * Tests for the tokenApi service (WIKI4AI-138).
 *
 * What these lock down is the endpoint contract the ProfilePage depends on:
 * the new hashed-token API under /api/v1/tokens — never the legacy
 * /api/v1/auth/token* paths — and the fact that `scopes` is always an array
 * (a string there is a 500 JSON parse error on the backend).
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import * as apiClient from '../services/apiClient'
import { listTokens, createToken, rotateToken, deleteToken } from '../services/tokenApi'

vi.mock('../services/apiClient', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
  getRedirectFromUrl: vi.fn(() => null),
}))

const metadata = {
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

describe('tokenApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('listTokens reads GET /api/v1/tokens', async () => {
    vi.mocked(apiClient.apiGet).mockResolvedValue([metadata] as never)

    const tokens = await listTokens()

    expect(apiClient.apiGet).toHaveBeenCalledWith('/api/v1/tokens')
    expect(tokens).toEqual([metadata])
    // The listing is what exposes `hashed` — the legacy listing does not.
    expect(tokens[0].hashed).toBe(true)
  })

  it('createToken posts to /api/v1/tokens with scopes as an array', async () => {
    vi.mocked(apiClient.apiPost).mockResolvedValue({ token: 'w4a_x', metadata } as never)

    const created = await createToken('CI Token', ['read', 'write'])

    expect(apiClient.apiPost).toHaveBeenCalledWith('/api/v1/tokens', {
      name: 'CI Token',
      scopes: ['read', 'write'],
    })
    expect(created.token).toBe('w4a_x')
    expect(created.metadata.id).toBe(7)

    const body = vi.mocked(apiClient.apiPost).mock.calls[0][1] as { scopes: unknown }
    expect(Array.isArray(body.scopes)).toBe(true)
    // No expiry requested → the field is omitted, not sent as null/empty string.
    expect(body).not.toHaveProperty('expiresAt')
  })

  it('createToken sends expiresAt when the caller set one', async () => {
    vi.mocked(apiClient.apiPost).mockResolvedValue({ token: 'w4a_y', metadata } as never)

    await createToken('CI Token', ['read'], '2027-01-01T00:00')

    expect(apiClient.apiPost).toHaveBeenCalledWith('/api/v1/tokens', {
      name: 'CI Token',
      scopes: ['read'],
      expiresAt: '2027-01-01T00:00',
    })
  })

  it('createToken keeps an empty scope selection as an empty array', async () => {
    vi.mocked(apiClient.apiPost).mockResolvedValue({ token: 'w4a_z', metadata } as never)

    await createToken('CI Token', [])

    const body = vi.mocked(apiClient.apiPost).mock.calls[0][1] as { scopes: unknown }
    expect(Array.isArray(body.scopes)).toBe(true)
    expect(body.scopes).toEqual([])
  })

  it('rotateToken posts to /api/v1/tokens/{id}/rotate without a body', async () => {
    vi.mocked(apiClient.apiPost).mockResolvedValue({ token: 'w4a_rotated', metadata } as never)

    const rotated = await rotateToken(7)

    expect(apiClient.apiPost).toHaveBeenCalledWith('/api/v1/tokens/7/rotate')
    expect(rotated.token).toBe('w4a_rotated')
  })

  it('deleteToken soft-revokes through DELETE /api/v1/tokens/{id}', async () => {
    vi.mocked(apiClient.apiDelete).mockResolvedValue(undefined as never)

    await deleteToken(7)

    expect(apiClient.apiDelete).toHaveBeenCalledWith('/api/v1/tokens/7')
    const urls = vi.mocked(apiClient.apiDelete).mock.calls.map(([url]) => url)
    expect(urls.some((url) => url.includes('/auth/token/'))).toBe(false)
  })
})
