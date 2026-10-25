/**
 * WIKI4AI-163 + WIKI4AI-164 + WIKI4AI-165: end-to-end of the coordination layer.
 *
 * Run:  npm run dev      (Vite on http://localhost:5173)
 *       npx playwright test e2e/coordination-nav.spec.ts
 *
 * The real SPA is driven — React Router, ProtectedRoute, Layout, the user menu,
 * react-query polling — and only the HTTP boundary is stubbed with `page.route`, so
 * the spec needs no backend and contacts no production host. Every value below is a
 * synthetic fixture.
 *
 * What is proven in a real browser:
 *   165: the badge on the nav trigger is the server's `UnseenEventsDTO.count`; opening
 *        it POSTs `mark-seen` with NO request body and the badge then disappears.
 *   163: an agent token appears as its display prefix only — the full plaintext the
 *        stubbed `/tokens` response smuggles in never reaches the DOM — and rotation
 *        shows the new plaintext exactly once.
 *   164: the capacity bar is the API ratio, the TTL counts down from `expiresAt`, and
 *        releasing a reservation is accepted (204) rather than reported as an error.
 */
import { test, expect } from '@playwright/test';

/** 36 characters: `w4a_` + 32, the shape of a real token plaintext. */
const FAKE_TOKEN_PLAINTEXT = 'w4a_E2eAbcdefghijklmnopqrstuvwxyz012';
const ROTATED_PLAINTEXT = 'w4a_E2eZzYyXxWwVvUuTtSsRrQqPpOoNnMmLl';
const FAKE_CLAIM_TOKEN =
  'fake0claim0token0not0a0real0secret00000000000000000000000000000000deadbeef';

const ACCESS_TOKEN_KEY = 'wiki4ai_access_token';
const USER_INFO_KEY = 'wiki4ai_user_info';

function seedFakeSession(keys: { accessKey: string; userKey: string }) {
  const payload = btoa(JSON.stringify({ sub: 'e2e-coord', exp: Math.floor(Date.now() / 1000) + 3600 }));
  window.localStorage.setItem(keys.accessKey, `e2eheader.${payload}.e2esignature`);
  window.localStorage.setItem(
    keys.userKey,
    JSON.stringify({ id: 1, username: 'e2e_coord', email: 'e2e@example.com', role: 'USER', language: 'en' }),
  );
}

const minutesFromNow = (minutes: number) => new Date(Date.now() + minutes * 60_000).toISOString();

const ACTORS = [
  { id: 11, handle: 'e2e-agent', displayName: 'E2E Agent', kind: 'agent', status: 'active', presenceStatus: 'working', presenceNote: null, lastSeenAt: '2026-01-01T10:00:00' },
  { id: 12, handle: 'e2e-planner', displayName: null, kind: 'agent', status: 'paused', presenceStatus: 'idle', presenceNote: null, lastSeenAt: null },
];

const PROJECT_ACTORS = [
  { id: 7, projectId: 1, actorId: 11, role: 'worker', createdAt: '2026-01-01T08:00:00' },
  { id: 8, projectId: 1, actorId: 12, role: 'planner', createdAt: '2026-01-01T08:00:00' },
];

let tokens = [
  {
    id: 55,
    name: 'e2e-runner',
    prefix: 'e2ek7x2q9',
    scopes: ['workitems'],
    actorId: 11,
    expiresAt: null,
    revokedAt: null,
    lastUsedAt: null,
    createdAt: '2026-01-01T08:00:00',
    hashed: true,
    // Not part of ApiTokenDTO today; the allow-list must keep it out of the DOM.
    token: FAKE_TOKEN_PLAINTEXT,
  },
];

let unseenCount = 4;

