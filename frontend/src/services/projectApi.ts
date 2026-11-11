/**
 * API service for Project operations.
 * Uses authenticated apiClient for all requests (automatic JWT token + 401 retry).
 */

import type { Project, ProjectDTO, ProjectTreeNode } from '../types/project';
import { apiGet, apiPost, apiPut, apiDelete, apiGetBlob } from './apiClient';
// WIKI4AI-187: sort/filter query parameters + the reorder body of the DnD lists
import { buildListQuery } from '../utils/listSort';
import type { ReorderBody } from '../utils/dragOrder';

const API_BASE_URL = import.meta.env.VITE_API_URL || '/api/v1';

/** Query of `GET /projects` (WIKI4AI-184). Every part is optional. */
export interface ProjectListQuery {
  /** `manual | name_asc | name_desc | created_asc | created_desc | updated_desc` */
  sort?: string;
  /** case-insensitive match on name + description */
  q?: string;
  /** `public | private` */
  visibility?: string;
}

class ProjectApiService {
  /**
   * Get all projects.
   *
   * WIKI4AI-187: ordering and filtering are the backend's job (`sort`, `q`,
   * `visibility`) — the UI sends the user's choice instead of re-sorting the
   * response. With no argument the request is exactly `/projects`, i.e. the
   * backend default order (`created_desc`, pinned first).
   */
  async getAllProjects(query?: ProjectListQuery): Promise<Project[]> {
    return apiGet(`${API_BASE_URL}/projects${buildListQuery(query ?? {})}`);
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

  /**
   * WIKI4AI-186: pin a project to the top of the lists.
   * Endpoint: POST /projects/{slug}/pin — requires MANAGE (403 otherwise).
   * Returns the updated project with `pinned`/`sortOrder`; the backend does NOT
   * bump `updatedAt`, so take the new state from this response, not from a refetch
   * of the version.
   */
  async pinProject(slug: string): Promise<Project> {
    return apiPost(`${API_BASE_URL}/projects/${slug}/pin`);
  }

  /**
   * WIKI4AI-186: unpin a project. Its manual `sortOrder` is preserved by the
   * backend, so the project lands back exactly where it was before pinning.
   * Endpoint: POST /projects/{slug}/unpin — requires MANAGE.
   */
  async unpinProject(slug: string): Promise<Project> {
    return apiPost(`${API_BASE_URL}/projects/${slug}/unpin`);
  }

  /**
   * WIKI4AI-187: move a project inside the manual order (drag & drop).
   * Endpoint: POST /projects/{slug}/reorder — requires MANAGE (403 otherwise).
   *
   * The body carries exactly one of `beforeSlug` / `afterSlug` / `position`
   * (WIKI4AI-184); a drag sends the neighbour it landed next to, so the server
   * resolves the slot against the order that exists at drop time rather than
   * against an index the UI read earlier. `updatedAt` is not bumped and the
   * whole list is renumbered densely.
   */
  async reorderProject(slug: string, body: ReorderBody): Promise<Project> {
    return apiPost(`${API_BASE_URL}/projects/${slug}/reorder`, body);
  }
}

export const projectApi = new ProjectApiService();
