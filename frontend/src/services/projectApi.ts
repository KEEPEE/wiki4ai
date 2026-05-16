/**
 * API service for Project operations.
 * Handles all HTTP requests to the backend project endpoints.
 */

import type { Project, ProjectDTO } from '../types/project';

const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080/api/v1';

class ProjectApiService {
  /**
   * Get all projects.
   */
  async getAllProjects(): Promise<Project[]> {
    const response = await fetch(`${API_BASE_URL}/projects`);
    if (!response.ok) {
      throw new Error(`Failed to fetch projects: ${response.statusText}`);
    }
    return response.json();
  }

  /**
   * Get a single project by ID.
   */
  async getProjectById(id: number): Promise<Project> {
    const response = await fetch(`${API_BASE_URL}/projects/${id}`);
    if (!response.ok) {
      throw new Error(`Failed to fetch project ${id}: ${response.statusText}`);
    }
    return response.json();
  }

  /**
   * Get a single project by slug.
   */
  async getProjectBySlug(slug: string): Promise<Project> {
    const response = await fetch(`${API_BASE_URL}/projects/slug/${slug}`);
    if (!response.ok) {
      throw new Error(`Failed to fetch project ${slug}: ${response.statusText}`);
    }
    return response.json();
  }

  /**
   * Create a new project.
   */
  async createProject(dto: ProjectDTO): Promise<Project> {
    const response = await fetch(`${API_BASE_URL}/projects`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(dto),
    });
    if (!response.ok) {
      throw new Error(`Failed to create project: ${response.statusText}`);
    }
    return response.json();
  }

  /**
   * Update an existing project.
   */
  async updateProject(id: number, dto: ProjectDTO): Promise<Project> {
    const response = await fetch(`${API_BASE_URL}/projects/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(dto),
    });
    if (!response.ok) {
      throw new Error(`Failed to update project ${id}: ${response.statusText}`);
    }
    return response.json();
  }

  /**
   * Delete a project.
   */
  async deleteProject(id: number): Promise<void> {
    const response = await fetch(`${API_BASE_URL}/projects/${id}`, {
      method: 'DELETE',
    });
    if (!response.ok) {
      throw new Error(`Failed to delete project ${id}: ${response.statusText}`);
    }
  }
}

export const projectApi = new ProjectApiService();
