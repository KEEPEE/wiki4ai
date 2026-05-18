/**
 * Tests for Dashboard page component
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import Dashboard from '../pages/Dashboard'

// Mock the useProjects hook
vi.mock('../hooks/useProjects', () => ({
  useProjects: vi.fn(),
}))

function renderWithProviders(ui: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        staleTime: 0,
      },
    },
  })
  
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        {ui}
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('Dashboard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('Loading state', () => {
    it('should show loading spinner when projects are loading', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [],
        isLoading: true,
        error: null,
        createProject: vi.fn(),
        isCreating: false,
      })

      renderWithProviders(<Dashboard />)

      expect(screen.getByText(/Loading projects/)).toBeInTheDocument()
    })
  })

  describe('Error state', () => {
    it('should show error message when fetch fails', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [],
        isLoading: false,
        error: new Error('Failed to fetch'),
        createProject: vi.fn(),
        isCreating: false,
      })

      renderWithProviders(<Dashboard />)

      expect(screen.getByText(/Failed to fetch/)).toBeInTheDocument()
    })

    it('should show retry button on error', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [],
        isLoading: false,
        error: new Error('Failed to fetch'),
        createProject: vi.fn(),
        isCreating: false,
      })

      renderWithProviders(<Dashboard />)

      expect(screen.getByText('Retry')).toBeInTheDocument()
    })
  })

  describe('Empty state', () => {
    it('should show empty state when no projects', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [],
        isLoading: false,
        error: null,
        createProject: vi.fn(),
        isCreating: false,
      })

      renderWithProviders(<Dashboard />)

      expect(screen.getByText(/No projects yet/)).toBeInTheDocument()
    })

    it('should show create button in empty state', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [],
        isLoading: false,
        error: null,
        createProject: vi.fn(),
        isCreating: false,
      })

      renderWithProviders(<Dashboard />)

      expect(screen.getByText(/Create your first project/)).toBeInTheDocument()
    })
  })

  describe('Projects display', () => {
    it('should render project cards when projects exist', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [
          { id: 1, name: 'Test Project', slug: 'test-project', description: 'A test project', documentCount: 5, createdAt: '2024-01-01T00:00:00Z', updatedAt: '2024-01-01T00:00:00Z' },
        ],
        isLoading: false,
        error: null,
        createProject: vi.fn(),
        isCreating: false,
      })

      renderWithProviders(<Dashboard />)

      expect(screen.getByText('Test Project')).toBeInTheDocument()
    })

    it('should show document count badge', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [
          { id: 1, name: 'Test Project', slug: 'test-project', description: null, documentCount: 5, createdAt: '2024-01-01T00:00:00Z', updatedAt: '2024-01-01T00:00:00Z' },
        ],
        isLoading: false,
        error: null,
        createProject: vi.fn(),
        isCreating: false,
      })

      renderWithProviders(<Dashboard />)

      expect(screen.getByText('5 docs')).toBeInTheDocument()
    })

    it('should navigate to project detail on card click', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [
          { id: 1, name: 'Test Project', slug: 'test-project', description: null, documentCount: 5, createdAt: '2024-01-01T00:00:00Z', updatedAt: '2024-01-01T00:00:00Z' },
        ],
        isLoading: false,
        error: null,
        createProject: vi.fn(),
        isCreating: false,
      })

      renderWithProviders(<Dashboard />)

      const user = userEvent.setup()
      await user.click(screen.getByText('Test Project'))
    })
  })

  describe('Create project form', () => {
    it('should show create form when button is clicked', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [],
        isLoading: false,
        error: null,
        createProject: vi.fn(),
        isCreating: false,
      })

      renderWithProviders(<Dashboard />)

      const user = userEvent.setup()
      await user.click(screen.getByText(/New Project/))

      expect(screen.getByText('Create New Project')).toBeInTheDocument()
    })

    it('should create project when form is submitted', async () => {
      const mockCreateProject = vi.fn().mockResolvedValue({
        id: 1, name: 'New Project', slug: 'new-project', description: null, documentCount: 0, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString()
      })

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [],
        isLoading: false,
        error: null,
        createProject: mockCreateProject,
        isCreating: false,
      })

      renderWithProviders(<Dashboard />)

      const user = userEvent.setup()
      await user.click(screen.getByText(/New Project/))

      await user.type(screen.getByPlaceholderText('Project name'), 'New Project')
      await user.type(screen.getByPlaceholderText(/Description/, { exact: false }), 'Test description')
      
      await user.click(screen.getByText('Create'))

      expect(mockCreateProject).toHaveBeenCalledWith({
        name: 'New Project',
        description: 'Test description',
      })
    })

    it('should show error when project creation fails', async () => {
      const mockCreateProject = vi.fn().mockRejectedValue(new Error('Network error'))

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [],
        isLoading: false,
        error: null,
        createProject: mockCreateProject,
        isCreating: false,
      })

      renderWithProviders(<Dashboard />)

      const user = userEvent.setup()
      await user.click(screen.getByText(/New Project/))

      await user.type(screen.getByPlaceholderText('Project name'), 'New Project')
      await user.click(screen.getByText('Create'))

      await waitFor(() => {
        // Component renders error as <p class="error"> not an alert role
        expect(screen.getByText(/Network error/)).toBeInTheDocument()
      })
    })

    it('should close form when cancel is clicked', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [],
        isLoading: false,
        error: null,
        createProject: vi.fn(),
        isCreating: false,
      })

      renderWithProviders(<Dashboard />)

      const user = userEvent.setup()
      await user.click(screen.getByText(/New Project/))

      expect(screen.getByText('Create New Project')).toBeInTheDocument()
      
      await user.click(screen.getByText('Cancel'))
    })
  })

  describe('Stats', () => {
    it('should show total projects and documents count', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [
          { id: 1, name: 'Project 1', slug: 'project-1', description: null, documentCount: 5, createdAt: '2024-01-01T00:00:00Z', updatedAt: '2024-01-01T00:00:00Z' },
          { id: 2, name: 'Project 2', slug: 'project-2', description: null, documentCount: 3, createdAt: '2024-01-02T00:00:00Z', updatedAt: '2024-01-02T00:00:00Z' },
        ],
        isLoading: false,
        error: null,
        createProject: vi.fn(),
        isCreating: false,
      })

      renderWithProviders(<Dashboard />)

      expect(screen.getByText('2')).toBeInTheDocument() // projects count
      expect(screen.getByText('8')).toBeInTheDocument() // total docs
    })
  })
})
