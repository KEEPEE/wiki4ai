/**
 * WIKI4AI-186: pinning projects and documents in the WebUI.
 *
 * Covers the three surfaces (project cards, document rows, both detail
 * headers): the right endpoint is called, the list re-orders immediately
 * (optimistic) and matches the server's `pinned`/`sortOrder` afterwards, a 403
 * rolls the item back and only shows a Toast, and the pinned state is exposed
 * to assistive tech (`aria-pressed`) plus visually (filled pin + state class).
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import Dashboard from '../pages/Dashboard'
import ProjectDetail from '../pages/ProjectDetail'
import DocumentViewer from '../pages/DocumentViewer'

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
    exportProject: vi.fn(),
    pinProject: vi.fn(),
    unpinProject: vi.fn(),
  },
}))

vi.mock('../services/documentApi', () => ({
  documentApi: {
    get: vi.fn(),
    getContent: vi.fn(),
    getBacklinks: vi.fn(),
    update: vi.fn(),
    pinDocument: vi.fn(),
    unpinDocument: vi.fn(),
  },
}))

/** The message `apiClient` builds for a 403 (`"${status}: ${detail}"`). */
const FORBIDDEN = new Error('403: Chýba MANAGE oprávnenie')
const FORBIDDEN_DOC = new Error('403: Chýba UPDATE oprávnenie')

const project = (slug: string, sortOrder: number, pinned = false) => ({
  id: slug.length,
  name: slug,
  description: null,
  slug,
  documentCount: 1,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  visibility: 'public',
  pinned,
  sortOrder,
})

const doc = (slug: string, sortOrder: number, pinned = false) => ({
  id: slug.length,
  title: slug,
  slug,
  content: '# body',
  projectId: 1,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  visibility: 'public',
  pinned,
  sortOrder,
})

