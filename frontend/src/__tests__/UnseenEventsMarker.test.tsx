/**
 * WIKI4AI-165: the unseen-events marker in the navigation.
 *
 * Three things are pinned here.
 *
 * 1. Actor session: the badge is `UnseenEventsDTO.count` from
 *    `GET /api/v1/actors/{id}/inbox/unseen`, and opening the marker POSTs
 *    `mark-seen` with NO body, after which the re-read says 0 and the badge is gone.
 *
 * 2. Legacy-JWT session (`actorId: null`): the badge is the derivation over the
 *    `WorkItemEventDTO.id`s the API actually delivered to this browser, compared with
 *    the maximum this browser has already been shown. Opening the marker moves that
 *    maximum and the badge disappears. No request is made to an inbox endpoint,
 *    because this caller has no inbox.
 *
 * 3. The count is not produced by a clock. `Date.now()` is made to throw while the
 *    derivation runs, and the derivation still answers — and a source scan asserts
 *    that neither the marker hook nor the derivation module contains `Date.now(` at
 *    all. A count built from timestamps would change on every render and would count
 *    events the API never sent.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { readFileSync } from 'node:fs';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { Menu, MenuItem } from '../components/Menu';
import {
  UnseenEventsBadge,
  UnseenEventsMenuItem,
  UnseenEventsProvider,
} from '../components/UnseenEventsMarker';
import {
  clearSeenEventHighWater,
  maxEventId,
  readSeenEventHighWater,
  recordSeenEventIds,
  unseenEventIds,
  SEEN_EVENT_HIGH_WATER_KEY,
} from '../utils/unseenEvents';

vi.mock('../services/apiClient', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
  getRedirectFromUrl: vi.fn(() => null),
}));

import { apiGet, apiPost } from '../services/apiClient';

const mockGet = vi.mocked(apiGet);
const mockPost = vi.mocked(apiPost);

function workItem(id: number) {
  return {
    id,
    projectId: 1,
    title: `Item ${id}`,
    body: null,
    status: 'ready',
    priority: null,
    labels: [],
    parentId: null,
    externalRef: null,
    assigneeActorId: null,
    claimToken: 'irrelevant-to-this-test',
    leaseExpiresAt: null,
    createdByUserId: 1,
    createdByActorId: null,
    updatedByActorId: null,
    createdAt: '2026-09-26T10:00:00',
    updatedAt: '2026-09-26T10:00:00',
    version: 1,
  };
}

/** Spring `Page` shape — the shape `/work-items/{id}/events` really answers with. */
function eventPage(ids: number[]) {
  return { content: ids.map((id) => ({ id, workItemId: 1, actorId: 11, event: 'status_changed', fromStatus: 'ready', toStatus: 'claimed', payload: null, createdAt: '2026-09-26T10:00:00' })), totalElements: ids.length };
}

