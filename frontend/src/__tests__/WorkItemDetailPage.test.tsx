/**
 * WIKI4AI-162: WorkItemDetailPage tests.
 *
 * Service → hook → page with only the HTTP layer mocked, asserting:
 *   - the timeline is chronological and `lease_expired` is rendered as its own,
 *     visually distinct entry (never swallowed by a generic status row),
 *   - comments are grouped by kind with a badge per kind,
 *   - the handoff card shows `to_handle` / `context` and marks a missing key as
 *     missing rather than rendering a blank,
 *   - reservations are the rows whose own `workItemId` is this item, live vs released
 *     read from `releasedAt`,
 *   - "waiting in state X for N min" is measured between the last status change and
 *     the newest API-reported moment — not against the wall clock,
 *   - and the claim secret the API still returns (WIKI4AI-177) is not rendered.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import WorkItemDetailPage from '../pages/WorkItemDetailPage';

vi.mock('../services/apiClient', () => ({
  apiGet: vi.fn(),
}));

import { apiGet } from '../services/apiClient';

const mockGet = vi.mocked(apiGet);

/** 64 characters, the shape of a real lease token; an obviously fake body. */
const FAKE_CLAIM_TOKEN =
  'fake0claim0token0not0a0real0secret00000000000000000000000000000000deadbeef';

const ITEM = {
  id: 42,
  projectId: 1,
  title: 'Reindex the search sidecar',
  body: 'Rebuild the embedding index after migration 27.',
  status: 'blocked',
  priority: 'high',
  labels: ['search', 'agents'],
  parentId: null,
  externalRef: 'JIRA-42',
  assigneeActorId: 11,
  claimToken: FAKE_CLAIM_TOKEN,
  // Lease already elapsed: the detail page must show it as expired.
  leaseExpiresAt: '2026-01-01T09:00:00Z',
  createdByUserId: 1,
  createdByActorId: 11,
  updatedByActorId: 11,
  createdAt: '2026-01-01T08:00:00Z',
  updatedAt: '2026-01-01T12:00:00Z',
  version: 6,
};

const EVENTS = [
  { id: 501, workItemId: 42, actorId: 11, event: 'created', fromStatus: null, toStatus: 'backlog', payload: null, createdAt: '2026-01-01T08:00:00Z' },
  { id: 502, workItemId: 42, actorId: 11, event: 'status_changed', fromStatus: 'backlog', toStatus: 'ready', payload: null, createdAt: '2026-01-01T08:30:00Z' },
  { id: 503, workItemId: 42, actorId: 11, event: 'claimed', fromStatus: 'ready', toStatus: 'claimed', payload: null, createdAt: '2026-01-01T09:00:00Z' },
  { id: 504, workItemId: 42, actorId: null, event: 'lease_expired', fromStatus: 'claimed', toStatus: 'ready', payload: null, createdAt: '2026-01-01T10:00:00Z' },
  { id: 505, workItemId: 42, actorId: 12, event: 'status_changed', fromStatus: 'ready', toStatus: 'blocked', payload: null, createdAt: '2026-01-01T11:00:00Z' },
];

