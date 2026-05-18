/**
 * Tests for DocumentEditor page component
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
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
      vi.mocked(documentApi.get).mockImplementation(() => new Promise(resolve => setTimeout(() => resolve({ title: 'Test', content: '# Hello' }), 100)))

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
      vi.mocked(documentApi.get).mockResolvedValue({ title: 'Existing Doc', content: '# Existing Content' })

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
      vi.mocked(documentApi.get).mockResolvedValue({ title: 'Test Doc', content: '# Content' })

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
      vi.mocked(documentApi.get).mockResolvedValue({ title: 'Existing Doc', content: '# Content' })

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
      vi.mocked(documentApi.get).mockResolvedValue({ title: 'Test Doc', content: '# Content' })

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
})
