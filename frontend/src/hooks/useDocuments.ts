/**
 * Custom React hook for managing documents within a project using TanStack Query.
 * Provides document fetching, creation, update, deletion and search with automatic cache management.
 */

import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import type { CreateDocumentDto, UpdateDocumentDto } from '../types/document';
import { documentApi, type DocumentListQuery } from '../services/documentApi';

/**
 * WIKI4AI-187: sort/filter are part of the cache key and are resolved by the
 * backend, not in the browser. The key stays prefixed with `['documents', slug]`
 * so the existing invalidations (pin, create, delete) keep matching every
 * parameterised variant of the list.
 */
const documentsQueryKey = (projectSlug: string, query: DocumentListQuery) =>
  ['documents', projectSlug, { sort: query.sort ?? '', q: query.q ?? '', size: query.size ?? 100 }] as const;
const DOCUMENTS_QUERY_PREFIX = (projectSlug: string) => ['documents', projectSlug] as const;
const SEARCH_DOCUMENTS_QUERY_KEY = (projectSlug: string, keyword: string) => ['documents', 'search', projectSlug, keyword] as const;

export function useDocuments(projectSlug: string, query: DocumentListQuery = {}) {
  const queryClient = useQueryClient();

  // Fetch all documents for a project using React Query.
  // `getByProjectPage` (not `getByProject`) because drag & drop has to know
  // whether the loaded window is the whole project or only its first 100 rows —
  // the backend clamps `size` at 100, and only the page envelope carries the
  // total. See `ProjectDetail` → `dndTruncated`.
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: documentsQueryKey(projectSlug, query),
    queryFn: () => documentApi.getByProjectPage(projectSlug, query),
    enabled: !!projectSlug,
  });

  // Create document mutation with cache invalidation
  const createMutation = useMutation({
    mutationFn: (data: CreateDocumentDto) => documentApi.create(projectSlug, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: DOCUMENTS_QUERY_PREFIX(projectSlug) });
    },
  });

  // Update document mutation with cache invalidation
  const updateMutation = useMutation({
    mutationFn: ({ docSlug, data }: { docSlug: string; data: UpdateDocumentDto }) =>
      documentApi.update(projectSlug, docSlug, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: DOCUMENTS_QUERY_PREFIX(projectSlug) });
    },
  });

  // Delete document mutation with cache invalidation
  const deleteMutation = useMutation({
    mutationFn: (docSlug: string) => documentApi.delete(projectSlug, docSlug),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: DOCUMENTS_QUERY_PREFIX(projectSlug) });
    },
  });

  // Upload document mutation with cache invalidation
  const uploadMutation = useMutation({
    mutationFn: (file: File) => documentApi.upload(projectSlug, file),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: DOCUMENTS_QUERY_PREFIX(projectSlug) });
    },
  });

  // Add link mutation with cache invalidation
  const addLinkMutation = useMutation({
    mutationFn: ({ docSlug, targetDocumentId }: { docSlug: string; targetDocumentId: number }) =>
      documentApi.addLink(projectSlug, docSlug, targetDocumentId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: DOCUMENTS_QUERY_PREFIX(projectSlug) });
    },
  });

  // Remove link mutation with cache invalidation
  const removeLinkMutation = useMutation({
    mutationFn: ({ docSlug, targetDocumentId }: { docSlug: string; targetDocumentId: number }) =>
      documentApi.removeLink(projectSlug, docSlug, targetDocumentId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: DOCUMENTS_QUERY_PREFIX(projectSlug) });
    },
  });

  return {
    documents: data?.items ?? [],
    /** Total in the project, not just the loaded page (WIKI4AI-187, DnD hint). */
    documentsTotal: data?.total ?? 0,
    isLoading,
    error,
    refetch,
    createDocument: createMutation.mutateAsync,
    updateDocument: updateMutation.mutateAsync,
    deleteDocument: deleteMutation.mutateAsync,
    uploadDocument: uploadMutation.mutateAsync,
    addLink: addLinkMutation.mutateAsync,
    removeLink: removeLinkMutation.mutateAsync,
    isCreating: createMutation.isPending,
    isUpdating: updateMutation.isPending,
    isDeleting: deleteMutation.isPending,
    isUploading: uploadMutation.isPending,
    isAddingLink: addLinkMutation.isPending,
    isRemovingLink: removeLinkMutation.isPending,
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
