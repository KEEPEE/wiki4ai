/**
 * WIKI4AI-161: BoardPage tests.
 *
 * The whole stack runs here — boardApi → useBoard → BoardPage — with only the HTTP
 * layer mocked, so the assertions cover the real paths the board calls and the real
 * DTO the board receives.
 *
 * SECURITY assertion (WIKI4AI-177): the mocked backend response DOES carry a
 * `claimToken` on every work item, exactly like the live API does today. The board
 * must not render it anywhere — not as text, not in a `title`, not in a `data-*`
 * attribute — and the lease must be shown as `leaseExpiresAt` instead.
 *
 * No timer is created by these tests and none is created by the page: the countdown
 * is `leaseExpiresAt − fetchedAt` (WIKI4AI-175).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import BoardPage from '../pages/BoardPage';

vi.mock('../services/apiClient', () => ({
  apiGet: vi.fn(),
}));

import { apiGet } from '../services/apiClient';

const mockGet = vi.mocked(apiGet);

/** 64 characters, the shape of a real lease token; an obviously fake body. */
const FAKE_CLAIM_TOKEN =
  'fake0claim0token0not0a0real0secret00000000000000000000000000000000deadbeef';

const minutesFromNow = (minutes: number) =>
  new Date(Date.now() + minutes * 60_000).toISOString();

function workItem(id: number, status: string, overrides: Record<string, unknown> = {}) {
  return {
    id,
    projectId: 1,
    title: `Item ${id}`,
    body: null,
    status,
    priority: 'high',
    labels: id === 1 ? ['frontend', 'coordination'] : [],
    parentId: null,
    externalRef: null,
    assigneeActorId: id === 1 ? 11 : id === 2 ? 12 : null,
    // The live API still returns this secret today (WIKI4AI-177). The UI must drop it.
    claimToken: FAKE_CLAIM_TOKEN,
    leaseExpiresAt: null,
    createdByUserId: 1,
    createdByActorId: null,
    updatedByActorId: null,
    createdAt: '2026-09-26T10:00:00',
    updatedAt: '2026-09-26T10:00:00',
    version: 1,
    ...overrides,
  };
}

const WORK_ITEMS = [
  workItem(1, 'running', { leaseExpiresAt: minutesFromNow(5) }),
  // A lease that already ran out: `lease_expired` is a legitimate state and the
  // card has to say so out loud.
  workItem(2, 'claimed', { leaseExpiresAt: minutesFromNow(-2) }),
  workItem(3, 'backlog'),
  workItem(4, 'blocked'),
  workItem(5, 'cancelled'),
  workItem(6, 'ready'),
  workItem(7, 'review'),
  workItem(8, 'done'),
];

const ACTORS = [
  { id: 11, handle: 'ci-agent', displayName: 'CI Agent', kind: 'agent', status: 'active', presenceStatus: 'working', presenceNote: null, lastSeenAt: null },
  { id: 12, handle: 'scraper-bot', displayName: null, kind: 'agent', status: 'paused', presenceStatus: 'idle', presenceNote: null, lastSeenAt: null },
  { id: 13, handle: 'old-runner', displayName: null, kind: 'agent', status: 'archived', presenceStatus: null, presenceNote: null, lastSeenAt: null },
];

const RESOURCES = [
  { id: 1, key: 'gpu', name: 'Render GPU', kind: 'hardware', capacity: 2, activeSlots: 1, enabled: true, metadata: null, availableSlots: 1, createdAt: null },
  { id: 2, key: 'llm-slots', name: null, kind: 'quota', capacity: 5, activeSlots: 5, enabled: true, metadata: null, availableSlots: 0, createdAt: null },
];

const BLOCKERS = [
  {
    id: 90,
    workItemId: 4,
    authorActorId: 11,
    kind: 'blocker',
    body: 'Migration 27 fails on a fresh schema — needs a human decision.',
    payload: { blocking: 'migration 27', needs_human: true },
    createdAt: '2026-09-26T11:00:00',
  },
];

