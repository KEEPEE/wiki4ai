/**
 * WIKI4AI-163: react-query hooks of the agents page.
 *
 * Same live-data rule as `useBoard.ts` (WIKI4AI-175): `refetchInterval: 10_000`, no
 * WebSocket, no SSE, and no timer owned by any component. The actor rows come from
 * `useActors()` in `useBoard.ts` — this file does not fetch `/api/v1/actors` under a
 * second name — and the lease-expiry count comes from `useActorMetrics()` in
 * `useMetrics.ts`, because `ActorMetricsDTO.leaseExpiredCount` is the API's number.
 *
 * What lives here: the token listing of the caller, the project role of each actor,
 * and the three token mutations the page exposes (rotate one, revoke one, revoke all
 * of an agent).
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { TokenInfo } from '../services/tokenApi';
import {
  listActorTokens,
  listProjectActors,
  revokeActorToken,
  revokeAllActorTokens,
  rotateActorToken,
  toTokenInfo,
  type ProjectActorDTO,
  type TokenCreated,
} from '../services/agentsApi';
import { BOARD_REFETCH_INTERVAL_MS, type QueryOptions } from './useBoard';

function interval(options: QueryOptions | undefined): number | false {
  const value = options?.refetchIntervalMs ?? BOARD_REFETCH_INTERVAL_MS;
  return value > 0 ? value : false;
}

/** Query key of the token listing: one key, so a mutation invalidates exactly one cache. */
export const TOKENS_QUERY_KEY = ['tokens'];

/**
 * `GET /api/v1/tokens` — every token of the caller, allow-listed to metadata with a
 * display `prefix`. The cache therefore cannot hold a secret: the plaintext of a
 * rotated token is returned by the mutation and kept in component state only, and it
 * is dropped when the page unmounts (WIKI4AI-168).
 */
export function useActorTokens(
  options?: QueryOptions,
): { tokens: TokenInfo[]; isLoading: boolean; isError: boolean; error: unknown } {
  const query = useQuery({
    queryKey: TOKENS_QUERY_KEY,
    queryFn: () => listActorTokens(),
    refetchInterval: interval(options),
    enabled: options?.enabled ?? true,
  });
  return {
    tokens: query.data ?? [],
    isLoading: query.isLoading,
    isError: query.isError,
    error: query.error,
  };
}

/**
 * `GET /api/v1/projects/{projectId}/actors` — the project role of each actor.
 * Disabled without a project id. An error here (403, or 404 for a private project)
 * means the page renders "role unknown"; it never falls back to a default role.
 */
export function useProjectActors(
  projectId: number | null,
  options?: QueryOptions,
): {
  projectActors: ProjectActorDTO[];
  isLoading: boolean;
  isError: boolean;
  error: unknown;
} {
  const query = useQuery({
    queryKey: ['project-actors', projectId],
    queryFn: () => listProjectActors(projectId as number),
    refetchInterval: interval(options),
    enabled: (options?.enabled ?? true) && projectId !== null && Number.isFinite(projectId),
  });
  return {
    projectActors: query.data ?? [],
    isLoading: query.isLoading,
    isError: query.isError,
    error: query.error,
  };
}

// ── Mutations ────────────────────────────────────────────────────────────────

/**
 * `POST /api/v1/tokens/{id}/rotate` — revokes the old row and issues its replacement
 * in one transaction. The response carries the new plaintext exactly once; `onSuccess`
 * invalidates the listing so the row comes back with its new prefix, while the
 * plaintext is handed to the caller and put in no cache.
 */
export function useRotateTokenMutation(): {
  /** Resolves to the allow-listed pair: the one-time plaintext and the new metadata. */
  rotate: (id: number) => Promise<{ token: string; metadata: TokenInfo }>;
  isPending: boolean;
  error: unknown;
} {
  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: (id: number) => rotateActorToken(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: TOKENS_QUERY_KEY });
    },
  });

  return {
    rotate: async (id: number) => {
      const created: TokenCreated = await mutation.mutateAsync(id);
      // `created.token` is read exactly here and handed to the caller; the metadata
      // goes through the allow-list so nothing unexpected rides along.
      return { token: created.token, metadata: toTokenInfo(created.metadata) };
    },
    isPending: mutation.isPending,
    error: mutation.error,
  };
}

/** `DELETE /api/v1/tokens/{id}` — soft revoke of one token (204). */
export function useRevokeTokenMutation(): {
  revoke: (id: number) => Promise<void>;
  isPending: boolean;
  error: unknown;
} {
  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: (id: number) => revokeActorToken(id),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: TOKENS_QUERY_KEY });
    },
  });
  return {
    revoke: (id: number) => mutation.mutateAsync(id),
    isPending: mutation.isPending,
    error: mutation.error,
  };
}

/**
 * `DELETE /api/v1/actors/{id}/tokens` — kill switch for one agent. Idempotent: a
 * second call is 204 again and changes zero rows, so a repeated click is a no-op.
 */
export function useRevokeAllActorTokensMutation(): {
  revokeAll: (actorId: number) => Promise<void>;
  isPending: boolean;
  error: unknown;
} {
  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: (actorId: number) => revokeAllActorTokens(actorId),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: TOKENS_QUERY_KEY });
    },
  });
  return {
    revokeAll: (actorId: number) => mutation.mutateAsync(actorId),
    isPending: mutation.isPending,
    error: mutation.error,
  };
}
