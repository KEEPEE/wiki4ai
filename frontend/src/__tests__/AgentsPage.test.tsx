/**
 * WIKI4AI-163: AgentsPage tests.
 *
 * The whole stack runs — agentsApi / boardApi / tokenApi → hooks → page — with only
 * the HTTP layer mocked, so the assertions cover the real endpoint shapes the page
 * calls (`Page` for the project-actor listing, bare arrays elsewhere).
 *
 * SECURITY assertions (WIKI4AI-168 / WIKI4AI-177):
 *   1. the mocked `GET /tokens` response carries a full 36-character plaintext in an
 *      extra field, exactly the kind of thing a future backend change would do. The
 *      allow-list in `agentsApi.toTokenInfo()` must drop it, so the page renders the
 *      display prefix and the plaintext appears nowhere in the tree.
 *   2. `POST /tokens/{id}/rotate` returns a 36-character fake. It is shown exactly
 *      once — in the one-time panel — and nowhere else: not in a list row, not in a
 *      `data-*` attribute, not in localStorage. After unmount it is gone for good,
 *      while the prefix stays.
 *   3. `leaseExpiredCount` is asserted to be the metrics endpoint's number, not a
 *      count the UI produced: the actors payload deliberately has no such field.
 *
 * No `setInterval` and no fire-and-forget `setTimeout` is involved (WIKI4AI-175).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import AgentsPage from '../pages/AgentsPage';

vi.mock('../services/apiClient', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
  getRedirectFromUrl: vi.fn(() => null),
}));

import { apiDelete, apiGet, apiPost } from '../services/apiClient';

const mockGet = vi.mocked(apiGet);
const mockPost = vi.mocked(apiPost);
const mockDelete = vi.mocked(apiDelete);

/** `w4a_` + 32 base62 characters = 36 total: the shape of a real token plaintext. */
const FAKE_PLAINTEXT = 'w4a_Abcdefghijklmnopqrstuvwxyz0123';
const ROTATED_PLAINTEXT = 'w4a_ZzYxWvUtSrQpOnMlKjIHgfedcba987';

const ACTORS = [
  {
    id: 11,
    handle: 'ci-agent',
    displayName: 'CI Agent',
    kind: 'agent',
    status: 'active',
    presenceStatus: 'working',
    presenceNote: null,
    lastSeenAt: '2026-09-26T10:00:00',
  },
  {
    id: 12,
    handle: 'human-op',
    displayName: null,
    kind: 'human',
    status: 'paused',
    presenceStatus: 'idle',
    presenceNote: null,
    lastSeenAt: null,
  },
  {
    id: 13,
    handle: 'indexer',
    displayName: null,
    kind: 'service',
    status: 'archived',
    presenceStatus: null,
    presenceNote: null,
    lastSeenAt: null,
  },
];

/** A token row as `/tokens` answers it — plus a leaked plaintext field the UI must drop. */
function tokenRow(overrides: Record<string, unknown> = {}) {
  return {
    id: 55,
    name: 'runner',
    prefix: 'k7x2q9zp',
    scopes: ['workitems'],
    actorId: 11,
    expiresAt: null,
    revokedAt: null,
    lastUsedAt: null,
    createdAt: '2026-09-26T09:00:00',
    hashed: true,
    // Not part of ApiTokenDTO today. If a backend ever adds it, the allow-list is
    // what keeps it out of the DOM.
    token: FAKE_PLAINTEXT,
    ...overrides,
  };
}

/** `ActorMetricsDTO.leaseExpiredCount` — the only source of that number in the app. */
const METRICS = {
  from: '2026-09-01T00:00:00',
  to: '2026-09-30T23:59:59',
  scope: 'all',
  projectId: null,
  actors: [
    {
      actorId: 11,
      eventCount: 40,
      createdCount: 5,
      claimedCount: 12,
      completedCount: 4,
      blockedCount: 1,
      leaseExpiredCount: 3,
      leaseExtendedCount: 9,
      statusSeconds: { claimed: 1800, running: 5400 },
      totalStatusSeconds: 7200,
      cycleCount: 2,
      totalCycleSeconds: 3600,
      averageCycleSeconds: 1800,
    },
  ],
  summary: {
    eventCount: 40,
    createdCount: 5,
    claimedCount: 12,
    completedCount: 4,
    blockedCount: 1,
    leaseExpiredCount: 3,
    leaseExtendedCount: 9,
    cycleCount: 2,
    totalCycleSeconds: 3600,
    averageCycleSeconds: 1800,
    totalStatusSeconds: 7200,
    statusSeconds: { claimed: 1800, running: 5400 },
    backfilledEventCount: 0,
    actorCount: 1,
  },
};

