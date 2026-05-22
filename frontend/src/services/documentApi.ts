/**
 * API service for Document operations using project slugs.
 * Uses authenticated apiClient for all requests (automatic JWT token + 401 retry).
 */

import type { Document, CreateDocumentDto, UpdateDocumentDto } from '../types/document';
import { apiGet, apiPost, apiPut, apiDelete, apiPostFormData } from './apiClient';

const API_BASE_URL = import.meta.env.VITE_API_URL || '/api/v1';

export interface DocumentContentResponse {
  title: string;
  content: string;
  wikiLinks: string[];
}

export const documentApi = {
  /**
   * Get all documents in a project by slug.
   * Backend returns a Spring Data Page object with { content: Document[], ... }.
   * We extract the .content array for frontend consumption.
   */
  getByProject: async (projectSlug: string): Promise<Document[]> => {
    const data = await apiGet<any>(`${API_BASE_URL}/projects/${projectSlug}/documents?page=0&size=50`);
    return Array.isArray(data) ? data : data.content || [];
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
};
