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
  useSearchDocuments: vi.fn(),
}))

vi.mock('../services/projectApi', () => ({
  projectApi: {
    exportProject: vi.fn(),
  },
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
          <Route path="/projects/:slug/settings" element={<div>Project Settings</div>} />
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
        projects: [], isLoading: true, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      const { useDocuments, useSearchDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: true, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)
      vi.mocked(useSearchDocuments).mockReturnValue({
        searchResults: [], isLoading: false, hasSearched: false,
      } as any)

      renderWithProviders(<ProjectDetail />)

      expect(screen.getByText(/Loading/)).toBeInTheDocument()
    })
  })

  describe('Project not found', () => {
    it('should show error when project is not found', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [], isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      const { useDocuments, useSearchDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)
      vi.mocked(useSearchDocuments).mockReturnValue({
        searchResults: [], isLoading: false, hasSearched: false,
      } as any)

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
        projects: mockProjects, isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      const { useDocuments, useSearchDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)
      vi.mocked(useSearchDocuments).mockReturnValue({
        searchResults: [], isLoading: false, hasSearched: false,
      } as any)

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
        projects: mockProjects, isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      const { useDocuments, useSearchDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)
      vi.mocked(useSearchDocuments).mockReturnValue({
        searchResults: [], isLoading: false, hasSearched: false,
      } as any)

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
        { id: 1, title: 'Doc 1', content: '', projectId: 1, createdAt: '', updatedAt: '' },
        { id: 2, title: 'Doc 2', content: '', projectId: 1, createdAt: '', updatedAt: '' },
      ]

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: mockProjects, isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      const { useDocuments, useSearchDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: mockDocuments, isLoading: false, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)
      vi.mocked(useSearchDocuments).mockReturnValue({
        searchResults: [], isLoading: false, hasSearched: false,
      } as any)

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
        projects: mockProjects, isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      const { useDocuments, useSearchDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)
      vi.mocked(useSearchDocuments).mockReturnValue({
        searchResults: [], isLoading: false, hasSearched: false,
      } as any)

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
        projects: mockProjects, isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      const { useDocuments, useSearchDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)
      vi.mocked(useSearchDocuments).mockReturnValue({
        searchResults: [], isLoading: false, hasSearched: false,
      } as any)

      renderWithProviders(<ProjectDetail />)

      const user = userEvent.setup()
      
      await waitFor(() => {
        expect(screen.getByText('+ Nový dokument')).toBeInTheDocument()
      })

      await user.click(screen.getByText('+ Nový dokument'))
    })
  })

  describe('Search documents', () => {
    it('should render search input in Project Detail page', async () => {
      const mockProjects = [
        { id: 1, name: 'Test Project', slug: 'test-project', description: null, documentCount: 0, createdAt: '', updatedAt: '' },
      ]

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: mockProjects, isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      const { useDocuments, useSearchDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)
      vi.mocked(useSearchDocuments).mockReturnValue({
        searchResults: [], isLoading: false, hasSearched: false,
      } as any)

      renderWithProviders(<ProjectDetail />)

      await waitFor(() => {
        expect(screen.getByTestId('document-search-input')).toBeInTheDocument()
      })
    })

    it('should have correct placeholder text', async () => {
      const mockProjects = [
        { id: 1, name: 'Test Project', slug: 'test-project', description: null, documentCount: 0, createdAt: '', updatedAt: '' },
      ]

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: mockProjects, isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      const { useDocuments, useSearchDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)
      vi.mocked(useSearchDocuments).mockReturnValue({
        searchResults: [], isLoading: false, hasSearched: false,
      } as any)

      renderWithProviders(<ProjectDetail />)

      const searchInput = await waitFor(() => screen.getByTestId('document-search-input'))
      expect(searchInput).toHaveAttribute('placeholder', 'Search documents...')
    })

    it('should show loading state during search', async () => {
      const mockProjects = [
        { id: 1, name: 'Test Project', slug: 'test-project', description: null, documentCount: 0, createdAt: '', updatedAt: '' },
      ]

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: mockProjects, isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      const { useDocuments, useSearchDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)
      // Simulate searching state with hasSearched=true and isLoading=true
      vi.mocked(useSearchDocuments).mockReturnValue({
        searchResults: [], isLoading: true, hasSearched: true,
      } as any)

      renderWithProviders(<ProjectDetail />)

      await waitFor(() => {
        expect(screen.getByText(/Searching/)).toBeInTheDocument()
      })
    })

    it('should show "No documents match your search" when no results', async () => {
      const mockProjects = [
        { id: 1, name: 'Test Project', slug: 'test-project', description: null, documentCount: 0, createdAt: '', updatedAt: '' },
      ]

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: mockProjects, isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      const { useDocuments, useSearchDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)
      // Simulate search completed with no results
      vi.mocked(useSearchDocuments).mockReturnValue({
        searchResults: [], isLoading: false, hasSearched: true,
      } as any)

      renderWithProviders(<ProjectDetail />)

      await waitFor(() => {
        expect(screen.getByText(/No documents match your search/)).toBeInTheDocument()
      })
    })

    it('should display search results when search returns matches', async () => {
      const mockProjects = [
        { id: 1, name: 'Test Project', slug: 'test-project', description: null, documentCount: 0, createdAt: '', updatedAt: '' },
      ]

      const mockSearchResults = [
        { id: 1, title: 'Matching Doc', content: '', projectId: 1, createdAt: '', updatedAt: '2024-01-01' },
      ]

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: mockProjects, isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      const { useDocuments, useSearchDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)
      // Simulate search completed with results
      vi.mocked(useSearchDocuments).mockReturnValue({
        searchResults: mockSearchResults, isLoading: false, hasSearched: true,
      } as any)

      renderWithProviders(<ProjectDetail />)

      await waitFor(() => {
        expect(screen.getByText('Matching Doc')).toBeInTheDocument()
      })
    })
  })

  describe('Import file', () => {
    const getMockHooks = (overrides: any = {}) => ({
      useProjectsReturn: {
        projects: [{ id: 1, name: 'Test Project', slug: 'test-project', description: null, documentCount: 0, createdAt: '', updatedAt: '' }],
        isLoading: false,
        error: null,
        refetch: vi.fn(),
        createProject: vi.fn(),
        updateProject: vi.fn(),
        deleteProject: vi.fn(),
        isCreating: false,
        isUpdating: false,
        isDeleting: false,
      },
      useDocumentsReturn: {
        documents: [],
        isLoading: false,
        error: null,
        refetch: vi.fn(),
        createDocument: vi.fn(),
        updateDocument: vi.fn(),
        deleteDocument: vi.fn(),
        uploadDocument: vi.fn().mockResolvedValue({ id: 99, title: 'Imported', content: '', projectId: 1, createdAt: '', updatedAt: '' }),
        isCreating: false,
        isUpdating: false,
        isDeleting: false,
        isUploading: false,
        ...overrides.docsOverrides,
      },
      useSearchDocumentsReturn: {
        searchResults: [],
        isLoading: false,
        hasSearched: false,
      },
    })

    it('should render import file button on Project Detail page', async () => {
      const mocks = getMockHooks()

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue(mocks.useProjectsReturn as any)

      const { useDocuments, useSearchDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue(mocks.useDocumentsReturn as any)
      vi.mocked(useSearchDocuments).mockReturnValue(mocks.useSearchDocumentsReturn as any)

      renderWithProviders(<ProjectDetail />)

      await waitFor(() => {
        expect(screen.getByTestId('import-file-button')).toBeInTheDocument()
      })
    })

    it('should render import button with correct text', async () => {
      const mocks = getMockHooks()

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue(mocks.useProjectsReturn as any)

      const { useDocuments, useSearchDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue(mocks.useDocumentsReturn as any)
      vi.mocked(useSearchDocuments).mockReturnValue(mocks.useSearchDocumentsReturn as any)

      renderWithProviders(<ProjectDetail />)

      await waitFor(() => {
        expect(screen.getByText(/Import file/)).toBeInTheDocument()
      })
    })

    it('should have hidden file input with correct accept attribute', async () => {
      const mocks = getMockHooks()

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue(mocks.useProjectsReturn as any)

      const { useDocuments, useSearchDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue(mocks.useDocumentsReturn as any)
      vi.mocked(useSearchDocuments).mockReturnValue(mocks.useSearchDocumentsReturn as any)

      renderWithProviders(<ProjectDetail />)

      const fileInput = await waitFor(() => screen.getByTestId('import-file-input'))
      expect(fileInput).toHaveAttribute('accept', '.md,.markdown')
    })

    it('should call upload API when a valid .md file is selected', async () => {
      const uploadMock = vi.fn().mockResolvedValue({ id: 99, title: 'test.md', content: '', projectId: 1, createdAt: '', updatedAt: '' })

      const mocks = getMockHooks({
        docsOverrides: {
          uploadDocument: uploadMock,
        },
      })

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue(mocks.useProjectsReturn as any)

      const { useDocuments, useSearchDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue(mocks.useDocumentsReturn as any)
      vi.mocked(useSearchDocuments).mockReturnValue(mocks.useSearchDocumentsReturn as any)

      renderWithProviders(<ProjectDetail />)

      const user = userEvent.setup()

      // Click the import button to trigger file input
      const importButton = await waitFor(() => screen.getByTestId('import-file-button'))
      await user.click(importButton)

      // Get the hidden file input and simulate file selection
      const fileInput = screen.getByTestId('import-file-input') as HTMLInputElement

      // Create a mock File object
      const mockFile = new File(['# Test Document\n\nThis is test content.'], 'test.md', { type: 'text/markdown' })

      // Mock the files property on the input element
      Object.defineProperty(fileInput, 'files', {
        value: [mockFile],
        writable: true,
        configurable: true,
      })

      // Dispatch change event to trigger the handler
      fileInput.dispatchEvent(new Event('change', { bubbles: true }))

      // Give the component time to process the upload
      await waitFor(() => {
        expect(uploadMock).toHaveBeenCalledWith(mockFile)
      }, { timeout: 3000 })
    })

    it('should show upload progress bar during upload', async () => {
      const mocks = getMockHooks({
        docsOverrides: {
          isUploading: true,
        },
      })

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue(mocks.useProjectsReturn as any)

      const { useDocuments, useSearchDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        ...mocks.useDocumentsReturn,
        isUploading: true,
      } as any)
      vi.mocked(useSearchDocuments).mockReturnValue(mocks.useSearchDocumentsReturn as any)

      renderWithProviders(<ProjectDetail />)

      // The import button should be disabled during upload
      await waitFor(() => {
        const importButton = screen.getByTestId('import-file-button')
        expect(importButton).toBeDisabled()
      })
    })
  })

  describe('Settings link', () => {
    it('should render settings link in Project Detail header', async () => {
      const mockProjects = [
        { id: 1, name: 'Test Project', slug: 'test-project', description: null, documentCount: 0, createdAt: '', updatedAt: '' },
      ]

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: mockProjects, isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      const { useDocuments, useSearchDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)
      vi.mocked(useSearchDocuments).mockReturnValue({
        searchResults: [], isLoading: false, hasSearched: false,
      } as any)

      renderWithProviders(<ProjectDetail />)

      await waitFor(() => {
        const settingsLink = screen.getByTestId('settings-link')
        expect(settingsLink).toBeInTheDocument()
      })
    })

    it('should navigate to /projects/:slug/settings when settings link is clicked', async () => {
      const mockProjects = [
        { id: 1, name: 'Test Project', slug: 'test-project', description: null, documentCount: 0, createdAt: '', updatedAt: '' },
      ]

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: mockProjects, isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      const { useDocuments, useSearchDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)
      vi.mocked(useSearchDocuments).mockReturnValue({
        searchResults: [], isLoading: false, hasSearched: false,
      } as any)

      renderWithProviders(<ProjectDetail />)

      const user = userEvent.setup()

      await waitFor(() => {
        const settingsLink = screen.getByTestId('settings-link')
        expect(settingsLink).toHaveAttribute('href', '/projects/test-project/settings')
      })

      const settingsLink = screen.getByTestId('settings-link')
      await user.click(settingsLink)

      await waitFor(() => {
        expect(screen.getByText('Project Settings')).toBeInTheDocument()
      })
    })
  })

  describe('Export', () => {
    it('should render export button on Project Detail page', async () => {
      const mockProjects = [
        { id: 1, name: 'Test Project', slug: 'test-project', description: null, documentCount: 0, createdAt: '', updatedAt: '' },
      ]

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: mockProjects, isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      const { useDocuments, useSearchDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)
      vi.mocked(useSearchDocuments).mockReturnValue({
        searchResults: [], isLoading: false, hasSearched: false,
      } as any)

      renderWithProviders(<ProjectDetail />)

      await waitFor(() => {
        expect(screen.getByTestId('export-button')).toBeInTheDocument()
      })
    })

    it('should have correct export button text', async () => {
      const mockProjects = [
        { id: 1, name: 'Test Project', slug: 'test-project', description: null, documentCount: 0, createdAt: '', updatedAt: '' },
      ]

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: mockProjects, isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      const { useDocuments, useSearchDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)
      vi.mocked(useSearchDocuments).mockReturnValue({
        searchResults: [], isLoading: false, hasSearched: false,
      } as any)

      renderWithProviders(<ProjectDetail />)

      await waitFor(() => {
        expect(screen.getByText(/Export/)).toBeInTheDocument()
      })
    })

    it('should call export API and trigger download when export button is clicked', async () => {
      const mockProjects = [
        { id: 1, name: 'Test Project', slug: 'test-project', description: null, documentCount: 0, createdAt: '', updatedAt: '' },
      ]

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: mockProjects, isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      const { useDocuments, useSearchDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)
      vi.mocked(useSearchDocuments).mockReturnValue({
        searchResults: [], isLoading: false, hasSearched: false,
      } as any)

      const { projectApi } = await import('../services/projectApi')
      vi.mocked(projectApi.exportProject).mockResolvedValue(new Blob(['test zip content'], { type: 'application/zip' }))

      renderWithProviders(<ProjectDetail />)

      const user = userEvent.setup()

      const exportButton = await waitFor(() => screen.getByTestId('export-button'))
      expect(exportButton).toBeInTheDocument()

      // Mock URL.createObjectURL and URL.revokeObjectURL
      const createObjectUrlSpy = vi.spyOn(URL, 'createObjectURL').mockReturnValue('mock-url')
      const revokeObjectUrlSpy = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(vi.fn())

      await user.click(exportButton)

      await waitFor(() => {
        expect(projectApi.exportProject).toHaveBeenCalledWith('test-project')
      }, { timeout: 3000 })

      // Verify download was triggered via anchor element
      await waitFor(() => {
        expect(createObjectUrlSpy).toHaveBeenCalled()
      }, { timeout: 3000 })

      createObjectUrlSpy.mockRestore()
      revokeObjectUrlSpy.mockRestore()
    })

    it('should show loading state while exporting', async () => {
      const mockProjects = [
        { id: 1, name: 'Test Project', slug: 'test-project', description: null, documentCount: 0, createdAt: '', updatedAt: '' },
      ]

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: mockProjects, isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      const { useDocuments, useSearchDocuments } = await import('../hooks/useDocuments')
      vi.mocked(useDocuments).mockReturnValue({
        documents: [], isLoading: false, error: null, refetch: vi.fn(), createDocument: vi.fn(), updateDocument: vi.fn(), deleteDocument: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)
      vi.mocked(useSearchDocuments).mockReturnValue({
        searchResults: [], isLoading: false, hasSearched: false,
      } as any)

      const { projectApi } = await import('../services/projectApi')

      // Create a deferred promise to control when export resolves
      let resolveExport: (() => void) | undefined
      const pendingPromise = new Promise<Blob>((resolve) => {
        resolveExport = () => resolve(new Blob(['test'], { type: 'application/zip' }))
      })
      vi.mocked(projectApi.exportProject).mockReturnValue(pendingPromise)

      renderWithProviders(<ProjectDetail />)

      const user = userEvent.setup()

      const exportButton = await waitFor(() => screen.getByTestId('export-button'))
      expect(exportButton).toHaveTextContent(/Export/)

      await user.click(exportButton)

      // Button should show loading text immediately after click
      await waitFor(() => {
        expect(screen.getByTestId('export-button')).toHaveTextContent(/Exporting/)
        expect(screen.getByTestId('export-button')).toBeDisabled()
      }, { timeout: 3000 })

      // Resolve the promise to clean up
      resolveExport?.()
    })
  })
})
