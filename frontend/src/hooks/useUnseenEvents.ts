/**
 * WIKI4AI-165: the "what arrived" marker in the navigation.
 *
 * Two paths, and which one runs is decided by the API, not by the UI:
 *
 * 1. `whoami.actorId !== null` → `GET /api/v1/actors/{id}/inbox/unseen` and the badge
 *    is `UnseenEventsDTO.count`. That number is the server's COUNT over the actor's
 *    event stream: authoritative, exact, nothing derived here. Opening the marker
 *    calls `POST /api/v1/actors/{id}/inbox/mark-seen` with no body and re-reads the
 *    count, which is 0 afterwards.
 *
 * 2. `whoami.actorId === null` (a legacy JWT — the normal human session on dev) →
 *    there is no inbox to ask, so the badge is the derivation documented in
 *    `src/utils/unseenEvents.ts`: the `WorkItemEventDTO.id` values this browser was
 *    actually sent that are greater than the maximum this browser has already been
 *    shown. Opening the marker records that maximum and the badge disappears.
 *
 *    LIMITATION, restated because it is the whole point of the story: this counts
 *    "new events this browser actually looked at", NOT "all new events". An event no
 *    request delivered to this browser is not in the number, and the scan is bounded
 *    by MAX_UNSEEN_SCAN_ITEMS. It is a derivation over server-assigned, monotonic
 *    event ids — not a UI-invented count, and not a timestamp comparison.
 *
 * There is no third path: no invented endpoint, and `Date.now()` is not used as a
 * source of a count anywhere in this file. When neither read produced an answer the
 * marker renders nothing — an unknown count is not rendered as zero.
 *
 * Polling: `refetchInterval`, no `setInterval`, no component timer (WIKI4AI-175).
 */

import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getWhoami, listWorkItemEvents, listWorkItems } from '../services/boardApi';
import { getUnseenEvents, markInboxSeen } from '../services/agentsApi';
import {
  maxEventId,
  readSeenEventHighWater,
  recordSeenEventIds,
  unseenEventIds,
} from '../utils/unseenEvents';
import { BOARD_REFETCH_INTERVAL_MS, type QueryOptions } from './useBoard';

/** Cadence of the cheap actor-backed reads. */
export const UNSEEN_REFETCH_INTERVAL_MS = BOARD_REFETCH_INTERVAL_MS;

/**
 * Cadence of the browser-backed scan. It is slower on purpose: the fallback path fans
 * out to one work-items list plus one event page per scanned item, and a nav badge is
 * not worth a request storm on every page of the app.
 */
export const UNSEEN_SCAN_REFETCH_INTERVAL_MS = 30_000;

/**
 * Upper bound of the fallback scan, in the spirit of `MAX_BLOCKER_SCAN_ITEMS` in
 * `useBoard.ts`: the events endpoint is addressed per work item, so the scan takes
 * the first N rows `GET /work-items` returned and no more. N is a bound, not a claim
 * that only N items have events.
 */
export const MAX_UNSEEN_SCAN_ITEMS = 6;

/** Event rows read per scanned work item. */
export const UNSEEN_SCAN_EVENT_SIZE = 25;

export type UnseenSource = 'actor-inbox' | 'browser-events' | 'unknown';

export interface UnseenEventsState {
  /** 0 only when a read actually established that nothing is new. */
  count: number;
  /** Which rule produced `count`; 'unknown' means `count` must not be rendered. */
  source: UnseenSource;
  /** False while neither read has produced an answer — the badge renders nothing. */
  known: boolean;
  isMarking: boolean;
  /** Actor id the marker resolved from `whoami`, null for a legacy JWT session. */
  actorId: number | null;
  markSeen: () => Promise<void>;
}

function interval(ms: number, options: QueryOptions | undefined): number | false {
  const value = options?.refetchIntervalMs ?? ms;
  return value > 0 ? value : false;
}

interface ScanResult {
  /** Every event id the API delivered to this browser in the last scan. */
  delivered: number[];
  /** `delivered` above the remembered high-water mark. */
  unseen: number[];
  highWater: number | null;
}

export function useUnseenEvents(options?: QueryOptions): UnseenEventsState {
  const queryClient = useQueryClient();

  // ── Who am I? The path is chosen from the answer, never from a guess. ──────
  const whoamiQuery = useQuery({
    queryKey: ['actors', 'whoami'],
    // `?? null` keeps the cache value defined: a caller without an actor is a real
    // answer, not a missing one.
    queryFn: async () => (await getWhoami()) ?? null,
    refetchInterval: interval(UNSEEN_REFETCH_INTERVAL_MS, options),
    enabled: options?.enabled ?? true,
  });

  const rawActorId = whoamiQuery.data?.actorId;
  const actorId = typeof rawActorId === 'number' && Number.isFinite(rawActorId) ? rawActorId : null;

  // ── Path 1: the actor's inbox, authoritative count from the server. ────────
  const inboxQuery = useQuery({
    queryKey: ['actor-inbox-unseen', actorId],
    queryFn: () => getUnseenEvents(actorId as number),
    refetchInterval: interval(UNSEEN_REFETCH_INTERVAL_MS, options),
    enabled: (options?.enabled ?? true) && actorId !== null,
  });

  // ── Path 2: no actor, so only what this browser was actually sent. ─────────
  const scanQuery = useQuery<ScanResult>({
    queryKey: ['unseen-events-scan'],
    queryFn: async () => {
      const items = await listWorkItems({});
      const itemIds = items
        .map((item) => item.id)
        .filter((id) => Number.isFinite(id))
        .slice(0, MAX_UNSEEN_SCAN_ITEMS);
      const pages = await Promise.all(
        itemIds.map((id) => listWorkItemEvents(id, { size: UNSEEN_SCAN_EVENT_SIZE })),
      );
      // One event delivered twice (two scans, two pages) is still ONE event, so the
      // ids are deduplicated before they are counted.
      const delivered = Array.from(
        new Set(
          pages
            .flat()
            .map((event) => event.id)
            .filter((id) => Number.isFinite(id)),
        ),
      );
      const highWater = readSeenEventHighWater();
      return { delivered, unseen: unseenEventIds(delivered, highWater), highWater };
    },
    refetchInterval: interval(UNSEEN_SCAN_REFETCH_INTERVAL_MS, options),
    enabled: (options?.enabled ?? true) && actorId === null,
  });

  // ── Which answer wins. ─────────────────────────────────────────────────────
  let count = 0;
  let source: UnseenSource = 'unknown';
  let known = false;

  if (actorId !== null && inboxQuery.data) {
    count = inboxQuery.data.count;
    source = 'actor-inbox';
    known = true;
  } else if (actorId === null && scanQuery.data) {
    count = scanQuery.data.unseen.length;
    source = 'browser-events';
    known = true;
  }

  const markSeen = async (): Promise<void> => {
    if (actorId !== null) {
      // No body: "everything the server has shown me is seen".
      await markInboxSeen(actorId);
      await queryClient.invalidateQueries({ queryKey: ['actor-inbox-unseen', actorId] });
      return;
    }
    const delivered = scanQuery.data?.delivered ?? [];
    // Records the highest server-assigned id this browser has been shown.
    recordSeenEventIds(delivered);
    await queryClient.invalidateQueries({ queryKey: ['unseen-events-scan'] });
  };

  return {
    count,
    source,
    known,
    isMarking: inboxQuery.isFetching || scanQuery.isFetching,
    actorId,
    markSeen,
  };
}

/** Exposed for tests and for the marker's tooltip. */
export { maxEventId };
