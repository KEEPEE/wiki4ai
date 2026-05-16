/**
 * API service for Document operations using project slugs.
 * Handles all HTTP requests to the backend document endpoints.
 */

import type { Document, CreateDocumentDto, UpdateDocumentDto } from '../types/document';

const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080/api/v1';

export interface DocumentContentResponse {
  title: string;
  content: string;
  wikiLinks: string[];
}

export const documentApi = {
  /**
   * Get all documents in a project by slug.
   */
  getByProject: (projectSlug: string): Promise<Document[]> =>
    fetch(`${API_BASE_URL}/projects/${projectSlug}/documents`).then((res) => {
      if (!res.ok) throw new Error(`Failed to fetch documents for project ${projectSlug}: ${res.statusText}`);
      return res.json();
    }),

  /**
   * Create a new document in a project.
   */
  create: (projectSlug: string, data: CreateDocumentDto): Promise<Document> =>
    fetch(`${API_BASE_URL}/projects/${projectSlug}/documents`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    }).then((res) => {
      if (!res.ok) throw new Error(`Failed to create document: ${res.statusText}`);
      return res.json();
    }),

  /**
   * Get a single document by project slug and document slug.
   */
  get: (projectSlug: string, docSlug: string): Promise<Document> =>
    fetch(`${API_BASE_URL}/projects/${projectSlug}/documents/${docSlug}`).then((res) => {
      if (!res.ok) throw new Error(`Failed to fetch document ${docSlug}: ${res.statusText}`);
      return res.json();
    }),

  /**
   * Get document content with rendered markdown and extracted wiki links.
   * Endpoint: GET /projects/:slug/documents/:docSlug/content
   */
  getContent: (projectSlug: string, docSlug: string): Promise<DocumentContentResponse> =>
    fetch(`${API_BASE_URL}/projects/${projectSlug}/documents/${docSlug}/content`).then((res) => {
      if (!res.ok) throw new Error(`Failed to fetch document content ${docSlug}: ${res.statusText}`);
      return res.json();
    }),

  /**
   * Update an existing document.
   */
  update: (projectSlug: string, docSlug: string, data: UpdateDocumentDto): Promise<Document> =>
    fetch(`${API_BASE_URL}/projects/${projectSlug}/documents/${docSlug}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    }).then((res) => {
      if (!res.ok) throw new Error(`Failed to update document ${docSlug}: ${res.statusText}`);
      return res.json();
    }),

  /**
   * Delete a document.
   */
  delete: (projectSlug: string, docSlug: string): Promise<void> =>
    fetch(`${API_BASE_URL}/projects/${projectSlug}/documents/${docSlug}`, {
      method: 'DELETE',
    }).then((res) => {
      if (!res.ok && res.status !== 204) throw new Error(`Failed to delete document ${docSlug}: ${res.statusText}`);
    }),
};
