/**
 * WIKI4AI-161/162: boardModel tests — the derivations the board and the detail page
 * render. The rule they hold: a fact the API did not send produces `null` or an
 * empty list, never a value the UI invented.
 */
import { describe, it, expect } from 'vitest';
import {
  boardColumns,
  durationParts,
  formatPayloadValue,
  groupCommentsByKind,
  groupItemsByStatus,
  handoffFields,
  isLeaseExpired,
  isStateEntryEvent,
  leaseRemainingMs,
  liveReservations,
  parseApiTime,
  reservationsForItem,
  sortEventsChronological,
  waitingInState,
} from '../pages/boardModel';
import { COMMENT_KINDS, type WorkItemCommentDTO, type WorkItemDTO, type WorkItemEventDTO } from '../services/boardApi';

function item(overrides: Partial<WorkItemDTO> & { id: number }): WorkItemDTO {
  return {
    projectId: 1,
    title: `Item ${overrides.id}`,
    body: null,
    status: 'ready',
    priority: null,
    labels: [],
    parentId: null,
    externalRef: null,
    assigneeActorId: null,
    leaseExpiresAt: null,
    createdByUserId: null,
    createdByActorId: null,
    updatedByActorId: null,
    createdAt: '2026-09-26T10:00:00',
    updatedAt: '2026-09-26T10:00:00',
    version: 1,
    ...overrides,
  };
}

function event(overrides: Partial<WorkItemEventDTO> & { id: number }): WorkItemEventDTO {
  return {
    workItemId: 1,
    actorId: null,
    event: 'status_changed',
    fromStatus: null,
    toStatus: null,
    payload: null,
    createdAt: '2026-09-26T10:00:00',
    ...overrides,
  };
}

function comment(overrides: Partial<WorkItemCommentDTO> & { id: number }): WorkItemCommentDTO {
  return {
    workItemId: 1,
    authorActorId: null,
    kind: 'note',
    body: 'body',
    payload: null,
    createdAt: '2026-09-26T10:00:00',
    ...overrides,
  };
}

describe('boardColumns / groupItemsByStatus', () => {
  it('renders the eight declared states as columns', () => {
    expect(boardColumns([])).toEqual([
      'backlog', 'ready', 'claimed', 'running', 'review', 'done', 'blocked', 'cancelled',
    ]);
  });

  it('adds a column for a status the API returned that the UI did not know', () => {
    const columns = boardColumns([item({ id: 1, status: 'verifying' })]);
    expect(columns).toContain('verifying');
  });

  it('groups items by their status', () => {
    const grouped = groupItemsByStatus([
      item({ id: 1, status: 'ready' }),
      item({ id: 2, status: 'running' }),
      item({ id: 3, status: 'ready' }),
    ]);
    expect(grouped.get('ready')?.map((i) => i.id)).toEqual([1, 3]);
    expect(grouped.get('running')?.map((i) => i.id)).toEqual([2]);
    expect(grouped.has('done')).toBe(false);
  });
});

describe('lease countdown', () => {
  const AT = Date.parse('2026-09-26T10:00:00');

  it('counts down from the deadline relative to the fetched moment', () => {
    const remaining = leaseRemainingMs('2026-09-26T11:05:00', AT);
    expect(remaining).toBe(65 * 60_000);
    expect(isLeaseExpired(remaining)).toBe(false);
    expect(durationParts(remaining as number)).toEqual({ hours: 1, minutes: 5, seconds: 0 });
  });

  it('reports an elapsed lease as expired — a legitimate state, not an error', () => {
    const remaining = leaseRemainingMs('2026-09-26T09:00:00', AT);
    expect(remaining).toBe(-60 * 60_000);
    expect(isLeaseExpired(remaining)).toBe(true);
  });

  it('an item with no lease has no countdown at all', () => {
    expect(leaseRemainingMs(null, AT)).toBeNull();
    expect(isLeaseExpired(null)).toBe(false);
  });

  it('an unparseable timestamp is not treated as a zero deadline', () => {
    expect(parseApiTime('not-a-date')).toBeNull();
    expect(leaseRemainingMs('not-a-date', AT)).toBeNull();
  });
});

