/**
 * API service for Document operations.
 * Handles all HTTP requests to the backend document endpoints.
 */

import type { Document, DocumentDTO } from '../types/document';

const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080/api';

class DocumentApiService {
  /**
   * Get all documents in a project.
   */
  async getDocumentsByProject(projectId: number): Promise<Document[]> {
    const response = await fetch(`${API_BASE_URL}/documents/project/${projectId}`);
    if (!response.ok) {
      throw new Error(`Failed to fetch documents for project ${projectId}: ${response.statusText}`);
    }
    return response.json();
  }

  /**
   * Get a single document by ID.
   */
  async getDocumentById(id: number): Promise<Document> {
    const response = await fetch(`${API_BASE_URL}/documents/${id}`);
    if (!response.ok) {
      throw new Error(`Failed to fetch document ${id}: ${response.statusText}`);
    }
    return response.json();
  }

  /**
   * Create a new document in a project.
   */
  async createDocument(projectId: number, dto: DocumentDTO): Promise<Document> {
    const response = await fetch(`${API_BASE_URL}/documents/project/${projectId}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(dto),
    });
    if (!response.ok) {
      throw new Error(`Failed to create document: ${response.statusText}`);
    }
    return response.json();
  }

  /**
   * Update an existing document.
   */
  async updateDocument(id: number, dto: DocumentDTO): Promise<Document> {
    const response = await fetch(`${API_BASE_URL}/documents/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(dto),
    });
    if (!response.ok) {
      throw new Error(`Failed to update document ${id}: ${response.statusText}`);
    }
    return response.json();
  }

  /**
   * Delete a document.
   */
  async deleteDocument(id: number): Promise<void> {
    const response = await fetch(`${API_BASE_URL}/documents/${id}`, {
      method: 'DELETE',
    });
    if (!response.ok) {
      throw new Error(`Failed to delete document ${id}: ${response.statusText}`);
    }
  }

  /**
   * Search documents by keyword within a project.
   */
  async searchDocuments(projectId: number, keyword: string): Promise<Document[]> {
    const response = await fetch(
      `${API_BASE_URL}/documents/project/${projectId}/search?keyword=${encodeURIComponent(keyword)}`
    );
    if (!response.ok) {
      throw new Error(`Failed to search documents: ${response.statusText}`);
    }
    return response.json();
  }
}

export const documentApi = new DocumentApiService();
