/**
 * WIKI4AI-164: `GET /api/v1/metrics/actors?from=&to=` (WIKI4AI-147).
 *
 * The hook is a pass-through. It sends the window boundaries the user typed and hands
 * the normalized response to the page; it computes no sum, no average and no share.
 * `summary`, `totalStatusSeconds` and `averageCycleSeconds` are the backend's numbers
 * (derived in `ActorMetricsService` from `work_item_events` alone) and the page
 * renders them as they are.
 *
 * Polling follows the same rule as the board (`refetchInterval: 10_000`, WIKI4AI-175):
 * no `setInterval`, no component-owned timer.
 */

import { useQuery } from '@tanstack/react-query';
import {
  getActorMetrics,
  type ActorMetricsResponseDTO,
  type MetricsWindow,
} from '../services/agentsApi';
import { BOARD_REFETCH_INTERVAL_MS, type QueryOptions } from './useBoard';

function interval(options: QueryOptions | undefined): number | false {
  const value = options?.refetchIntervalMs ?? BOARD_REFETCH_INTERVAL_MS;
  return value > 0 ? value : false;
}

export interface ActorMetricsState {
  metrics: ActorMetricsResponseDTO | null;
  isLoading: boolean;
  isError: boolean;
  error: unknown;
}

export function useActorMetrics(
  window: MetricsWindow = {},
  options?: QueryOptions,
): ActorMetricsState {
  const query = useQuery({
    // The window is part of the key: a metric belongs to a window, and a cache entry
    // must not answer a different window with the same numbers.
    queryKey: ['actor-metrics', window.from ?? null, window.to ?? null],
    queryFn: () => getActorMetrics(window),
    refetchInterval: interval(options),
    enabled: options?.enabled ?? true,
  });

  return {
    metrics: query.data ?? null,
    isLoading: query.isLoading,
    isError: query.isError,
    error: query.error,
  };
}
