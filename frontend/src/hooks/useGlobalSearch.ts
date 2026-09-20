/**
 * Custom React hook for the global (cross-project) hybrid document search (WIKI4AI-61).
 * Returns results with project attribution and loading state based on the keyword.
 * The query only fires once the trimmed keyword reaches 2 characters — the backend
 * rejects shorter keywords with 400.
 */

import { useQuery } from '@tanstack/react-query';
import { documentApi } from '../services/documentApi';

const GLOBAL_SEARCH_QUERY_KEY = (keyword: string) => ['search', 'global', keyword] as const;

export function useGlobalSearch(keyword: string, limit = 20) {
  const trimmedKeyword = keyword.trim();

  const { data, isLoading, isFetching } = useQuery({
    queryKey: GLOBAL_SEARCH_QUERY_KEY(trimmedKeyword),
    queryFn: () => documentApi.searchGlobal(trimmedKeyword, limit),
    enabled: trimmedKeyword.length >= 2,
  });

  return {
    results: data ?? [],
    isLoading: isLoading || isFetching,
    hasSearched: trimmedKeyword.length >= 2,
  };
}
