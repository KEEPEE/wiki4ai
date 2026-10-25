/**
 * WIKI4AI-165: the "what arrived" marker for a caller that has NO actor.
 *
 * ── Why this file exists ─────────────────────────────────────────────────────
 * The inbox is strictly actor-scoped. `GET /api/v1/actors/{id}/inbox/unseen` needs an
 * actor id, and `ActorInboxController.requireInboxAccess` answers 403 for a foreign
 * actor. A human browser session on dev is authenticated with a legacy JWT, and
 * `GET /api/v1/actors/whoami` reports `actorId: null` for it — so there is no inbox,
 * no `actors.last_seen_event_id` marker and no server-side count to display.
 *
 * The rule of this story is that the UI must not invent a number in that situation.
 * So the badge is derived from the one fact that IS real:
 *
 *   the set of `WorkItemEventDTO.id` values the API actually sent to THIS browser,
 *   minus the ones this browser has already been shown.
 *
 * `work_item_events.id` is a server-assigned, monotonic identity of an event
 * (BIGSERIAL, append-only). Comparing ids is therefore a comparison of server-issued
 * identities, not a UI guess, and it is the same mechanism the backend itself uses
 * for the actor inbox: "unseen = id > last_seen_event_id" (UnseenEventsDTO).
 *
 * ── The limitation, stated plainly ───────────────────────────────────────────
 * This measures **"new events you actually looked at"**, NOT "all new events":
 *   - the only events this browser can count are the ones some request delivered to
 *     it, so an event nobody fetched is invisible to this number;
 *   - the scan is bounded (see MAX_UNSEEN_SCAN_ITEMS in `useUnseenEvents`), so even
 *     the fetched set is a window, not the whole stream;
 *   - the high-water mark is per browser (localStorage), not per actor: another tab
 *     or another machine has its own.
 * An actor-backed session does not use this path at all — it gets the exact
 * `UnseenEventsDTO.count` from the server.
 *
 * ── What this file must never do ─────────────────────────────────────────────
 * No `Date.now()`, no `createdAt` comparison, no invented endpoint. A count built by
 * comparing timestamps to the wall clock would change every render and would count
 * events the API never sent. `unseenEventIds()` is pure and takes no clock at all.
 */

/** High-water mark: the largest `work_item_events.id` this browser has been shown. */
export const SEEN_EVENT_HIGH_WATER_KEY = 'wiki4ai_seen_event_high_water';

/** A high-water mark of `null` means "this browser has never been shown an event". */
export function readSeenEventHighWater(): number | null {
  let raw: string | null = null;
  try {
    raw = localStorage.getItem(SEEN_EVENT_HIGH_WATER_KEY);
  } catch {
    return null;
  }
  if (raw === null || raw === '') return null;
  const value = Number(raw);
  return Number.isFinite(value) && value >= 0 ? value : null;
}

/** Largest id in a list of server-assigned ids; null for an empty list. */
export function maxEventId(eventIds: readonly number[]): number | null {
  let max: number | null = null;
  for (const id of eventIds) {
    if (typeof id !== 'number' || !Number.isFinite(id)) continue;
    if (max === null || id > max) max = id;
  }
  return max;
}

/** Persist the new high-water mark. Only an id is stored — never a token, never a body. */
export function recordSeenEventIds(eventIds: readonly number[]): number | null {
  const max = maxEventId(eventIds);
  if (max === null) return readSeenEventHighWater();
  const previous = readSeenEventHighWater();
  const next = previous === null ? max : Math.max(previous, max);
  try {
    localStorage.setItem(SEEN_EVENT_HIGH_WATER_KEY, String(next));
  } catch {
    // Storage unavailable: the marker degrades to "unknown", it does not guess.
  }
  return next;
}

/**
 * The derivation itself: ids strictly greater than the remembered maximum.
 *
 * Pure — no clock, no arguments derived from time. `highWater === null` means nothing
 * has ever been shown, so every delivered id is new (the same "null is not 0" rule
 * the backend documents for `lastSeenEventId`).
 */
export function unseenEventIds(
  deliveredEventIds: readonly number[],
  highWater: number | null,
): number[] {
  return deliveredEventIds.filter(
    (id) => typeof id === 'number' && Number.isFinite(id) && (highWater === null || id > highWater),
  );
}

/** Drop the marker on logout: a signed-out browser keeps no reading position. */
export function clearSeenEventHighWater(): void {
  try {
    localStorage.removeItem(SEEN_EVENT_HIGH_WATER_KEY);
  } catch {
    // Nothing to clean up if storage itself is unreachable.
  }
}