function renderWithProviders(ui: React.ReactElement, route = '/', providedClient?: QueryClient) {
  const queryClient =
    providedClient ??
    new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 0 } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[route]}>
        <Routes>
          <Route path="/" element={ui} />
          <Route path="/projects/:slug" element={ui} />
          <Route path="/projects/:slug/settings" element={<div>Settings</div>} />
          <Route path="/projects/:slug/documents/:docId" element={ui} />
          <Route path="/projects/:slug/documents/:docId/edit" element={<div>Editor</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

async function mockDashboard(projects: ReturnType<typeof project>[]) {
  const { useProjects } = await import('../hooks/useProjects')
  vi.mocked(useProjects).mockReturnValue({
    projects,
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
}

async function mockProjectDetail(
  projects: ReturnType<typeof project>[],
  documents: ReturnType<typeof doc>[],
) {
  const { useProjects } = await import('../hooks/useProjects')
  vi.mocked(useProjects).mockReturnValue({
    projects,
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
  const { useDocuments, useSearchDocuments } = await import('../hooks/useDocuments')
  vi.mocked(useDocuments).mockReturnValue({
    documents,
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
  } as any)
  vi.mocked(useSearchDocuments).mockReturnValue({
    searchResults: [],
    isLoading: false,
    hasSearched: false,
  } as any)
}

async function mockViewerDocument(pinned = false) {
  const { documentApi } = await import('../services/documentApi')
  vi.mocked(documentApi.get).mockResolvedValue({ ...doc('my-doc', 0, pinned), content: '# Hello' } as any)
  vi.mocked(documentApi.getBacklinks).mockResolvedValue([] as any)
}

/** Slugs of the project cards, in the order they are rendered. */
const cardOrder = () =>
  screen
    .getAllByTestId(/^project-card-/)
    .map((el) => (el.getAttribute('data-testid') as string).replace('project-card-', ''))

/** Slugs of the document rows, in the order they are rendered. */
const rowOrder = () =>
  screen
    .getAllByTestId(/^document-item-/)
    .map((el) => (el.getAttribute('data-testid') as string).replace('document-item-', ''))

describe('WIKI4AI-186: pinning projects (Dashboard cards)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('pins via POST /projects/{slug}/pin and moves the card to the top at once', async () => {
    const user = userEvent.setup()
    await mockDashboard([project('own-cloud', 0), project('glass-admin', 1)])
    const { projectApi } = await import('../services/projectApi')
    vi.mocked(projectApi.pinProject).mockResolvedValue(project('glass-admin', -1, true) as any)

    renderWithProviders(<Dashboard />)
    expect(cardOrder()).toEqual(['own-cloud', 'glass-admin'])

    await user.click(screen.getByTestId('pin-project-glass-admin'))

    expect(projectApi.pinProject).toHaveBeenCalledWith('glass-admin')
    await waitFor(() => expect(cardOrder()).toEqual(['glass-admin', 'own-cloud']))
    expect(screen.getByTestId('pin-project-glass-admin')).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByTestId('project-card-glass-admin')).toHaveAttribute('data-pinned', 'true')
  })

  it('unpins via /unpin and asks for a refetch so the order matches the server', async () => {
    const user = userEvent.setup()
    // Server order: pinned first. The unpinned slot the project returns to is
    // the server's business (its list is ordered by its own key), so the client
    // must not invent it — it flips the pin and asks for a fresh list.
    await mockDashboard([project('glass-admin', -1, true), project('own-cloud', 0)])
    const { projectApi } = await import('../services/projectApi')
    vi.mocked(projectApi.unpinProject).mockResolvedValue(project('glass-admin', 1) as any)

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 0 } } })
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    renderWithProviders(<Dashboard />, '/', queryClient)
    expect(cardOrder()).toEqual(['glass-admin', 'own-cloud'])

    await user.click(screen.getByTestId('pin-project-glass-admin'))

    expect(projectApi.unpinProject).toHaveBeenCalledWith('glass-admin')
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ['projects'] }))
    expect(screen.getByTestId('pin-project-glass-admin')).toHaveAttribute('aria-pressed', 'false')
  })

  it('rolls back on 403 (no MANAGE) and shows a Toast without changing state', async () => {
    const user = userEvent.setup()
    await mockDashboard([project('own-cloud', 0), project('glass-admin', 1)])
    const { projectApi } = await import('../services/projectApi')
    vi.mocked(projectApi.pinProject).mockRejectedValue(FORBIDDEN)

    renderWithProviders(<Dashboard />)
    await user.click(screen.getByTestId('pin-project-glass-admin'))

    await waitFor(() =>
      expect(screen.getByText("You don't have permission to pin this project")).toBeInTheDocument(),
    )
    expect(cardOrder()).toEqual(['own-cloud', 'glass-admin'])
    expect(screen.getByTestId('pin-project-glass-admin')).toHaveAttribute('aria-pressed', 'false')
    // The in-flight flag is cleared once the request settles.
    await waitFor(() => expect(screen.getByTestId('pin-project-glass-admin')).not.toHaveAttribute('disabled'))
  })

  it('exposes an accessible label and title on the pin control', () => {
    void mockDashboard([project('own-cloud', 0)])
    renderWithProviders(<Dashboard />)
    const button = screen.getByTestId('pin-project-own-cloud')
    expect(button).toHaveAccessibleName('Pin project own-cloud to the top')
    expect(button).toHaveAttribute('title', 'Pin project own-cloud to the top')
  })
})