describe('waitingInState (WIKI4AI-162)', () => {
  it('measures from the last state-entry event to the newest API-reported moment', () => {
    const itemRow = item({ id: 1, status: 'blocked', updatedAt: '2026-09-26T12:00:00' });
    const events = [
      event({ id: 1, event: 'created', toStatus: 'backlog', createdAt: '2026-09-26T09:00:00' }),
      event({ id: 2, event: 'status_changed', fromStatus: 'ready', toStatus: 'blocked', createdAt: '2026-09-26T10:00:00' }),
      event({ id: 3, event: 'lease_expired', createdAt: '2026-09-26T11:00:00' }),
    ];
    const waiting = waitingInState(itemRow, events);
    expect(waiting).not.toBeNull();
    expect(waiting?.status).toBe('blocked');
    expect(waiting?.minutes).toBe(120);
    expect(waiting?.sinceAt).toBe('2026-09-26T10:00:00');
  });

  it('counts an item entered by `claimed` — a timeline with NO status_changed', () => {
    // The exact live shape of a claimed item: `created` → ready, `claimed` → claimed.
    // Keying the clock on `status_changed` alone made this case render nothing.
    const itemRow = item({ id: 1, status: 'claimed', updatedAt: '2026-09-26T10:30:00' });
    const events = [
      event({ id: 1, event: 'created', toStatus: 'ready', createdAt: '2026-09-26T10:00:00' }),
      event({ id: 2, event: 'claimed', fromStatus: 'ready', toStatus: 'claimed', createdAt: '2026-09-26T10:10:00' }),
    ];
    const waiting = waitingInState(itemRow, events);
    expect(waiting).not.toBeNull();
    expect(waiting?.status).toBe('claimed');
    expect(waiting?.sinceAt).toBe('2026-09-26T10:10:00');
    expect(waiting?.minutes).toBe(20);
  });

  it('lease_extended does NOT restart the wait — the item waits since the first claim', () => {
    const itemRow = item({ id: 1, status: 'claimed', updatedAt: '2026-09-26T11:00:00' });
    const events = [
      event({ id: 1, event: 'created', toStatus: 'ready', createdAt: '2026-09-26T10:00:00' }),
      event({ id: 2, event: 'claimed', fromStatus: 'ready', toStatus: 'claimed', createdAt: '2026-09-26T10:10:00' }),
      // from == to by design (WIKI4AI-145): a lease renewal moves the item nowhere.
      event({ id: 3, event: 'lease_extended', fromStatus: 'claimed', toStatus: 'claimed', createdAt: '2026-09-26T10:50:00' }),
    ];
    const waiting = waitingInState(itemRow, events);
    expect(waiting?.sinceAt).toBe('2026-09-26T10:10:00');
    expect(waiting?.minutes).toBe(50);
  });

  it('lease_expired counts as an entry into ready', () => {
    const itemRow = item({ id: 1, status: 'ready', updatedAt: '2026-09-26T11:00:00' });
    const events = [
      event({ id: 1, event: 'claimed', fromStatus: 'ready', toStatus: 'claimed', createdAt: '2026-09-26T10:00:00' }),
      event({ id: 2, event: 'lease_expired', fromStatus: 'claimed', toStatus: 'ready', createdAt: '2026-09-26T10:40:00' }),
    ];
    const waiting = waitingInState(itemRow, events);
    expect(waiting?.status).toBe('ready');
    expect(waiting?.sinceAt).toBe('2026-09-26T10:40:00');
    expect(waiting?.minutes).toBe(20);
  });

  it('claim_released counts as an entry into ready', () => {
    const itemRow = item({ id: 1, status: 'ready', updatedAt: '2026-09-26T11:00:00' });
    const events = [
      event({ id: 1, event: 'claimed', fromStatus: 'ready', toStatus: 'claimed', createdAt: '2026-09-26T10:00:00' }),
      event({ id: 2, event: 'claim_released', fromStatus: 'claimed', toStatus: 'ready', createdAt: '2026-09-26T10:20:00' }),
    ];
    const waiting = waitingInState(itemRow, events);
    expect(waiting?.status).toBe('ready');
    expect(waiting?.sinceAt).toBe('2026-09-26T10:20:00');
    expect(waiting?.minutes).toBe(40);
  });

  it('stale_claim_cleared counts as an entry too', () => {
    const itemRow = item({ id: 1, status: 'ready', updatedAt: '2026-09-26T10:30:00' });
    const events = [
      event({ id: 1, event: 'stale_claim_cleared', fromStatus: 'claimed', toStatus: 'ready', createdAt: '2026-09-26T10:10:00' }),
    ];
    const waiting = waitingInState(itemRow, events);
    expect(waiting?.status).toBe('ready');
    expect(waiting?.minutes).toBe(20);
  });

  it('deleted is not an entry into a state', () => {
    const waiting = waitingInState(item({ id: 1, status: 'cancelled' }), [
      event({ id: 1, event: 'deleted', fromStatus: 'ready', toStatus: 'cancelled', createdAt: '2026-09-26T10:00:00' }),
    ]);
    expect(waiting).toBeNull();
  });

  it('has no waiting time when no event carries a toStatus', () => {
    const waiting = waitingInState(item({ id: 1 }), [
      event({ id: 1, event: 'created', createdAt: '2026-09-26T10:00:00' }),
      event({ id: 2, event: 'lease_extended', toStatus: 'ready', createdAt: '2026-09-26T10:10:00' }),
    ]);
    expect(waiting).toBeNull();
  });

  it('never reports a negative wait', () => {
    const waiting = waitingInState(item({ id: 1, updatedAt: '2026-09-26T09:00:00' }), [
      event({ id: 1, event: 'status_changed', toStatus: 'ready', createdAt: '2026-09-26T10:00:00' }),
    ]);
    expect(waiting?.minutes).toBe(0);
  });
});

