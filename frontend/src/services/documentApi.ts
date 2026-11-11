/**
 * API service for Document operations using project slugs.
 * Uses authenticated apiClient for all requests (automatic JWT token + 401 retry).
 */

import type { Document, CreateDocumentDto, UpdateDocumentDto, EmbeddingStatus, GlobalSearchResult } from '../types/document';
import { apiGet, apiPost, apiPut, apiDelete, apiPostFormData } from './apiClient';
// WIKI4AI-187: sort/filter query parameters + the reorder body of the DnD list
import { buildListQuery } from '../utils/listSort';
import type { ReorderBody } from '../utils/dragOrder';

const API_BASE_URL = import.meta.env.VITE_API_URL || '/api/v1';

export interface DocumentContentResponse {
  title: string;
  content: string;
  wikiLinks: string[];
}

/** Query of `GET /projects/{slug}/documents` (WIKI4AI-184). */
export interface DocumentListQuery {
  sort?: string;
  q?: string;
  page?: number;
  /** backend default 50, clamped to 100 */
  size?: number;
}

/** One page of the document list plus the total, which the UI needs for DnD. */
export interface DocumentListPage {
  items: Document[];
  /** total number of documents in the project (ignoring the page window) */
  total: number;
}

export const documentApi = {
  /**
   * Get all documents in a project by slug.
   * Backend returns a Spring Data Page object with { content: Document[], ... }.
   * We extract the .content array for frontend consumption.
   *
   * WIKI4AI-187: `query` sends the sort/filter choice to the backend
   * (`sort`, `q`) instead of re-ordering the response client-side.
   */
  getByProject: async (projectSlug: string, query?: DocumentListQuery): Promise<Document[]> => {
    const data = await apiGet<any>(
      `${API_BASE_URL}/projects/${projectSlug}/documents${buildListQuery({ page: 0, size: 50, ...query })}`,
    );
    return Array.isArray(data) ? data : data.content || [];
  },

  /**
   * WIKI4AI-187: same request as `getByProject`, but keeps `totalElements`.
   *
   * Drag & drop needs to know whether the window it can reorder is the whole
   * list or only part of it: the backend clamps `size` to 100, so a project with
   * more documents can only be reordered inside the loaded page. Without the
   * total the UI could not say so (see `ProjectDetail`, `dndTruncated`).
   */
  getByProjectPage: async (projectSlug: string, query?: DocumentListQuery): Promise<DocumentListPage> => {
    const data = await apiGet<any>(
      `${API_BASE_URL}/projects/${projectSlug}/documents${buildListQuery({ page: 0, size: 100, ...query })}`,
    );
    if (Array.isArray(data)) return { items: data, total: data.length };
    return {
      items: data?.content ?? [],
      total: typeof data?.totalElements === 'number' ? data.totalElements : (data?.content?.length ?? 0),
    };
  },

  /**
   * Create a new document in a project.
   */
  create: (projectSlug: string, data: CreateDocumentDto): Promise<Document> =>
    apiPost(`${API_BASE_URL}/projects/${projectSlug}/documents`, data),

  /**
   * Get a single document by project slug and document slug.
   */
  get: (projectSlug: string, docSlug: string): Promise<Document> =>
    apiGet(`${API_BASE_URL}/projects/${projectSlug}/documents/${docSlug}`),

  /**
   * Get document content with rendered markdown and extracted wiki links.
   * Endpoint: GET /projects/:slug/documents/:docSlug/content
   */
  getContent: (projectSlug: string, docSlug: string): Promise<DocumentContentResponse> =>
    apiGet(`${API_BASE_URL}/projects/${projectSlug}/documents/${docSlug}/content`),

  /**
   * Update an existing document.
   */
  update: (projectSlug: string, docSlug: string, data: UpdateDocumentDto): Promise<Document> =>
    apiPut(`${API_BASE_URL}/projects/${projectSlug}/documents/${docSlug}`, data),

  /**
   * Delete a document.
   */
  delete: (projectSlug: string, docSlug: string): Promise<void> =>
    apiDelete(`${API_BASE_URL}/projects/${projectSlug}/documents/${docSlug}`),

  /**
   * Search documents in a project by keyword.
   * Endpoint: GET /projects/:slug/documents/search?keyword={keyword}
   */
  search: (projectSlug: string, keyword: string): Promise<Document[]> =>
    apiGet(`${API_BASE_URL}/projects/${projectSlug}/documents/search?keyword=${encodeURIComponent(keyword)}`),

  /**
   * Global (cross-project) hybrid document search (WIKI4AI-61).
   * Endpoint: GET /search/documents?keyword={keyword}&limit={limit}
   * Requires an authenticated session (JWT); anonymous requests get 401.
   */
  searchGlobal: (keyword: string, limit = 20): Promise<GlobalSearchResult[]> =>
    apiGet(`${API_BASE_URL}/search/documents?keyword=${encodeURIComponent(keyword)}&limit=${limit}`),

  /**
   * Upload a .md file as a new document.
   * Endpoint: POST /projects/:slug/documents/upload (multipart/form-data)
   */
  upload: (projectSlug: string, file: File): Promise<Document> => {
    const formData = new FormData();
    formData.append('file', file);
    return apiPostFormData(`${API_BASE_URL}/projects/${projectSlug}/documents/upload`, formData);
  },

  /**
   * Get backlinks - documents that link TO a specific document.
   * Endpoint: GET /projects/:slug/documents/:docSlug/backlinks
   */
  getBacklinks: (projectSlug: string, docSlug: string): Promise<Document[]> =>
    apiGet(`${API_BASE_URL}/projects/${projectSlug}/documents/${docSlug}/backlinks`),

  /**
   * Add a link from one document to another.
   * Endpoint: POST /projects/:slug/documents/:docSlug/links
   */
  addLink: (projectSlug: string, docSlug: string, targetDocumentId: number): Promise<Document> =>
    apiPost(`${API_BASE_URL}/projects/${projectSlug}/documents/${docSlug}/links`, {
      targetDocumentId,
    }),

  /**
   * Remove a link between two documents.
   * Endpoint: DELETE /projects/:slug/documents/:docSlug/links/:targetDocId
   */
  removeLink: (projectSlug: string, docSlug: string, targetDocumentId: number): Promise<void> =>
    apiDelete(`${API_BASE_URL}/projects/${projectSlug}/documents/${docSlug}/links/${targetDocumentId}`),

  /**
   * Get links - documents that a specific document links TO.
   * Endpoint: GET /projects/:slug/documents/:docSlug/links
   */
  getLinks: (projectSlug: string, docSlug: string): Promise<Document[]> =>
    apiGet(`${API_BASE_URL}/projects/${projectSlug}/documents/${docSlug}/links`),

  /**
   * Move a document to another project.
   * Endpoint: POST /projects/:slug/documents/:docSlug/move
   */
  moveDocument: (projectSlug: string, docSlug: string, targetProjectSlug: string): Promise<Document> =>
    apiPost(`${API_BASE_URL}/projects/${projectSlug}/documents/${docSlug}/move`, {
      targetProjectSlug,
    }),

  /**
   * Copy a document to another project (or within the same project).
   * Endpoint: POST /projects/:slug/documents/:docSlug/copy
   */
  copyDocument: (projectSlug: string, docSlug: string, targetProjectSlug?: string): Promise<Document> => {
    const body = targetProjectSlug ? { targetProjectSlug } : {};
    return apiPost(`${API_BASE_URL}/projects/${projectSlug}/documents/${docSlug}/copy`, body);
  },

  /**
   * Embedding sidecar availability (WIKI4AI-35/36).
   * Endpoint: GET /embeddings/status
   */
  getEmbeddingStatus: (): Promise<EmbeddingStatus> =>
    apiGet(`${API_BASE_URL}/embeddings/status`),

  /**
   * WIKI4AI-186: pin a document to the top of its project's list.
   * Endpoint: POST /projects/:slug/documents/:docSlug/pin — requires UPDATE in
   * the project (403 otherwise). Returns the updated document with
   * `pinned`/`sortOrder`; neither `version` nor `updatedAt` changes, so an
   * in-progress edit is not pushed into a 409 by a pin.
   */
  pinDocument: (projectSlug: string, docSlug: string): Promise<Document> =>
    apiPost(`${API_BASE_URL}/projects/${projectSlug}/documents/${docSlug}/pin`),

  /**
   * WIKI4AI-186: unpin a document. The manual `sortOrder` is kept, so the
   * document returns to exactly the position it had before pinning.
   * Endpoint: POST /projects/:slug/documents/:docSlug/unpin — requires UPDATE.
   */
  unpinDocument: (projectSlug: string, docSlug: string): Promise<Document> =>
    apiPost(`${API_BASE_URL}/projects/${projectSlug}/documents/${docSlug}/unpin`),

  /**
   * WIKI4AI-187: move a document inside the manual order of its project
   * (drag & drop). Endpoint: POST /projects/:slug/documents/:docSlug/reorder —
   * requires UPDATE in the project (403 otherwise).
   *
   * Exactly one of `beforeSlug` / `afterSlug` / `position` (WIKI4AI-184). The
   * document `version` is NOT bumped, so reordering never pushes a concurrent
   * edit into a 409.
   */
  reorderDocument: (projectSlug: string, docSlug: string, body: ReorderBody): Promise<Document> =>
    apiPost(`${API_BASE_URL}/projects/${projectSlug}/documents/${docSlug}/reorder`, body),
};
