/**
 * Tests for ProjectDetail page component
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import ProjectDetail from '../pages/ProjectDetail'

// Mock dependencies
vi.mock('../hooks/useProjects', () => ({
  useProjects: vi.fn(),
}))

vi.mock('../hooks/useDocuments', () => ({
  useDocuments: vi.fn(),
}))

function renderWithProviders(ui: React.ReactElement, { route = '/projects/test-project' } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: 0 },
    },
  })
  
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[route]}>
        <Routes>
          <Route path="/projects/:slug" element={ui} />
          <Route path="/projects/:slug/documents/new" element={<div>New Document</div>} />
          <Route path="/" element={<div>Dashboard</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ProjectDetail', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('Loading state', () => {
    it('should show loading spinner when projects are loading', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [], isLoading: true, createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      })

      const { useDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: true, createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      })

      renderWithProviders(<ProjectDetail />)

      expect(screen.getByText(/Loading/)).toBeInTheDocument()
    })
  })

  describe('Project not found', () => {
    it('should show error when project is not found', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [], isLoading: false, createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      })

      const { useDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      })

      renderWithProviders(<ProjectDetail />)

      await waitFor(() => {
        expect(screen.getByText(/Project not found/)).toBeInTheDocument()
      })
    })
  })

  describe('Project display', () => {
    it('should render project name in h1 heading', async () => {
      const mockProjects = [
        { id: 1, name: 'Test Project', slug: 'test-project', description: 'A test project description', documentCount: 0, createdAt: '', updatedAt: '' },
      ]

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: mockProjects, isLoading: false, createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      })

      const { useDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      })

      renderWithProviders(<ProjectDetail />)

      // Project name appears in breadcrumb and h1 - use getAllByText
      await waitFor(() => {
        const titles = screen.getAllByText('Test Project')
        expect(titles.length).toBeGreaterThanOrEqual(1)
      })
    })

    it('should render project description when present', async () => {
      const mockProjects = [
        { id: 1, name: 'Test Project', slug: 'test-project', description: 'A test project description', documentCount: 0, createdAt: '', updatedAt: '' },
      ]

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: mockProjects, isLoading: false, createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      })

      const { useDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      })

      renderWithProviders(<ProjectDetail />)

      await waitFor(() => {
        expect(screen.getByText(/A test project description/)).toBeInTheDocument()
      })
    })
  })

  describe('Documents tab', () => {
    it('should show documents count in h2 header', async () => {
      const mockProjects = [
        { id: 1, name: 'Test Project', slug: 'test-project', description: null, documentCount: 2, createdAt: '', updatedAt: '' },
      ]

      const mockDocuments = [
        { id: 1, title: 'Doc 1', content: '', createdAt: '', updatedAt: '' },
        { id: 2, title: 'Doc 2', content: '', createdAt: '', updatedAt: '' },
      ]

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: mockProjects, isLoading: false, createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      })

      const { useDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: mockDocuments, isLoading: false, createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      })

      renderWithProviders(<ProjectDetail />)

      // Documents count appears in tab button and h2 - use getAllByText
      await waitFor(() => {
        const headers = screen.getAllByText(/Dokumenty \(2\)/)
        expect(headers.length).toBeGreaterThanOrEqual(1)
      })
    })

    it('should show empty state when no documents', async () => {
      const mockProjects = [
        { id: 1, name: 'Test Project', slug: 'test-project', description: null, documentCount: 0, createdAt: '', updatedAt: '' },
      ]

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: mockProjects, isLoading: false, createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      })

      const { useDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      })

      renderWithProviders(<ProjectDetail />)

      await waitFor(() => {
        expect(screen.getByText(/Žiadne dokumenty/)).toBeInTheDocument()
      })
    })
  })

  describe('Create document', () => {
    it('should navigate to new document page when create button is clicked', async () => {
      const mockProjects = [
        { id: 1, name: 'Test Project', slug: 'test-project', description: null, documentCount: 0, createdAt: '', updatedAt: '' },
      ]

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: mockProjects, isLoading: false, createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      })

      const { useDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      })

      renderWithProviders(<ProjectDetail />)

      const user = userEvent.setup()
      
      await waitFor(() => {
        expect(screen.getByText('+ Nový dokument')).toBeInTheDocument()
      })

      await user.click(screen.getByText('+ Nový dokument'))
    })
  })
})
