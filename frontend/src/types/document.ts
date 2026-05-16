/**
 * TypeScript type definitions for Document entity.
 */

export interface Document {
  id: number;
  title: string;
  content: string | null;
  projectId: number;
  linkedDocuments?: number[];
  createdAt: string;
  updatedAt: string;
}

export interface DocumentDTO {
  id?: number;
  title: string;
  content?: string;
  projectId: number;
  linkedDocuments?: number[];
  createdAt?: string;
  updatedAt?: string;
}
