/**
 * WIKI4AI-164: capacity ledger of `/resources`.
 *
 * `GET /resources` gives capacity and live slots; the holdings themselves are per
 * resource (`GET /resources/{key}/reservations`), so one query reads the resources and
 * then each resource's ledger. It resolves into ONE `fetchedAt`, which is the single
 * reference point every TTL countdown on the page is measured against — that is why
 * the countdown advances on a refetch and not on a timer (WIKI4AI-175).
 *
 * Release is `POST /api/v1/reservations/{id}/release` and is idempotent: the backend
 * answers 204 again and decrements `active_slots` exactly once. The mutation records
 * which ids this browser has seen accepted with a 204, so a second click is a no-op
 * instead of a red error — the state shown is still the API's (`releasedAt`), the
 * local record only says "the server already accepted this".
 */

import { useRef } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  listResources,
  listReservations,
  releaseReservation,
  type ReservationDTO,
  type ResourceDTO,
} from '../services/boardApi';
import { BOARD_REFETCH_INTERVAL_MS, type QueryOptions } from './useBoard';

function interval(options: QueryOptions | undefined): number | false {
  const value = options?.refetchIntervalMs ?? BOARD_REFETCH_INTERVAL_MS;
  return value > 0 ? value : false;
}

/** Resources plus their ledgers, fetched in one pass so one `fetchedAt` rules them all. */
export interface CapacityLedger {
  resources: ResourceDTO[];
  /** resource key -> its reservation rows, exactly as the ledger endpoint returned them. */
  reservations: Record<string, ReservationDTO[]>;
  /** The moment this whole snapshot was fetched — the reference point of every TTL. */
  fetchedAt: number;
}

export function useCapacityLedger(
  options?: QueryOptions,
): {
  ledger: CapacityLedger | null;
  isLoading: boolean;
  isError: boolean;
  error: unknown;
  refetch: () => void;
} {
  const query = useQuery({
    queryKey: ['capacity-ledger'],
    queryFn: async (): Promise<CapacityLedger> => {
      const resources = await listResources();
      const entries = await Promise.all(
        resources.map(async (resource) => [
          resource.key,
          await listReservations(resource.key),
        ] as const),
      );
      return {
        resources,
        reservations: Object.fromEntries(entries),
        fetchedAt: Date.now(),
      };
    },
    refetchInterval: interval(options),
    enabled: options?.enabled ?? true,
  });

  return {
    ledger: query.data ?? null,
    isLoading: query.isLoading,
    isError: query.isError,
    error: query.error,
    refetch: () => {
      void query.refetch();
    },
  };
}

/**
 * Manual release. `onSuccess` records the id (a 204 the browser actually received) and
 * invalidates the ledger so the authoritative `releasedAt` arrives on the next read.
 */
export function useReleaseReservationMutation(): {
  release: (reservationId: number) => Promise<void>;
  releasedIds: Set<number>;
  isPending: boolean;
  error: unknown;
  resetError: () => void;
} {
  const queryClient = useQueryClient();
  // Ids whose release this browser has already seen accepted with a 204. A ref, so the
  // record survives re-renders (and is gone on remount, when the ledger is re-read).
  const releasedRef = useRef<Set<number>>(new Set());
  const mutation = useMutation({
    mutationFn: (reservationId: number) => releaseReservation(reservationId),
    onSuccess: (_data, reservationId) => {
      releasedRef.current.add(reservationId);
      void queryClient.invalidateQueries({ queryKey: ['capacity-ledger'] });
      void queryClient.invalidateQueries({ queryKey: ['resources'] });
    },
  });

  return {
    release: (reservationId: number) => mutation.mutateAsync(reservationId),
    releasedIds: releasedRef.current,
    isPending: mutation.isPending,
    error: mutation.error,
    resetError: mutation.reset,
  };
}
