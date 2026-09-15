/**
 * Custom React hook for the embedding sidecar availability status (WIKI4AI-36).
 * The WebUI uses this to show a "semantic search unavailable" hint when the
 * sidecar is down — text-only search still works in that case.
 */

import { useQuery } from '@tanstack/react-query';
import { documentApi } from '../services/documentApi';

const EMBEDDING_STATUS_QUERY_KEY = ['embeddings', 'status'] as const;

export function useEmbeddingStatus() {
  return useQuery({
    queryKey: EMBEDDING_STATUS_QUERY_KEY,
    queryFn: () => documentApi.getEmbeddingStatus(),
    // Re-check periodically so the banner disappears once the sidecar recovers.
    refetchInterval: 30_000,
    staleTime: 15_000,
    retry: false,
  });
}