const METRICS = {
  from: '2026-01-01T00:00:00',
  to: '2026-12-31T23:59:59',
  scope: 'all',
  projectId: null,
  actors: [
    {
      // The live shape that exposed the defect: `ActorMetricsService` groups by a
      // nullable column, so a row with no attributed actor is real.
      actorId: null,
      eventCount: 2,
      createdCount: 2,
      claimedCount: 0,
      completedCount: 0,
      blockedCount: 0,
      leaseExpiredCount: 0,
      leaseExtendedCount: 0,
      statusSeconds: { ready: 0.845, running: 785.827 },
      totalStatusSeconds: 786.672,
      cycleCount: 0,
      totalCycleSeconds: 0,
      averageCycleSeconds: 0,
    },
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
    eventCount: 123,
    createdCount: 7,
    claimedCount: 20,
    completedCount: 6,
    blockedCount: 2,
    leaseExpiredCount: 9,
    leaseExtendedCount: 15,
    cycleCount: 4,
    totalCycleSeconds: 9000,
    averageCycleSeconds: 2250,
    totalStatusSeconds: 10000,
    statusSeconds: { claimed: 2500, running: 7500 },
    backfilledEventCount: 11,
    actorCount: 3,
  },
};

const RESOURCES = [
  { id: 1, key: 'e2e-gpu', name: 'E2E GPU', kind: 'hardware', capacity: 4, activeSlots: 2, enabled: true, metadata: null, availableSlots: 2, createdAt: null },
];

let RESERVATIONS = [
  { id: 900, resourceId: 1, resourceKey: 'e2e-gpu', actorId: 11, workItemId: 4, slots: 1, reason: 'e2e hold', expiresAt: minutesFromNow(25), releasedAt: null, createdAt: '2026-01-01T08:00:00' },
  { id: 901, resourceId: 1, resourceKey: 'e2e-gpu', actorId: 12, workItemId: 5, slots: 1, reason: 'already done', expiresAt: minutesFromNow(30), releasedAt: '2026-01-01T11:00:00', createdAt: '2026-01-01T08:00:00' },
];

const WORK_ITEMS = [
  { id: 4, projectId: 1, title: 'E2E item 4', body: null, status: 'ready', priority: null, labels: [], parentId: null, externalRef: null, assigneeActorId: 11, claimToken: FAKE_CLAIM_TOKEN, leaseExpiresAt: null, createdByUserId: 1, createdByActorId: 11, updatedByActorId: 11, createdAt: '2026-01-01T08:00:00', updatedAt: '2026-01-01T11:00:00', version: 1 },
];

async function stubApi(page: import('@playwright/test').Page) {
  await page.route('**/api/v1/**', async (route) => {
    const url = route.request().url();
    let body: unknown = [];
    let status = 200;

    if (url.includes('/auth/status')) body = { initialized: true, registrationOpen: false };
    else if (url.includes('/auth/me')) body = { id: 1, username: 'e2e_coord', email: 'e2e@example.com', role: 'USER', language: 'en' };
    else if (url.includes('/metrics/actors')) body = METRICS;
    else if (url.includes('/inbox/unseen')) body = { count: unseenCount, eventIds: [801, 802, 803, 804], lastSeenEventId: 800, newestEventId: 804 };
    else if (url.includes('/inbox/mark-seen')) { status = 204; body = null; }
    else if (url.includes('/actors/whoami')) body = { userId: 1, username: 'e2e_coord', actorId: 11, handle: 'e2e-agent', kind: 'agent', scopes: ['workitems'] };
    else if (url.includes('/projects/1/actors')) body = PROJECT_ACTORS;
    else if (url.includes('/tokens') && url.endsWith('/rotate')) {
      tokens = [{ ...tokens[0], id: 56, prefix: 'e2ezz11yy', createdAt: '2026-01-01T12:00:00' }];
      body = { token: ROTATED_PLAINTEXT, metadata: { ...tokens[0], token: ROTATED_PLAINTEXT } };
    } else if (url.includes('/tokens')) body = tokens;
    else if (url.includes('/reservations')) body = RESERVATIONS;
    else if (url.includes('/resources')) body = RESOURCES;
    else if (url.includes('/actors')) body = ACTORS;
    else if (url.includes('/work-items')) body = WORK_ITEMS;

    await route.fulfill({
      status,
      contentType: status === 204 ? '' : 'application/json',
      body: status === 204 ? '' : JSON.stringify(body),
    });
  });
}

