/**
 * TypeScript type definitions for Project entity.
 */

export interface Project {
  id: number;
  name: string;
  description: string | null;
  slug: string;
  createdAt: string;
  updatedAt: string;
}

export interface ProjectDTO {
  id?: number;
  name: string;
  description?: string;
  slug?: string;
  createdAt?: string;
  updatedAt?: string;
}
