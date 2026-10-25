/**
 * WIKI4AI-161 / WIKI4AI-162: react-query hooks of the coordination layer.
 *
 * Live data without a live connection: every query here refetches on an interval
 * (`refetchInterval: 10_000`) — no WebSocket, no SSE, no EventSource. Each query
 * function returns its payload together with `fetchedAt`, the moment the fetch
 * happened; that timestamp is the reference point every countdown is measured
 * against, so a countdown advances because the data was refetched, not because a
 * `setInterval` mutated component state (WIKI4AI-175).
 *
 * The hooks add no business logic of their own: they call one boardApi function per
 * endpoint and hand the rows to the page. Where the API has no filter for a fact
 * (blocker comments are per work item, there is no cross-item comment endpoint), the
 * hook asks the API for exactly that filtered list per item and passes the answers
 * through — it does not synthesize a blocker the backend did not report.
 */

import { useQuery, type QueryObserverResult } from '@tanstack/react-query';
import {
  getWhoami,
  getWorkItem,
  listActors,
  listReservations,
  listResources,
  listWorkItemComments,
  listWorkItemEvents,
  listWorkItems,
  type ActorDTO,
  type ActorWhoamiDTO,
  type ResourceDTO,
  type ReservationDTO,
  type WorkItemCommentDTO,
  type WorkItemDTO,
  type WorkItemEventDTO,
  type WorkItemFilters,
} from '../services/boardApi';
import { reservationsForItem, sortEventsChronological } from '../pages/boardModel';

/** Live cadence of the board (task requirement: 10s polling, nothing push-based). */
export const BOARD_REFETCH_INTERVAL_MS = 10_000;

/**
 * Upper bound of per-item comment lookups the board performs in one pass. The
 * comments endpoint is addressed by work item id, so "what is waiting for a human"
 * is the union of the blocker queries of the visible items — bounded on purpose so
 * a big board cannot turn into an unbounded fan-out.
 */
export const MAX_BLOCKER_SCAN_ITEMS = 25;

export interface QueryOptions {
  /** Override the polling interval (tests pass 0 to keep the interval timer out of the run). */
  refetchIntervalMs?: number;
  enabled?: boolean;
}

function interval(options: QueryOptions | undefined): number | false {
  const value = options?.refetchIntervalMs ?? BOARD_REFETCH_INTERVAL_MS;
  return value > 0 ? value : false;
}

/** A fetched payload plus the moment it was fetched — the reference point of every countdown. */
export interface Snapshot<T> {
  data: T;
  fetchedAt: number;
}

// ── Board ────────────────────────────────────────────────────────────────────

export function useWorkItems(
  filters: WorkItemFilters,
  options?: QueryOptions,
): {
  items: WorkItemDTO[];
  fetchedAt: number | null;
  isLoading: boolean;
  isError: boolean;
  error: unknown;
  refetch: () => Promise<QueryObserverResult>;
} {
  const query = useQuery({
    queryKey: ['work-items', filters],
    queryFn: async (): Promise<Snapshot<WorkItemDTO[]>> => ({
      data: await listWorkItems(filters),
      fetchedAt: Date.now(),
    }),
    refetchInterval: interval(options),
    enabled: options?.enabled ?? true,
  });

  return {
    items: query.data?.data ?? [],
    fetchedAt: query.data?.fetchedAt ?? null,
    isLoading: query.isLoading,
    isError: query.isError,
    error: query.error,
    refetch: query.refetch,
  };
}

export function useActors(
  status?: string | null,
  options?: QueryOptions,
): { actors: ActorDTO[]; isLoading: boolean; isError: boolean; error: unknown } {
  const query = useQuery({
    queryKey: ['actors', status ?? null],
    queryFn: () => listActors(status ?? null),
    refetchInterval: interval(options),
    enabled: options?.enabled ?? true,
  });
  return {
    actors: query.data ?? [],
    isLoading: query.isLoading,
    isError: query.isError,
    error: query.error,
  };
}

export function useResources(
  options?: QueryOptions,
): { resources: ResourceDTO[]; isLoading: boolean; isError: boolean; error: unknown } {
  const query = useQuery({
    queryKey: ['resources'],
    queryFn: () => listResources(),
    refetchInterval: interval(options),
    enabled: options?.enabled ?? true,
  });
  return {
    resources: query.data ?? [],
    isLoading: query.isLoading,
    isError: query.isError,
    error: query.error,
  };
}

/**
 * "What is waiting for you": the blocker comments the backend flags as
 * `needs_human` (WIKI4AI-151). The filter is sent to the API — `kind=blocker` and
 * `needs_human=true` are the backend's own parameters — and the UI renders the rows
 * it gets back. It does not decide on its own which comments "look like" blockers.
 */