const PROJECT_ACTORS = [
  { id: 7, projectId: 1, actorId: 11, role: 'worker', createdAt: '2026-09-01T08:00:00' },
  { id: 8, projectId: 1, actorId: 12, role: 'planner', createdAt: '2026-09-01T08:00:00' },
];

let tokens: ReturnType<typeof tokenRow>[];

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/agents']}>
        <AgentsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  tokens = [tokenRow()];

  mockGet.mockImplementation(async (url: string) => {
    if (url.startsWith('/api/v1/metrics/actors')) return METRICS;
    if (url.startsWith('/api/v1/projects/')) return PROJECT_ACTORS;
    if (url.startsWith('/api/v1/tokens')) return tokens;
    if (url.startsWith('/api/v1/actors/whoami')) {
      return { userId: 1, username: 'keepee', actorId: null, handle: null, kind: null, scopes: [] };
    }
    if (url.startsWith('/api/v1/actors')) return ACTORS;
    return [];
  });

  mockPost.mockImplementation(async (url: string) => {
    if (url.endsWith('/rotate')) {
      tokens = [
        tokenRow({
          id: 56,
          prefix: 'zz11yy22',
          name: 'runner',
          createdAt: '2026-09-26T12:00:00',
        }),
      ];
      return {
        token: ROTATED_PLAINTEXT,
        metadata: { ...tokens[0], token: ROTATED_PLAINTEXT },
      };
    }
    return {};
  });

  mockDelete.mockImplementation(async (url: string) => {
    // The backend's own answer to a revoke: the row stays, stamped with `revokedAt`.
    if (url.includes('/tokens/55')) {
      tokens = [tokenRow({ revokedAt: '2026-09-26T12:30:00' })];
    }
    return undefined as never;
  });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('AgentsPage (WIKI4AI-163)', () => {
  it('renders handle, kind, status and lastSeenAt for every actor', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByTestId('agent-card-11')).toBeInTheDocument());

    expect(screen.getByTestId('agent-handle-11')).toHaveTextContent('ci-agent');
    expect(screen.getByTestId('agent-kind-11')).toHaveTextContent('agent');
    expect(screen.getByTestId('agent-kind-12')).toHaveTextContent('human');
    expect(screen.getByTestId('agent-kind-13')).toHaveTextContent('service');
    expect(screen.getByTestId('agent-status-11')).toHaveAttribute('data-presence', 'active');
    expect(screen.getByTestId('agent-lastseen-11')).toHaveTextContent('Sep 26');
    // `lastSeenAt: null` is "never", not an empty value and not a made-up time.
    expect(screen.getByTestId('agent-lastseen-12')).toHaveTextContent('never');
  });

  it('shows the project role only after the project-actors endpoint answered', async () => {
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(screen.getByTestId('agent-card-11')).toBeInTheDocument());

    // No project selected → no role claim at all.
    expect(screen.getByTestId('agent-role-11')).toHaveTextContent('unknown');

    await user.type(screen.getByTestId('agents-project-input'), '1');

    await waitFor(() => expect(screen.getByTestId('agent-role-11')).toHaveTextContent('worker'));
    expect(screen.getByTestId('agent-role-12')).toHaveTextContent('planner');
    // Actor 13 has no project_actors row: "unknown", never a default role.
    expect(screen.getByTestId('agent-role-13')).toHaveTextContent('unknown');
  });

  it('renders agent tokens as their display prefix and never the full token', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByTestId('agent-token-55')).toBeInTheDocument());

    // The listing row is the prefix handle only.
    expect(screen.getByTestId('agent-token-55-prefix')).toHaveTextContent('w4a_k7x2q9zp…');

    // The mocked listing response carried a full plaintext; the allow-list dropped it.
    expect(document.body.textContent).not.toContain(FAKE_PLAINTEXT);
    expect(document.body.innerHTML).not.toContain(FAKE_PLAINTEXT);
    expect(localStorage.length).toBe(0);
  });

  it('shows leaseExpiredCount straight from the metrics endpoint', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByTestId('agent-card-11')).toBeInTheDocument());

    // The number is the mocked metrics value, and it came from /metrics/actors.
    expect(screen.getByTestId('agent-leaseexpired-11')).toHaveTextContent('3');
    expect(mockGet).toHaveBeenCalledWith(expect.stringContaining('/api/v1/metrics/actors'));

    // Actors with no metrics row show "no metrics in this window", not 0.
    expect(screen.getByTestId('agent-leaseexpired-12')).toHaveTextContent('no metrics in this window');
  });

  it('rotates a token: the new plaintext is shown exactly once and the prefix updates', async () => {
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(screen.getByTestId('agent-token-55-rotate')).toBeInTheDocument());

    await user.click(screen.getByTestId('agent-token-55-rotate'));

    await waitFor(() => expect(mockPost).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/tokens/55/rotate'),
    ));

    // Exactly one place in the tree carries the plaintext: the one-time panel.
    await waitFor(() => expect(screen.getByTestId('rotated-token-value')).toBeInTheDocument());
    expect(screen.getAllByText(ROTATED_PLAINTEXT)).toHaveLength(1);
    expect(screen.getByTestId('copy-rotated-token-btn')).toBeInTheDocument();

    // The listing itself still shows only a prefix — the rotated row included.
    await waitFor(() => expect(screen.getByTestId('agent-token-56-prefix')).toHaveTextContent('w4a_zz11yy22…'));
    expect(screen.getByTestId('agent-token-56-prefix')).not.toHaveTextContent(ROTATED_PLAINTEXT);

    // And the plaintext is not persisted anywhere.
    expect(localStorage.length).toBe(0);
  });

  it('drops the one-time plaintext on unmount: a remount shows the prefix, never the token', async () => {
    const user = userEvent.setup();
    const view = renderPage();
    await waitFor(() => expect(screen.getByTestId('agent-token-55-rotate')).toBeInTheDocument());

    await user.click(screen.getByTestId('agent-token-55-rotate'));
    await waitFor(() => expect(screen.getByTestId('rotated-token-value')).toBeInTheDocument());
    expect(document.body.textContent).toContain(ROTATED_PLAINTEXT);

    view.unmount();

    // Remount: the backend cannot hand the plaintext back, and neither can the page.
    renderPage();
    await waitFor(() => expect(screen.getByTestId('agent-token-56-prefix')).toBeInTheDocument());
    expect(document.body.textContent).not.toContain(ROTATED_PLAINTEXT);
    expect(screen.queryByTestId('rotated-token-value')).not.toBeInTheDocument();
    expect(screen.getByTestId('agent-token-56-prefix')).toHaveTextContent('w4a_zz11yy22…');
  });

  it('cancels a single token through DELETE /tokens/{id}', async () => {
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(screen.getByTestId('agent-token-55-revoke')).toBeInTheDocument());

    await user.click(screen.getByTestId('agent-token-55-revoke'));

    await waitFor(() => expect(mockDelete).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/tokens/55'),
    ));

    await waitFor(() => expect(screen.getByTestId('agent-token-55-revoked')).toBeInTheDocument());
    // A revoked token cannot be rotated or cancelled again.
    expect(screen.getByTestId('agent-token-55-rotate')).toBeDisabled();
    expect(screen.getByTestId('agent-token-55-revoke')).toBeDisabled();
  });

  it('cancels every token of an agent through DELETE /actors/{id}/tokens', async () => {
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(screen.getByTestId('agent-revoke-all-11')).toBeInTheDocument());

    await user.click(screen.getByTestId('agent-revoke-all-11'));

    await waitFor(() => expect(mockDelete).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/actors/11/tokens'),
    ));
  });
});
