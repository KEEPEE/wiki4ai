/**
 * WIKI4AI-168: the profile UI must never persist an API-token plaintext, and must
 * never render one outside the single "shown once" panel.
 *
 * Backend contract these tests hold the UI to:
 * - a token is `w4a_` + 32 base62 characters (36 total),
 * - the backend stores a SHA-256 digest plus an 8-character display prefix,
 * - `GET /api/v1/tokens` returns the menu — never the plaintext, never the hash,
 * - the plaintext is returned exactly once, by create and by rotate.
 *
 * Every token in this file is a synthetic fixture. It has the real shape so the
 * assertions exercise the real pattern, but it is not — and never was — a
 * credential.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { useEffect } from 'react';
import { render, screen, waitFor, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import ProfilePage from '../pages/ProfilePage';
import { AuthProvider, useAuth } from '../contexts/AuthContext';
import * as apiClient from '../services/apiClient';
import {
  STORED_NAMED_TOKENS_KEY,
  LEGACY_GENERATED_TOKEN_KEY,
} from '../utils/tokenStorage';

vi.mock('../services/apiClient', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
  getRedirectFromUrl: vi.fn(() => null),
}));

// AuthProvider probes the instance status and re-syncs a cached user on mount;
// neither is what these tests are about, so both are stubbed deterministically.
vi.mock('../services/authApi', () => ({
  login: vi.fn(),
  register: vi.fn(),
  getAuthStatus: vi.fn(() => Promise.resolve({ initialized: true, registrationOpen: false })),
  getMe: vi.fn(() => Promise.resolve(null)),
}));

// ── Synthetic fixtures ───────────────────────────────────────────────────────

/** `w4a_` + 32 base62 characters — the real token shape, an obviously fake body. */
const FAKE_TOKEN = 'w4a_FAKEONLYNOTAREALTOKEN12345678901';
const FAKE_TOKEN_ROTATED = 'w4a_ROTATEDFAKEONLYNOTAREAL123456789';
/** The 8-character display prefix the listing returns in place of a secret. */
const FAKE_PREFIX = 'FAKEONLY';
const FAKE_PREFIX_ROTATED = 'ROTATEDF';
/** A fake of the deprecated quick-generate JWT (never a real one). */
const FAKE_LEGACY_JWT = 'eyJFAKELEGACYJWTNOTAREALTOKEN000000';

const mockProfile = {
  id: 1,
  username: 'testuser',
  email: 'test@example.com',
  role: 'USER',
  createdAt: '2026-01-01T00:00:00',
};

/** One row of `GET /api/v1/tokens`: metadata only, exactly as the API returns it. */
function tokenRow(overrides: Record<string, unknown> = {}) {
  return {
    id: 12,
    name: 'CI Runner',
    prefix: FAKE_PREFIX,
    scopes: ['read', 'write'],
    actorId: null,
    expiresAt: null,
    revokedAt: null,
    lastUsedAt: null,
    createdAt: '2026-03-01T00:00:00',
    hashed: true,
    ...overrides,
  };
}

/** `/auth/me` → profile, `/tokens` → the given live listing. */
function stubReads(tokens: unknown[]) {
  vi.mocked(apiClient.apiGet).mockImplementation(((url: string) =>
    Promise.resolve(url.endsWith('/tokens') ? tokens : mockProfile)) as never);
}

/**
 * `POST /api/v1/tokens` and `POST /api/v1/tokens/{id}/rotate`, each returning its
 * plaintext once. Like the backend, the new row joins the listing afterwards.
 */
function stubWrites(
  tokens: unknown[],
  created: { token: string; metadata: Record<string, unknown> },
  rotated: { token: string; metadata: Record<string, unknown> },
) {
  vi.mocked(apiClient.apiPost).mockImplementation(((url: string) => {
    if (url.endsWith('/rotate')) {
      tokens.push(rotated.metadata);
      return Promise.resolve(rotated);
    }
    tokens.push(created.metadata);
    return Promise.resolve(created);
  }) as never);
}

function renderProfilePage() {
  return render(
    <AuthProvider>
      <MemoryRouter initialEntries={['/profile']}>
        <Routes>
          <Route path="/profile" element={<ProfilePage />} />
        </Routes>
      </MemoryRouter>
    </AuthProvider>,
  );
}

/** Every string inside a parsed JSON value — keys included. */
function collectStrings(value: unknown, out: string[] = []): string[] {
  if (typeof value === 'string') {
    out.push(value);
    return out;
  }
  if (Array.isArray(value)) {
    value.forEach((item) => collectStrings(item, out));
    return out;
  }
  if (value !== null && typeof value === 'object') {
    for (const [key, item] of Object.entries(value as Record<string, unknown>)) {
      out.push(key);
      collectStrings(item, out);
    }
  }
  return out;
}