const COMMENTS = [
  { id: 700, workItemId: 42, authorActorId: 11, kind: 'status', body: 'Started on the index rebuild.', payload: null, createdAt: '2026-01-01T09:05:00Z' },
  {
    id: 701,
    workItemId: 42,
    authorActorId: 11,
    kind: 'blocker',
    body: 'Migration 27 is not applied on this host.',
    payload: { blocking: 'migration 27', needs_human: true },
    createdAt: '2026-01-01T11:00:00Z',
  },
  {
    id: 702,
    workItemId: 42,
    authorActorId: 11,
    kind: 'handoff',
    body: 'Handing over to the reviewer.',
    payload: { to_handle: 'reviewer-bot', context: 'Schema is patched; needs a re-run of the indexer.' },
    createdAt: '2026-01-01T11:10:00Z',
  },
  {
    id: 703,
    workItemId: 42,
    authorActorId: 12,
    kind: 'handoff',
    body: 'Incomplete handoff from the previous run.',
    payload: { context: 'No target was named.' },
    createdAt: '2026-01-01T11:20:00Z',
  },
  {
    id: 704,
    workItemId: 42,
    authorActorId: 12,
    kind: 'result',
    body: 'Index rebuilt on staging.',
    payload: { where: 'staging', next: 'production', artifacts: ['index-27.tar.gz'] },
    createdAt: '2026-01-01T12:00:00Z',
  },
  { id: 705, workItemId: 42, authorActorId: 12, kind: 'note', body: 'Kept the old index for rollback.', payload: null, createdAt: '2026-01-01T12:05:00Z' },
  {
    id: 706,
    workItemId: 42,
    authorActorId: 12,
    kind: 'decision',
    body: 'Ship the new index behind a flag.',
    payload: { what: 'flagged rollout', why: 'rollback safety', applies_to: 'search sidecar' },
    createdAt: '2026-01-01T12:10:00Z',
  },
];

const RESOURCES = [
  { id: 1, key: 'gpu', name: 'Render GPU', kind: 'hardware', capacity: 2, activeSlots: 1, enabled: true, metadata: null, availableSlots: 1, createdAt: null },
];

const RESERVATIONS = [
  { id: 800, resourceId: 1, resourceKey: 'gpu', actorId: 11, workItemId: 42, slots: 1, reason: 'index rebuild', expiresAt: '2026-01-01T13:00:00Z', createdAt: '2026-01-01T09:00:00Z' },
  { id: 801, resourceId: 1, resourceKey: 'gpu', actorId: 12, workItemId: 42, slots: 1, reason: 'old run', expiresAt: '2026-01-01T10:00:00Z', releasedAt: '2026-01-01T09:50:00Z', createdAt: '2026-01-01T08:00:00Z' },
  { id: 802, resourceId: 1, resourceKey: 'gpu', actorId: 13, workItemId: 77, slots: 1, reason: 'someone else', expiresAt: '2026-01-01T14:00:00Z', createdAt: '2026-01-01T09:00:00Z' },
];

const ACTORS = [
  { id: 11, handle: 'ci-agent', displayName: 'CI Agent', kind: 'agent', status: 'active', presenceStatus: 'working', presenceNote: null, lastSeenAt: null },
  { id: 12, handle: 'reviewer-bot', displayName: null, kind: 'agent', status: 'paused', presenceStatus: 'idle', presenceNote: null, lastSeenAt: null },
];

function renderDetail(entry = '/board/42') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <Route path="/board" element={<div>board route</div>} />
          <Route path="/board/:id" element={<WorkItemDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  mockGet.mockImplementation(async (url: string) => {
    if (url.includes('/events')) return { content: EVENTS };
    if (url.includes('/comments')) return { content: COMMENTS };
    if (url.includes('/reservations')) return RESERVATIONS;
    if (url.startsWith('/api/v1/resources')) return RESOURCES;
    if (url.startsWith('/api/v1/actors')) return ACTORS;
    if (url.startsWith('/api/v1/work-items')) return ITEM;
    return [];
  });
});

