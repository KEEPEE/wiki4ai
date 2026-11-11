/**
 * WIKI4AI-187: sort menu, server-side filters and drag&drop ordering.
 *
 * The list is ordered and filtered by the BACKEND, so the tests assert two
 * things at once: the parameters the page sends, and what it renders from the
 * response. Drag & drop is simulated with the real HTML5 event sequence
 * (dragstart on the handle → dragover on the target row → drop), because that
 * sequence — not a library call — is what the component has to get right.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import Dashboard from '../pages/Dashboard'
import ProjectDetail from '../pages/ProjectDetail'
import { PROJECT_SORT_STORAGE_KEY, DOCS_SORT_STORAGE_KEY } from '../utils/listSort'

// ── mocks ───────────────────────────────────────────────────────────────────
vi.mock('../hooks/useProjects', () => ({ useProjects: vi.fn() }))
vi.mock('../hooks/useDocuments', () => ({
  useDocuments: vi.fn(),
  useSearchDocuments: vi.fn(),
}))
vi.mock('../hooks/useEmbeddingStatus', () => ({
  useEmbeddingStatus: vi.fn(() => ({ data: undefined })),
}))
vi.mock('../services/projectApi', () => ({
  projectApi: {
    pinProject: vi.fn(),
    unpinProject: vi.fn(),
    reorderProject: vi.fn(),
    exportProject: vi.fn(),
  },
}))
vi.mock('../services/documentApi', () => ({
  documentApi: {
    pinDocument: vi.fn(),
    unpinDocument: vi.fn(),
    reorderDocument: vi.fn(),
  },
}))

// ── fixtures ────────────────────────────────────────────────────────────────
/** Three root projects in the manual order the backend would return. */
const projectRows = [
  { id: 1, slug: 'alpha', name: 'Alpha', description: null, documentCount: 1, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', visibility: 'public', pinned: false, sortOrder: 0 },
  { id: 2, slug: 'bravo', name: 'Bravo', description: null, documentCount: 2, createdAt: '2026-01-02T00:00:00Z', updatedAt: '2026-01-02T00:00:00Z', visibility: 'public', pinned: false, sortOrder: 1 },
  { id: 3, slug: 'charlie', name: 'Charlie', description: null, documentCount: 3, createdAt: '2026-01-03T00:00:00Z', updatedAt: '2026-01-03T00:00:00Z', visibility: 'private', pinned: false, sortOrder: 2 },
]

const docRows = [
  { id: 11, slug: 'onboarding-guide', title: 'Onboarding guide', content: '', projectId: 1, visibility: 'public', pinned: false, sortOrder: 0, createdAt: '', updatedAt: '2026-01-01T00:00:00Z' },
  { id: 12, slug: 'admin-roles', title: 'Admin roles', content: '', projectId: 1, visibility: 'public', pinned: false, sortOrder: 1, createdAt: '', updatedAt: '2026-01-02T00:00:00Z' },
  { id: 13, slug: 'runbook', title: 'Runbook', content: '', projectId: 1, visibility: 'public', pinned: false, sortOrder: 2, createdAt: '', updatedAt: '2026-01-03T00:00:00Z' },
]

function renderWithProviders(ui: React.ReactElement, route = '/') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: 0 } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[route]}>
        <Routes>
          <Route path="/" element={ui} />
          <Route path="/projects/:slug" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** `useProjects` as a fake backend: records the query, returns the rows. */
async function mockProjectsQuery(rows = projectRows) {
  const { useProjects } = await import('../hooks/useProjects')
  const impl = vi.fn((_query?: { sort?: string; q?: string; visibility?: string }) => ({
    projects: rows,
    isLoading: false,
    error: null,
    refetch: vi.fn(),
    createProject: vi.fn(),
    updateProject: vi.fn(),
    deleteProject: vi.fn(),
    isCreating: false,
    isUpdating: false,
    isDeleting: false,
  }))
  vi.mocked(useProjects).mockImplementation(impl as any)
  return impl
}

