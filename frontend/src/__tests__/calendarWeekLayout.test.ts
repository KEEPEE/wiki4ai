/**
 * WIKI4AI-110: unit tests for the week-view layout helpers.
 *
 * Covers week anchoring (Monday-first), time parsing (backend "HH:mm:ss" and
 * "HH:mm"), event span normalization (all-day → null, missing/degenerate end
 * times) and the overlap-column algorithm that places overlapping timed
 * events side by side inside a day column.
 */
import { describe, it, expect } from 'vitest';
import {
  DAY_MINUTES,
  startOfWeek,
  weekDays,
  timeToMinutes,
  eventSpan,
  layoutDayEvents,
  type TimedSpan,
} from '../pages/calendarWeekLayout';

// ── Helpers ──────────────────────────────────────────────────────────────────

const span = (startMin: number, endMin: number): TimedSpan => ({ startMin, endMin });

describe('calendarWeekLayout — week anchoring', () => {
  it('startOfWeek returns the Monday of the week (Sunday belongs to the same week)', () => {
    // 2026-09-27 is a Sunday, 2026-09-28 a Monday.
    const sunday = new Date(2026, 8, 27);
    expect(sunday.getDay()).toBe(0);
    const monday = startOfWeek(sunday);
    expect(monday.toISOString().slice(0, 10)).toBe('2026-09-21');
    expect(monday.getDay()).toBe(1);

    // A Monday anchors to itself.
    const mondayInput = new Date(2026, 8, 28);
    expect(startOfWeek(mondayInput).toISOString().slice(0, 10)).toBe('2026-09-28');
  });

  it('weekDays returns the seven Mon–Sun days of the week', () => {
    const days = weekDays(new Date(2026, 8, 27)); // Sunday → week of Sep 21–27
    expect(days).toHaveLength(7);
    expect(days.map((d) => d.toISOString().slice(0, 10))).toEqual([
      '2026-09-21',
      '2026-09-22',
      '2026-09-23',
      '2026-09-24',
      '2026-09-25',
      '2026-09-26',
      '2026-09-27',
    ]);
  });
});

describe('calendarWeekLayout — time parsing', () => {
  it('parses HH:mm and backend HH:mm:ss into minutes from midnight', () => {
    expect(timeToMinutes('09:30')).toBe(570);
    expect(timeToMinutes('09:30:45')).toBe(570); // backend format is normalized
    expect(timeToMinutes('00:00')).toBe(0);
    expect(timeToMinutes('23:59')).toBe(DAY_MINUTES - 1);
  });

  it('returns null for missing or unparseable times', () => {
    expect(timeToMinutes(null)).toBeNull();
    expect(timeToMinutes(undefined)).toBeNull();
    expect(timeToMinutes('')).toBeNull();
    expect(timeToMinutes('garbage')).toBeNull();
    expect(timeToMinutes('25:00')).toBeNull();
    expect(timeToMinutes('12:60')).toBeNull();
  });
});

describe('calendarWeekLayout — event spans', () => {
  it('all-day events (no start time) have no grid span', () => {
    expect(eventSpan(null, null)).toBeNull();
    expect(eventSpan(undefined, undefined)).toBeNull();
  });

  it('computes the span from HH:mm[:ss] times', () => {
    expect(eventSpan('10:00', '11:00')).toEqual({ startMin: 600, endMin: 660 });
    expect(eventSpan('10:00:00', '11:30:00')).toEqual({ startMin: 600, endMin: 690 }); // backend format
  });

  it('defaults a missing end time to one hour', () => {
    expect(eventSpan('10:00', null)).toEqual({ startMin: 600, endMin: 660 });
  });

  it('treats a degenerate (end <= start) span as one hour, clamped to midnight', () => {
    expect(eventSpan('10:00', '10:00')).toEqual({ startMin: 600, endMin: 660 });
    // Overnight-looking 23:30 → 00:00 stays inside the day.
    expect(eventSpan('23:30', '00:00')).toEqual({ startMin: 1410, endMin: DAY_MINUTES });
  });
});

describe('calendarWeekLayout — overlap columns', () => {
  it('keeps non-overlapping events full width (cols = 1)', () => {
    const placed = layoutDayEvents([span(540, 600), span(660, 720)]); // 09:00–10:00, 11:00–12:00
    expect(placed).toHaveLength(2);
    for (const p of placed) {
      expect(p.cols).toBe(1);
      expect(p.col).toBe(0);
    }
    // Input order is preserved through the indices.
    expect(placed.map((p) => p.index)).toEqual([0, 1]);
  });

  it('places a two-way overlap side by side (two half columns)', () => {
    const placed = layoutDayEvents([span(600, 720), span(660, 780)]); // 10:00–12:00, 11:00–13:00
    expect(placed).toHaveLength(2);
    for (const p of placed) {
      expect(p.cols).toBe(2);
    }
    const cols = placed.map((p) => p.col).sort((a, b) => a - b);
    expect(cols).toEqual([0, 1]);
  });

  it('groups transitively overlapping events into one cluster and reuses freed columns', () => {
    // A(09–11) overlaps B(10–12); B overlaps C(11–13); A and C do not touch.
    const placed = layoutDayEvents([span(540, 660), span(600, 720), span(660, 780)]);
    expect(placed).toHaveLength(3);
    // Two columns are enough: C starts exactly when A ends and reuses column 0.
    for (const p of placed) {
      expect(p.cols).toBe(2);
    }
    const byIndex = new Map(placed.map((p) => [p.index, p.col]));
    expect(byIndex.get(0)).toBe(0); // A
    expect(byIndex.get(1)).toBe(1); // B overlaps A
    expect(byIndex.get(2)).toBe(0); // C reuses A's freed column
  });

  it('gives identical events their own columns', () => {
    const placed = layoutDayEvents([span(540, 600), span(540, 600)]);
    expect(placed.map((p) => p.cols)).toEqual([2, 2]);
    expect(placed.map((p) => p.col).sort((a, b) => a - b)).toEqual([0, 1]);
  });

  it('skips all-day entries and keeps stable indices into the input array', () => {
    const placed = layoutDayEvents([null, span(600, 660), null]);
    expect(placed).toHaveLength(1);
    expect(placed[0].index).toBe(1);
    expect(placed[0].cols).toBe(1);
  });

  it('handles an empty day', () => {
    expect(layoutDayEvents([])).toEqual([]);
    expect(layoutDayEvents([null, null])).toEqual([]);
  });
});