export function useWaitingForMe(
  workItemIds: number[],
  options?: QueryOptions,
): {
  comments: WorkItemCommentDTO[];
  scannedItemIds: number[];
  isLoading: boolean;
  isError: boolean;
  error: unknown;
} {
  const ids = workItemIds.slice(0, MAX_BLOCKER_SCAN_ITEMS);
  const query = useQuery({
    queryKey: ['work-item-blockers', ids],
    queryFn: async (): Promise<Snapshot<WorkItemCommentDTO[]>> => {
      const pages = await Promise.all(
        ids.map((id) =>
          listWorkItemComments(id, { kind: 'blocker', needsHuman: true, size: 50 }),
        ),
      );
      const comments = pages.flat();
      comments.sort((a, b) => (a.createdAt ?? '').localeCompare(b.createdAt ?? ''));
      return { data: comments, fetchedAt: Date.now() };
    },
    refetchInterval: interval(options),
    enabled: (options?.enabled ?? true) && ids.length > 0,
  });

  return {
    comments: query.data?.data ?? [],
    scannedItemIds: ids,
    isLoading: query.isLoading,
    isError: query.isError,
    error: query.error,
  };
}

export function useWhoami(
  options?: QueryOptions,
): { whoami: ActorWhoamiDTO | null; isLoading: boolean } {
  const query = useQuery({
    queryKey: ['actors', 'whoami'],
    queryFn: () => getWhoami(),
    refetchInterval: interval(options),
    enabled: options?.enabled ?? true,
  });
  return { whoami: query.data ?? null, isLoading: query.isLoading };
}

// ── Work item detail ─────────────────────────────────────────────────────────

export function useWorkItem(
  id: number,
  options?: QueryOptions,
): {
  item: WorkItemDTO | null;
  fetchedAt: number | null;
  isLoading: boolean;
  isError: boolean;
  error: unknown;
} {
  const query = useQuery({
    queryKey: ['work-item', id],
    queryFn: async (): Promise<Snapshot<WorkItemDTO>> => ({
      data: await getWorkItem(id),
      fetchedAt: Date.now(),
    }),
    refetchInterval: interval(options),
    enabled: (options?.enabled ?? true) && Number.isFinite(id),
  });
  return {
    item: query.data?.data ?? null,
    fetchedAt: query.data?.fetchedAt ?? null,
    isLoading: query.isLoading,
    isError: query.isError,
    error: query.error,
  };
}

/** Timeline, oldest first — the order the detail page renders it in. */
export function useWorkItemEvents(
  id: number,
  options?: QueryOptions,
): { events: WorkItemEventDTO[]; isLoading: boolean; isError: boolean; error: unknown } {
  const query = useQuery({
    queryKey: ['work-item-events', id],
    queryFn: () => listWorkItemEvents(id),
    select: sortEventsChronological,
    refetchInterval: interval(options),
    enabled: (options?.enabled ?? true) && Number.isFinite(id),
  });
  return {
    events: query.data ?? [],
    isLoading: query.isLoading,
    isError: query.isError,
    error: query.error,
  };
}

export function useWorkItemComments(
  id: number,
  options?: QueryOptions,
): { comments: WorkItemCommentDTO[]; isLoading: boolean; isError: boolean; error: unknown } {
  const query = useQuery({
    queryKey: ['work-item-comments', id],
    queryFn: () => listWorkItemComments(id),
    refetchInterval: interval(options),
    enabled: (options?.enabled ?? true) && Number.isFinite(id),
  });
  return {
    comments: query.data ?? [],
    isLoading: query.isLoading,
    isError: query.isError,
    error: query.error,
  };
}

/**
 * Reservations that belong to this work item. The reservations endpoint is
 * addressed by resource key, so the hook reads every resource's ledger and keeps
 * the rows whose `workItemId` is this item — a filter on a field the API sends,
 * not a guess about which resource "belongs" to the item.
 */
export function useItemReservations(
  workItemId: number,
  resources: ResourceDTO[],
  options?: QueryOptions,
): { reservations: ReservationDTO[]; isLoading: boolean } {
  const keys = resources.map((r) => r.key);
  const query = useQuery({
    queryKey: ['work-item-reservations', workItemId, keys],
    queryFn: async (): Promise<ReservationDTO[]> => {
      const ledgers = await Promise.all(keys.map((key) => listReservations(key)));
      return reservationsForItem(ledgers.flat(), workItemId);
    },
    refetchInterval: interval(options),
    enabled: (options?.enabled ?? true) && Number.isFinite(workItemId) && keys.length > 0,
  });
  return { reservations: query.data ?? [], isLoading: query.isLoading };
}