describe('isStateEntryEvent (WIKI4AI-162)', () => {
  it('accepts every event that names the state it moved the item into', () => {
    for (const kind of ['created', 'status_changed', 'claimed', 'claim_released', 'lease_expired', 'stale_claim_cleared']) {
      expect(isStateEntryEvent(event({ id: 1, event: kind, toStatus: 'ready' }))).toBe(true);
    }
  });

  it('rejects lease_extended and deleted, and any event without a toStatus', () => {
    expect(isStateEntryEvent(event({ id: 1, event: 'lease_extended', toStatus: 'claimed' }))).toBe(false);
    expect(isStateEntryEvent(event({ id: 2, event: 'deleted', toStatus: 'cancelled' }))).toBe(false);
    expect(isStateEntryEvent(event({ id: 3, event: 'created', toStatus: null }))).toBe(false);
    expect(isStateEntryEvent(event({ id: 4, event: 'claimed', toStatus: 'claimed', createdAt: null }))).toBe(false);
  });
});

describe('timeline ordering', () => {
  it('sorts events oldest first and keeps undated ones last', () => {
    const sorted = sortEventsChronological([
      event({ id: 3, event: 'lease_expired', createdAt: '2026-09-26T12:00:00' }),
      event({ id: 1, event: 'created', createdAt: '2026-09-26T09:00:00' }),
      event({ id: 2, event: 'claimed', createdAt: null }),
      event({ id: 4, event: 'status_changed', createdAt: '2026-09-26T10:00:00' }),
    ]);
    expect(sorted.map((e) => e.id)).toEqual([1, 4, 3, 2]);
  });
});

describe('comments by kind and the handoff card', () => {
  it('groups comments by kind in the contract order', () => {
    const grouped = groupCommentsByKind(
      [
        comment({ id: 1, kind: 'note' }),
        comment({ id: 2, kind: 'handoff' }),
        comment({ id: 3, kind: 'blocker' }),
        comment({ id: 4, kind: 'handoff' }),
      ],
      COMMENT_KINDS,
    );
    expect([...grouped.keys()]).toEqual(['blocker', 'handoff', 'note']);
    expect(grouped.get('handoff')?.map((c) => c.id)).toEqual([2, 4]);
  });

  it('keeps an unknown kind visible instead of dropping it', () => {
    const grouped = groupCommentsByKind([comment({ id: 1, kind: 'escalation' })], COMMENT_KINDS);
    expect(grouped.get('escalation')).toHaveLength(1);
  });

  it('reads to_handle and context out of the handoff payload', () => {
    const fields = handoffFields(
      comment({
        id: 1,
        kind: 'handoff',
        payload: { to_handle: 'reviewer-bot', context: 'Needs a human sign-off', artifacts: ['a.pdf'] },
      }),
    );
    expect(fields.toHandle).toBe('reviewer-bot');
    expect(fields.context).toBe('Needs a human sign-off');
    expect(fields.missing).toEqual([]);
    expect(fields.extra).toEqual([['artifacts', 'a.pdf']]);
  });

  it('reports a missing handoff field as missing, not as empty', () => {
    const fields = handoffFields(comment({ id: 1, kind: 'handoff', payload: { context: 'x' } }));
    expect(fields.toHandle).toBeNull();
    expect(fields.missing).toEqual(['to_handle']);

    const noPayload = handoffFields(comment({ id: 2, kind: 'handoff', payload: null }));
    expect(noPayload.missing).toEqual(['to_handle', 'context']);
  });

  it('formats payload values without inventing structure', () => {
    expect(formatPayloadValue(true)).toBe('true');
    expect(formatPayloadValue(['a', 'b'])).toBe('a, b');
    expect(formatPayloadValue(null)).toBe('');
  });
});

describe('reservations', () => {
  const rows = [
    { id: 1, resourceId: 1, resourceKey: 'gpu', actorId: 2, workItemId: 42, slots: 1, reason: null, expiresAt: null, createdAt: null },
    { id: 2, resourceId: 1, resourceKey: 'gpu', actorId: 3, workItemId: 7, slots: 1, reason: null, expiresAt: null, releasedAt: '2026-09-26T10:00:00', createdAt: null },
    { id: 3, resourceId: 1, resourceKey: 'gpu', actorId: 3, workItemId: 42, slots: 2, reason: 'render', expiresAt: null, releasedAt: null, createdAt: null },
  ];

  it('keeps only the rows whose own workItemId matches', () => {
    expect(reservationsForItem(rows, 42).map((r) => r.id)).toEqual([1, 3]);
    expect(reservationsForItem(rows, 999)).toEqual([]);
  });

  it('live vs released is read from releasedAt, not guessed', () => {
    expect(liveReservations(rows).map((r) => r.id)).toEqual([1, 3]);
  });
});
