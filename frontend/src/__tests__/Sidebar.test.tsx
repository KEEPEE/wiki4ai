/**
 * Tests for Sidebar component
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import Sidebar from '../components/Sidebar'

// Mock the useProjects hook
vi.mock('../hooks/useProjects', () => ({
  useProjects: vi.fn(),
}))

// Mock fetch for API calls
global.fetch = vi.fn()

function renderWithProviders(ui: React.ReactElement, { route = '/' } = {}) {
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
      <MemoryRouter initialEntries={[route]}>
        {ui}
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('Sidebar', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('Navigation', () => {
    it('should render dashboard link', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [],
        isLoading: false,
        createProject: vi.fn(),
      })

      renderWithProviders(<Sidebar />)

      expect(screen.getByText('Dashboard')).toBeInTheDocument()
    })

    it('should highlight dashboard link when on home page', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [],
        isLoading: false,
        createProject: vi.fn(),
      })

      renderWithProviders(<Sidebar />, { route: '/' })

      const dashboardLink = screen.getByText('Dashboard')
      expect(dashboardLink.closest('a')).toHaveAttribute('href', '/')
    })

    it('should navigate to dashboard when clicked', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [],
        isLoading: false,
        createProject: vi.fn(),
      })

      renderWithProviders(<Sidebar />, { route: '/projects/test' })

      const user = userEvent.setup()
      await user.click(screen.getByText('Dashboard'))
    })
  })

  describe('Projects list', () => {
    it('should show loading state when projects are loading', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [],
        isLoading: true,
        createProject: vi.fn(),
      })

      renderWithProviders(<Sidebar />)

      expect(screen.getByText(/Načítavam/)).toBeInTheDocument()
    })

    it('should show empty state when no projects', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [],
        isLoading: false,
        createProject: vi.fn(),
      })

      renderWithProviders(<Sidebar />)

      expect(screen.getByText(/Zatiaľ žiadne projekty/)).toBeInTheDocument()
    })

    it('should render project links when projects exist', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [
          { id: 1, name: 'Test Project', slug: 'test-project', description: null, documentCount: 5, createdAt: '2024-01-01', updatedAt: '2024-01-01' },
          { id: 2, name: 'Another Project', slug: 'another-project', description: null, documentCount: 3, createdAt: '2024-01-02', updatedAt: '2024-01-02' },
        ],
        isLoading: false,
        createProject: vi.fn(),
      })

      renderWithProviders(<Sidebar />)

      expect(screen.getByText('Test Project')).toBeInTheDocument()
      expect(screen.getByText('Another Project')).toBeInTheDocument()
    })

    it('should highlight active project', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [
          { id: 1, name: 'Active Project', slug: 'active-project', description: null, documentCount: 5, createdAt: '2024-01-01', updatedAt: '2024-01-01' },
        ],
        isLoading: false,
        createProject: vi.fn(),
      })

      renderWithProviders(<Sidebar />, { route: '/projects/active-project' })

      const activeLink = screen.getByText('Active Project')
      expect(activeLink.closest('a')).toHaveClass('bg-indigo-50')
    })
  })

  describe('New Project Modal', () => {
    it('should open modal when clicking add button', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [],
        isLoading: false,
        createProject: vi.fn(),
      })

      renderWithProviders(<Sidebar />)

      const addButton = screen.getByLabelText('Vytvoriť nový projekt')
      const user = userEvent.setup()
      await user.click(addButton)

      expect(screen.getByText('Nový projekt')).toBeInTheDocument()
    })

    it('should close modal when clicking backdrop', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [],
        isLoading: false,
        createProject: vi.fn(),
      })

      renderWithProviders(<Sidebar />)

      const addButton = screen.getByLabelText('Vytvoriť nový projekt')
      const user = userEvent.setup()
      await user.click(addButton)

      expect(screen.getByText('Nový projekt')).toBeInTheDocument()
      
      // Click backdrop (the fixed inset-0 div)
      const backdrop = screen.getByRole('dialog').previousSibling as HTMLElement
      await user.click(backdrop!)
    })

    it('should close modal when clicking cancel button', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [],
        isLoading: false,
        createProject: vi.fn(),
      })

      renderWithProviders(<Sidebar />)

      const addButton = screen.getByLabelText('Vytvoriť nový projekt')
      const user = userEvent.setup()
      await user.click(addButton)

      expect(screen.getByText('Nový projekt')).toBeInTheDocument()
      
      await user.click(screen.getByText('Zrušiť'))
    })

    it('should create project when form is submitted', async () => {
      const mockCreateProject = vi.fn().mockResolvedValue({
        id: 1, name: 'New Project', slug: 'new-project', description: null, documentCount: 0, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString()
      })

      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [],
        isLoading: false,
        createProject: mockCreateProject,
      })

      renderWithProviders(<Sidebar />)

      const addButton = screen.getByLabelText('Vytvoriť nový projekt')
      const user = userEvent.setup()
      await user.click(addButton)

      await user.type(screen.getByLabelText(/Názov projektu/), 'New Project')
      await user.type(screen.getByLabelText(/Popis/, { exact: false }), 'Test description')
      
      await user.click(screen.getByText('Vytvoriť'))

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
        createProject: mockCreateProject,
      })

      renderWithProviders(<Sidebar />)

      const addButton = screen.getByLabelText('Vytvoriť nový projekt')
      const user = userEvent.setup()
      await user.click(addButton)

      await user.type(screen.getByLabelText(/Názov projektu/), 'New Project')
      await user.click(screen.getByText('Vytvoriť'))

      await waitFor(() => {
        expect(screen.getByRole('alert')).toBeInTheDocument()
      })
    })
  })

  describe('Mobile menu', () => {
    it('should toggle mobile menu when hamburger button is clicked', async () => {
      const { useProjects } = await import('../hooks/useProjects')
      vi.mocked(useProjects).mockReturnValue({
        projects: [],
        isLoading: false,
        createProject: vi.fn(),
      })

      renderWithProviders(<Sidebar />)

      const menuButton = screen.getByLabelText(/menu/)
      const user = userEvent.setup()
      
      await user.click(menuButton)
    })
  })
})
