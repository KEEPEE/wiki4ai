/**
 * WIKI4AI-161 + WIKI4AI-162: end-to-end of the coordination board and its detail.
 *
 * Run:  npm run dev   (Vite on http://localhost:5173)
 *       npx playwright test e2e/board.spec.ts
 *
 * The spec drives the real SPA (React Router, ProtectedRoute, react-query polling)
 * and stubs only the HTTP boundary with `page.route`, so it needs no backend and
 * touches no production host. Every value below is a synthetic fixture.
 *
 * It also proves the WIKI4AI-177 boundary in a real browser: the stubbed API returns
 * `claimToken` on every work item — exactly what the live API does today — and the
 * page must not put that secret anywhere in the DOM.
 */
import { test, expect } from '@playwright/test';

/** 64 characters, the shape of a real lease token; an obviously fake body. */
const FAKE_CLAIM_TOKEN =
  'fake0claim0token0not0a0real0secret00000000000000000000000000000000deadbeef';

const ACCESS_TOKEN_KEY = 'wiki4ai_access_token';
const USER_INFO_KEY = 'wiki4ai_user_info';

/** A fake JWT with a future `exp` — the shape AuthContext needs to treat the session as live. */
function seedFakeSession(keys: { accessKey: string; userKey: string }) {
  const payload = btoa(JSON.stringify({ sub: 'e2e-board', exp: Math.floor(Date.now() / 1000) + 3600 }));
  window.localStorage.setItem(keys.accessKey, `e2eheader.${payload}.e2esignature`);
  window.localStorage.setItem(
    keys.userKey,
    JSON.stringify({ id: 1, username: 'e2e_board', email: 'e2e@example.com', role: 'USER', language: 'en' }),
  );
}

const minutesFromNow = (minutes: number) => new Date(Date.now() + minutes * 60_000).toISOString();

function workItem(id: number, status: string, overrides: Record<string, unknown> = {}) {
  return {
    id,
    projectId: 1,
    title: `E2E item ${id}`,
    body: 'Synthetic e2e fixture.',
    status,
    priority: 'high',
    labels: ['e2e'],
    parentId: null,
    externalRef: null,
    assigneeActorId: 11,
    // The live API still returns the lease secret today (WIKI4AI-177).
    claimToken: FAKE_CLAIM_TOKEN,
    leaseExpiresAt: null,
    createdByUserId: 1,
    createdByActorId: 11,
    updatedByActorId: 11,
    createdAt: '2026-01-01T08:00:00',
    updatedAt: '2026-01-01T08:00:00',
    version: 1,
    ...overrides,
  };
}

const WORK_ITEMS = [
  workItem(1, 'running', { leaseExpiresAt: minutesFromNow(9) }),
  workItem(2, 'claimed', { leaseExpiresAt: minutesFromNow(-3) }),
  // Claimed, then its lease ran out: back to ready, waiting there since 10:00.
  workItem(4, 'ready', { updatedAt: '2026-01-01T11:00:00' }),
  workItem(5, 'cancelled'),
];

const ACTORS = [
  { id: 11, handle: 'e2e-agent', displayName: 'E2E Agent', kind: 'agent', status: 'active', presenceStatus: 'working', presenceNote: null, lastSeenAt: null },
  { id: 12, handle: 'e2e-paused', displayName: null, kind: 'agent', status: 'paused', presenceStatus: 'idle', presenceNote: null, lastSeenAt: null },
];

const RESOURCES = [
  { id: 1, key: 'e2e-gpu', name: 'E2E GPU', kind: 'hardware', capacity: 3, activeSlots: 1, enabled: true, metadata: null, availableSlots: 2, createdAt: null },
];

const RESERVATIONS = [
  { id: 900, resourceId: 1, resourceKey: 'e2e-gpu', actorId: 11, workItemId: 4, slots: 1, reason: 'e2e hold', expiresAt: minutesFromNow(30), createdAt: '2026-01-01T08:00:00' },
  { id: 901, resourceId: 1, resourceKey: 'e2e-gpu', actorId: 12, workItemId: 5, slots: 1, reason: 'other item', expiresAt: minutesFromNow(30), createdAt: '2026-01-01T08:00:00' },
];

const EVENTS = [
  // The shape a real claimed item has: NO `status_changed` row anywhere.
  { id: 800, workItemId: 4, actorId: 11, event: 'created', fromStatus: null, toStatus: 'ready', payload: null, createdAt: '2026-01-01T08:00:00' },
  { id: 801, workItemId: 4, actorId: 11, event: 'claimed', fromStatus: 'ready', toStatus: 'claimed', payload: null, createdAt: '2026-01-01T09:00:00' },
  // from == to by design (WIKI4AI-145): a renewal must not restart the waiting clock.
  { id: 803, workItemId: 4, actorId: 11, event: 'lease_extended', fromStatus: 'claimed', toStatus: 'claimed', payload: null, createdAt: '2026-01-01T09:30:00' },
  { id: 802, workItemId: 4, actorId: null, event: 'lease_expired', fromStatus: 'claimed', toStatus: 'ready', payload: null, createdAt: '2026-01-01T10:00:00' },
];

const COMMENTS = [
  {
    id: 850,
    workItemId: 4,
    authorActorId: 11,
    kind: 'blocker',
    body: 'Needs a human: the migration is not applied.',
    payload: { blocking: 'migration 27', needs_human: true },
    createdAt: '2026-01-01T09:00:00',
  },
  {
    id: 851,
    workItemId: 4,
    authorActorId: 11,
    kind: 'handoff',
    body: 'Over to the reviewer.',
    payload: { to_handle: 'e2e-reviewer', context: 'Schema patched, re-run the indexer.' },
    createdAt: '2026-01-01T09:30:00',
  },
];

