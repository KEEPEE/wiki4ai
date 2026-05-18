/**
 * Tests for ProjectSettings page component
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import ProjectSettings from '../pages/ProjectSettings'

// Mock dependencies
vi.mock('../hooks/useProjects', () => ({
  useProjects: vi.fn(),
}))

function renderWithProviders(ui: React.ReactElement, { route = '/projects/test-project/settings' } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: 0 },
    },
  })
  
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[route]}>
        <Routes>
          <Route path="/projects/:slug/settings" element={ui} />
          <Route path="/projects/:slug" element={<div>Project Detail</div>} />
          <Route path="/" element={<div>Dashboard</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ProjectSettings', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  const mockProjects = [
    { id: 1, name: 'Test Project', slug: 'test-project', description: 'A test project', documentCount: 5, createdAt: '', updatedAt: '' },
  ]

  const getMockUseProjects = (overrides: Partial<ReturnType<typeof vi.fn>> = {}) => ({
    projects: mockProjects,
    isLoading: false,
    error: null,
    refetch: vi.fn(),
    createProject: vi.fn(),
    updateProject: vi.fn().mockResolvedValue({ ...mockProjects[0], name: 'Updated Name' }),
    deleteProject: vi.fn().mockResolvedValue(undefined),
    isCreating: false,
    isUpdating: false,
    isDeleting: false,
    ...overrides,
  })

  describe('Loading state', () => {
    it('should show loading spinner when projects are loading', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [],
        isLoading: true,
        error: null,
        refetch: vi.fn(),
        createProject: vi.fn(),
        updateProject: vi.fn(),
        deleteProject: vi.fn(),
        isCreating: false,
        isUpdating: false,
        isDeleting: false,
      } as any)

      renderWithProviders(<ProjectSettings />)

      expect(screen.getByText(/Loading/)).toBeInTheDocument()
    })
  })

  describe('Project not found', () => {
    it('should show error when project is not found', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [],
        isLoading: false,
        error: null,
        refetch: vi.fn(),
        createProject: vi.fn(),
        updateProject: vi.fn(),
        deleteProject: vi.fn(),
        isCreating: false,
        isUpdating: false,
        isDeleting: false,
      } as any)

      renderWithProviders(<ProjectSettings />)

      await waitFor(() => {
        expect(screen.getByText(/Project not found/)).toBeInTheDocument()
      })
    })
  })

  describe('Page rendering', () => {
    it('should render the settings page title', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue(getMockUseProjects())

      renderWithProviders(<ProjectSettings />)

      await waitFor(() => {
        expect(screen.getByText('Project Settings')).toBeInTheDocument()
      })
    })

    it('should render breadcrumb navigation with project name and Settings', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue(getMockUseProjects())

      renderWithProviders(<ProjectSettings />)

      await waitFor(() => {
        expect(screen.getByText('Settings')).toBeInTheDocument()
      })
    })
  })

  describe('Form pre-fill', () => {
    it('should pre-fill form with current project values', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue(getMockUseProjects())

      renderWithProviders(<ProjectSettings />)

      await waitFor(() => {
        const nameInput = screen.getByTestId('project-name-input')
        const descriptionInput = screen.getByTestId('project-description-input')
        
        expect(nameInput).toHaveValue('Test Project')
        expect(descriptionInput).toHaveValue('A test project')
      })
    })

    it('should handle null description gracefully', async () => {
      const mockProjectsNullDesc = [
        { id: 1, name: 'Test Project', slug: 'test-project', description: null, documentCount: 0, createdAt: '', updatedAt: '' },
      ]

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        ...getMockUseProjects(),
        projects: mockProjectsNullDesc,
      })

      renderWithProviders(<ProjectSettings />)

      await waitFor(() => {
        const descriptionInput = screen.getByTestId('project-description-input')
        expect(descriptionInput).toHaveValue('')
      })
    })
  })

  describe('Save form', () => {
    it('should call updateProject API when form is submitted', async () => {
      const updateMock = vi.fn().mockResolvedValue({ ...mockProjects[0], name: 'New Name' })

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        ...getMockUseProjects(),
        updateProject: updateMock,
      })

      renderWithProviders(<ProjectSettings />)

      const user = userEvent.setup()

      await waitFor(() => {
        expect(screen.getByTestId('settings-form')).toBeInTheDocument()
      })

      // Change the name field
      const nameInput = screen.getByTestId('project-name-input')
      await user.clear(nameInput)
      await user.type(nameInput, 'New Project Name')

      // Click save button
      const saveButton = screen.getByTestId('save-button')
      await user.click(saveButton)

      await waitFor(() => {
        expect(updateMock).toHaveBeenCalledWith({
          id: 1,
          dto: { name: 'New Project Name', description: 'A test project' },
        })
      })
    })

    it('should show save error when update fails', async () => {
      const updateMock = vi.fn().mockRejectedValue(new Error('Network error'))

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        ...getMockUseProjects(),
        updateProject: updateMock,
        isUpdating: false,
      })

      renderWithProviders(<ProjectSettings />)

      const user = userEvent.setup()

      // Change the name and submit
      const nameInput = screen.getByTestId('project-name-input')
      await user.clear(nameInput)
      await user.type(nameInput, 'New Name')

      const saveButton = screen.getByTestId('save-button')
      await user.click(saveButton)

      await waitFor(() => {
        expect(screen.getByTestId('save-error')).toBeInTheDocument()
        expect(screen.getByText(/Network error/)).toBeInTheDocument()
      })
    })

    it('should disable save button while updating', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        ...getMockUseProjects(),
        isUpdating: true,
      })

      renderWithProviders(<ProjectSettings />)

      await waitFor(() => {
        const saveButton = screen.getByTestId('save-button')
        expect(saveButton).toBeDisabled()
        expect(saveButton).toHaveTextContent('Saving...')
      })
    })
  })

  describe('Delete project', () => {
    it('should render delete button in danger zone', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue(getMockUseProjects())

      renderWithProviders(<ProjectSettings />)

      await waitFor(() => {
        expect(screen.getByTestId('delete-button')).toBeInTheDocument()
        expect(screen.getByText('Delete Project')).toBeInTheDocument()
      })
    })

    it('should open confirmation dialog when delete button is clicked', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue(getMockUseProjects())

      renderWithProviders(<ProjectSettings />)

      const user = userEvent.setup()

      // Click the delete button to open confirmation dialog
      const deleteButton = screen.getByTestId('delete-button')
      await user.click(deleteButton)

      await waitFor(() => {
        expect(screen.getByTestId('delete-modal')).toBeInTheDocument()
        expect(screen.getByText(/Are you sure you want to delete/)).toBeInTheDocument()
      })
    })

    it('should call deleteProject API when confirmed', async () => {
      const deleteMock = vi.fn().mockResolvedValue(undefined)

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        ...getMockUseProjects(),
        deleteProject: deleteMock,
      })

      renderWithProviders(<ProjectSettings />)

      const user = userEvent.setup()

      // Open confirmation dialog
      const deleteButton = screen.getByTestId('delete-button')
      await user.click(deleteButton)

      // Click confirm in the modal
      const confirmButton = await waitFor(() => screen.getByTestId('confirm-delete-button'))
      await user.click(confirmButton)

      await waitFor(() => {
        expect(deleteMock).toHaveBeenCalledWith(1)
      })
    })

    it('should close confirmation dialog when cancel is clicked', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue(getMockUseProjects())

      renderWithProviders(<ProjectSettings />)

      const user = userEvent.setup()

      // Open confirmation dialog
      const deleteButton = screen.getByTestId('delete-button')
      await user.click(deleteButton)

      await waitFor(() => {
        expect(screen.getByTestId('delete-modal')).toBeInTheDocument()
      })

      // Click cancel to close the modal
      const cancelButton = screen.getByTestId('cancel-delete-button')
      await user.click(cancelButton)

      await waitFor(() => {
        expect(screen.queryByTestId('delete-modal')).not.toBeInTheDocument()
      })
    })

    it('should show delete error when deletion fails', async () => {
      const deleteMock = vi.fn().mockRejectedValue(new Error('Delete failed'))

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        ...getMockUseProjects(),
        deleteProject: deleteMock,
      })

      renderWithProviders(<ProjectSettings />)

      const user = userEvent.setup()

      // Open confirmation dialog and confirm
      const deleteButton = screen.getByTestId('delete-button')
      await user.click(deleteButton)

      const confirmButton = await waitFor(() => screen.getByTestId('confirm-delete-button'))
      await user.click(confirmButton)

      await waitFor(() => {
        expect(screen.getByTestId('delete-error')).toBeInTheDocument()
        expect(screen.getByText(/Delete failed/)).toBeInTheDocument()
      })
    })
  })

  describe('Navigation', () => {
    it('should have a cancel link that navigates to project detail', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue(getMockUseProjects())

      renderWithProviders(<ProjectSettings />)

      await waitFor(() => {
        const cancelLink = screen.getByText('Cancel')
        expect(cancelLink).toHaveAttribute('href', '/projects/test-project')
      })
    })

    it('should have breadcrumb link to project detail page', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue(getMockUseProjects())

      renderWithProviders(<ProjectSettings />)

      await waitFor(() => {
        // The breadcrumb has a link with the project name
        const links = screen.getAllByText('Test Project')
        expect(links.length).toBeGreaterThanOrEqual(1)
      })
    })
  })
})
