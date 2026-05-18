/**
 * Mock helper factories for creating properly typed mocks in tests
 */
import { vi } from 'vitest'

// Helper to create a complete useProjects mock return value
export function createUseProjectsMock(overrides = {}) {
  return {
    projects: [],
    isLoading: false,
    error: null,
    refetch: vi.fn().mockResolvedValue({ data: [] }),
    createProject: vi.fn(),
    updateProject: vi.fn(),
    deleteProject: vi.fn(),
    isCreating: false,
    isUpdating: false,
    isDeleting: false,
    ...overrides,
  } as any
}

// Helper to create a complete useDocuments mock return value
export function createUseDocumentsMock(overrides = {}) {
  return {
    documents: [],
    isLoading: false,
    error: null,
    refetch: vi.fn().mockResolvedValue({ data: [] }),
    createDocument: vi.fn(),
    updateDocument: vi.fn(),
    deleteDocument: vi.fn(),
    isCreating: false,
    isUpdating: false,
    isDeleting: false,
    ...overrides,
  } as any
}

// Helper to create a valid Document object
export function createDocument(overrides = {}) {
  return {
    id: 1,
    title: 'Test Doc',
    content: '',
    projectId: 1,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    ...overrides,
  }
}

// Helper to create a valid Project object
export function createProject(overrides = {}) {
  return {
    id: 1,
    name: 'Test Project',
    slug: 'test-project',
    description: null,
    documentCount: 0,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    ...overrides,
  }
}
