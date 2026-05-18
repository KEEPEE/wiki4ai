/**
 * Custom React hook for managing documents within a project using TanStack Query.
 * Provides document fetching, creation, update, deletion and search with automatic cache management.
 */

import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import type { CreateDocumentDto, UpdateDocumentDto } from '../types/document';
import { documentApi } from '../services/documentApi';

const DOCUMENTS_QUERY_KEY = (projectSlug: string) => ['documents', projectSlug] as const;
const SEARCH_DOCUMENTS_QUERY_KEY = (projectSlug: string, keyword: string) => ['documents', 'search', projectSlug, keyword] as const;

export function useDocuments(projectSlug: string) {
  const queryClient = useQueryClient();

  // Fetch all documents for a project using React Query
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: DOCUMENTS_QUERY_KEY(projectSlug),
    queryFn: () => documentApi.getByProject(projectSlug),
    enabled: !!projectSlug,
  });

  // Create document mutation with cache invalidation
  const createMutation = useMutation({
    mutationFn: (data: CreateDocumentDto) => documentApi.create(projectSlug, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: DOCUMENTS_QUERY_KEY(projectSlug) });
    },
  });

  // Update document mutation with cache invalidation
  const updateMutation = useMutation({
    mutationFn: ({ docSlug, data }: { docSlug: string; data: UpdateDocumentDto }) =>
      documentApi.update(projectSlug, docSlug, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: DOCUMENTS_QUERY_KEY(projectSlug) });
    },
  });

  // Delete document mutation with cache invalidation
  const deleteMutation = useMutation({
    mutationFn: (docSlug: string) => documentApi.delete(projectSlug, docSlug),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: DOCUMENTS_QUERY_KEY(projectSlug) });
    },
  });

  return {
    documents: data ?? [],
    isLoading,
    error,
    refetch,
    createDocument: createMutation.mutateAsync,
    updateDocument: updateMutation.mutateAsync,
    deleteDocument: deleteMutation.mutateAsync,
    isCreating: createMutation.isPending,
    isUpdating: updateMutation.isPending,
    isDeleting: deleteMutation.isPending,
  };
}

/**
 * Custom React hook for searching documents within a project.
 * Returns search results and loading state based on the provided keyword.
 */
export function useSearchDocuments(projectSlug: string, keyword: string) {
  const trimmedKeyword = keyword.trim();

  const { data, isLoading, isFetching } = useQuery({
    queryKey: SEARCH_DOCUMENTS_QUERY_KEY(projectSlug, trimmedKeyword),
    queryFn: () => documentApi.search(projectSlug, trimmedKeyword),
    enabled: !!projectSlug && trimmedKeyword.length > 0,
  });

  return {
    searchResults: data ?? [],
    isLoading: isLoading || isFetching,
    hasSearched: trimmedKeyword.length > 0,
  };
}
