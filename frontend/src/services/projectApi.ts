/**
 * API service for Project operations.
 * Uses authenticated apiClient for all requests (automatic JWT token + 401 retry).
 */

import type { Project, ProjectDTO, ProjectTreeNode } from '../types/project';
import { apiGet, apiPost, apiPut, apiDelete, apiGetBlob } from './apiClient';

const API_BASE_URL = import.meta.env.VITE_API_URL || '/api/v1';

class ProjectApiService {
  /**
   * Get all projects.
   */
  async getAllProjects(): Promise<Project[]> {
    return apiGet(`${API_BASE_URL}/projects`);
  }

  /**
   * Get a single project by ID.
   */
  async getProjectById(id: number): Promise<Project> {
    return apiGet(`${API_BASE_URL}/projects/by-id/${id}`);
  }

  /**
   * Get a single project by slug.
   */
  async getProjectBySlug(slug: string): Promise<Project> {
    return apiGet(`${API_BASE_URL}/projects/${slug}`);
  }

  /**
   * Get the nested subproject tree rooted at the given project (WIKI4AI-30).
   */
  async getTree(slug: string): Promise<ProjectTreeNode> {
    return apiGet(`${API_BASE_URL}/projects/${slug}/tree`);
  }

  /**
   * Create a new project.
   */
  async createProject(dto: ProjectDTO): Promise<Project> {
    return apiPost(`${API_BASE_URL}/projects`, dto);
  }

  /**
   * Update an existing project.
   */
  async updateProject(id: number, dto: ProjectDTO): Promise<Project> {
    return apiPut(`${API_BASE_URL}/projects/by-id/${id}`, dto);
  }

  /**
   * Delete a project.
   */
  async deleteProject(id: number): Promise<void> {
    await apiDelete(`${API_BASE_URL}/projects/by-id/${id}`);
  }

  /**
   * Export a project as a ZIP archive.
   */
  async exportProject(slug: string): Promise<Blob> {
    return apiGetBlob(`${API_BASE_URL}/projects/${slug}/export`);
  }
}

export const projectApi = new ProjectApiService();