/** Route every /api/v1 call to the fixtures above. */
async function stubApi(page: import('@playwright/test').Page) {
  await page.route('**/api/v1/**', async (route) => {
    const url = route.request().url();
    let body: unknown = [];

    if (url.includes('/auth/status')) body = { initialized: true, registrationOpen: false };
    else if (url.includes('/auth/me')) body = { id: 1, username: 'e2e_board', email: 'e2e@example.com', role: 'USER', language: 'en' };
    else if (url.includes('/events')) body = { content: EVENTS, totalElements: EVENTS.length };
    else if (url.includes('/comments')) body = { content: COMMENTS, totalElements: COMMENTS.length };
    else if (url.includes('/reservations')) body = RESERVATIONS;
    else if (url.includes('/resources')) body = RESOURCES;
    else if (url.includes('/actors')) body = ACTORS;
    else if (/\/work-items\/\d+$/.test(url)) body = WORK_ITEMS.find((i) => url.endsWith(`/${i.id}`)) ?? WORK_ITEMS[0];
    else if (url.includes('/work-items')) body = WORK_ITEMS;

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(body),
    });
  });
}

test.describe('Coordination board - WIKI4AI-161', () => {
  test.beforeEach(async ({ page }) => {
    // Auth comes from localStorage (AuthContext), so a fake session is enough to
    // reach a ProtectedRoute page. The API itself is stubbed below.
    await page.addInitScript(seedFakeSession, {
      accessKey: ACCESS_TOKEN_KEY,
      userKey: USER_INFO_KEY,
    });
    await stubApi(page);
  });

  test('board renders the state columns, a lease countdown and the sidebar', async ({ page }) => {
    await page.goto('/board');

    // Every state is a column, blocked and cancelled included.
    for (const status of ['backlog', 'ready', 'claimed', 'running', 'review', 'done', 'blocked', 'cancelled']) {
      await expect(page.getByTestId(`board-column-${status}`)).toBeVisible();
    }

    // Card: title, assignee with presence dot, labels, live lease countdown.
    await expect(page.getByTestId('board-card-1')).toBeVisible();
    await expect(page.getByTestId('board-card-title-1')).toHaveText('E2E item 1');
    await expect(page.getByTestId('board-card-assignee-1')).toHaveText('e2e-agent');
    await expect(page.getByTestId('board-card-assignee-1')).toHaveAttribute('data-presence', 'active');
    await expect(page.getByTestId('board-card-lease-1')).toContainText(/m/);

    // An elapsed lease is called out, not hidden.
    await expect(page.getByTestId('board-card-lease-2')).toHaveText('Lease expired');

    // Sidebar: blocker flagged needs_human, resource capacity, live agents.
    await expect(page.getByTestId('board-waiting-list')).toContainText('Needs a human');
    await expect(page.getByTestId('board-capacity-e2e-gpu')).toContainText('1 / 3');
    await expect(page.getByTestId('board-agents-list')).toContainText('e2e-agent');

    // WIKI4AI-177: the lease secret the API returned is nowhere in the DOM.
    const html = await page.content();
    expect(html).not.toContain(FAKE_CLAIM_TOKEN);
    expect(html).not.toContain('claimToken');
  });

  test('opening a card shows the detail with timeline, handoff card and reservations', async ({ page }) => {
    await page.goto('/board');
    await page.getByTestId('board-card-title-4').click();

    await expect(page).toHaveURL(/\/board\/4$/);
    await expect(page.getByTestId('wi-title')).toHaveText('E2E item 4');

    // Timeline is chronological and lease_expired is its own, distinct entry.
    await expect(page.getByTestId('wi-event-800')).toBeVisible();
    const expired = page.getByTestId('wi-event-802');
    await expect(expired).toHaveAttribute('data-event', 'lease_expired');
    await expect(expired).toHaveClass(/wi-event-lease-expired/);
    await expect(page.getByTestId('wi-event-alert-802')).toBeVisible();

    // Comments grouped by kind with a badge.
    await expect(page.getByTestId('wi-comment-group-blocker')).toContainText('blocker');

    // Handoff card: who and what, straight from the payload.
    await expect(page.getByTestId('wi-handoff-851')).toContainText('e2e-reviewer');
    await expect(page.getByTestId('wi-handoff-851')).toContainText('re-run the indexer');

    // Reservations of this item only.
    await expect(page.getByTestId('wi-reservation-900')).toBeVisible();
    await expect(page.getByTestId('wi-reservations')).not.toContainText('other item');

    // Waiting time derived from the timeline. This timeline has NO `status_changed`
    // row — the entry into the state is `lease_expired` (10:00) and the API's newest
    // moment is updatedAt (11:00), so the card must read 60 min in ready.
    await expect(page.getByTestId('wi-waiting')).toContainText('ready');
    await expect(page.getByTestId('wi-waiting')).toContainText('60 min');
    await expect(page.getByTestId('wi-event-801')).toHaveAttribute('data-event', 'claimed');
    await expect(page.getByTestId('wi-event-803')).toHaveAttribute('data-event', 'lease_extended');

    // WIKI4AI-177 again, on the detail page.
    const html = await page.content();
    expect(html).not.toContain(FAKE_CLAIM_TOKEN);
    expect(html).not.toContain('claimToken');
  });
});
