/**
 * Custom React hook for managing projects using TanStack Query.
 * Provides project fetching, creation, update, and deletion with automatic cache management.
 */

import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import type { ProjectDTO } from '../types/project';
import { projectApi } from '../services/projectApi';

const PROJECTS_QUERY_KEY = ['projects'] as const;

export function useProjects() {
  const queryClient = useQueryClient();

  // Fetch all projects using React Query
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: PROJECTS_QUERY_KEY,
    queryFn: () => projectApi.getAllProjects(),
  });

  // Create project mutation with cache invalidation
  const createMutation = useMutation({
    mutationFn: (dto: ProjectDTO) => projectApi.createProject(dto),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: PROJECTS_QUERY_KEY });
    },
  });

  // Update project mutation with cache invalidation
  const updateMutation = useMutation({
    mutationFn: ({ id, dto }: { id: number; dto: ProjectDTO }) =>
      projectApi.updateProject(id, dto),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: PROJECTS_QUERY_KEY });
    },
  });

  // Delete project mutation with cache invalidation
  const deleteMutation = useMutation({
    mutationFn: (id: number) => projectApi.deleteProject(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: PROJECTS_QUERY_KEY });
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