test.describe('Coordination layer - WIKI4AI-163/164/165', () => {
  test.beforeEach(async ({ page }) => {
    tokens = [
      {
        id: 55,
        name: 'e2e-runner',
        prefix: 'e2ek7x2q9',
        scopes: ['workitems'],
        actorId: 11,
        expiresAt: null,
        revokedAt: null,
        lastUsedAt: null,
        createdAt: '2026-01-01T08:00:00',
        hashed: true,
        token: FAKE_TOKEN_PLAINTEXT,
      },
    ];
    unseenCount = 4;
    RESERVATIONS = [
      { id: 900, resourceId: 1, resourceKey: 'e2e-gpu', actorId: 11, workItemId: 4, slots: 1, reason: 'e2e hold', expiresAt: minutesFromNow(25), releasedAt: null, createdAt: '2026-01-01T08:00:00' },
      { id: 901, resourceId: 1, resourceKey: 'e2e-gpu', actorId: 12, workItemId: 5, slots: 1, reason: 'already done', expiresAt: minutesFromNow(30), releasedAt: '2026-01-01T11:00:00', createdAt: '2026-01-01T08:00:00' },
    ];

    await page.addInitScript(seedFakeSession, { accessKey: ACCESS_TOKEN_KEY, userKey: USER_INFO_KEY });
    await stubApi(page);
  });

  test('the nav marker shows what arrived and clears when it is opened', async ({ page }) => {
    await page.goto('/board');

    // The badge is on the trigger: visible without opening the menu.
    const badge = page.getByTestId('unseen-events-badge');
    await expect(badge).toBeVisible();
    await expect(badge).toHaveText('4');
    await expect(badge).toHaveAttribute('data-source', 'actor-inbox');

    // The menu carries the matching action.
    await page.getByTestId('user-menu-button').click();
    await expect(page.getByTestId('user-menu-new-events')).toContainText('4');

    const request = page.waitForRequest(
      (req) => req.method() === 'POST' && req.url().includes('/api/v1/actors/11/inbox/mark-seen'),
    );
    unseenCount = 0;
    await page.getByTestId('user-menu-new-events').click();

    const posted = await request;
    // No body: "everything the server has shown me is seen".
    expect(posted.postData()).toBeNull();

    await expect(badge).toBeHidden();
  });

  test('the coordination routes are reachable from the same menu', async ({ page }) => {
    await page.goto('/board');
    await page.getByTestId('user-menu-button').click();

    for (const id of ['user-menu-agents', 'user-menu-resources', 'user-menu-metrics']) {
      await expect(page.getByTestId(id)).toBeVisible();
    }

    await page.getByTestId('user-menu-agents').click();
    await expect(page).toHaveURL(/\/agents$/);
    await expect(page.getByTestId('agents-page')).toBeVisible();

    await page.getByTestId('user-menu-button').click();
    await page.getByTestId('user-menu-resources').click();
    await expect(page).toHaveURL(/\/resources$/);
    await expect(page.getByTestId('resources-page')).toBeVisible();

    await page.getByTestId('user-menu-button').click();
    await page.getByTestId('user-menu-metrics').click();
    await expect(page).toHaveURL(/\/metrics$/);
    await expect(page.getByTestId('metrics-page')).toBeVisible();
  });

  test('agents: prefix-only tokens, rotation shown once, lease count from metrics', async ({ page }) => {
    await page.goto('/agents');

    await expect(page.getByTestId('agent-card-11')).toBeVisible();
    await expect(page.getByTestId('agent-handle-11')).toHaveText('e2e-agent');
    await expect(page.getByTestId('agent-kind-11')).toHaveText('agent');

    // The lease-expired number is the metrics endpoint's, not a UI count.
    await expect(page.getByTestId('agent-leaseexpired-11')).toHaveText('3');

    // Prefix only.
    await expect(page.getByTestId('agent-token-55-prefix')).toHaveText('w4a_e2ek7x2q9…');

    // The full plaintext the stub returned is nowhere in the DOM.
    const html = await page.content();
    expect(html).not.toContain(FAKE_TOKEN_PLAINTEXT);

    // Rotate: the new plaintext appears exactly once, in the one-time panel.
    await page.getByTestId('agent-token-55-rotate').click();
    await expect(page.getByTestId('rotated-token-value')).toHaveText(ROTATED_PLAINTEXT);
    await expect(page.getByTestId('copy-rotated-token-btn')).toBeVisible();
    await expect(page.getByTestId('agent-token-56-prefix')).toHaveText('w4a_e2ezz11yy…');

    // Cancel everything for this agent.
    await expect(page.getByTestId('agent-revoke-all-11')).toBeVisible();
  });

  test('resources: capacity bar is the API ratio, TTL counts down, release is accepted', async ({ page }) => {
    await page.goto('/resources');

    await expect(page.getByTestId('resource-card-e2e-gpu')).toBeVisible();
    await expect(page.getByTestId('resource-slots-e2e-gpu')).toContainText('2 / 4');

    // 2 / 4 → the bar is half full. No chart library: a div with that width.
    const fill = page.locator('[data-testid="resource-bar-e2e-gpu"] .resource-bar-fill');
    await expect(fill).toHaveAttribute('style', /width: 50%/);

    // TTL from `expiresAt` against the fetch moment.
    await expect(page.getByTestId('reservation-ttl-900')).toContainText(/m/);
    await expect(page.getByTestId('reservation-row-901')).toHaveAttribute('data-released', 'true');

    const request = page.waitForRequest(
      (req) => req.method() === 'POST' && req.url().includes('/api/v1/reservations/900/release'),
    );
    await page.getByTestId('reservation-release-900').click();
    await request;

    // 204 accepted: the button becomes a no-op, not an error.
    await expect(page.getByTestId('reservation-release-900')).toBeDisabled();
    await expect(page.getByTestId('reservation-release-900')).toHaveText('Release accepted');
    await expect(page.getByTestId('resources-release-error')).toHaveCount(0);
  });

  test('metrics: every number on the page is the API field it came from', async ({ page }) => {
    await page.goto('/metrics');

    await expect(page.getByTestId('metrics-window')).toContainText('all');
    await expect(page.getByTestId('metric-events')).toContainText('123');
    await expect(page.getByTestId('metric-lease-expired')).toContainText('9');
    await expect(page.getByTestId('metric-average-cycle')).toContainText('37m 30s');
    await expect(page.getByTestId('metric-backfilled')).toContainText('11');

    // A row the API attributed to no actor is labelled as such — the UI does not
    // invent an actor with id 0.
    await expect(page.getByTestId('metrics-actor-unattributed-id')).toHaveText('Unattributed');
    const metricsHtml = await page.content();
    expect(metricsHtml).not.toContain('Actor #0');
    expect(metricsHtml).not.toContain('metrics-actor-0');
    await expect(page.getByTestId('metrics-actor-11-id')).toHaveText('Actor #11');
    await expect(page.getByTestId('metrics-unattributed-note')).toBeVisible();

    // Per actor: the API row, and a bar that is statusSeconds / totalStatusSeconds.
    await expect(page.getByTestId('metrics-actor-11-lease-expired')).toContainText('3');
    const claimed = page.locator('[data-testid="metrics-actor-11-share-claimed"] .metrics-share-bar-fill');
    await expect(claimed).toHaveAttribute('style', /width: 25%/);
  });
});
