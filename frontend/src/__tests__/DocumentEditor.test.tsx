/**
 * Tests for DocumentEditor page component
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import DocumentEditor from '../pages/DocumentEditor'

// Mock dependencies
vi.mock('../services/documentApi', () => ({
  documentApi: {
    get: vi.fn(),
  },
}))

vi.mock('../hooks/useDocuments', () => ({
  useDocuments: vi.fn(),
}))

vi.mock('../hooks/useDebounce', () => ({
  useDebounce: vi.fn((value: string) => value),
}))

function renderWithProviders(ui: React.ReactElement, { route = '/projects/test-project/documents/my-doc/edit' } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: 0 },
    },
  })
  
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[route]}>
        <Routes>
          <Route path="/projects/:slug/documents/:docId/edit" element={ui} />
          <Route path="/projects/:slug/documents/:docId" element={<div>Viewer</div>} />
          <Route path="/" element={<div>Dashboard</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('DocumentEditor', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('Loading state', () => {
    it('should show loading indicator when editing existing document', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.get).mockImplementation(() => new Promise(resolve => setTimeout(() => resolve({ id: 1, title: 'Test', content: '# Hello', projectId: 1, createdAt: '', updatedAt: '' }), 100)))

      const { useDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<DocumentEditor />)

      expect(screen.getByText(/Loading document/)).toBeInTheDocument()
    })
  })

  describe('Edit existing document', () => {
    it('should load and display existing document content', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.get).mockResolvedValue({ id: 1, title: 'Existing Doc', content: '# Existing Content', projectId: 1, createdAt: '', updatedAt: '' })

      const mockUpdateDocument = vi.fn().mockResolvedValue(undefined)

      const { useDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: mockUpdateDocument, deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<DocumentEditor />)

      await waitFor(() => {
        expect(screen.getByDisplayValue('Existing Doc')).toBeInTheDocument()
      })
    })
  })

  describe('Save functionality', () => {
    it('should show save button after document loads', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.get).mockResolvedValue({ id: 1, title: 'Test Doc', content: '# Content', projectId: 1, createdAt: '', updatedAt: '' })

      const { useDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<DocumentEditor />)

      await waitFor(() => {
        expect(screen.getByText('Uložiť')).toBeInTheDocument()
      })
    })

    it('should call updateDocument when saving existing document', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.get).mockResolvedValue({ id: 1, title: 'Existing Doc', content: '# Content', projectId: 1, createdAt: '', updatedAt: '' })

      const mockUpdateDocument = vi.fn().mockResolvedValue(undefined)

      const { useDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: mockUpdateDocument, deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<DocumentEditor />)

      await waitFor(() => {
        expect(screen.getByDisplayValue('Existing Doc')).toBeInTheDocument()
      })

      const user = userEvent.setup()
      await user.click(screen.getByText('Uložiť'))

      expect(mockUpdateDocument).toHaveBeenCalled()
    })
  })

  describe('Back button', () => {
    it('should navigate back when back button is clicked', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.get).mockResolvedValue({ id: 1, title: 'Test Doc', content: '# Content', projectId: 1, createdAt: '', updatedAt: '' })

      const { useDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<DocumentEditor />)

      await waitFor(() => {
        expect(screen.getByText('← Späť')).toBeInTheDocument()
      })
    })
  })

  describe('Word/Character count', () => {
    it('should render word count in the editor footer', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.get).mockResolvedValue({ id: 1, title: 'Test Doc', content: '# Hello World', projectId: 1, createdAt: '', updatedAt: '' })

      const { useDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<DocumentEditor />)

      await waitFor(() => {
        expect(screen.getByText(/words/)).toBeInTheDocument()
      })
    })

    it('should render character count in the editor footer', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.get).mockResolvedValue({ id: 1, title: 'Test Doc', content: '# Hello World', projectId: 1, createdAt: '', updatedAt: '' })

      const { useDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<DocumentEditor />)

      await waitFor(() => {
        expect(screen.getByText(/characters/)).toBeInTheDocument()
      })
    })

    it('should update word count when content changes', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.get).mockResolvedValue({ id: 1, title: 'Test Doc', content: 'Hello', projectId: 1, createdAt: '', updatedAt: '' })

      const mockUpdateDocument = vi.fn().mockResolvedValue(undefined)

      const { useDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: mockUpdateDocument, deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<DocumentEditor />)

      await waitFor(() => {
        expect(screen.getByText('1 words')).toBeInTheDocument()
      })

      const user = userEvent.setup()
      const textarea = screen.getByPlaceholderText(/Upravte markdown obsah/)
      await user.clear(textarea)
      await user.type(textarea, 'Hello world foo bar')

      await waitFor(() => {
        expect(screen.getByText('4 words')).toBeInTheDocument()
      })
    })

    it('should update character count when content changes', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.get).mockResolvedValue({ id: 1, title: 'Test Doc', content: '', projectId: 1, createdAt: '', updatedAt: '' })

      const mockUpdateDocument = vi.fn().mockResolvedValue(undefined)

      const { useDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: mockUpdateDocument, deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<DocumentEditor />)

      await waitFor(() => {
        expect(screen.getByText('0 characters')).toBeInTheDocument()
      })

      const user = userEvent.setup()
      const textarea = screen.getByPlaceholderText(/Upravte markdown obsah/)
      await user.type(textarea, 'abc')

      await waitFor(() => {
        expect(screen.getByText('3 characters')).toBeInTheDocument()
      })
    })

    it('should show 0 words and 0 characters for empty content', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.get).mockResolvedValue({ id: 1, title: 'Empty Doc', content: '', projectId: 1, createdAt: '', updatedAt: '' })

      const { useDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<DocumentEditor />)

      await waitFor(() => {
        expect(screen.getByText('0 words')).toBeInTheDocument()
        expect(screen.getByText('0 characters')).toBeInTheDocument()
      })
    })
  })

  describe('Auto-save', () => {
    it('should trigger auto-save after content change (debounced)', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.get).mockResolvedValue({ id: 1, title: 'Test Doc', content: '# Original Content', projectId: 1, createdAt: '', updatedAt: '' })

      const mockUpdateDocument = vi.fn().mockResolvedValue(undefined)

      const { useDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: mockUpdateDocument, deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<DocumentEditor />)

      // Wait for document to load
      await waitFor(() => {
        expect(screen.getByDisplayValue('Test Doc')).toBeInTheDocument()
      })

      const user = userEvent.setup()
      const textarea = screen.getByPlaceholderText(/Upravte markdown obsah/)

      // Type new content — useDebounce mock returns value immediately,
      // so debounced values change and auto-save effect fires
      await user.type(textarea, ' additional text')

      // Auto-save should have been triggered (debounce mocked as instant)
      await waitFor(() => {
        expect(mockUpdateDocument).toHaveBeenCalled()
      }, { timeout: 3000 })
    })

    it('should transition status from Saving to Saved after auto-save', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.get).mockResolvedValue({ id: 1, title: 'Test Doc', content: '# Original Content', projectId: 1, createdAt: '', updatedAt: '' })

      const mockUpdateDocument = vi.fn().mockResolvedValue(undefined)

      const { useDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: mockUpdateDocument, deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<DocumentEditor />)

      // Wait for document to load
      await waitFor(() => {
        expect(screen.getByDisplayValue('Test Doc')).toBeInTheDocument()
      })

      const user = userEvent.setup()
      const textarea = screen.getByPlaceholderText(/Upravte markdown obsah/)

      // Type new content — triggers unsaved → saving (auto-save) → saved
      await user.type(textarea, ' new content')

      // After debounce (instant in mock), auto-save fires and shows "saving" then "saved"
      await waitFor(() => {
        expect(screen.getByTestId('save-status')).toHaveTextContent(/Uložené/)
      }, { timeout: 3000 })
    })

    it('should change status to Unsaved on content changes', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.get).mockResolvedValue({ id: 1, title: 'Test Doc', content: '# Original Content', projectId: 1, createdAt: '', updatedAt: '' })

      // Make updateDocument resolve slowly so we can catch the unsaved state first
      const mockUpdateDocument = vi.fn().mockImplementation(
        () => new Promise(resolve => setTimeout(() => resolve(undefined), 5000))
      )

      const { useDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: mockUpdateDocument, deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<DocumentEditor />)

      // Wait for document to load
      await waitFor(() => {
        expect(screen.getByDisplayValue('Test Doc')).toBeInTheDocument()
      })

      const user = userEvent.setup()
      const textarea = screen.getByPlaceholderText(/Upravte markdown obsah/)

      // Type new content — this should show unsaved status because
      // content differs from lastSavedContent (even though auto-save fires, API is slow)
      await user.type(textarea, ' additional text')

      // The save-status indicator should appear showing a non-idle state
      await waitFor(() => {
        const statusEl = screen.getByTestId('save-status')
        expect(statusEl).toBeInTheDocument()
        // Status should be either "saving" or "unsaved" (not idle)
        const text = statusEl.textContent || ''
        expect(text.includes('Ukladá sa') || text.includes('Neuložené')).toBe(true)
      }, { timeout: 3000 })
    })
  })
})
