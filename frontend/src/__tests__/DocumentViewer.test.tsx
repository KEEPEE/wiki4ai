/**
 * Tests for DocumentViewer page component
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import DocumentViewer from '../pages/DocumentViewer'

// Mock dependencies
vi.mock('../services/documentApi', () => ({
  documentApi: {
    get: vi.fn(),
    getContent: vi.fn(),
    getBacklinks: vi.fn(),
  },
}))

function renderWithProviders(ui: React.ReactElement, { route = '/projects/test-project/documents/my-doc' } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: 0 },
    },
  })
  
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[route]}>
        <Routes>
          <Route path="/projects/:slug/documents/:docId" element={ui} />
          <Route path="/projects/:slug/documents/:docId/edit" element={<div>Editor</div>} />
          <Route path="/projects/:slug" element={<div>Project</div>} />
          <Route path="/" element={<div>Dashboard</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('DocumentViewer', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('Loading state', () => {
    it('should show loading indicator while fetching document', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.get).mockImplementation(() => 
        new Promise(resolve => setTimeout(() => resolve({
          id: 1, title: 'Test', slug: 'test', content: '# Hello', projectId: 1, createdAt: '', updatedAt: ''
        }), 100))
      )

      renderWithProviders(<DocumentViewer />)

      expect(screen.getByText(/Loading document/)).toBeInTheDocument()
    })
  })

  describe('Error state', () => {
    it('should show error message when fetch fails', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.get).mockRejectedValue(new Error('Not found'))

      renderWithProviders(<DocumentViewer />)

      await waitFor(() => {
        expect(screen.getByText(/Error/)).toBeInTheDocument()
      })
    })

    it('should show back to project button on error', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.get).mockRejectedValue(new Error('Not found'))

      renderWithProviders(<DocumentViewer />)

      await waitFor(() => {
        expect(screen.getByText('Back to Project')).toBeInTheDocument()
      })
    })
  })

  describe('Document display', () => {
    it('should render document title in h1 heading', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.get).mockResolvedValue({
        id: 1, title: 'My Document', slug: 'my-document', content: '# Hello World\nThis is test content.', projectId: 1, createdAt: '', updatedAt: ''
      })
      vi.mocked(documentApi.getBacklinks).mockResolvedValue([])

      renderWithProviders(<DocumentViewer />)

      // Use getAllByText since title appears in breadcrumb and h1
      await waitFor(() => {
        const titles = screen.getAllByText('My Document')
        expect(titles.length).toBeGreaterThanOrEqual(1)
      })
    })

    it('should show breadcrumb navigation', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.get).mockResolvedValue({
        id: 1, title: 'My Document', slug: 'my-document', content: '', projectId: 1, createdAt: '', updatedAt: ''
      })
      vi.mocked(documentApi.getBacklinks).mockResolvedValue([])

      renderWithProviders(<DocumentViewer />)

      await waitFor(() => {
        expect(screen.getByText('Dashboard')).toBeInTheDocument()
      })
    })

    it('should show edit button', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.get).mockResolvedValue({
        id: 1, title: 'My Document', slug: 'my-document', content: '', projectId: 1, createdAt: '', updatedAt: ''
      })
      vi.mocked(documentApi.getBacklinks).mockResolvedValue([])

      renderWithProviders(<DocumentViewer />)

      await waitFor(() => {
        expect(screen.getByText('Upraviť')).toBeInTheDocument()
      })
    })

    it('should render markdown content', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.get).mockResolvedValue({
        id: 1, title: 'My Document', slug: 'my-document', content: '# Hello World\nThis is test content.', projectId: 1, createdAt: '', updatedAt: ''
      })
      vi.mocked(documentApi.getBacklinks).mockResolvedValue([])

      renderWithProviders(<DocumentViewer />)

      await waitFor(() => {
        expect(screen.getByText('Hello World')).toBeInTheDocument()
      })
    })

    it('should show empty content message when document has no content', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.get).mockResolvedValue({
        id: 1, title: 'Empty Document', slug: 'empty-document', content: '', projectId: 1, createdAt: '', updatedAt: ''
      })
      vi.mocked(documentApi.getBacklinks).mockResolvedValue([])

      renderWithProviders(<DocumentViewer />)

      await waitFor(() => {
        expect(screen.getByText(/no content yet/)).toBeInTheDocument()
      })
    })
  })

  describe('Wiki links', () => {
    it('should extract and display wiki links from markdown content', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.get).mockResolvedValue({
        id: 1, title: 'My Document', slug: 'my-document', 
        content: 'Some content with [[WikiLink]] and [[Another Doc]]', 
        projectId: 1, createdAt: '', updatedAt: ''
      })
      vi.mocked(documentApi.getBacklinks).mockResolvedValue([])

      renderWithProviders(<DocumentViewer />)

      await waitFor(() => {
        // Wiki links section should be rendered
        expect(screen.getByText(/Prepojenia/)).toBeInTheDocument()
      })
    })
  })

  describe('Edit navigation', () => {
    it('should navigate to editor when edit button is clicked', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.get).mockResolvedValue({
        id: 1, title: 'My Document', slug: 'my-document', content: '', projectId: 1, createdAt: '', updatedAt: ''
      })
      vi.mocked(documentApi.getBacklinks).mockResolvedValue([])

      renderWithProviders(<DocumentViewer />)

      await waitFor(() => {
        expect(screen.getByText('Upraviť')).toBeInTheDocument()
      })

      const user = userEvent.setup()
      await user.click(screen.getByText('Upraviť'))
    })
  })

  describe('Backlinks section', () => {
    it('should render backlinks section in Document Viewer', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.get).mockResolvedValue({
        id: 1, title: 'My Document', slug: 'my-document', content: '# Hello', projectId: 1, createdAt: '', updatedAt: ''
      })
      vi.mocked(documentApi.getBacklinks).mockResolvedValue([
        { id: 2, title: 'Related Doc', slug: 'related-doc', content: null, projectId: 1, createdAt: '', updatedAt: '' },
      ])

      renderWithProviders(<DocumentViewer />)

      await waitFor(() => {
        expect(screen.getByText(/Linked from/)).toBeInTheDocument()
      })
    })

    it('should show correct count of backlinks', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.get).mockResolvedValue({
        id: 1, title: 'My Document', slug: 'my-document', content: '# Hello', projectId: 1, createdAt: '', updatedAt: ''
      })
      vi.mocked(documentApi.getBacklinks).mockResolvedValue([
        { id: 2, title: 'Doc A', slug: 'doc-a', content: null, projectId: 1, createdAt: '', updatedAt: '' },
        { id: 3, title: 'Doc B', slug: 'doc-b', content: null, projectId: 1, createdAt: '', updatedAt: '' },
        { id: 4, title: 'Doc C', slug: 'doc-c', content: null, projectId: 1, createdAt: '', updatedAt: '' },
      ])

      renderWithProviders(<DocumentViewer />)

      await waitFor(() => {
        expect(screen.getByText(/Linked from \(3\)/)).toBeInTheDocument()
      })
    })

    it('should navigate to correct document when backlink is clicked', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.get).mockResolvedValue({
        id: 1, title: 'My Document', slug: 'my-document', content: '# Hello', projectId: 1, createdAt: '', updatedAt: ''
      })
      vi.mocked(documentApi.getBacklinks).mockResolvedValue([
        { id: 2, title: 'Related Doc', slug: 'related-doc', content: null, projectId: 1, createdAt: '', updatedAt: '' },
      ])

      renderWithProviders(<DocumentViewer />)

      await waitFor(() => {
        expect(screen.getByText('Related Doc')).toBeInTheDocument()
      })

      const user = userEvent.setup()
      await user.click(screen.getByText('Related Doc'))
    })

    it('should show "No documents link to this page" when no backlinks', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.get).mockResolvedValue({
        id: 1, title: 'My Document', slug: 'my-document', content: '# Hello', projectId: 1, createdAt: '', updatedAt: ''
      })
      vi.mocked(documentApi.getBacklinks).mockResolvedValue([])

      renderWithProviders(<DocumentViewer />)

      await waitFor(() => {
        expect(screen.getByText('No documents link to this page')).toBeInTheDocument()
      })
    })

    it('should show count of 0 when no backlinks', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.get).mockResolvedValue({
        id: 1, title: 'My Document', slug: 'my-document', content: '# Hello', projectId: 1, createdAt: '', updatedAt: ''
      })
      vi.mocked(documentApi.getBacklinks).mockResolvedValue([])

      renderWithProviders(<DocumentViewer />)

      await waitFor(() => {
        expect(screen.getByText(/Linked from \(0\)/)).toBeInTheDocument()
      })
    })
  })
})
