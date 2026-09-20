/**
 * TypeScript type definitions for Document entity.
 */

export interface Document {
  id: number;
  title: string;
  slug?: string;
  content: string | null;
  projectId: number;
  linkedDocuments?: number[];
  createdAt: string;
  updatedAt: string;
  /**
   * Hybrid-search relevance score (RRF, WIKI4AI-35). Present only on search
   * results — higher is more relevant.
   */
  score?: number;
}

/**
 * Single hit of the global (cross-project) hybrid document search (WIKI4AI-61).
 * Unlike `Document`, carries an `excerpt` (~200 chars around the first keyword
 * occurrence) instead of full content, plus project attribution.
 */
export interface GlobalSearchResult {
  id: number;
  title: string;
  slug?: string;
  projectId: number;
  /** URL-friendly slug of the owning project */
  projectSlug: string;
  /** Display name of the owning project */
  projectName: string;
  /** Hybrid-search relevance score (RRF) — higher is more relevant */
  score?: number;
  updatedAt: string;
  /** Context snippet of ~200 characters around the first keyword occurrence */
  excerpt?: string | null;
}

/**
 * Embedding sidecar availability (GET /api/v1/embeddings/status, WIKI4AI-35).
 * When `available` is false, search degrades to text-only matching.
 */
export interface EmbeddingStatus {
  available: boolean;
  model: string;
  dim: number;
}

export interface CreateDocumentDto {
  title: string;
  content?: string;
}

export interface UpdateDocumentDto {
  title?: string;
  content?: string;
}

export interface LinkCreateDto {
  targetDocumentId: number;
}