function renderBoard() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/board']}>
        <BoardPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  mockGet.mockImplementation(async (url: string) => {
    if (url.startsWith('/api/v1/work-items/')) {
      if (url.includes('/comments')) {
        const id = Number(url.match(/work-items\/(\d+)/)?.[1]);
        return { content: id === 4 ? BLOCKERS : [] };
      }
      if (url.includes('/events')) return { content: [] };
      const match = url.match(/work-items\/(\d+)/);
      return workItem(match ? Number(match[1]) : 1, 'ready');
    }
    if (url.startsWith('/api/v1/work-items')) return WORK_ITEMS;
    if (url.startsWith('/api/v1/actors')) return ACTORS;
    if (url.includes('/reservations')) return [];
    if (url.startsWith('/api/v1/resources')) return RESOURCES;
    return [];
  });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('BoardPage (WIKI4AI-161)', () => {
  it('renders a column for every state, blocked and cancelled included', async () => {
    renderBoard();
    await waitFor(() => expect(screen.getByTestId('board-card-1')).toBeInTheDocument());

    for (const status of ['backlog', 'ready', 'claimed', 'running', 'review', 'done', 'blocked', 'cancelled']) {
      expect(screen.getByTestId(`board-column-${status}`)).toBeInTheDocument();
    }
    expect(screen.getByTestId('board-column-count-blocked')).toHaveTextContent('1');
    expect(screen.getByTestId('board-column-count-cancelled')).toHaveTextContent('1');
  });

  it('a card shows its title, its assignee with a presence dot, and its labels', async () => {
    renderBoard();
    const card = await screen.findByTestId('board-card-1');
    expect(screen.getByTestId('board-card-title-1')).toHaveTextContent('Item 1');

    const assignee = screen.getByTestId('board-card-assignee-1');
    expect(assignee).toHaveTextContent('ci-agent');
    // The dot is a rendering of ActorDTO.status, not an inference.
    expect(assignee).toHaveAttribute('data-presence', 'active');

    expect(screen.getByTestId('board-card-labels-1')).toHaveTextContent('frontend');
    expect(screen.getByTestId('board-card-labels-1')).toHaveTextContent('coordination');
    expect(card).toHaveAttribute('data-status', 'running');
  });

  it('an unassigned item says so; an assignee the API did not send is not invented', async () => {
    renderBoard();
    await screen.findByTestId('board-card-1');
    expect(screen.getByTestId('board-card-assignee-3')).toHaveTextContent('Unassigned');
  });

  it('a live lease is counted down from leaseExpiresAt', async () => {
    renderBoard();
    const lease = await screen.findByTestId('board-card-lease-1');
    expect(lease).toHaveTextContent(/\d+m/);
    expect(lease).not.toHaveTextContent('Lease expired');
  });

  it('an elapsed lease is marked as expired, not hidden', async () => {
    renderBoard();
    const lease = await screen.findByTestId('board-card-lease-2');
    expect(lease).toHaveTextContent('Lease expired');
    expect(screen.getByTestId('board-card-2')).toHaveClass('board-card-lease-expired');
  });

  it('an item with no lease renders no countdown at all', async () => {
    renderBoard();
    await screen.findByTestId('board-card-1');
    expect(screen.queryByTestId('board-card-lease-3')).not.toBeInTheDocument();
  });

  it('sidebar: blocker comments the backend flags needs_human are listed', async () => {
    renderBoard();
    const list = await screen.findByTestId('board-waiting-list');
    expect(list).toHaveTextContent('needs a human decision');
    expect(screen.getByTestId('board-waiting-90')).toHaveTextContent('Item 4');
  });

  it('sidebar: resource capacity is activeSlots over capacity from /resources', async () => {
    renderBoard();
    const capacity = await screen.findByTestId('board-capacity-list');
    expect(capacity).toHaveTextContent('Render GPU');
    expect(screen.getByTestId('board-capacity-gpu')).toHaveTextContent('1 / 2 slots');
    expect(screen.getByTestId('board-capacity-llm-slots')).toHaveTextContent('5 / 5 slots');
  });

  it('sidebar: live agents come from /actors with their status', async () => {
    renderBoard();
    const agents = await screen.findByTestId('board-agents-list');
    expect(agents).toHaveTextContent('ci-agent');
    expect(agents).toHaveTextContent('old-runner');
    expect(screen.getByTestId('board-agent-11')).toHaveAttribute('data-presence', 'active');
    expect(screen.getByTestId('board-agent-13')).toHaveAttribute('data-presence', 'archived');
    expect(screen.getByTestId('board-agents-active-count')).toHaveTextContent('1');
  });

  it('the status filter is sent to the API as a parameter, not applied client-side', async () => {
    renderBoard();
    await screen.findByTestId('board-card-1');
    mockGet.mockClear();

    await userEvent.selectOptions(screen.getByTestId('board-filter-status'), 'blocked');

    await waitFor(() => {
      const urls = mockGet.mock.calls.map((call) => call[0] as string);
      expect(urls.some((u) => u.includes('/api/v1/work-items?status=blocked'))).toBe(true);
    });
  });

  it('the label filter is sent as a whole-token parameter', async () => {
    renderBoard();
    await screen.findByTestId('board-card-1');
    mockGet.mockClear();

    await userEvent.type(screen.getByTestId('board-filter-label'), 'coordination');

    await waitFor(() => {
      const urls = mockGet.mock.calls.map((call) => call[0] as string);
      expect(urls.some((u) => u.includes('label=coordination'))).toBe(true);
    });
  });

  it('SECURITY (WIKI4AI-177): the claim token the API returns is nowhere in the rendered tree', async () => {
    const { container } = renderBoard();
    await screen.findByTestId('board-card-1');

    const html = container.innerHTML;
    expect(html).not.toContain(FAKE_CLAIM_TOKEN);
    expect(html).not.toContain('claimToken');
    expect(html).not.toContain('claim_token');
    // The lease is rendered as its deadline instead.
    expect(screen.getByTestId('board-card-lease-1')).toBeInTheDocument();
  });
});
