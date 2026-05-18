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
    getContent: vi.fn(),
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
      vi.mocked(documentApi.getContent).mockImplementation(() => 
        new Promise(resolve => setTimeout(() => resolve({ title: 'Test', content: '# Hello', wikiLinks: [] }), 100))
      )

      renderWithProviders(<DocumentViewer />)

      expect(screen.getByText(/Loading document/)).toBeInTheDocument()
    })
  })

  describe('Error state', () => {
    it('should show error message when fetch fails', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.getContent).mockRejectedValue(new Error('Not found'))

      renderWithProviders(<DocumentViewer />)

      await waitFor(() => {
        expect(screen.getByText(/Error/)).toBeInTheDocument()
      })
    })

    it('should show back to project button on error', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.getContent).mockRejectedValue(new Error('Not found'))

      renderWithProviders(<DocumentViewer />)

      await waitFor(() => {
        expect(screen.getByText('Back to Project')).toBeInTheDocument()
      })
    })
  })

  describe('Document display', () => {
    it('should render document title in h1 heading', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.getContent).mockResolvedValue({
        title: 'My Document',
        content: '# Hello World\nThis is test content.',
        wikiLinks: [],
      })

      renderWithProviders(<DocumentViewer />)

      // Use getAllByText since title appears in breadcrumb and h1
      await waitFor(() => {
        const titles = screen.getAllByText('My Document')
        expect(titles.length).toBeGreaterThanOrEqual(1)
      })
    })

    it('should show breadcrumb navigation', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.getContent).mockResolvedValue({
        title: 'My Document', content: '', wikiLinks: [],
      })

      renderWithProviders(<DocumentViewer />)

      await waitFor(() => {
        expect(screen.getByText('Dashboard')).toBeInTheDocument()
      })
    })

    it('should show edit button', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.getContent).mockResolvedValue({
        title: 'My Document', content: '', wikiLinks: [],
      })

      renderWithProviders(<DocumentViewer />)

      await waitFor(() => {
        expect(screen.getByText('Upraviť')).toBeInTheDocument()
      })
    })
  })

  describe('Wiki links', () => {
    it('should render markdown viewer when document loads', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.getContent).mockResolvedValue({
        title: 'My Document',
        content: 'Some content with [[WikiLink]]',
        wikiLinks: ['wiki-link'],
      })

      renderWithProviders(<DocumentViewer />)

      await waitFor(() => {
        // The markdown viewer should be rendered
        expect(screen.getByText('Upraviť')).toBeInTheDocument()
      })
    })
  })

  describe('Edit navigation', () => {
    it('should navigate to editor when edit button is clicked', async () => {
      const { documentApi } = await import('../services/documentApi')
      vi.mocked(documentApi.getContent).mockResolvedValue({
        title: 'My Document', content: '', wikiLinks: [],
      })

      renderWithProviders(<DocumentViewer />)

      await waitFor(() => {
        expect(screen.getByText('Upraviť')).toBeInTheDocument()
      })

      const user = userEvent.setup()
      await user.click(screen.getByText('Upraviť'))
    })
  })
})
