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
}

export interface CreateDocumentDto {
  title: string;
  content?: string;
}

export interface UpdateDocumentDto {
  title: string;
  content?: string;
}

export interface LinkCreateDto {
  targetDocumentId: number;
}