describe('WIKI4AI-186: pinning documents (ProjectDetail)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('pins a document row via POST .../documents/{docSlug}/pin and re-sorts the list', async () => {
    const user = userEvent.setup()
    await mockProjectDetail([project('test-project', 0)], [doc('first', 0), doc('second', 1)])
    const { documentApi } = await import('../services/documentApi')
    vi.mocked(documentApi.pinDocument).mockResolvedValue(doc('second', -1, true) as any)

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 0 } } })
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    renderWithProviders(<ProjectDetail />, '/projects/test-project', queryClient)
    expect(rowOrder()).toEqual(['first', 'second'])

    await user.click(screen.getByTestId('pin-doc-second'))

    expect(documentApi.pinDocument).toHaveBeenCalledWith('test-project', 'second')
    await waitFor(() => expect(rowOrder()).toEqual(['second', 'first']))
    // The list is refetched so the final order is exactly the server's.
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ['documents', 'test-project'] }))
    expect(screen.getByTestId('document-item-second')).toHaveAttribute('data-pinned', 'true')
    expect(screen.getByText('Document second pinned to the top')).toBeInTheDocument()
  })

  it('rolls a document row back on 403 and keeps the list untouched', async () => {
    const user = userEvent.setup()
    await mockProjectDetail([project('test-project', 0)], [doc('first', 0), doc('second', 1)])
    const { documentApi } = await import('../services/documentApi')
    vi.mocked(documentApi.pinDocument).mockRejectedValue(FORBIDDEN_DOC)

    renderWithProviders(<ProjectDetail />, '/projects/test-project')
    await user.click(screen.getByTestId('pin-doc-second'))

    await waitFor(() =>
      expect(screen.getByText("You don't have permission to pin this document")).toBeInTheDocument(),
    )
    expect(rowOrder()).toEqual(['first', 'second'])
    expect(screen.getByTestId('pin-doc-second')).toHaveAttribute('aria-pressed', 'false')
  })

  it('pins the project itself from the project header', async () => {
    const user = userEvent.setup()
    await mockProjectDetail([project('test-project', 0)], [doc('first', 0)])
    const { projectApi } = await import('../services/projectApi')
    vi.mocked(projectApi.pinProject).mockResolvedValue(project('test-project', -1, true) as any)

    renderWithProviders(<ProjectDetail />, '/projects/test-project')
    await user.click(screen.getByTestId('pin-project-test-project'))

    expect(projectApi.pinProject).toHaveBeenCalledWith('test-project')
    await waitFor(() =>
      expect(screen.getByTestId('pin-project-test-project')).toHaveAttribute('aria-pressed', 'true'),
    )
  })
})

describe('WIKI4AI-186: pinning from the document viewer header', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows the pin next to the visibility toggle and pins on click', async () => {
    const user = userEvent.setup()
    await mockViewerDocument(false)
    const { documentApi } = await import('../services/documentApi')
    vi.mocked(documentApi.pinDocument).mockResolvedValue(doc('my-doc', -1, true) as any)

    renderWithProviders(<DocumentViewer />, '/projects/test-project/documents/my-doc')

    const button = await screen.findByTestId('pin-doc-my-doc')
    expect(button).toHaveAttribute('aria-pressed', 'false')

    await user.click(button)

    expect(documentApi.pinDocument).toHaveBeenCalledWith('test-project', 'my-doc')
    await waitFor(() => expect(button).toHaveAttribute('aria-pressed', 'true'))
    expect(screen.getByText('Document my-doc pinned to the top')).toBeInTheDocument()
  })

  it('starts pinned when the document is pinned and unpins on click', async () => {
    const user = userEvent.setup()
    await mockViewerDocument(true)
    const { documentApi } = await import('../services/documentApi')
    vi.mocked(documentApi.unpinDocument).mockResolvedValue(doc('my-doc', 3) as any)

    renderWithProviders(<DocumentViewer />, '/projects/test-project/documents/my-doc')

    const button = await screen.findByTestId('pin-doc-my-doc')
    expect(button).toHaveAttribute('aria-pressed', 'true')

    await user.click(button)

    expect(documentApi.unpinDocument).toHaveBeenCalledWith('test-project', 'my-doc')
    await waitFor(() => expect(button).toHaveAttribute('aria-pressed', 'false'))
  })

  it('rolls back and toasts on 403 (no UPDATE permission)', async () => {
    const user = userEvent.setup()
    await mockViewerDocument(false)
    const { documentApi } = await import('../services/documentApi')
    vi.mocked(documentApi.pinDocument).mockRejectedValue(FORBIDDEN_DOC)

    renderWithProviders(<DocumentViewer />, '/projects/test-project/documents/my-doc')

    const button = await screen.findByTestId('pin-doc-my-doc')
    await user.click(button)

    await waitFor(() =>
      expect(screen.getByText("You don't have permission to pin this document")).toBeInTheDocument(),
    )
    expect(button).toHaveAttribute('aria-pressed', 'false')
  })
})
