/**
 * Tests for the subproject hierarchy UI (WIKI4AI-31):
 * - Dashboard: nested tree rendering, navigation from rows, collapse toggle
 * - ProjectDetail: full breadcrumb chain, subproject creation with parentId
 * - Breadcrumb component: ancestor chain rendering
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import Dashboard from '../pages/Dashboard'
import ProjectDetail from '../pages/ProjectDetail'
import Breadcrumb from '../components/Breadcrumb'
import { useProjects } from '../hooks/useProjects'
import { useDocuments, useSearchDocuments } from '../hooks/useDocuments'

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

/** Route probe that shows which project detail page we navigated to. */
const DetailProbe = () => {
  const { slug } = useParams<{ slug: string }>()
  return <div>PROBE-{slug}</div>
}

function renderWithProviders(ui: React.ReactElement, initialEntries: string[] = ['/']) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: 0 },
    },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={initialEntries}>
        {ui}
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const mockUseProjects = (projects: any[], overrides: Record<string, unknown> = {}) => {
  vi.mocked(useProjects).mockReturnValue({
    projects,
    isLoading: false,
    error: null,
    refetch: vi.fn(),
    createProject: vi.fn().mockResolvedValue(undefined),
    updateProject: vi.fn().mockResolvedValue(undefined),
    deleteProject: vi.fn().mockResolvedValue(undefined),
    isCreating: false,
    isUpdating: false,
    isDeleting: false,
    ...overrides,
  } as any)
}

const mockUseDocuments = () => {
  vi.mocked(useDocuments).mockReturnValue({
    documents: [],
    isLoading: false,
    error: null,
    refetch: vi.fn(),
    createDocument: vi.fn().mockResolvedValue(undefined),
    updateDocument: vi.fn().mockResolvedValue(undefined),
    deleteDocument: vi.fn().mockResolvedValue(undefined),
    isCreating: false,
    isUpdating: false,
    isDeleting: false,
    uploadDocument: vi.fn(),
    isUploading: false,
  } as any)
  vi.mocked(useSearchDocuments).mockReturnValue({
    searchResults: [],
    isLoading: false,
    hasSearched: false,
  } as any)
}


// Hierarchy fixture: root -> child -> grandchild (depths 1..3)
const hierarchyProjects = [
  { id: 1, name: 'Root Project', slug: 'root-project', description: null, documentCount: 2, createdAt: '', updatedAt: '', parentSlug: null, depth: 1 },
  { id: 2, name: 'Child Project', slug: 'child-project', description: null, documentCount: 1, createdAt: '', updatedAt: '', parentSlug: 'root-project', depth: 2 },
  { id: 3, name: 'Grandchild Project', slug: 'grandchild-project', description: null, documentCount: 0, createdAt: '', updatedAt: '', parentSlug: 'child-project', depth: 3 },
]

