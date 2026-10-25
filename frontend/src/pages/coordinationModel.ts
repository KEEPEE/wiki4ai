/**
 * WIKI4AI-163 / WIKI4AI-164: pure derivations of the agents, resources and metrics
 * pages. Same contract as `boardModel.ts` (WIKI4AI-161), which this file extends:
 *
 * 1. **Nothing is invented.** Every function returns `null` (or an empty list) when
 *    the API did not provide the fact. A resource with `capacity: null` has no bar,
 *    not a 0% bar; a reservation with no `expiresAt` has no countdown, not an expired
 *    one; an actor with no `statusSeconds` entry has no bar for that state.
 * 2. **No clocks, no arithmetic the API already did.** A countdown is
 *    `expiresAt − fetchedAt` (the moment the query fetched), never `Date.now()` in a
 *    render and never a `setInterval` (WIKI4AI-175). Percentages are only ever the
 *    ratio of two numbers the API sent (`activeSlots/capacity`,
 *    `statusSeconds/totalStatusSeconds`) — a sum, an average or a count is never
 *    computed here, because `GET /metrics/actors` already returns `summary`,
 *    `totalStatusSeconds` and `averageCycleSeconds`.
 * 3. Formatting is not computing: turning 7500 seconds into "2h 05m" is a rendering
 *    of the API's own double, and it is the only transformation allowed.
 */

import { durationParts, leaseRemainingMs, parseApiTime } from './boardModel';

type Translator = (key: string, options?: Record<string, unknown>) => string;

/**
 * `part / whole` as a percentage, or `null` when the ratio is not derivable.
 *
 * `null` is the honest answer for `whole === 0` and for a missing operand: a bar of
 * width 0 would claim "the API measured an empty capacity", and a bar of width 100
 * would claim "everything is taken". Both are statements the API did not make.
 */
export function sharePercent(part: number | null, whole: number | null): number | null {
  if (part === null || whole === null) return null;
  if (!Number.isFinite(part) || !Number.isFinite(whole) || whole <= 0) return null;
  const percent = (part / whole) * 100;
  if (!Number.isFinite(percent)) return null;
  return Math.min(100, Math.max(0, percent));
}

/** Inline style for a CSS bar: width is the API's ratio, nothing else. */
export function barStyle(percent: number | null): { width: string } | undefined {
  if (percent === null) return undefined;
  return { width: `${percent.toFixed(2)}%` };
}

/**
 * Remaining TTL of a reservation, relative to `fetchedAt`.
 *
 * This is `leaseRemainingMs` under the board's name for the same arithmetic — one
 * implementation, so the two pages cannot drift apart. `null` means the reservation
 * carries no deadline, which is not the same as "deadline reached".
 */
export function reservationRemainingMs(
  expiresAt: string | null,
  fetchedAt: number | null,
): number | null {
  if (fetchedAt === null) return null;
  return leaseRemainingMs(expiresAt, fetchedAt);
}

/** True when the API's own deadline is already behind the fetch that reported it. */
export function isReservationExpired(remainingMs: number | null): boolean {
  return remainingMs !== null && remainingMs <= 0;
}

/**
 * Seconds → readable duration. Formatting only: the value is the API's double, and
 * the unit words come from i18n so no locale is hardcoded.
 */
export function formatDurationSeconds(seconds: number | null, t: Translator): string | null {
  if (seconds === null || !Number.isFinite(seconds) || seconds < 0) return null;
  const { hours, minutes, seconds: rest } = durationParts(seconds * 1000);
  if (hours > 0) return t('common.durationHoursMinutes', { hours, minutes });
  if (minutes > 0) return t('common.durationMinutesSeconds', { minutes, seconds: rest });
  return t('common.durationSeconds', { seconds: Math.round(seconds * 100) / 100 });
}

/** Locale-aware rendering of a backend timestamp; null when there is no timestamp. */
export function formatApiDateTime(iso: string | null, lang: string): string | null {
  const ms = parseApiTime(iso);
  if (ms === null) return null;
  return new Intl.DateTimeFormat(lang === 'sk' ? 'sk' : 'en', {
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(ms));
}

/** One state's slice of an actor's time, with the share the API made possible. */
export interface StatusShare {
  status: string;
  seconds: number;
  /** `seconds / totalStatusSeconds` as a percent, or null when the total is not usable. */
  percent: number | null;
}

/**
 * `statusSeconds` → rows, ordered by the API's own map order (the backend returns a
 * LinkedHashMap). Sorting by size would be a UI opinion; the API's order is kept.
 * The percent is the ratio the story asks for and nothing more.
 */
export function statusSecondsShares(
  statusSeconds: Record<string, number>,
  totalStatusSeconds: number | null,
): StatusShare[] {
  return Object.entries(statusSeconds).map(([status, seconds]) => ({
    status,
    seconds,
    percent: sharePercent(seconds, totalStatusSeconds),
  }));
}

/** Live vs released, decided by the API's own `releasedAt` (see boardModel.liveReservations). */
export function isReleased(releasedAt: string | null | undefined): boolean {
  return releasedAt !== null && releasedAt !== undefined;
}
