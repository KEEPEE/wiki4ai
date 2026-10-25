/**
 * WIKI4AI-161/162: boardApi tests.
 *
 * Two things are pinned here:
 *   1. the exact endpoint each function calls (the coordination UI is only as good
 *      as its paths), and
 *   2. the WIKI4AI-177 boundary: `claimToken` is dropped at the service edge, so no
 *      hook, component or test downstream of boardApi can ever hold the secret.
 *
 * Every token in this file is a synthetic fixture with the real 64-character shape.
 * It is not, and never was, a credential.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../services/apiClient', () => ({
  apiGet: vi.fn(),
}));

import * as boardApi from '../services/boardApi';
import { apiGet } from '../services/apiClient';

const mockGet = vi.mocked(apiGet);

/** 64 characters, the shape of a real lease token — and an obviously fake body. */
const FAKE_CLAIM_TOKEN =
  'fake0claim0token0not0a0real0secret00000000000000000000000000000000deadbeef';

function rawWorkItem(overrides: Record<string, unknown> = {}) {
  return {
    id: 7,
    projectId: 3,
    title: 'Wire the board',
    body: 'Columns and lease countdown',
    status: 'running',
    priority: 'high',
    labels: ['frontend', 'coordination'],
    parentId: null,
    externalRef: null,
    assigneeActorId: 11,
    claimToken: FAKE_CLAIM_TOKEN,
    leaseExpiresAt: '2026-09-26T12:00:00',
    createdByUserId: 1,
    createdByActorId: 11,
    updatedByActorId: 11,
    createdAt: '2026-09-26T10:00:00',
    updatedAt: '2026-09-26T11:30:00',
    version: 4,
    ...overrides,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('boardApi (WIKI4AI-161)', () => {
  it('listWorkItems calls GET /work-items with only the filters it was given', async () => {
    mockGet.mockResolvedValue([rawWorkItem()]);

    const items = await boardApi.listWorkItems({ projectId: 3, status: 'running', label: 'frontend' });

    expect(mockGet).toHaveBeenCalledWith('/api/v1/work-items?projectId=3&status=running&label=frontend');
    expect(items).toHaveLength(1);
    expect(items[0].title).toBe('Wire the board');
  });

  it('listWorkItems sends no query string when there are no filters', async () => {
    mockGet.mockResolvedValue([]);
    await boardApi.listWorkItems();
    expect(mockGet).toHaveBeenCalledWith('/api/v1/work-items');
  });

  it('listWorkItems accepts a Spring Page body as well as a bare array', async () => {
    mockGet.mockResolvedValue({ content: [rawWorkItem({ id: 8 })], totalElements: 1 });
    const items = await boardApi.listWorkItems({ assignee: 11 });
    expect(mockGet).toHaveBeenCalledWith('/api/v1/work-items?assignee=11');
    expect(items[0].id).toBe(8);
  });

  it('getWorkItem calls GET /work-items/{id}', async () => {
    mockGet.mockResolvedValue(rawWorkItem({ id: 42 }));
    const item = await boardApi.getWorkItem(42);
    expect(mockGet).toHaveBeenCalledWith('/api/v1/work-items/42');
    expect(item.id).toBe(42);
  });

  it('listWorkItemEvents calls GET /work-items/{id}/events and unwraps the Page', async () => {
    mockGet.mockResolvedValue({ content: [{ id: 1, workItemId: 42, event: 'created' }] });
    const events = await boardApi.listWorkItemEvents(42);
    expect(mockGet).toHaveBeenCalledWith('/api/v1/work-items/42/events?page=0&size=100');
    expect(events[0].event).toBe('created');
  });

  it('listWorkItemComments sends kind and needs_human as backend parameters', async () => {
    mockGet.mockResolvedValue({ content: [] });
    await boardApi.listWorkItemComments(42, { kind: 'blocker', needsHuman: true, size: 50 });
    expect(mockGet).toHaveBeenCalledWith(
      '/api/v1/work-items/42/comments?kind=blocker&needs_human=true&page=0&size=50',
    );
  });

  it('listActors calls GET /actors', async () => {
    mockGet.mockResolvedValue([{ id: 11, handle: 'ci-agent', status: 'active' }]);
    const actors = await boardApi.listActors();
    expect(mockGet).toHaveBeenCalledWith('/api/v1/actors');
    expect(actors[0].handle).toBe('ci-agent');
  });

  it('getWhoami calls GET /actors/whoami', async () => {
    mockGet.mockResolvedValue({ actorId: 11, handle: 'ci-agent', scopes: ['read'] });
    const whoami = await boardApi.getWhoami();
    expect(mockGet).toHaveBeenCalledWith('/api/v1/actors/whoami');
    expect(whoami.handle).toBe('ci-agent');
  });

  it('listResources calls GET /resources', async () => {
    mockGet.mockResolvedValue([{ id: 1, key: 'gpu', capacity: 2, activeSlots: 1, availableSlots: 1 }]);
    const resources = await boardApi.listResources();
    expect(mockGet).toHaveBeenCalledWith('/api/v1/resources');
    expect(resources[0].availableSlots).toBe(1);
  });

  it('listReservations calls GET /resources/{key}/reservations', async () => {
    mockGet.mockResolvedValue([{ id: 5, resourceKey: 'gpu', workItemId: 42, slots: 1 }]);
    const reservations = await boardApi.listReservations('gpu');
    expect(mockGet).toHaveBeenCalledWith('/api/v1/resources/gpu/reservations');
    expect(reservations[0].workItemId).toBe(42);
  });

  it('unwrapPage accepts an array, a Page and anything else', () => {
    expect(boardApi.unwrapPage<number>([1, 2])).toEqual([1, 2]);
    expect(boardApi.unwrapPage<number>({ content: [3] })).toEqual([3]);
    expect(boardApi.unwrapPage<number>(null)).toEqual([]);
    expect(boardApi.unwrapPage<number>({ nope: true })).toEqual([]);
  });
});

describe('boardApi claim-token boundary (WIKI4AI-177)', () => {
  it('toWorkItem does not carry claimToken out of the service', () => {
    const item = boardApi.toWorkItem(rawWorkItem());
    expect(Object.keys(item)).not.toContain('claimToken');
    expect(JSON.stringify(item)).not.toContain(FAKE_CLAIM_TOKEN);
    expect(JSON.stringify(item)).not.toContain('claimToken');
  });

  it('listWorkItems strips claimToken from every row it returns', async () => {
    mockGet.mockResolvedValue([rawWorkItem(), rawWorkItem({ id: 9 })]);
    const items = await boardApi.listWorkItems();
    for (const item of items) {
      expect(item).not.toHaveProperty('claimToken');
    }
    // The lease deadline survives; the secret does not.
    expect(items[0].leaseExpiresAt).toBe('2026-09-26T12:00:00');
  });

  it('getWorkItem strips claimToken too', async () => {
    mockGet.mockResolvedValue(rawWorkItem({ id: 42 }));
    const item = await boardApi.getWorkItem(42);
    expect(item).not.toHaveProperty('claimToken');
  });
});
