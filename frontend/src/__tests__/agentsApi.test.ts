/**
 * WIKI4AI-163 / WIKI4AI-165: agentsApi normalization tests.
 *
 * The two response shapes the coordination endpoints really answer with are both
 * exercised: a Spring `Page` (`{content:[…]}`) and a bare array. A UI that only
 * handled one of them silently renders an empty list — which is how a page looks
 * "empty" while the API returned rows.
 *
 * Also pinned: the token allow-list. A listing response that smuggles a plaintext
 * field must not produce a TokenInfo that carries it.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  listProjectActors,
  roleOfActor,
  toActorMetrics,
  toActorMetricsResponse,
  toProjectActor,
  toTokenInfo,
  toUnseenEvents,
  tokensOfActor,
} from '../services/agentsApi';

vi.mock('../services/apiClient', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiDelete: vi.fn(),
}));

import { apiGet } from '../services/apiClient';

const mockGet = vi.mocked(apiGet);

beforeEach(() => vi.clearAllMocks());

describe('agentsApi response shapes (WIKI4AI-163)', () => {
  it('accepts a Spring Page for project actors', async () => {
    mockGet.mockResolvedValue({
      content: [{ id: 1, projectId: 4, actorId: 11, role: 'planner', createdAt: '2026-09-01T08:00:00' }],
      totalElements: 1,
    } as never);

    const rows = await listProjectActors(4);
    expect(rows).toHaveLength(1);
    expect(rows[0].role).toBe('planner');
    expect(roleOfActor(rows, 11)).toBe('planner');
    // An actor with no row has no role — null, not a default.
    expect(roleOfActor(rows, 12)).toBeNull();
  });

  it('accepts a bare array for project actors', async () => {
    mockGet.mockResolvedValue([
      { id: 2, projectId: 4, actorId: 12, role: 'viewer', createdAt: null },
    ] as never);

    const rows = await listProjectActors(4);
    expect(rows).toHaveLength(1);
    expect(rows[0].role).toBe('viewer');
    expect(rows[0].createdAt).toBeNull();
  });

  it('keeps a missing role null instead of naming one', async () => {
    mockGet.mockResolvedValue([{ id: 3, projectId: 4, actorId: 13 }] as never);
    const rows = await listProjectActors(4);
    expect(rows[0].role).toBeNull();
    expect(roleOfActor(rows, 13)).toBeNull();
  });
});

describe('agentsApi token allow-list (WIKI4AI-168 / WIKI4AI-177)', () => {
  it('copies metadata fields and drops anything shaped like a secret', () => {
    const row = toTokenInfo({
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
      // Not part of ApiTokenDTO. If it ever arrives, it stops here.
      token: 'w4a_Abcdefghijklmnopqrstuvwxyz0123',
      plaintext: 'w4a_Abcdefghijklmnopqrstuvwxyz0123',
      hashedValue: 'deadbeef',
    });

    expect(row.prefix).toBe('k7x2q9zp');
    expect(row).not.toHaveProperty('token');
    expect(row).not.toHaveProperty('plaintext');
    expect(row).not.toHaveProperty('hashedValue');
    expect(JSON.stringify(row)).not.toContain('w4a_Abcdefghijklmnopqrstuvwxyz0123');
  });

  it('groups tokens by the actorId the API put on the row', () => {
    const rows = [
      toTokenInfo({ id: 1, actorId: 11, prefix: 'aaaaaaaa' }),
      toTokenInfo({ id: 2, actorId: 12, prefix: 'bbbbbbbb' }),
      // An unbound token belongs to no agent.
      toTokenInfo({ id: 3, actorId: null, prefix: 'cccccccc' }),
    ];
    expect(tokensOfActor(rows, 11).map((t) => t.id)).toEqual([1]);
    expect(tokensOfActor(rows, 99)).toEqual([]);
  });
});

describe('agentsApi inbox and metrics normalization (WIKI4AI-164 / WIKI4AI-165)', () => {
  it('keeps a null actorId null — it is never coerced to actor 0', () => {
    // `ActorMetricsDTO.actorId` is a Java `Long` and the service groups events by a
    // nullable column, so "no actor attributed" is a real row.
    expect(toActorMetrics({ actorId: null, eventCount: 2 }).actorId).toBeNull();
    expect(toActorMetrics({}).actorId).toBeNull();
    expect(toActorMetrics({ actorId: 11 }).actorId).toBe(11);

    // Same rule in the project-actor allow-list copy.
    const unnamed = toProjectActor({ id: 1, projectId: 4, role: 'viewer' });
    expect(unnamed.actorId).toBeNull();
    // A row that names no actor answers for no id — not for 0, not for anyone.
    expect(roleOfActor([unnamed], 0)).toBeNull();
    expect(roleOfActor([unnamed], 11)).toBeNull();
    // And a caller without a usable id gets no role rather than a guessed one.
    expect(roleOfActor([{ ...unnamed, actorId: 11, role: 'worker' }], Number.NaN)).toBeNull();
  });

  it('treats an absent unseen marker as null, never as 0', () => {
    const seen = toUnseenEvents({ count: 4 });
    expect(seen.count).toBe(4);
    expect(seen.eventIds).toEqual([]);
    expect(seen.lastSeenEventId).toBeNull();
    expect(seen.newestEventId).toBeNull();

    const empty = toUnseenEvents(null);
    expect(empty.count).toBe(0);
    expect(empty.lastSeenEventId).toBeNull();
  });

  it('passes metrics numbers through and tolerates a missing summary', () => {
    const response = toActorMetricsResponse({
      from: '2026-09-01T00:00:00',
      to: null,
      scope: 'self',
      actors: [
        {
          actorId: 11,
          eventCount: 7,
          leaseExpiredCount: 2,
          statusSeconds: { running: 12.5 },
          totalStatusSeconds: 12.5,
          averageCycleSeconds: 44.98,
        },
      ],
    });

    expect(response.to).toBeNull();
    expect(response.summary).toBeNull();
    expect(response.actors[0].leaseExpiredCount).toBe(2);
    // A double is kept as a double: 44.98 s is a measurement, not 45.
    expect(response.actors[0].averageCycleSeconds).toBe(44.98);
    expect(response.actors[0].statusSeconds).toEqual({ running: 12.5 });
    expect(response.actors[0].createdCount).toBe(0);
  });
});