/** `useDocuments` + the search hook, both inert. */
async function mockDocumentsQuery(rows = docRows, total = rows.length) {
  const { useDocuments, useSearchDocuments } = await import('../hooks/useDocuments')
  const impl = vi.fn((_slug: string, _query?: { sort?: string; size?: number }) => ({
    documents: rows,
    documentsTotal: total,
    isLoading: false,
    error: null,
    refetch: vi.fn(),
    createDocument: vi.fn(),
    updateDocument: vi.fn(),
    deleteDocument: vi.fn(),
    uploadDocument: vi.fn(),
    isCreating: false,
    isUpdating: false,
    isDeleting: false,
    isUploading: false,
  }))
  vi.mocked(useDocuments).mockImplementation(impl as any)
  vi.mocked(useSearchDocuments).mockReturnValue({
    searchResults: [],
    isLoading: false,
    hasSearched: false,
  } as any)

  const { useProjects } = await import('../hooks/useProjects')
  vi.mocked(useProjects).mockReturnValue({
    projects: [{ id: 1, slug: 'glass-admin', name: 'Glass Admin', description: null, documentCount: rows.length, createdAt: '', updatedAt: '', visibility: 'public', pinned: false, sortOrder: 0 }],
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
  return impl
}

/** Order of the project cards as rendered. */
const projectOrder = () =>
  Array.from(document.querySelectorAll('[data-testid^="project-card-"]')).map(
    (el) => el.getAttribute('data-testid')?.replace('project-card-', ''),
  )

/** Order of the document rows as rendered. */
const docOrder = () =>
  Array.from(document.querySelectorAll('[data-testid^="document-item-"]')).map(
    (el) => el.getAttribute('data-testid')?.replace('document-item-', ''),
  )

/**
 * A stand-in for `DataTransfer` — jsdom creates drag events without one, but a
 * real browser always has it, and the component reads `setData`/`effectAllowed`.
 */
function makeDataTransfer() {
  const store: Record<string, string> = {}
  return {
    setData: (type: string, value: string) => {
      store[type] = value
    },
    getData: (type: string) => store[type] ?? '',
    setDragImage: () => undefined,
    effectAllowed: 'move',
    dropEffect: 'move',
    files: [],
    items: [],
    types: [],
  }
}

/** The real HTML5 drag sequence: handle → over target → drop on target. */
function dragOnto(handleTestId: string, targetTestId: string) {
  // The target is re-queried for every event: a re-render can replace the node,
  // and an event dispatched on a detached element never reaches React.
  fireEvent.dragStart(screen.getByTestId(handleTestId), { dataTransfer: makeDataTransfer() })
  fireEvent.dragOver(screen.getByTestId(targetTestId), { dataTransfer: makeDataTransfer() })
  fireEvent.drop(screen.getByTestId(targetTestId), { dataTransfer: makeDataTransfer() })
}

beforeEach(() => {
  vi.clearAllMocks()
  // The sort choice is persisted; a leftover value would change the initial
  // request of every later test in this file.
  window.localStorage.clear()
})

// ════════════════════════════════════════════════════════════════════════════
describe('Dashboard: sort menu (server parameter + persistence)', () => {
  it('asks the backend for the selected sort and remembers it', async () => {
    const query = await mockProjectsQuery()
    renderWithProviders(<Dashboard />)

    await waitFor(() => expect(screen.getByTestId('sort-select')).toBeInTheDocument())
    // Default = the endpoint's own default, so nothing is sent at first.
    expect(query.mock.calls[0][0]).toMatchObject({ sort: 'created_desc' })

    await userEvent.selectOptions(screen.getByTestId('sort-select'), 'name_asc')

    await waitFor(() => expect(query.mock.calls.at(-1)![0]).toMatchObject({ sort: 'name_asc' }))
    expect(window.localStorage.getItem(PROJECT_SORT_STORAGE_KEY)).toBe('name_asc')
  })

  it('restores the persisted sort before the first request', async () => {
    window.localStorage.setItem(PROJECT_SORT_STORAGE_KEY, 'manual')
    const query = await mockProjectsQuery()
    renderWithProviders(<Dashboard />)

    await waitFor(() => expect(screen.getByTestId('sort-select')).toBeInTheDocument())
    expect(query.mock.calls[0][0]).toMatchObject({ sort: 'manual' })
    expect(screen.getByTestId('sort-select')).toHaveValue('manual')
  })

  it('offers exactly the five documented orderings', async () => {
    await mockProjectsQuery()
    renderWithProviders(<Dashboard />)

    const select = screen.getByTestId('sort-select')
    expect(Array.from(select.querySelectorAll('option')).map((o) => o.getAttribute('value'))).toEqual([
      'manual',
      'name_asc',
      'name_desc',
      'created_desc',
      'updated_desc',
    ])
  })
})

// ════════════════════════════════════════════════════════════════════════════
describe('Dashboard: visibility filter', () => {
  it('sends visibility=public and shows a removable chip', async () => {
    const query = await mockProjectsQuery()
    renderWithProviders(<Dashboard />)

    await waitFor(() => expect(screen.getByTestId('visibility-filter')).toBeInTheDocument())
    await userEvent.selectOptions(screen.getByTestId('visibility-filter'), 'public')

    await waitFor(() => expect(query.mock.calls.at(-1)![0]).toMatchObject({ visibility: 'public' }))
    expect(screen.getByTestId('filter-chip-visibility')).toHaveTextContent('Visibility: Public')

    await userEvent.click(screen.getByTestId('filter-chip-clear-visibility'))
    await waitFor(() => expect(query.mock.calls.at(-1)![0]).toMatchObject({ visibility: '' }))
    expect(screen.queryByTestId('filter-chip-visibility')).not.toBeInTheDocument()
  })

  it('clears both filters with one click', async () => {
    const query = await mockProjectsQuery()
    renderWithProviders(<Dashboard />)

    await userEvent.type(screen.getByTestId('search-input'), 'alpha')
    await userEvent.selectOptions(screen.getByTestId('visibility-filter'), 'private')

    // Both filters reached the backend before they are cleared together.
    await waitFor(() =>
      expect(query.mock.calls.at(-1)![0]).toMatchObject({ q: 'alpha', visibility: 'private' }),
    )
    await waitFor(() => expect(screen.getByTestId('filter-clear-all')).toBeInTheDocument())
    await userEvent.click(screen.getByTestId('filter-clear-all'))

    await waitFor(() =>
      expect(query.mock.calls.at(-1)![0]).toMatchObject({ q: '', visibility: '' }),
    )
  })
})

// ════════════════════════════════════════════════════════════════════════════
describe('Dashboard: drag & drop ordering', () => {
  it('locks the handles unless the list is in the manual order', async () => {
    await mockProjectsQuery()
    const { projectApi } = await import('../services/projectApi')
    renderWithProviders(<Dashboard />)

    await waitFor(() => expect(screen.getByTestId('drag-handle-project-alpha')).toBeInTheDocument())
    expect(screen.getByTestId('drag-handle-project-alpha')).toHaveAttribute('data-dnd-enabled', 'false')
    // The reason is stated, with a one-click way out of it.
    expect(screen.getByTestId('dnd-status')).toHaveTextContent('Drag & drop works only in the custom order.')

    dragOnto('drag-handle-project-charlie', 'project-card-alpha')
    expect(projectApi.reorderProject).not.toHaveBeenCalled()
  })

  it('a click on a locked handle switches to the manual order and unlocks DnD', async () => {
    await mockProjectsQuery()
    renderWithProviders(<Dashboard />)

    await waitFor(() => expect(screen.getByTestId('drag-handle-project-alpha')).toBeInTheDocument())
    await userEvent.click(screen.getByTestId('drag-handle-project-charlie'))

    await waitFor(() =>
      expect(screen.getByTestId('drag-handle-project-alpha')).toHaveAttribute('data-dnd-enabled', 'true'),
    )
    expect(screen.getByTestId('sort-select')).toHaveValue('manual')
    expect(window.localStorage.getItem(PROJECT_SORT_STORAGE_KEY)).toBe('manual')
  })

  it('drops an item before its neighbour and sends beforeSlug', async () => {
    window.localStorage.setItem(PROJECT_SORT_STORAGE_KEY, 'manual')
    await mockProjectsQuery()
    const { projectApi } = await import('../services/projectApi')
    vi.mocked(projectApi.reorderProject).mockResolvedValue({} as any)

    renderWithProviders(<Dashboard />)
    await waitFor(() => expect(projectOrder()).toEqual(['alpha', 'bravo', 'charlie']))

    dragOnto('drag-handle-project-charlie', 'project-card-alpha')

    await waitFor(() =>
      expect(projectApi.reorderProject).toHaveBeenCalledWith('charlie', { beforeSlug: 'alpha' }),
    )
  })

  it('drops an item after its neighbour and sends afterSlug', async () => {
    window.localStorage.setItem(PROJECT_SORT_STORAGE_KEY, 'manual')
    await mockProjectsQuery()
    const { projectApi } = await import('../services/projectApi')
    vi.mocked(projectApi.reorderProject).mockResolvedValue({} as any)

    renderWithProviders(<Dashboard />)
    await waitFor(() => expect(projectOrder()).toEqual(['alpha', 'bravo', 'charlie']))

    dragOnto('drag-handle-project-alpha', 'project-card-charlie')

    await waitFor(() =>
      expect(projectApi.reorderProject).toHaveBeenCalledWith('alpha', { afterSlug: 'charlie' }),
    )
  })

  it('shows the drop line on the hovered card while dragging', async () => {
    window.localStorage.setItem(PROJECT_SORT_STORAGE_KEY, 'manual')
    await mockProjectsQuery()
    renderWithProviders(<Dashboard />)

    await waitFor(() => expect(screen.getByTestId('project-card-alpha')).toBeInTheDocument())
    fireEvent.dragStart(screen.getByTestId('drag-handle-project-charlie'), { dataTransfer: makeDataTransfer() })
    fireEvent.dragOver(screen.getByTestId('project-card-alpha'), { dataTransfer: makeDataTransfer() })

    // Moving up -> the line is drawn above the hovered card.
    expect(screen.getByTestId('project-card-alpha')).toHaveClass('drop-above')
    expect(screen.getByTestId('project-card-charlie')).toHaveClass('is-dragging')
  })

  it('reorders optimistically while the request is in flight', async () => {
    window.localStorage.setItem(PROJECT_SORT_STORAGE_KEY, 'manual')
    await mockProjectsQuery()
    const { projectApi } = await import('../services/projectApi')
    let settle: ((v: any) => void) | undefined
    vi.mocked(projectApi.reorderProject).mockImplementation(
      () => new Promise<any>((resolve) => { settle = resolve }),
    )

    renderWithProviders(<Dashboard />)
    await waitFor(() => expect(projectOrder()).toEqual(['alpha', 'bravo', 'charlie']))

    dragOnto('drag-handle-project-charlie', 'project-card-alpha')

    // The new order is on screen before the server answers.
    await waitFor(() => expect(projectOrder()).toEqual(['charlie', 'alpha', 'bravo']))
    settle?.({})
  })

  it('rolls back and toasts when the reorder fails', async () => {
    window.localStorage.setItem(PROJECT_SORT_STORAGE_KEY, 'manual')
    await mockProjectsQuery()
    const { projectApi } = await import('../services/projectApi')
    vi.mocked(projectApi.reorderProject).mockRejectedValue(new Error('403: Chýba MANAGE'))

    renderWithProviders(<Dashboard />)
    await waitFor(() => expect(projectOrder()).toEqual(['alpha', 'bravo', 'charlie']))

    dragOnto('drag-handle-project-charlie', 'project-card-alpha')

    await waitFor(() =>
      expect(screen.getByText("You don't have permission to change the order of projects")).toBeInTheDocument(),
    )
    // The optimistic move is discarded: the server list is rendered again.
    expect(projectOrder()).toEqual(['alpha', 'bravo', 'charlie'])
  })

  it('stays locked while a filter is active, and says so', async () => {
    window.localStorage.setItem(PROJECT_SORT_STORAGE_KEY, 'manual')
    await mockProjectsQuery()
    const { projectApi } = await import('../services/projectApi')
    renderWithProviders(<Dashboard />)

    await waitFor(() => expect(screen.getByTestId('drag-handle-project-alpha')).toBeInTheDocument())
    expect(screen.getByTestId('drag-handle-project-alpha')).toHaveAttribute('data-dnd-enabled', 'true')

    await userEvent.type(screen.getByTestId('search-input'), 'alpha')

    await waitFor(() =>
      expect(screen.getByTestId('drag-handle-project-alpha')).toHaveAttribute('data-dnd-enabled', 'false'),
    )
    expect(screen.getByTestId('dnd-status')).toHaveTextContent('Drag & drop is off while a filter is active')

    dragOnto('drag-handle-project-charlie', 'project-card-alpha')
    expect(projectApi.reorderProject).not.toHaveBeenCalled()
  })
})

// ════════════════════════════════════════════════════════════════════════════
describe('ProjectDetail: document ordering', () => {
  it('sends the chosen sort to the document list endpoint', async () => {
    const query = await mockDocumentsQuery()
    renderWithProviders(<ProjectDetail />, '/projects/glass-admin')

    await waitFor(() => expect(screen.getByTestId('sort-select')).toBeInTheDocument())
    // useDocuments(projectSlug, query) — the query is the second argument.
    expect(query.mock.calls[0][1]).toMatchObject({ sort: 'updated_desc', size: 100 })

    await userEvent.selectOptions(screen.getByTestId('sort-select'), 'manual')

    await waitFor(() => expect(query.mock.calls.at(-1)![1]).toMatchObject({ sort: 'manual' }))
    expect(window.localStorage.getItem(DOCS_SORT_STORAGE_KEY)).toBe('manual')
  })

  it('drags a document onto another and calls reorder with the anchor', async () => {
    window.localStorage.setItem(DOCS_SORT_STORAGE_KEY, 'manual')
    await mockDocumentsQuery()
    const { documentApi } = await import('../services/documentApi')
    // Hold the response so the optimistic order can be observed before the
    // (mocked) refetch replaces the list with the server's.
    let settle: ((v: any) => void) | undefined
    vi.mocked(documentApi.reorderDocument).mockImplementation(
      () => new Promise<any>((resolve) => { settle = resolve }),
    )

    renderWithProviders(<ProjectDetail />, '/projects/glass-admin')
    await waitFor(() => expect(docOrder()).toEqual(['onboarding-guide', 'admin-roles', 'runbook']))

    dragOnto('drag-handle-doc-runbook', 'document-item-onboarding-guide')

    await waitFor(() =>
      expect(documentApi.reorderDocument).toHaveBeenCalledWith('glass-admin', 'runbook', {
        beforeSlug: 'onboarding-guide',
      }),
    )
    // The row jumped to its new place before the server answered.
    expect(docOrder()).toEqual(['runbook', 'onboarding-guide', 'admin-roles'])
    settle?.({})
  })

  it('locks the document handles unless sort is manual', async () => {
    await mockDocumentsQuery()
    const { documentApi } = await import('../services/documentApi')
    renderWithProviders(<ProjectDetail />, '/projects/glass-admin')

    await waitFor(() => expect(screen.getByTestId('drag-handle-doc-runbook')).toBeInTheDocument())
    expect(screen.getByTestId('drag-handle-doc-runbook')).toHaveAttribute('data-dnd-enabled', 'false')

    dragOnto('drag-handle-doc-runbook', 'document-item-onboarding-guide')
    expect(documentApi.reorderDocument).not.toHaveBeenCalled()
  })

  it('warns when only part of a large project can be reordered', async () => {
    window.localStorage.setItem(DOCS_SORT_STORAGE_KEY, 'manual')
    // 100 loaded (the backend's page maximum) out of 137.
    const many = Array.from({ length: 100 }, (_, i) => ({
      id: 200 + i,
      slug: `doc-${i}`,
      title: `Doc ${i}`,
      content: '',
      projectId: 1,
      visibility: 'public',
      pinned: false,
      sortOrder: i,
      createdAt: '',
      updatedAt: '',
    }))
    await mockDocumentsQuery(many, 137)

    renderWithProviders(<ProjectDetail />, '/projects/glass-admin')

    await waitFor(() => expect(screen.getByTestId('dnd-truncated')).toBeInTheDocument())
    expect(screen.getByTestId('dnd-truncated')).toHaveTextContent('137 documents')
    expect(screen.getByTestId('dnd-truncated')).toHaveTextContent('first 100')
  })
})
