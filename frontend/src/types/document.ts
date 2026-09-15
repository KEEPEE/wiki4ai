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
