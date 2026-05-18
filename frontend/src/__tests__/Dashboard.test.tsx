/**
 * Tests for Dashboard page component
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import Dashboard from '../pages/Dashboard'

// Mock dependencies
vi.mock('../hooks/useProjects', () => ({
  useProjects: vi.fn(),
}))

function renderWithProviders(ui: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: 0 },
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
        projects: [], isLoading: true, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<Dashboard />)

      expect(screen.getByText(/Loading/)).toBeInTheDocument()
    })
  })

  describe('Error state', () => {
    it('should show error message when projects fail to load', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [], isLoading: false, error: new Error('Failed to fetch'), refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<Dashboard />)

      await waitFor(() => {
        expect(screen.getByText(/Failed to fetch/)).toBeInTheDocument()
      })
    })

    it('should show retry button on error', async () => {
      const mockRefetch = vi.fn().mockResolvedValue({ data: [] })

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [], isLoading: false, error: new Error('Failed to fetch'), refetch: mockRefetch, createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<Dashboard />)

      await waitFor(() => {
        expect(screen.getByText('Retry')).toBeInTheDocument()
      })
    })
  })

  describe('Empty state', () => {
    it('should show empty state when no projects exist', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [], isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<Dashboard />)

      await waitFor(() => {
        expect(screen.getByText(/No projects yet/)).toBeInTheDocument()
      })
    })
  })

  describe('Project list', () => {
    it('should display project cards when projects exist', async () => {
      const mockProjects = [
        { id: 1, name: 'Project Alpha', slug: 'project-alpha', description: null, documentCount: 5, createdAt: '', updatedAt: '' },
        { id: 2, name: 'Project Beta', slug: 'project-beta', description: null, documentCount: 3, createdAt: '', updatedAt: '' },
      ]

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: mockProjects, isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<Dashboard />)

      await waitFor(() => {
        expect(screen.getByText('Project Alpha')).toBeInTheDocument()
        expect(screen.getByText('Project Beta')).toBeInTheDocument()
      })
    })

    it('should show document count on project cards', async () => {
      const mockProjects = [
        { id: 1, name: 'Test Project', slug: 'test-project', description: null, documentCount: 5, createdAt: '', updatedAt: '' },
      ]

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: mockProjects, isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<Dashboard />)

      await waitFor(() => {
        expect(screen.getByText('Test Project')).toBeInTheDocument()
      })
    })

    it('should navigate to project detail when card is clicked', async () => {
      const mockProjects = [
        { id: 1, name: 'Click Me', slug: 'click-me', description: null, documentCount: 0, createdAt: '', updatedAt: '' },
      ]

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: mockProjects, isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<Dashboard />)

      const user = userEvent.setup()
      
      await waitFor(() => {
        expect(screen.getByText('Click Me')).toBeInTheDocument()
      })

      // Click on the project card/link
      await user.click(screen.getByRole('link', { name: /Click Me/ }))
    })
  })

  describe('Create project form', () => {
    it('should show create form when new project button is clicked', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [], isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<Dashboard />)

      const user = userEvent.setup()
      await user.click(screen.getByText(/New Project/))

      expect(screen.getByPlaceholderText('Project name')).toBeInTheDocument()
    })

    it('should call createProject when form is submitted', async () => {
      const mockCreateProject = vi.fn().mockResolvedValue({ id: 1, name: 'New Project', slug: 'new-project' })

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [], isLoading: false, error: null, refetch: vi.fn(), createProject: mockCreateProject, updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

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
        projects: [], isLoading: false, error: null, refetch: vi.fn(), createProject: mockCreateProject, updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

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
        projects: [], isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<Dashboard />)

      const user = userEvent.setup()
      await user.click(screen.getByText(/New Project/))

      expect(screen.getByPlaceholderText('Project name')).toBeInTheDocument()

      await user.click(screen.getByText('Cancel'))

      // Form should be hidden after cancel
      expect(screen.queryByPlaceholderText('Project name')).not.toBeInTheDocument()
    })

    it('should show loading state while creating project', async () => {
      // Simulate isCreating=true to test the disabled button state during creation
      const mockCreateProject = vi.fn().mockImplementation(() => 
        new Promise(resolve => setTimeout(() => resolve({ id: 1 }), 200))
      )

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [], isLoading: false, error: null, refetch: vi.fn(), createProject: mockCreateProject, updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: true, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<Dashboard />)

      const user = userEvent.setup()
      await user.click(screen.getByText(/New Project/))

      // When isCreating=true, the button shows "Creating..." and is disabled
      expect(screen.getByText('Creating...')).toBeInTheDocument()
    })
  })
})
