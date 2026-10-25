/**
 * WIKI4AI-164: ResourcesPage tests.
 *
 * Only the HTTP layer is mocked. The assertions prove the page is a renderer of the
 * capacity ledger, not a calculator of it:
 *   - the bar width is `activeSlots / capacity` from `GET /resources` (2 / 4 → 50%),
 *   - a resource whose capacity is 0 or null gets NO bar (`data-derivable="false"`),
 *     because a 0% bar would be a claim the API never made,
 *   - the TTL is `expiresAt − fetchedAt`, i.e. derived from the payload and the moment
 *     the payload was fetched — no `setInterval`, no component timer (WIKI4AI-175),
 *   - live vs released comes from the row's own `releasedAt`,
 *   - release is `POST /reservations/{id}/release`, and because the backend is
 *     idempotent a second click must be a no-op, not an error.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ResourcesPage from '../pages/ResourcesPage';

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

const minutesFromNow = (minutes: number) =>
  new Date(Date.now() + minutes * 60_000).toISOString();

const RESOURCES = [
  {
    id: 1,
    key: 'gpu',
    name: 'Render GPU',
    kind: 'hardware',
    capacity: 4,
    activeSlots: 2,
    enabled: true,
    metadata: null,
    availableSlots: 2,
    createdAt: '2026-09-01T08:00:00',
  },
  {
    // capacity 0: the bar must not be drawn, because 0/0 is not a measurement.
    id: 2,
    key: 'empty-quota',
    name: 'Empty quota',
    kind: 'quota',
    capacity: 0,
    activeSlots: 0,
    enabled: false,
    metadata: null,
    availableSlots: 0,
    createdAt: null,
  },
];

let reservations: Record<string, unknown[]>;

function reservation(id: number, overrides: Record<string, unknown> = {}) {
  return {
    id,
    resourceId: 1,
    resourceKey: 'gpu',
    actorId: 11,
    workItemId: 4,
    slots: 1,
    reason: 'render hold',
    expiresAt: minutesFromNow(30),
    releasedAt: null,
    createdAt: '2026-09-26T09:00:00',
    ...overrides,
  };
}

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/resources']}>
        <ResourcesPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  reservations = {
    gpu: [reservation(900), reservation(901, { releasedAt: '2026-09-26T10:00:00' })],
    'empty-quota': [],
  };

  mockGet.mockImplementation(async (url: string) => {
    if (url.includes('/reservations')) {
      const key = decodeURIComponent(url.split('/resources/')[1].split('/')[0]);
      return reservations[key] ?? [];
    }
    if (url.startsWith('/api/v1/resources')) return RESOURCES;
    return [];
  });

  mockPost.mockImplementation(async () => undefined);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('ResourcesPage (WIKI4AI-164)', () => {
  it('draws the capacity bar as the API ratio activeSlots / capacity', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByTestId('resource-card-gpu')).toBeInTheDocument());

    const bar = screen.getByTestId('resource-bar-gpu');
    expect(bar).toHaveAttribute('data-derivable', 'true');
    const fill = bar.querySelector('.resource-bar-fill') as HTMLElement;
    // 2 / 4 → 50%. The page did not invent the number, it divided two API fields.
    expect(fill.style.width).toBe('50%');

    expect(screen.getByTestId('resource-slots-gpu')).toHaveTextContent('2 / 4');
    // `availableSlots` is read from the API, not subtracted by the UI.
    expect(screen.getByTestId('resource-slots-gpu')).toHaveTextContent('2 free');
  });

  it('draws no bar when the API gave no usable capacity', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByTestId('resource-card-empty-quota')).toBeInTheDocument());

    const bar = screen.getByTestId('resource-bar-empty-quota');
    expect(bar).toHaveAttribute('data-derivable', 'false');
    const fill = bar.querySelector('.resource-bar-fill') as HTMLElement;
    expect(fill.style.width).toBe('');
    expect(screen.getByTestId('resource-disabled-empty-quota')).toBeInTheDocument();
  });

  it('counts down each reservation TTL from expiresAt against the fetch moment', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByTestId('reservation-row-900')).toBeInTheDocument());

    // expiresAt is 30 minutes ahead of `fetchedAt`. The row reads the difference
    // between the two, so it is 30m give or take the milliseconds the test took.
    expect(screen.getByTestId('reservation-ttl-900')).toHaveTextContent(/^(29|30)m \d+s$/);
    expect(screen.getByTestId('reservation-row-900')).toHaveAttribute('data-released', 'false');
  });

  it('shows a released reservation as released, straight from releasedAt', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByTestId('reservation-row-901')).toBeInTheDocument());

    expect(screen.getByTestId('reservation-row-901')).toHaveAttribute('data-released', 'true');
    expect(screen.getByTestId('reservation-released-901')).toBeInTheDocument();
    expect(screen.queryByTestId('reservation-release-901')).not.toBeInTheDocument();
  });

  it('releases a reservation and survives the idempotent second click', async () => {
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(screen.getByTestId('reservation-release-900')).toBeEnabled());

    await user.click(screen.getByTestId('reservation-release-900'));
    await waitFor(() => expect(mockPost).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/reservations/900/release'),
    ));

    // The 204 was received: the button is now a no-op labelled "Release accepted".
    await waitFor(() => expect(screen.getByTestId('reservation-release-900')).toBeDisabled());
    expect(screen.getByTestId('reservation-release-900')).toHaveTextContent('Release accepted');
    expect(screen.queryByTestId('resources-release-error')).not.toBeInTheDocument();

    // A second click must not look like an error and must not fire a second request.
    await user.click(screen.getByTestId('reservation-release-900'));
    expect(mockPost).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('resources-release-error')).not.toBeInTheDocument();

    // The local record only says "the server already accepted this". The state the
    // page ultimately renders is the ledger's own `releasedAt` — asserted in the
    // released-row test above, which reads it straight off the API payload.
  });

  it('calls an expired deadline what it is', async () => {
    reservations.gpu = [reservation(902, { expiresAt: minutesFromNow(-5) })];
    renderPage();
    await waitFor(() => expect(screen.getByTestId('reservation-row-902')).toBeInTheDocument());

    expect(screen.getByTestId('reservation-ttl-902')).toHaveTextContent('deadline passed');
  });

  it('says "no deadline" when the API sent no expiresAt', async () => {
    reservations.gpu = [reservation(903, { expiresAt: null })];
    renderPage();
    await waitFor(() => expect(screen.getByTestId('reservation-row-903')).toBeInTheDocument());

    expect(screen.getByTestId('reservation-ttl-903')).toHaveTextContent('no deadline');
  });
});
