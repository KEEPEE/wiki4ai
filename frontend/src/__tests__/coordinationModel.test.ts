/**
 * WIKI4AI-164: the pure derivations behind the resources and metrics pages.
 *
 * The contract: a ratio is only formed from two numbers the API sent, a countdown is
 * only `deadline − fetchedAt`, and a missing operand produces `null` — never 0, never
 * 100%. A 0% bar and a 100% bar are both statements the API did not make.
 */
import { describe, it, expect } from 'vitest';
import {
  barStyle,
  formatDurationSeconds,
  isReservationExpired,
  reservationRemainingMs,
  sharePercent,
  statusSecondsShares,
} from '../pages/coordinationModel';

const t = (key: string, options?: Record<string, unknown>) =>
  `${key}:${JSON.stringify(options ?? {})}`;

describe('sharePercent / barStyle (WIKI4AI-164)', () => {
  it('is the ratio of the two API numbers', () => {
    expect(sharePercent(2, 4)).toBe(50);
    expect(sharePercent(1, 3)).toBeCloseTo(33.333, 3);
    expect(barStyle(50)?.width).toBe('50.00%');
  });

  it('refuses to draw a bar the API did not make possible', () => {
    expect(sharePercent(0, 0)).toBeNull();
    expect(sharePercent(3, 0)).toBeNull();
    expect(sharePercent(null, 4)).toBeNull();
    expect(sharePercent(2, null)).toBeNull();
    expect(barStyle(null)).toBeUndefined();
  });

  it('clamps an over-capacity reading to the bar, without hiding it in the number', () => {
    // The invariant lives in the backend; a drifted row must not draw a 300% bar.
    expect(sharePercent(6, 4)).toBe(100);
  });
});

describe('reservation TTL (WIKI4AI-175)', () => {
  const FETCHED_AT = Date.parse('2026-09-26T10:00:00');

  it('is expiresAt minus the fetch moment', () => {
    expect(reservationRemainingMs('2026-09-26T10:30:00', FETCHED_AT)).toBe(30 * 60_000);
    expect(isReservationExpired(reservationRemainingMs('2026-09-26T09:00:00', FETCHED_AT))).toBe(true);
  });

  it('has no countdown when the API sent no deadline or there is no fetch yet', () => {
    expect(reservationRemainingMs(null, FETCHED_AT)).toBeNull();
    expect(reservationRemainingMs('not-a-time', FETCHED_AT)).toBeNull();
    expect(reservationRemainingMs('2026-09-26T10:30:00', null)).toBeNull();
    expect(isReservationExpired(null)).toBe(false);
  });
});

describe('statusSecondsShares (WIKI4AI-164)', () => {
  it('pairs each state with its share of the API total', () => {
    const shares = statusSecondsShares({ claimed: 1800, running: 5400 }, 7200);
    expect(shares).toEqual([
      { status: 'claimed', seconds: 1800, percent: 25 },
      { status: 'running', seconds: 5400, percent: 75 },
    ]);
  });

  it('keeps the percent null when the total is not usable', () => {
    const shares = statusSecondsShares({ claimed: 10 }, 0);
    expect(shares[0].percent).toBeNull();
    expect(statusSecondsShares({}, 100)).toEqual([]);
  });
});

describe('formatDurationSeconds (formatting, not computing)', () => {
  it('renders the API double readably', () => {
    expect(formatDurationSeconds(7500, t)).toBe('common.durationHoursMinutes:{"hours":2,"minutes":5}');
    expect(formatDurationSeconds(1800, t)).toBe('common.durationMinutesSeconds:{"minutes":30,"seconds":0}');
    expect(formatDurationSeconds(44.98, t)).toBe('common.durationSeconds:{"seconds":44.98}');
  });

  it('returns null for a value that is not a duration', () => {
    expect(formatDurationSeconds(null, t)).toBeNull();
    expect(formatDurationSeconds(Number.NaN, t)).toBeNull();
    expect(formatDurationSeconds(-1, t)).toBeNull();
  });
});
