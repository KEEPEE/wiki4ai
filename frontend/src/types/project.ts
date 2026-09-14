/**
 * TypeScript type definitions for Project entity.
 */

export interface Project {
  id: number;
  name: string;
  description: string | null;
  slug: string;
  documentCount: number;
  createdAt: string;
  updatedAt: string;
  /** Slug of the parent project, or null/undefined for root projects (WIKI4AI-29). */
  parentSlug?: string | null;
  /** Hierarchy depth: 1 = root, max 5 (WIKI4AI-29). */
  depth?: number;
}

export interface ProjectDTO {
  id?: number;
  name: string;
  description?: string;
  slug?: string;
  documentCount?: number;
  createdAt?: string;
  updatedAt?: string;
  /**
   * Optional parent project id (WIKI4AI-29/30).
   * - omitted (undefined) on update: no move (backward compatible)
   * - explicit null on update: move back to root
   * - number: create under / move to that parent
   */
  parentId?: number | null;
}

/**
 * Node of the nested subproject tree returned by GET /projects/{slug}/tree (WIKI4AI-30).
 */
export interface ProjectTreeNode {
  id: number;
  name: string;
  slug: string;
  /** Slug of the parent node, null for the tree root. */
  parentSlug: string | null;
  depth: number;
  hasChildren: boolean;
  documentCount: number;
  children: ProjectTreeNode[];
}