/** The stored menu, parsed straight out of localStorage. */
function readStoredMenu(): Array<Record<string, unknown>> {
  const raw = localStorage.getItem(STORED_NAMED_TOKENS_KEY);
  expect(raw, 'the token menu key must still exist').not.toBeNull();
  const parsed: unknown = JSON.parse(raw as string);
  expect(Array.isArray(parsed)).toBe(true);
  return parsed as Array<Record<string, unknown>>;
}

async function createNamedToken(name = 'CI Runner') {
  await waitFor(() => {
    expect(screen.getByTestId('create-named-token-btn')).toBeInTheDocument();
  });
  await userEvent.type(screen.getByTestId('api-token-name'), name);
  await userEvent.click(screen.getByTestId('create-named-token-btn'));
  await waitFor(() => {
    expect(screen.getByTestId('one-time-token-display')).toBeInTheDocument();
  });
}

describe('WIKI4AI-168: token plaintext is never persisted or re-rendered', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
  });

  it('fixture is a fake token of the real shape (w4a_ + 32 base62 chars)', () => {
    expect(FAKE_TOKEN).toHaveLength(36);
    expect(FAKE_TOKEN).toMatch(/^w4a_[0-9A-Za-z]{32}$/);
    expect(FAKE_TOKEN_ROTATED).toHaveLength(36);
    expect(FAKE_TOKEN_ROTATED).toMatch(/^w4a_[0-9A-Za-z]{32}$/);
  });

  // ── 1. localStorage ────────────────────────────────────────────────────────
  it('keeps no token plaintext in localStorage after creating a token', async () => {
    // The listing starts empty; the create call below adds the new row to it,
    // exactly as the backend would.
    const listing: unknown[] = [];
    stubReads(listing);
    stubWrites(listing, { token: FAKE_TOKEN, metadata: tokenRow() }, {
      token: FAKE_TOKEN_ROTATED,
      metadata: tokenRow({ id: 11, prefix: FAKE_PREFIX_ROTATED }),
    });

    renderProfilePage();
    await createNamedToken();

    const raw = localStorage.getItem(STORED_NAMED_TOKENS_KEY) as string;
    const stored = JSON.parse(raw);

    // The headline assertion: no string anywhere in the stored value is a token.
    expect(collectStrings(stored).some((s) => s.startsWith('w4a_'))).toBe(false);
    expect(raw).not.toContain(FAKE_TOKEN);

    // The menu itself survived, minus the secret field.
    expect(Array.isArray(stored)).toBe(true);
    expect(stored).toHaveLength(1);
    expect(stored[0]).toMatchObject({ tokenId: 12, name: 'CI Runner', hashed: true });
    expect(stored[0]).not.toHaveProperty('accessToken');
    expect(stored[0]).not.toHaveProperty('token');
  });

  // ── 2. DOM ─────────────────────────────────────────────────────────────────
  it('renders the plaintext only in the one-time panel, never in a token card', async () => {
    // The listing starts empty; the create call below adds the new row to it,
    // exactly as the backend would.
    const listing: unknown[] = [];
    stubReads(listing);
    stubWrites(listing, { token: FAKE_TOKEN, metadata: tokenRow() }, {
      token: FAKE_TOKEN_ROTATED,
      metadata: tokenRow({ id: 11, prefix: FAKE_PREFIX_ROTATED }),
    });

    renderProfilePage();
    await createNamedToken();

    // The card list carries the display prefix, not the secret.
    const cards = screen.getByTestId('api-tokens-list');
    expect(cards.innerHTML).not.toContain(FAKE_TOKEN);
    expect(cards).toHaveTextContent(`w4a_${FAKE_PREFIX}`);

    // The one-time panel is the single place the secret is rendered.
    expect(screen.getByTestId('one-time-token-display')).toHaveTextContent(FAKE_TOKEN);
  });

  // ── 3. title attribute ─────────────────────────────────────────────────────
  it('never puts a token plaintext in a title attribute', async () => {
    // The listing starts empty; the create call below adds the new row to it,
    // exactly as the backend would.
    const listing: unknown[] = [];
    stubReads(listing);
    stubWrites(listing, { token: FAKE_TOKEN, metadata: tokenRow() }, {
      token: FAKE_TOKEN_ROTATED,
      metadata: tokenRow({ id: 11, prefix: FAKE_PREFIX_ROTATED }),
    });

    renderProfilePage();
    await createNamedToken();

    const preview = document.querySelector('.token-card-preview');
    expect(preview).not.toBeNull();
    expect(preview).not.toHaveAttribute('title', FAKE_TOKEN);
    expect(preview?.getAttribute('title')).not.toMatch(/^w4a_[0-9A-Za-z]{32}$/);

    // No element on the page carries the secret as a tooltip.
    const titles = Array.from(document.querySelectorAll('[title]')).map((el) => el.getAttribute('title'));
    expect(titles.some((title) => title === FAKE_TOKEN)).toBe(false);
    expect(titles.some((title) => (title ?? '').startsWith('w4a_'))).toBe(false);
  });

  // ── 4. reload ──────────────────────────────────────────────────────────────
  it('shows only the display prefix after the page is mounted again', async () => {
    // The listing starts empty; the create call below adds the new row to it,
    // exactly as the backend would.
    const listing: unknown[] = [];
    stubReads(listing);
    stubWrites(listing, { token: FAKE_TOKEN, metadata: tokenRow() }, {
      token: FAKE_TOKEN_ROTATED,
      metadata: tokenRow({ id: 11, prefix: FAKE_PREFIX_ROTATED }),
    });

    const first = renderProfilePage();
    await createNamedToken();
    expect(screen.getByTestId('one-time-token-display')).toHaveTextContent(FAKE_TOKEN);

    // Simulate a reload: unmount, mount again, localStorage survives.
    first.unmount();
    const second = renderProfilePage();
    await waitFor(() => {
      expect(screen.getByText('CI Runner')).toBeInTheDocument();
    });

    expect(second.container.innerHTML).not.toContain(FAKE_TOKEN);
    expect(document.body.innerHTML).not.toContain(FAKE_TOKEN);
    expect(screen.queryByTestId('one-time-token-display')).not.toBeInTheDocument();
    expect(screen.getByTestId('api-tokens-list')).toHaveTextContent(`w4a_${FAKE_PREFIX}…`);
  });

  // ── 5. copy action ─────────────────────────────────────────────────────────
  it('offers no copy action for a stored token after a reload', async () => {
    // The listing starts empty; the create call below adds the new row to it,
    // exactly as the backend would.
    const listing: unknown[] = [];
    stubReads(listing);
    stubWrites(listing, { token: FAKE_TOKEN, metadata: tokenRow() }, {
      token: FAKE_TOKEN_ROTATED,
      metadata: tokenRow({ id: 11, prefix: FAKE_PREFIX_ROTATED }),
    });

    const first = renderProfilePage();
    await createNamedToken();
    // While the plaintext is in state, copying is available.
    expect(screen.getByTestId('copy-one-time-token-btn')).toBeInTheDocument();

    first.unmount();
    renderProfilePage();
    await waitFor(() => {
      expect(screen.getByTestId('delete-token-12')).toBeInTheDocument();
    });

    expect(screen.queryByTestId('copy-stored-token-12')).not.toBeInTheDocument();
    expect(screen.queryByTestId('copy-one-time-token-btn')).not.toBeInTheDocument();
    expect(screen.queryAllByRole('button', { name: /^Copy$/ })).toHaveLength(0);
  });

  // ── 6. migration of an existing installation ───────────────────────────────
  it('erases the plaintext an older build already left in localStorage', async () => {
    // A real user of the previous version: the key holds the whole secret.
    localStorage.setItem(
      STORED_NAMED_TOKENS_KEY,
      JSON.stringify([
        {
          tokenId: 12,
          name: 'CI Runner',
          accessToken: FAKE_TOKEN,
          expiresAt: null,
          createdAt: '2026-03-01T00:00:00',
          hashed: true,
        },
      ]),
    );
    localStorage.setItem(LEGACY_GENERATED_TOKEN_KEY, FAKE_TOKEN);

    renderProfilePage();
    await waitFor(() => {
      expect(screen.getByTestId('profile-username')).toHaveTextContent('testuser');
    });

    const stored = readStoredMenu();
    expect(JSON.stringify(stored)).not.toContain(FAKE_TOKEN);
    expect(collectStrings(stored).some((s) => s.startsWith('w4a_'))).toBe(false);

    // Repaired, not discarded: the menu entry is still there, without the secret.
    expect(stored).toHaveLength(1);
    expect(stored[0]).toMatchObject({ tokenId: 12, name: 'CI Runner', hashed: true });
    expect(stored[0]).not.toHaveProperty('accessToken');

    // The plaintext the deprecated quick-generate flow used to persist is gone too.
    expect(localStorage.getItem(LEGACY_GENERATED_TOKEN_KEY)).toBeNull();

    // And the repaired entry never renders a secret.
    expect(document.body.innerHTML).not.toContain(FAKE_TOKEN);
  });

  // ── 7. logout ──────────────────────────────────────────────────────────────
  it('logout removes the token menu from localStorage', async () => {
    localStorage.setItem(
      STORED_NAMED_TOKENS_KEY,
      JSON.stringify([
        { tokenId: 12, name: 'CI Runner', accessToken: FAKE_TOKEN, createdAt: '2026-03-01T00:00:00', hashed: true },
      ]),
    );
    localStorage.setItem(LEGACY_GENERATED_TOKEN_KEY, FAKE_LEGACY_JWT);

    let logout: (() => void) | null = null;
    function AuthProbe({ onReady }: { onReady: (fn: () => void) => void }) {
      const auth = useAuth();
      useEffect(() => {
        onReady(auth.logout);
      }, [auth.logout, onReady]);
      return null;
    }

    render(
      <AuthProvider>
        <AuthProbe onReady={(fn) => { logout = fn; }} />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(logout).not.toBeNull();
    });
    act(() => {
      (logout as () => void)();
    });

    expect(localStorage.getItem(STORED_NAMED_TOKENS_KEY)).toBeNull();
    expect(localStorage.getItem(LEGACY_GENERATED_TOKEN_KEY)).toBeNull();

    const everything: string[] = [];
    for (let i = 0; i < localStorage.length; i += 1) {
      const key = localStorage.key(i);
      if (key !== null) everything.push(key, localStorage.getItem(key) as string);
    }
    expect(everything.some((value) => value.startsWith('w4a_'))).toBe(false);
    expect(everything).not.toContain(FAKE_TOKEN);
  });

  // ── 8. rotation ────────────────────────────────────────────────────────────
  it('shows the rotated plaintext exactly once and revokes the old copy', async () => {
    const listing = [tokenRow({ id: 7, name: 'CI Runner', prefix: 'abc12345' })];
    stubReads(listing);
    stubWrites(listing, { token: FAKE_TOKEN, metadata: tokenRow() }, {
      token: FAKE_TOKEN_ROTATED,
      metadata: tokenRow({ id: 11, name: 'CI Runner', prefix: FAKE_PREFIX_ROTATED }),
    });

    const first = renderProfilePage();
    await waitFor(() => {
      expect(screen.getByTestId('rotate-token-7')).toBeInTheDocument();
    });
    await userEvent.click(screen.getByTestId('rotate-token-7'));

    await waitFor(() => {
      expect(screen.getByTestId('rotated-token-display')).toBeInTheDocument();
    });
    expect(screen.getByTestId('rotated-token-display')).toHaveTextContent(FAKE_TOKEN_ROTATED);
    expect(screen.getByTestId('copy-rotated-token-btn')).toBeInTheDocument();

    // The replacement is stored as metadata only.
    const stored = readStoredMenu();
    expect(JSON.stringify(stored)).not.toContain(FAKE_TOKEN_ROTATED);
    expect(collectStrings(stored).some((s) => s.startsWith('w4a_'))).toBe(false);
    expect(stored.some((entry) => entry.tokenId === 11)).toBe(true);
    expect(stored.some((entry) => entry.tokenId === 7)).toBe(false);

    // Reload: the rotated secret is gone, and the old token is not copyable either.
    first.unmount();
    const second = renderProfilePage();
    await waitFor(() => {
      expect(screen.getByTestId('api-tokens-list')).toHaveTextContent('CI Runner');
    });

    expect(second.container.innerHTML).not.toContain(FAKE_TOKEN_ROTATED);
    expect(document.body.innerHTML).not.toContain(FAKE_TOKEN_ROTATED);
    expect(screen.queryByTestId('rotated-token-display')).not.toBeInTheDocument();
    expect(screen.queryByTestId('copy-rotated-token-btn')).not.toBeInTheDocument();
    expect(screen.queryByTestId('copy-stored-token-7')).not.toBeInTheDocument();
    expect(screen.queryByTestId('copy-stored-token-11')).not.toBeInTheDocument();
  });

  // ── 9. deprecated quick-generate flow ──────────────────────────────────────
  it('no longer persists the quick-generate token either', async () => {
    stubReads([]);
    vi.mocked(apiClient.apiPost).mockResolvedValue({ accessToken: FAKE_LEGACY_JWT } as never);

    const first = renderProfilePage();
    await waitFor(() => {
      expect(screen.getByTestId('generate-token-btn')).toBeInTheDocument();
    });
    await userEvent.click(screen.getByTestId('generate-token-btn'));

    await waitFor(() => {
      expect(screen.getByTestId('legacy-token-display')).toBeInTheDocument();
    });

    expect(localStorage.getItem(LEGACY_GENERATED_TOKEN_KEY)).toBeNull();
    expect(localStorage.getItem(STORED_NAMED_TOKENS_KEY) ?? '').not.toContain(FAKE_LEGACY_JWT);

    // State-only means it is gone after a reload.
    first.unmount();
    const second = renderProfilePage();
    await waitFor(() => {
      expect(screen.getByTestId('generate-token-btn')).toBeInTheDocument();
    });
    expect(second.container.innerHTML).not.toContain(FAKE_LEGACY_JWT);
    expect(screen.queryByTestId('legacy-token-display')).not.toBeInTheDocument();
  });
});
