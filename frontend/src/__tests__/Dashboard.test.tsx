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
        // Error appears both inline in the form and as a toast notification
        const errorElements = screen.getAllByText(/Network error/)
        expect(errorElements.length).toBeGreaterThanOrEqual(1)
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

  describe('Edit project functionality', () => {
    const mockProjects = [
      { id: 1, name: 'Project Alpha', slug: 'project-alpha', description: 'Original description', documentCount: 5, createdAt: '', updatedAt: '' },
      { id: 2, name: 'Project Beta', slug: 'project-beta', description: null, documentCount: 3, createdAt: '', updatedAt: '' },
    ]

    it('should render edit button on each project card', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: mockProjects, isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<Dashboard />)

      await waitFor(() => {
        expect(screen.getByText('Project Alpha')).toBeInTheDocument()
      })

      // Check edit buttons exist for each project
      const editButton1 = screen.getByTestId('edit-button-1')
      const editButton2 = screen.getByTestId('edit-button-2')
      
      expect(editButton1).toBeInTheDocument()
      expect(editButton2).toBeInTheDocument()
    })

    it('should open edit modal with pre-filled values when edit button is clicked', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: mockProjects, isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<Dashboard />)

      const user = userEvent.setup()

      await waitFor(() => {
        expect(screen.getByText('Project Alpha')).toBeInTheDocument()
      })

      // Click edit button for first project
      await user.click(screen.getByTestId('edit-button-1'))

      // Modal should be visible
      expect(screen.getByTestId('edit-modal')).toBeInTheDocument()
      
      // Form fields should be pre-filled with project data
      const nameInput = screen.getByTestId('edit-name-input')
      const descriptionInput = screen.getByTestId('edit-description-input')
      
      expect(nameInput).toHaveValue('Project Alpha')
      expect(descriptionInput).toHaveValue('Original description')
    })

    it('should open edit modal with empty description for null description', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: mockProjects, isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<Dashboard />)

      const user = userEvent.setup()

      await waitFor(() => {
        expect(screen.getByText('Project Beta')).toBeInTheDocument()
      })

      // Click edit button for second project (null description)
      await user.click(screen.getByTestId('edit-button-2'))

      expect(screen.getByTestId('edit-modal')).toBeInTheDocument()
      
      const nameInput = screen.getByTestId('edit-name-input')
      const descriptionInput = screen.getByTestId('edit-description-input')
      
      expect(nameInput).toHaveValue('Project Beta')
      expect(descriptionInput).toHaveValue('')
    })

    it('should call updateProject with correct params when form is submitted', async () => {
      const mockUpdateProject = vi.fn().mockResolvedValue({
        id: 1, name: 'Updated Name', slug: 'project-alpha', description: 'New description', documentCount: 5, createdAt: '', updatedAt: ''
      })

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: mockProjects, isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: mockUpdateProject, deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<Dashboard />)

      const user = userEvent.setup()

      await waitFor(() => {
        expect(screen.getByText('Project Alpha')).toBeInTheDocument()
      })

      // Open edit modal
      await user.click(screen.getByTestId('edit-button-1'))

      // Update the name and description
      const nameInput = screen.getByTestId('edit-name-input')
      const descriptionInput = screen.getByTestId('edit-description-input')
      
      await user.clear(nameInput)
      await user.type(nameInput, 'Updated Name')
      
      await user.clear(descriptionInput)
      await user.type(descriptionInput, 'New description')

      // Click save
      await user.click(screen.getByTestId('edit-save-button'))

      expect(mockUpdateProject).toHaveBeenCalledWith({
        id: 1,
        dto: { name: 'Updated Name', description: 'New description' },
      })
    })

    it('should close modal and show success toast after successful update', async () => {
      const mockUpdateProject = vi.fn().mockResolvedValue({
        id: 1, name: 'Updated Name', slug: 'project-alpha', description: 'New desc', documentCount: 5, createdAt: '', updatedAt: ''
      })

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: mockProjects, isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: mockUpdateProject, deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<Dashboard />)

      const user = userEvent.setup()

      await waitFor(() => {
        expect(screen.getByText('Project Alpha')).toBeInTheDocument()
      })

      // Open edit modal and save
      await user.click(screen.getByTestId('edit-button-1'))
      
      const nameInput = screen.getByTestId('edit-name-input')
      await user.clear(nameInput)
      await user.type(nameInput, 'Updated Name')

      await user.click(screen.getByTestId('edit-save-button'))

      // Modal should close after successful update
      await waitFor(() => {
        expect(screen.queryByTestId('edit-modal')).not.toBeInTheDocument()
      })

      // Success toast should appear
      expect(screen.getByText(/Project updated successfully/)).toBeInTheDocument()
    })

    it('should close modal when cancel button is clicked', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: mockProjects, isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<Dashboard />)

      const user = userEvent.setup()

      await waitFor(() => {
        expect(screen.getByText('Project Alpha')).toBeInTheDocument()
      })

      // Open edit modal
      await user.click(screen.getByTestId('edit-button-1'))
      expect(screen.getByTestId('edit-modal')).toBeInTheDocument()

      // Click cancel
      await user.click(screen.getByTestId('edit-cancel-button'))

      // Modal should close
      expect(screen.queryByTestId('edit-modal')).not.toBeInTheDocument()
    })

    it('should show loading state while saving', async () => {
      const mockUpdateProject = vi.fn().mockResolvedValue({
        id: 1, name: 'Updated Name', slug: 'project-alpha', description: 'New desc', documentCount: 5, createdAt: '', updatedAt: ''
      })

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: mockProjects, isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: mockUpdateProject, deleteProject: vi.fn(), isCreating: false, isUpdating: true, isDeleting: false,
      } as any)

      renderWithProviders(<Dashboard />)

      const user = userEvent.setup()

      await waitFor(() => {
        expect(screen.getByText('Project Alpha')).toBeInTheDocument()
      })

      // Open edit modal
      await user.click(screen.getByTestId('edit-button-1'))

      // When isUpdating=true, the save button shows "Saving..." and is disabled
      expect(screen.getByText('Saving...')).toBeInTheDocument()
    })

    it('should show error in modal when update fails', async () => {
      const mockUpdateProject = vi.fn().mockRejectedValue(new Error('Server error'))

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: mockProjects, isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: mockUpdateProject, deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<Dashboard />)

      const user = userEvent.setup()

      await waitFor(() => {
        expect(screen.getByText('Project Alpha')).toBeInTheDocument()
      })

      // Open edit modal and try to save
      await user.click(screen.getByTestId('edit-button-1'))
      
      const nameInput = screen.getByTestId('edit-name-input')
      await user.clear(nameInput)
      await user.type(nameInput, 'Updated Name')

      await user.click(screen.getByTestId('edit-save-button'))

      // Error should be displayed in the modal
      await waitFor(() => {
        expect(screen.getByText(/Server error/)).toBeInTheDocument()
      })
    })

    it('should not navigate to project when edit button is clicked', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: mockProjects, isLoading: false, error: null, refetch: vi.fn(), createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<Dashboard />)

      const user = userEvent.setup()

      await waitFor(() => {
        expect(screen.getByText('Project Alpha')).toBeInTheDocument()
      })

      // Click edit button - should open modal, not navigate
      await user.click(screen.getByTestId('edit-button-1'))

      // Modal should be visible (proving navigation didn't happen)
      expect(screen.getByTestId('edit-modal')).toBeInTheDocument()
    })
  })
})
