/**
 * Custom React hook for managing projects using TanStack Query.
 * Provides project fetching, creation, update, and deletion with automatic cache management.
 */

import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import type { ProjectDTO } from '../types/project';
import { projectApi, type ProjectListQuery } from '../services/projectApi';

/**
 * WIKI4AI-187: the sort/filter choice is part of the cache key, so switching to
 * `?sort=manual` or typing a filter fetches a new list instead of re-ordering
 * the old one client-side. TanStack hashes the key structurally, so a fresh
 * object literal per render is fine.
 *
 * Every mutation still invalidates `['projects']`, which prefix-matches all
 * parameterised keys — a create/delete refreshes whatever list is on screen.
 */
const projectsQueryKey = (query: ProjectListQuery) =>
  ['projects', { sort: query.sort ?? '', q: query.q ?? '', visibility: query.visibility ?? '' }] as const;

export function useProjects(query: ProjectListQuery = {}) {
  const queryClient = useQueryClient();

  // Fetch all projects using React Query
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: projectsQueryKey(query),
    queryFn: () => projectApi.getAllProjects(query),
  });

  // Create project mutation with cache invalidation
  const createMutation = useMutation({
    mutationFn: (dto: ProjectDTO) => projectApi.createProject(dto),
    onSuccess: () => {
      // Prefix key: invalidates every parameterised list (`['projects', {...}]`).
      queryClient.invalidateQueries({ queryKey: ['projects'] });
    },
  });

  // Update project mutation with cache invalidation
  const updateMutation = useMutation({
    mutationFn: ({ id, dto }: { id: number; dto: ProjectDTO }) =>
      projectApi.updateProject(id, dto),
    onSuccess: () => {
      // Prefix key: invalidates every parameterised list (`['projects', {...}]`).
      queryClient.invalidateQueries({ queryKey: ['projects'] });
    },
  });

  // Delete project mutation with cache invalidation
  const deleteMutation = useMutation({
    mutationFn: (id: number) => projectApi.deleteProject(id),
    onSuccess: () => {
      // Prefix key: invalidates every parameterised list (`['projects', {...}]`).
      queryClient.invalidateQueries({ queryKey: ['projects'] });
    },
  });

  return {
    projects: data ?? [],
    isLoading,
    error,
    refetch,
    createProject: createMutation.mutateAsync,
    updateProject: updateMutation.mutateAsync,
    deleteProject: deleteMutation.mutateAsync,
    isCreating: createMutation.isPending,
    isUpdating: updateMutation.isPending,
    isDeleting: deleteMutation.isPending,
  };
}
