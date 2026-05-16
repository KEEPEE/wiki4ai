/**
 * Custom React hook for managing projects.
 * Provides project fetching, creation, update, and deletion functionality.
 */

import { useState, useEffect, useCallback } from 'react';
import type { Project, ProjectDTO } from '../types/project';
import { projectApi } from '../services/projectApi';

interface UseProjectsReturn {
  projects: Project[];
  loading: boolean;
  error: string | null;
  fetchProjects: () => Promise<void>;
  createProject: (dto: ProjectDTO) => Promise<Project>;
  updateProject: (id: number, dto: ProjectDTO) => Promise<Project>;
  deleteProject: (id: number) => Promise<void>;
}

export function useProjects(): UseProjectsReturn {
  const [projects, setProjects] = useState<Project[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchProjects = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await projectApi.getAllProjects();
      setProjects(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to fetch projects');
    } finally {
      setLoading(false);
    }
  }, []);

  const createProject = useCallback(async (dto: ProjectDTO): Promise<Project> => {
    const newProject = await projectApi.createProject(dto);
    setProjects(prev => [newProject, ...prev]);
    return newProject;
  }, []);

  const updateProject = useCallback(async (id: number, dto: ProjectDTO): Promise<Project> => {
    const updated = await projectApi.updateProject(id, dto);
    setProjects(prev => prev.map(p => (p.id === id ? updated : p)));
    return updated;
  }, []);

  const deleteProject = useCallback(async (id: number): Promise<void> => {
    await projectApi.deleteProject(id);
    setProjects(prev => prev.filter(p => p.id !== id));
  }, []);

  // Auto-fetch on mount
  useEffect(() => {
    fetchProjects();
  }, [fetchProjects]);

  return { projects, loading, error, fetchProjects, createProject, updateProject, deleteProject };
}
