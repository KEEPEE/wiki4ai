/**
 * WIKI4AI-164 / WIKI4AI-165: a POST that succeeds with 204 must not look like a failure.
 *
 * `POST /api/v1/reservations/{id}/release` and `POST /api/v1/actors/{id}/inbox/mark-seen`
 * are `ResponseEntity<Void>` in the backend: 204, no body. `apiPost` used to call
 * `res.json()` on every 2xx, which throws on an empty body — so a release the server
 * had just accepted surfaced in the UI as "the release failed", and a second click on
 * an idempotent endpoint looked like a second failure.
 *
 * Only 204 is special-cased: it is the one status defined to carry no body.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { apiPost } from '../services/apiClient';

const mockFetch = vi.fn() as any;
window.fetch = mockFetch;

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
});

describe('apiPost and 204 No Content', () => {
  it('resolves instead of parsing a body that is not there', async () => {
    mockFetch.mockResolvedValueOnce({
      status: 204,
      ok: true,
      // No `json` on purpose: a 204 response has nothing to parse, and calling for it
      // is exactly the bug this guards.
    } as never);

    await expect(apiPost<void>('/api/v1/reservations/900/release')).resolves.toEqual({});
    expect(mockFetch.mock.calls[0][1].method).toBe('POST');
    // No body sent: "release this reservation", nothing else.
    expect(mockFetch.mock.calls[0][1].body).toBeUndefined();
  });

  it('still parses a 200 JSON body', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => ({ token: 'w4a_x', metadata: { id: 1 } }),
    } as never);

    await expect(apiPost('/api/v1/tokens/1/rotate')).resolves.toEqual({
      token: 'w4a_x',
      metadata: { id: 1 },
    });
  });

  it('still rejects a 4xx with the server message', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 403,
      headers: new Map([['Content-Type', 'application/json']]),
      text: async () => JSON.stringify({ message: 'Not your reservation' }),
      json: async () => ({ message: 'Not your reservation' }),
    } as never);

    await expect(apiPost('/api/v1/reservations/900/release')).rejects.toBeTruthy();
  });
});
