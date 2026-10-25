/**
 * WIKI4AI-161 / WIKI4AI-162: pure derivations of the coordination board.
 *
 * Everything the board and the work item detail show is computed here from the API
 * payload — no component guesses a state, a countdown or a presence. Two rules
 * hold this file together:
 *
 * 1. **Nothing is invented.** Every function returns `null` (or an empty list) when
 *    the API did not provide the fact. A missing `leaseExpiresAt` is "no lease",
 *    not "lease of zero"; a timeline whose events carry no `toStatus` has no waiting
 *    time, so the detail page renders no waiting card.
 * 2. **No clocks.** A countdown is `deadline − referencePoint`, where the reference
 *    point is the moment the query last fetched its data. The page re-renders when
 *    react-query refetches (refetchInterval 10s), so the countdown advances without
 *    a single `setInterval` — which is exactly the flake source WIKI4AI-175 is about.
 */

import type {
  ReservationDTO,
  WorkItemCommentDTO,
  WorkItemDTO,
  WorkItemEventDTO,
} from '../services/boardApi';

/** Columns of the board: the seven working states plus the two terminal ones. */
export const BOARD_COLUMNS = [
  'backlog',
  'ready',
  'claimed',
  'running',
  'review',
  'done',
  'blocked',
  'cancelled',
] as const;

/**
 * Columns to render: the declared states, plus any status the API actually returned
 * that is not in the list. A state the backend invented must appear on the board,
 * not be dropped by a UI that only knows eight names.
 */
export function boardColumns(items: WorkItemDTO[]): string[] {
  const columns: string[] = [...BOARD_COLUMNS];
  for (const item of items) {
    if (item.status && !columns.includes(item.status)) columns.push(item.status);
  }
  return columns;
}

/** Group items by their status. Items with an empty status are not shown as a column. */
export function groupItemsByStatus(items: WorkItemDTO[]): Map<string, WorkItemDTO[]> {
  const grouped = new Map<string, WorkItemDTO[]>();
  for (const item of items) {
    if (!item.status) continue;
    const bucket = grouped.get(item.status);
    if (bucket) bucket.push(item);
    else grouped.set(item.status, [item]);
  }
  return grouped;
}

/**
 * Parse a backend timestamp. The backend serializes `LocalDateTime` without an
 * offset ("2026-09-26T12:00:00"), i.e. server-local wall time; JS parses such a
 * string as local wall time too, so both sides agree on the same instant.
 * Returns null for anything unparseable — an unknown time is not a zero time.
 */
export function parseApiTime(value: string | null | undefined): number | null {
  if (!value || typeof value !== 'string') return null;
  const ms = Date.parse(value);
  return Number.isFinite(ms) ? ms : null;
}

/**
 * Remaining lease in milliseconds, relative to `referenceMs` (the moment the data
 * was fetched). `null` means "there is no lease to count down" — an unclaimed item
 * has no deadline, and the card must not imply one.
 */
export function leaseRemainingMs(
  leaseExpiresAt: string | null,
  referenceMs: number,
): number | null {
  const deadline = parseApiTime(leaseExpiresAt);
  if (deadline === null) return null;
  return deadline - referenceMs;
}

/** A lease whose deadline has passed. `lease_expired` is a legitimate state, not an error. */
export function isLeaseExpired(remainingMs: number | null): boolean {
  return remainingMs !== null && remainingMs <= 0;
}

/** Split a duration into whole units for i18n composition (no unit text here). */
export function durationParts(ms: number): { hours: number; minutes: number; seconds: number } {
  const total = Math.max(0, Math.floor(ms / 1000));
  return {
    hours: Math.floor(total / 3600),
    minutes: Math.floor((total % 3600) / 60),
    seconds: total % 60,
  };
}

/** Events oldest first; ties keep the order the API sent. */
export function sortEventsChronological(events: WorkItemEventDTO[]): WorkItemEventDTO[] {
  return [...events].sort((a, b) => {
    const ta = parseApiTime(a.createdAt);
    const tb = parseApiTime(b.createdAt);
    if (ta === null && tb === null) return 0;
    if (ta === null) return 1;
    if (tb === null) return -1;
    return ta - tb;
  });
}

/**
 * Which timeline events count as an ENTRY INTO A STATE.
 *
 * The backend writes eight event kinds (`WorkItemEvent`) and only one of them is
 * called `status_changed`. A state is entered by **every event that carries a
 * `toStatus`**: `created` lands the item in `backlog`/`ready`, `claimed` moves it to
 * `claimed`, `claim_released` and `lease_expired` put it back to `ready`. Keying the
 * waiting clock on the single name `status_changed` loses the most common case on the
 * board — an item sitting in `claimed`, entered there by `claimed` and never by a
 * `status_changed` row. That was the WIKI4AI-162 defect.
 *
 * Two kinds are excluded on purpose:
 *   - `lease_extended` — the backend records it with `from == to` (WIKI4AI-145)
 *     because renewing a lease does not move the item anywhere. Counting it would
 *     restart the clock on every heartbeat, so a claimed item's wait would never grow
 *     past one lease.
 *   - `deleted` — the item is gone; there is no state left to wait in.
 */
const NON_ENTRY_EVENTS: readonly string[] = ['lease_extended', 'deleted'];

/** True when this event is what put the item into the state it is now waiting in. */
export function isStateEntryEvent(event: WorkItemEventDTO): boolean {
  if (!event.toStatus) return false;
  if (NON_ENTRY_EVENTS.includes(event.event)) return false;
  return parseApiTime(event.createdAt) !== null;
}