describe('WorkItemDetailPage (WIKI4AI-162)', () => {
  it('renders the item header: title, status, labels and external ref', async () => {
    renderDetail();
    expect(await screen.findByTestId('wi-title')).toHaveTextContent('Reindex the search sidecar');
    expect(screen.getByTestId('wi-status')).toHaveTextContent('Blocked');
    expect(screen.getByTestId('wi-labels')).toHaveTextContent('search');
    expect(screen.getByTestId('wi-external-ref')).toHaveTextContent('JIRA-42');
  });

  it('timeline is chronological and every event is labelled', async () => {
    renderDetail();
    const timeline = await screen.findByTestId('wi-timeline');
    const entries = timeline.querySelectorAll('li[data-testid]');
    const ids = Array.from(entries).map((el) => el.getAttribute('data-testid'));
    expect(ids).toEqual([
      'wi-event-501', 'wi-event-502', 'wi-event-503', 'wi-event-504', 'wi-event-505',
    ]);
    expect(screen.getByTestId('wi-event-status-502')).toHaveTextContent('Backlog');
    expect(screen.getByTestId('wi-event-status-502')).toHaveTextContent('Ready');
  });

  it('lease_expired is rendered and visually distinct, not swallowed', async () => {
    renderDetail();
    const expired = await screen.findByTestId('wi-event-504');
    expect(expired).toHaveAttribute('data-event', 'lease_expired');
    expect(expired).toHaveClass('wi-event-lease-expired');
    expect(screen.getByTestId('wi-event-alert-504')).toHaveTextContent('lease ran out');
  });

  it('comments are grouped by kind and each carries its badge', async () => {
    renderDetail();
    await screen.findByTestId('wi-comments');
    for (const kind of ['status', 'blocker', 'result', 'handoff', 'note', 'decision']) {
      expect(screen.getByTestId(`wi-comment-group-${kind}`)).toBeInTheDocument();
    }
    expect(screen.getByTestId('wi-comment-group-blocker')).toHaveTextContent('blocker');
    expect(screen.getByTestId('wi-comment-704')).toHaveTextContent('Index rebuilt on staging');
    expect(screen.getByTestId('wi-comment-704')).toHaveAttribute('data-kind', 'result');
    expect(screen.getByTestId('wi-comment-706')).toHaveTextContent('rollback safety');
  });

  it('handoff card shows what / where / who from the payload', async () => {
    renderDetail();
    const card = await screen.findByTestId('wi-handoff-702');
    expect(card).toHaveTextContent('reviewer-bot');
    expect(card).toHaveTextContent('needs a re-run of the indexer');
    expect(screen.queryByTestId('wi-handoff-warning-702')).not.toBeInTheDocument();
  });

  it('a handoff missing to_handle says it is missing instead of rendering blank', async () => {
    renderDetail();
    const card = await screen.findByTestId('wi-handoff-703');
    expect(card).toHaveTextContent('missing to_handle');
    expect(screen.getByTestId('wi-handoff-warning-703')).toHaveTextContent('to_handle');
  });

  it('reservations are the rows of this item, live vs released from releasedAt', async () => {
    renderDetail();
    // The reservation ledger is read per resource key, so it resolves one round
    // after /resources — wait for the rows instead of assuming they are already there.
    await waitFor(() => expect(screen.getByTestId('wi-reservation-800')).toBeInTheDocument());
    const list = screen.getByTestId('wi-reservations');
    expect(list).toHaveTextContent('index rebuild');
    expect(list).not.toHaveTextContent('someone else');
    expect(screen.getByTestId('wi-reservation-800')).toHaveTextContent('live until');
    expect(screen.getByTestId('wi-reservation-801')).toHaveTextContent('released');
  });

  it('waiting time is measured from the last status change to the newest API moment', async () => {
    renderDetail();
    // Last status_changed: 11:00. Newest API moment (updatedAt 12:00) → 60 min.
    const waiting = await screen.findByTestId('wi-waiting');
    expect(waiting).toHaveTextContent('blocked');
    expect(waiting).toHaveTextContent('60 min');
  });

  it('shows the waiting card for a claimed item whose timeline has only created + claimed', async () => {
    // Regression guard for the live defect: a claimed item's timeline carries NO
    // `status_changed` row — the entry into the state is the `claimed` event itself.
    mockGet.mockImplementation(async (url: string) => {
      if (url.includes('/events')) {
        return {
          content: [
            { id: 901, workItemId: 42, actorId: 11, event: 'created', fromStatus: null, toStatus: 'ready', payload: null, createdAt: '2026-01-01T10:00:00Z' },
            { id: 902, workItemId: 42, actorId: 11, event: 'claimed', fromStatus: 'ready', toStatus: 'claimed', payload: null, createdAt: '2026-01-01T10:10:00Z' },
          ],
        };
      }
      if (url.includes('/comments')) return { content: [] };
      if (url.includes('/reservations')) return [];
      if (url.startsWith('/api/v1/resources')) return RESOURCES;
      if (url.startsWith('/api/v1/actors')) return ACTORS;
      if (url.startsWith('/api/v1/work-items')) {
        return { ...ITEM, status: 'claimed', updatedAt: '2026-01-01T10:40:00Z' };
      }
      return [];
    });

    renderDetail();
    // Entry `claimed` at 10:10, newest API-reported moment (updatedAt 10:40) → 30 min.
    const waiting = await screen.findByTestId('wi-waiting');
    expect(waiting).toHaveTextContent('claimed');
    expect(waiting).toHaveTextContent('30 min');
  });

  it('a lease_extended heartbeat does not restart the wait on the detail page', async () => {
    mockGet.mockImplementation(async (url: string) => {
      if (url.includes('/events')) {
        return {
          content: [
            { id: 911, workItemId: 42, actorId: 11, event: 'claimed', fromStatus: 'ready', toStatus: 'claimed', payload: null, createdAt: '2026-01-01T10:00:00Z' },
            { id: 912, workItemId: 42, actorId: 11, event: 'lease_extended', fromStatus: 'claimed', toStatus: 'claimed', payload: null, createdAt: '2026-01-01T10:50:00Z' },
          ],
        };
      }
      if (url.includes('/comments')) return { content: [] };
      if (url.includes('/reservations')) return [];
      if (url.startsWith('/api/v1/resources')) return RESOURCES;
      if (url.startsWith('/api/v1/actors')) return ACTORS;
      if (url.startsWith('/api/v1/work-items')) {
        return { ...ITEM, status: 'claimed', updatedAt: '2026-01-01T10:50:00Z' };
      }
      return [];
    });

    renderDetail();
    // Measured from `claimed` (10:00) to 10:50 → 50 min, not 0 from the renewal.
    const waiting = await screen.findByTestId('wi-waiting');
    expect(waiting).toHaveTextContent('50 min');
  });

  it('an elapsed lease is shown as expired with its deadline', async () => {
    renderDetail();
    const lease = await screen.findByTestId('wi-lease');
    expect(lease).toHaveTextContent('Lease expired');
  });

  it('an item with no lease says there is none', async () => {
    mockGet.mockImplementation(async (url: string) => {
      if (url.startsWith('/api/v1/work-items/42')) return { ...ITEM, leaseExpiresAt: null };
      if (url.includes('/events')) return { content: [] };
      if (url.includes('/comments')) return { content: [] };
      if (url.includes('/reservations')) return [];
      if (url.startsWith('/api/v1/resources')) return RESOURCES;
      if (url.startsWith('/api/v1/actors')) return ACTORS;
      return { ...ITEM, leaseExpiresAt: null };
    });
    renderDetail();
    const lease = await screen.findByTestId('wi-lease');
    expect(lease).toHaveTextContent('No active lease');
    expect(screen.getByTestId('wi-timeline-empty')).toBeInTheDocument();
    expect(screen.getByTestId('wi-comments-empty')).toBeInTheDocument();
    expect(screen.getByTestId('wi-reservations-empty')).toBeInTheDocument();
    // No status change in the timeline → no waiting card at all.
    expect(screen.queryByTestId('wi-waiting')).not.toBeInTheDocument();
  });

  it('SECURITY (WIKI4AI-177): the claim token is not rendered anywhere on the detail', async () => {
    const { container } = renderDetail();
    await screen.findByTestId('wi-title');

    const html = container.innerHTML;
    expect(html).not.toContain(FAKE_CLAIM_TOKEN);
    expect(html).not.toContain('claimToken');
    expect(html).not.toContain('claim_token');
  });

  it('a bad id in the route does not fetch the work item', async () => {
    renderDetail('/board/not-a-number');
    await waitFor(() => expect(screen.getByTestId('wi-not-found')).toBeInTheDocument());
    const urls = mockGet.mock.calls.map((call) => call[0] as string);
    expect(urls.some((u) => u.includes('/work-items'))).toBe(false);
  });
});