function renderMarker() {
  return render(
    <MemoryRouter initialEntries={['/board']}>
      <UnseenEventsProvider>
        <Menu
          trigger={
            <>
              <UnseenEventsBadge />
              <span>menu</span>
            </>
          }
          ariaLabel="user menu"
          testId="marker-trigger"
          panelTestId="marker-panel"
        >
          <UnseenEventsMenuItem />
          <MenuItem testId="decoy-item">Decoy</MenuItem>
        </Menu>
      </UnseenEventsProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('UnseenEventsMarker (WIKI4AI-165)', () => {
  it('actor session: the badge is the server count and mark-seen clears it', async () => {
    const user = userEvent.setup();
    let unseenCount = 7;

    mockGet.mockImplementation(async (url: string) => {
      if (url.includes('/inbox/unseen')) {
        return { count: unseenCount, eventIds: [101, 102], lastSeenEventId: 100, newestEventId: 102 };
      }
      if (url.includes('/actors/whoami')) {
        return { userId: 1, username: 'keepee', actorId: 11, handle: 'ci-agent', kind: 'agent', scopes: [] };
      }
      return [];
    });
    mockPost.mockImplementation(async () => {
      unseenCount = 0;
      return undefined as never;
    });

    renderMarker();

    await waitFor(() => expect(screen.getByTestId('unseen-events-badge')).toBeInTheDocument());
    expect(screen.getByTestId('unseen-events-badge')).toHaveTextContent('7');
    expect(screen.getByTestId('unseen-events-badge')).toHaveAttribute('data-source', 'actor-inbox');
    expect(mockGet).toHaveBeenCalledWith(expect.stringContaining('/api/v1/actors/11/inbox/unseen'));

    await user.click(screen.getByTestId('marker-trigger'));
    expect(screen.getByTestId('user-menu-new-events')).toHaveTextContent('7');

    await user.click(screen.getByTestId('user-menu-new-events'));

    // No body: "everything the server has shown me is seen".
    await waitFor(() => expect(mockPost).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/actors/11/inbox/mark-seen'),
    ));
    const call = mockPost.mock.calls.find(([url]) => url.includes('mark-seen'));
    expect(call?.[1]).toBeUndefined();

    await waitFor(() => expect(screen.queryByTestId('unseen-events-badge')).not.toBeInTheDocument());
  });

  it('legacy JWT session: the badge counts delivered event ids above the remembered maximum', async () => {
    const user = userEvent.setup();

    mockGet.mockImplementation(async (url: string) => {
      if (url.includes('/actors/whoami')) {
        // The dev human session: a legacy JWT has no actor, so there is no inbox.
        return { userId: 1, username: 'keepee', actorId: null, handle: null, kind: null, scopes: [] };
      }
      if (url.includes('/events')) return eventPage([10, 11]);
      if (url.includes('/work-items')) return [workItem(1), workItem(2)];
      return [];
    });

    renderMarker();

    // Nothing has ever been shown, so every delivered id is new: 10 and 11.
    await waitFor(() => expect(screen.getByTestId('unseen-events-badge')).toHaveTextContent('2'));
    expect(screen.getByTestId('unseen-events-badge')).toHaveAttribute('data-source', 'browser-events');

    // No inbox call: this caller has no actor, and the UI does not ask anyway.
    expect(mockGet).not.toHaveBeenCalledWith(expect.stringContaining('/inbox/'));

    await user.click(screen.getByTestId('marker-trigger'));
    await user.click(screen.getByTestId('user-menu-new-events'));

    // The browser now remembers id 11; the same delivered ids are no longer new.
    await waitFor(() => expect(screen.queryByTestId('unseen-events-badge')).not.toBeInTheDocument());
    expect(readSeenEventHighWater()).toBe(11);
  });

  it('renders nothing when neither read produced an answer', async () => {
    mockGet.mockImplementation(async (url: string) => {
      if (url.includes('/actors/whoami')) throw new Error('403');
      if (url.includes('/work-items')) throw new Error('500');
      return [];
    });

    renderMarker();

    // Give the queries time to fail before asserting the badge stayed away.
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.queryByTestId('unseen-events-badge')).not.toBeInTheDocument();
    expect(screen.queryByTestId('user-menu-new-events')).not.toBeInTheDocument();
  });

  it('derives the count from event ids only — Date.now() is not a source', () => {
    // Make the clock explode: a derivation that read it would throw here.
    const clock = vi.spyOn(Date, 'now').mockImplementation(() => {
      throw new Error('the marker must not read the wall clock to count events');
    });

    expect(unseenEventIds([5, 6, 7], 5)).toEqual([6, 7]);
    // `null` is "never shown", not 0 — the same rule the backend documents for
    // `lastSeenEventId`.
    expect(unseenEventIds([5, 6, 7], null)).toEqual([5, 6, 7]);
    expect(unseenEventIds([5, 6], 6)).toEqual([]);
    expect(maxEventId([5, 9, 3])).toBe(9);
    expect(maxEventId([])).toBeNull();

    expect(clock).not.toHaveBeenCalled();
  });

  it('the high-water mark stores only a server-assigned event id', () => {
    expect(recordSeenEventIds([4, 12, 7])).toBe(12);
    expect(localStorage.getItem(SEEN_EVENT_HIGH_WATER_KEY)).toBe('12');
    // Monotonic: an older scan never moves the marker backwards.
    expect(recordSeenEventIds([3])).toBe(12);
    clearSeenEventHighWater();
    expect(readSeenEventHighWater()).toBeNull();
  });

  it('the marker module contains no Date.now() at all', () => {
    // Relative to the frontend root, which is the vitest cwd. Comments are stripped
    // first: these files *document* that they do not read the clock, and that
    // documentation mentions `Date.now()` on purpose.
    const code = (path: string) =>
      readFileSync(path, 'utf8')
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .replace(/\/\/.*$/gm, '');

    const hook = code('src/hooks/useUnseenEvents.ts');
    const derivation = code('src/utils/unseenEvents.ts');
    const marker = code('src/components/UnseenEventsMarker.tsx');

    expect(hook).not.toContain('Date.now(');
    expect(derivation).not.toContain('Date.now(');
    expect(marker).not.toContain('Date.now(');
    // And no `setInterval` either — the marker polls with refetchInterval (WIKI4AI-175).
    expect(hook).not.toContain('setInterval');
    expect(marker).not.toContain('setInterval');
  });
});