/**
 * How long the item has been sitting in its current state.
 *
 * Derived from the timeline, never from the wall clock: the start is the newest
 * state-entry event (see `isStateEntryEvent`), the end is the newest timestamp the
 * API actually reported (the newest event, or `updatedAt` when no later event
 * exists). If the timeline carries no state-entry event at all, there is no waiting
 * time to report and this returns null.
 */
export interface WaitingInState {
  /** The state the item is waiting in — the `toStatus` of that entry event. */
  status: string;
  /** ISO string of the event that started the wait. */
  sinceAt: string;
  /** ISO string of the newest API-reported moment the wait is measured to. */
  measuredTo: string;
  minutes: number;
}

export function waitingInState(
  item: WorkItemDTO,
  events: WorkItemEventDTO[],
): WaitingInState | null {
  const sorted = sortEventsChronological(events);
  let stateEntry: WorkItemEventDTO | null = null;
  for (const event of sorted) {
    if (isStateEntryEvent(event)) stateEntry = event;
  }
  if (!stateEntry) return null;

  const sinceMs = parseApiTime(stateEntry.createdAt);
  if (sinceMs === null) return null;

  const knownMoments = sorted
    .map((e) => parseApiTime(e.createdAt))
    .filter((t): t is number => t !== null);
  const updatedAtMs = parseApiTime(item.updatedAt);
  if (updatedAtMs !== null) knownMoments.push(updatedAtMs);
  if (knownMoments.length === 0) return null;

  const measuredToMs = Math.max(...knownMoments);
  return {
    status: stateEntry.toStatus as string,
    sinceAt: stateEntry.createdAt as string,
    measuredTo: new Date(measuredToMs).toISOString(),
    minutes: Math.max(0, Math.round((measuredToMs - sinceMs) / 60_000)),
  };
}

/** Comments grouped by kind, kinds in the contract order, unknown kinds last. */
export function groupCommentsByKind(
  comments: WorkItemCommentDTO[],
  kindOrder: readonly string[],
): Map<string, WorkItemCommentDTO[]> {
  const grouped = new Map<string, WorkItemCommentDTO[]>();
  for (const comment of comments) {
    const kind = comment.kind || 'note';
    const bucket = grouped.get(kind);
    if (bucket) bucket.push(comment);
    else grouped.set(kind, [comment]);
  }
  const ordered = new Map<string, WorkItemCommentDTO[]>();
  for (const kind of kindOrder) {
    if (grouped.has(kind)) ordered.set(kind, grouped.get(kind) as WorkItemCommentDTO[]);
  }
  for (const [kind, list] of grouped) {
    if (!ordered.has(kind)) ordered.set(kind, list);
  }
  return ordered;
}

/**
 * The handoff card fields, straight from the comment payload (WIKI4AI-149):
 * `to_handle` (who takes it over) and `context` (why/what). A key the comment does
 * not carry is reported as missing — the card shows "missing", never an empty value,
 * because an empty handoff is the failure this story is meant to make visible.
 */
export interface HandoffFields {
  toHandle: string | null;
  context: string | null;
  /** Payload keys the contract guarantees but this comment does not carry. */
  missing: string[];
  /** Extra keys the author added beyond the contract (kept, not dropped). */
  extra: Array<[string, string]>;
}

const HANDOFF_REQUIRED = ['to_handle', 'context'] as const;

export function handoffFields(comment: WorkItemCommentDTO): HandoffFields {
  const payload = comment.payload && typeof comment.payload === 'object' ? comment.payload : {};
  const text = (value: unknown): string | null =>
    typeof value === 'string' && value.trim() !== '' ? value : null;

  const toHandle = text(payload.to_handle);
  const context = text(payload.context);

  const missing = HANDOFF_REQUIRED.filter((key) => text(payload[key]) === null);
  const extra = Object.entries(payload)
    .filter(([key]) => !HANDOFF_REQUIRED.includes(key as (typeof HANDOFF_REQUIRED)[number]))
    .map(([key, value]) => [key, formatPayloadValue(value)] as [string, string]);

  return { toHandle, context, missing, extra };
}

/** Render one payload value as compact text (arrays joined, objects as JSON). */
export function formatPayloadValue(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (Array.isArray(value)) return value.map((v) => formatPayloadValue(v)).join(', ');
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

/** Live holdings only: `releasedAt == null` is the API's own statement that a reservation is live. */
export function liveReservations(reservations: ReservationDTO[]): ReservationDTO[] {
  return reservations.filter((r) => r.releasedAt === null || r.releasedAt === undefined);
}

/** Reservations that belong to one work item (the API carries `workItemId` on each row). */
export function reservationsForItem(
  reservations: ReservationDTO[],
  workItemId: number,
): ReservationDTO[] {
  return reservations.filter((r) => r.workItemId === workItemId);
}

/**
 * Presence of an actor, taken from `ActorDTO.status` — the dot is a rendering of a
 * value the API sent, never an inference from "last seen N minutes ago".
 */
export function presenceOf(actor: { status: string }): string {
  return actor.status;
}

/** Actor lookup by id, for the assignee dot on a card. `null` when the API did not send that actor. */
export function buildActorIndex(actors: Array<{ id: number }>): Map<number, { id: number }> {
  const index = new Map<number, { id: number }>();
  for (const actor of actors) index.set(actor.id, actor);
  return index;
}