describe('Dashboard subproject tree (WIKI4AI-31)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders subprojects as nested rows under their parent card', async () => {
    mockUseProjects(hierarchyProjects)

    renderWithProviders(<Dashboard />)

    await waitFor(() => {
      expect(screen.getByText('Root Project')).toBeInTheDocument()
    })

    // Subprojects appear inside the root card's tree, not as top-level cards
    const childRow = screen.getByTestId('subproject-item-child-project')
    expect(childRow).toBeInTheDocument()
    expect(screen.getByTestId('subproject-item-grandchild-project')).toBeInTheDocument()

    // Grandchild is nested one level deeper (indented) than the child
    const marginPx = (el: HTMLElement) => parseInt(el.style.marginLeft || '0', 10)
    const grandchildRow = screen.getByTestId('subproject-item-grandchild-project')
    expect(marginPx(grandchildRow)).toBeGreaterThan(marginPx(childRow))
  })

  it('navigates to the subproject when a tree row is clicked', async () => {
    mockUseProjects(hierarchyProjects)

    renderWithProviders(
      <Routes>
        <Route path="/" element={<Dashboard />} />
        <Route path="/projects/:slug" element={<DetailProbe />} />
      </Routes>,
    )

    await waitFor(() => {
      expect(screen.getByTestId('subproject-item-child-project')).toBeInTheDocument()
    })

    const user = userEvent.setup()
    await user.click(screen.getByTestId('subproject-item-child-project'))

    await waitFor(() => {
      expect(screen.getByText('PROBE-child-project')).toBeInTheDocument()
    })
  })

  it('collapses and expands a subproject branch via the toggle', async () => {
    mockUseProjects(hierarchyProjects)

    renderWithProviders(<Dashboard />)

    const user = userEvent.setup()
    await waitFor(() => {
      expect(screen.getByTestId('subproject-toggle-child-project')).toBeInTheDocument()
    })

    // Grandchild visible initially (expanded by default)
    expect(screen.getByTestId('subproject-item-grandchild-project')).toBeInTheDocument()

    // Collapse the child branch
    await user.click(screen.getByTestId('subproject-toggle-child-project'))
    expect(screen.queryByTestId('subproject-item-grandchild-project')).not.toBeInTheDocument()

    // Expand again
    await user.click(screen.getByTestId('subproject-toggle-child-project'))
    await waitFor(() => {
      expect(screen.getByTestId('subproject-item-grandchild-project')).toBeInTheDocument()
    })
  })

  it('renders flat cards for projects without hierarchy (back-compat)', async () => {
    const flatProjects = [
      { id: 1, name: 'Alpha', slug: 'alpha', description: null, documentCount: 0, createdAt: '', updatedAt: '' },
      { id: 2, name: 'Beta', slug: 'beta', description: null, documentCount: 0, createdAt: '', updatedAt: '' },
    ]
    mockUseProjects(flatProjects)

    renderWithProviders(<Dashboard />)

    await waitFor(() => {
      expect(screen.getByText('Alpha')).toBeInTheDocument()
      expect(screen.getByText('Beta')).toBeInTheDocument()
    })
    expect(screen.queryByTestId(/subproject-item-/)).not.toBeInTheDocument()
  })
})

describe('ProjectDetail hierarchy (WIKI4AI-31)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows the full breadcrumb chain for a nested project', async () => {
    mockUseProjects(hierarchyProjects)
    mockUseDocuments()

    renderWithProviders(
      <Routes>
        <Route path="/" element={<div>HOME</div>} />
        <Route path="/projects/:slug" element={<ProjectDetail />} />
      </Routes>,
      ['/projects/grandchild-project'],
    )

    await waitFor(() => {
      expect(screen.getByTestId('project-breadcrumb')).toBeInTheDocument()
    })

    // Chain: Dashboard -> Root Project -> Child Project -> Grandchild Project
    const breadcrumb = screen.getByTestId('project-breadcrumb')
    expect(breadcrumb).toHaveTextContent(/Dashboard/)
    expect(breadcrumb).toHaveTextContent(/Root Project/)
    expect(breadcrumb).toHaveTextContent(/Child Project/)
    expect(breadcrumb).toHaveTextContent(/Grandchild Project/)

    // Ancestor links point at the parent projects
    expect(screen.getByTestId('breadcrumb-link-root-project')).toHaveAttribute('href', '/projects/root-project')
    expect(screen.getByTestId('breadcrumb-link-child-project')).toHaveAttribute('href', '/projects/child-project')
  })

  it('navigates to the parent when an ancestor breadcrumb link is clicked', async () => {
    mockUseProjects(hierarchyProjects)
    mockUseDocuments()

    renderWithProviders(
      <Routes>
        <Route path="/" element={<div>HOME</div>} />
        <Route path="/projects/:slug" element={<ProjectDetail />} />
      </Routes>,
      ['/projects/grandchild-project'],
    )

    const user = userEvent.setup()
    await waitFor(() => {
      expect(screen.getByTestId('breadcrumb-link-root-project')).toBeInTheDocument()
    })

    await user.click(screen.getByTestId('breadcrumb-link-root-project'))

    // The root project's own breadcrumb shows no ancestors (only Dashboard -> name)
    await waitFor(() => {
      const breadcrumb = screen.getByTestId('project-breadcrumb')
      expect(breadcrumb).toHaveTextContent(/Root Project/)
      expect(screen.queryByTestId('breadcrumb-link-child-project')).not.toBeInTheDocument()
    })
  })

  it('creates a subproject with parentId when using the inline form', async () => {
    const createProject = vi.fn().mockResolvedValue(undefined)
    mockUseProjects(hierarchyProjects, { createProject })
    mockUseDocuments()

    renderWithProviders(
      <Routes>
        <Route path="/" element={<div>HOME</div>} />
        <Route path="/projects/:slug" element={<ProjectDetail />} />
      </Routes>,
      ['/projects/root-project'],
    )

    const user = userEvent.setup()
    await waitFor(() => {
      expect(screen.getByTestId('create-subproject-button')).toBeInTheDocument()
    })

    await user.click(screen.getByTestId('create-subproject-button'))
    await user.type(screen.getByTestId('subproject-name-input'), 'New Sub')
    // WIKI4AI-73: EN default catalog
    await user.click(screen.getByRole('button', { name: /Create Subproject/ }))

    await waitFor(() => {
      expect(createProject).toHaveBeenCalledWith({ name: 'New Sub', parentId: 1 })
    })
  })

  it('lists direct subprojects with links in the subprojects section', async () => {
    mockUseProjects(hierarchyProjects)
    mockUseDocuments()

    renderWithProviders(
      <Routes>
        <Route path="/" element={<div>HOME</div>} />
        <Route path="/projects/:slug" element={<ProjectDetail />} />
      </Routes>,
      ['/projects/root-project'],
    )

    await waitFor(() => {
      expect(screen.getByTestId('subproject-entry-child-project')).toBeInTheDocument()
    })

    // Only the direct child is listed, not the grandchild
    expect(screen.queryByTestId('subproject-entry-grandchild-project')).not.toBeInTheDocument()
  })
})

