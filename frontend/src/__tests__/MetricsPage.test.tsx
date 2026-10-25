/**
 * WIKI4AI-164: MetricsPage tests.
 *
 * The page is a renderer of `ActorMetricsResponseDTO`. The tests pin that down:
 *   - every summary card shows the value the mocked endpoint put in `summary`,
 *   - the per-actor bar width is `statusSeconds / totalStatusSeconds` (1800 / 7200 =
 *     25%), which is the only ratio the page is allowed to form,
 *   - `leaseExpiredCount` and `averageCycleSeconds` are displayed as the API sent them,
 *   - and the summary is NOT a sum of the actors: the fixture makes the two disagree
 *     on purpose (summary 9, the single actor 3). If the UI added the rows up it would
 *     show 9 in the actor row or 3 in the card — the test asserts both numbers stay
 *     where the API put them.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import MetricsPage from '../pages/MetricsPage';

vi.mock('../services/apiClient', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
  getRedirectFromUrl: vi.fn(() => null),
}));

import { apiGet } from '../services/apiClient';

const mockGet = vi.mocked(apiGet);

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
  // Deliberately NOT the sum of the actors above: a UI that added rows up would
  // produce these numbers and be caught by the assertions below.
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

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/metrics']}>
        <MetricsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  mockGet.mockImplementation(async (url: string) => {
    if (url.startsWith('/api/v1/metrics/actors')) return METRICS;
    return [];
  });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('MetricsPage (WIKI4AI-164)', () => {
  it('renders the window the API echoed back', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByTestId('metrics-window')).toBeInTheDocument());

    expect(screen.getByTestId('metrics-window')).toHaveTextContent('2026-09-01');
    expect(screen.getByTestId('metrics-window')).toHaveTextContent('all');
    expect(mockGet).toHaveBeenCalledWith(expect.stringContaining('/api/v1/metrics/actors'));
  });

  it('renders every summary field as the API sent it', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByTestId('metrics-summary')).toBeInTheDocument());

    expect(screen.getByTestId('metric-events')).toHaveTextContent('123');
    expect(screen.getByTestId('metric-created')).toHaveTextContent('7');
    expect(screen.getByTestId('metric-claimed')).toHaveTextContent('20');
    expect(screen.getByTestId('metric-completed')).toHaveTextContent('6');
    expect(screen.getByTestId('metric-blocked')).toHaveTextContent('2');
    expect(screen.getByTestId('metric-lease-expired')).toHaveTextContent('9');
    expect(screen.getByTestId('metric-lease-extended')).toHaveTextContent('15');
    expect(screen.getByTestId('metric-cycle-count')).toHaveTextContent('4');
    expect(screen.getByTestId('metric-actor-count')).toHaveTextContent('3');
    expect(screen.getByTestId('metric-backfilled')).toHaveTextContent('11');
    // 2250 s → 37m 30s: formatting of the API's own double.
    expect(screen.getByTestId('metric-average-cycle')).toHaveTextContent('37m 30s');
  });

  it('shows leaseExpiredCount and averageCycleSeconds per actor from the API row', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByTestId('metrics-actor-11')).toBeInTheDocument());

    expect(screen.getByTestId('metrics-actor-11-lease-expired')).toHaveTextContent('3');
    expect(screen.getByTestId('metrics-actor-11-cycle')).toHaveTextContent('30m 0s');
    expect(screen.getByTestId('metrics-actor-11-cycle')).toHaveTextContent('2');
    expect(screen.getByTestId('metrics-actor-11-events')).toHaveTextContent('40');
    // The summary's 9 did not leak into the actor row, and the actor's 3 did not
    // leak into the summary card: both numbers stay where the API put them.
    expect(screen.getByTestId('metric-lease-expired')).toHaveTextContent('9');
  });

  it('draws the time-in-state bar as statusSeconds / totalStatusSeconds', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByTestId('metrics-actor-11-share')).toBeInTheDocument());

    const row = screen.getByTestId('metrics-actor-11-share-claimed');
    const fill = row.querySelector('.metrics-share-bar-fill') as HTMLElement;
    // 1800 / 7200 = 25%
    expect(fill.style.width).toBe('25%');
    expect(row).toHaveTextContent('30m 0s');

    const running = screen.getByTestId('metrics-actor-11-share-running');
    expect((running.querySelector('.metrics-share-bar-fill') as HTMLElement).style.width).toBe('75%');

    // The instance-wide share uses the summary's own map and its own total:
    // 2500 / 10000 = 25%.
    const summaryRow = screen.getByTestId('metrics-summary-share-claimed');
    expect((summaryRow.querySelector('.metrics-share-bar-fill') as HTMLElement).style.width).toBe('25%');
  });

  it('never turns a null actorId into "Actor #0"', async () => {
    // Exactly the live shape that exposed the defect: `ActorMetricsService` groups by
    // `event.getActorId()`, and that column is nullable — so a row with no actor is a
    // real row, and `0` is a number the API never returned.
    mockGet.mockImplementation(async () => ({
      from: null,
      to: null,
      scope: 'all',
      projectId: null,
      actors: [
        {
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
        ...METRICS.summary,
        actorCount: 2,
      },
    }));

    const { container } = renderPage();
    await waitFor(() => expect(screen.getByTestId('metrics-actor-unattributed')).toBeInTheDocument());

    // No invented actor: the string "Actor #0" is nowhere in the tree.
    expect(document.body.textContent).not.toContain('Actor #0');
    expect(container.querySelectorAll('[data-testid="metrics-actor-0"]')).toHaveLength(0);

    // The row is labelled for what the API said it is.
    expect(screen.getByTestId('metrics-actor-unattributed-id')).toHaveTextContent('Unattributed');

    // A real id keeps its real label — nothing was renamed.
    expect(screen.getByTestId('metrics-actor-11-id')).toHaveTextContent('Actor #11');

    // Rendered rows === rows the API sent.
    expect(container.querySelectorAll('li.metrics-actor')).toHaveLength(2);

    // The API's own `actorCount` is shown untouched, and the note explains the row.
    expect(screen.getByTestId('metric-actor-count')).toHaveTextContent('2');
    expect(screen.getByTestId('metrics-unattributed-note')).toBeInTheDocument();
  });

  it('renders nothing invented when the window has no actors', async () => {
    mockGet.mockImplementation(async () => ({
      from: null,
      to: null,
      scope: 'self',
      projectId: null,
      actors: [],
      summary: null,
    }));

    renderPage();
    await waitFor(() => expect(screen.getByTestId('metrics-window')).toBeInTheDocument());

    expect(screen.getByTestId('metrics-window')).toHaveTextContent('(default)');
    expect(screen.getByTestId('metrics-window')).toHaveTextContent('self');
    expect(screen.queryByTestId('metrics-summary')).not.toBeInTheDocument();
    expect(screen.getByTestId('metrics-no-actors')).toBeInTheDocument();
  });
});