describe('ProjectDetail cold load (hook order regression)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('survives loading -> loaded re-render without React hook-order errors', async () => {
    // Simulates a direct URL / hard refresh: first render sees an empty,
    // still-loading projects query; the second render has data. All hooks must
    // be called in the same order on both renders (early returns live after
    // every hook call in ProjectDetail).
    const loadingState: Record<string, unknown> = {
      projects: [], isLoading: true, error: null, refetch: vi.fn(),
      createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(),
      isCreating: false, isUpdating: false, isDeleting: false,
    }
    const loadedState: Record<string, unknown> = {
      projects: hierarchyProjects, isLoading: false, error: null, refetch: vi.fn(),
      createProject: vi.fn(), updateProject: vi.fn(), deleteProject: vi.fn(),
      isCreating: false, isUpdating: false, isDeleting: false,
    }
    vi.mocked(useProjects).mockReturnValue(loadingState as any)
    mockUseDocuments()

    // Wrapper with a changing prop forces ProjectDetail to re-render.
    function Shell({ _phase }: { _phase: number }) {
      void _phase; // changing this prop forces a re-render
      return (
        <Routes>
          <Route path="/" element={<div>HOME</div>} />
          <Route path="/projects/:slug" element={<ProjectDetail />} />
        </Routes>
      )
    }
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 0 } } })
    const { rerender } = render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/projects/root-project']}>
          <Shell _phase={1} />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    expect(screen.getByText(/Loading/)).toBeInTheDocument()

    // Data arrives — re-render must not throw (previously: React error #310)
    vi.mocked(useProjects).mockReturnValue(loadedState as any)
    rerender(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/projects/root-project']}>
          <Shell _phase={2} />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    await waitFor(() => {
      expect(screen.getByTestId('project-breadcrumb')).toBeInTheDocument()
    })
  })
})

describe('Breadcrumb ancestors (WIKI4AI-31)', () => {
  it('renders the ancestor chain when provided', () => {
    renderWithProviders(
      <Breadcrumb
        projectSlug="deep-doc-project"
        documentTitle="My Doc"
        ancestors={[
          { slug: 'root-a', name: 'Root A' },
          { slug: 'mid-b', name: 'Mid B' },
        ]}
      />,
    )

    expect(screen.getByTestId('breadcrumb-ancestor-root-a')).toHaveAttribute('href', '/projects/root-a')
    expect(screen.getByTestId('breadcrumb-ancestor-mid-b')).toHaveAttribute('href', '/projects/mid-b')
    // Chain order: Dashboard -> Root A -> Mid B -> deep-doc-project -> My Doc
    const nav = screen.getByRole('navigation', { name: 'Breadcrumb' })
    expect(nav.textContent).toContain('Root A')
    expect(nav.textContent?.indexOf('Root A')).toBeLessThan(nav.textContent?.indexOf('Mid B'))
  })

  it('renders the legacy single-project breadcrumb when ancestors are omitted', () => {
    renderWithProviders(<Breadcrumb projectSlug="plain" documentTitle="Doc" />)

    expect(screen.queryByTestId(/breadcrumb-ancestor-/)).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'plain' })).toHaveAttribute('href', '/projects/plain')
  })
})
