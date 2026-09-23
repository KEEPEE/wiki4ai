/**
 * Tests for the global (cross-project) search page (WIKI4AI-61).
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import SearchPage from '../pages/SearchPage'

// Mock the search hook and embedding status — the page logic under test is
// rendering + navigation, not the query layer.
vi.mock('../hooks/useGlobalSearch', () => ({
  useGlobalSearch: vi.fn(),
}))

vi.mock('../hooks/useEmbeddingStatus', () => ({
  useEmbeddingStatus: vi.fn(() => ({ data: undefined })),
}))

const sampleResults = [
  {
    id: 1,
    title: 'Push Notifications Design',
    slug: 'push-notifications-design',
    projectId: 10,
    projectSlug: 'agent-helpers',
    projectName: 'Agent Helpers',
    score: 0.032787,
    updatedAt: '2026-09-10T10:00:00',
    excerpt: '…we decided to use WebSockets for push notifications…',
  },
  {
    id: 2,
    title: 'Embedding Sidecar Runbook',
    slug: 'embedding-sidecar-runbook',
    projectId: 20,
    projectSlug: 'servers',
    projectName: 'Servers',
    score: 0.016393,
    updatedAt: '2026-09-11T12:30:00',
    excerpt: '…the embedding sidecar serves the Qwen model on :8030…',
  },
]

function renderSearchPage({ route = '/search?q=embedding' } = {}) {
  return render(
    <MemoryRouter initialEntries={[route]}>
      <Routes>
        <Route path="/search" element={<SearchPage />} />
        <Route
          path="/projects/:slug/documents/:docId"
          element={<div data-testid="document-viewer-stub">Document Viewer</div>}
        />
      </Routes>
    </MemoryRouter>,
  )
}

describe('SearchPage (WIKI4AI-61)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('should prefill the input from the ?q= parameter and render results with project badges, scores and excerpts', async () => {
    const { useGlobalSearch } = await import('../hooks/useGlobalSearch')
    vi.mocked(useGlobalSearch).mockReturnValue({
      results: sampleResults,
      isLoading: false,
      hasSearched: true,
    })

    renderSearchPage()

    const input = screen.getByTestId('global-search-page-input') as HTMLInputElement
    expect(input.value).toBe('embedding')

    // Both hits rendered with project attribution
    expect(screen.getByText('Push Notifications Design')).toBeInTheDocument()
    expect(screen.getByText('Embedding Sidecar Runbook')).toBeInTheDocument()
    expect(screen.getByTestId('project-badge-1')).toHaveTextContent('Agent Helpers')
    expect(screen.getByTestId('project-badge-2')).toHaveTextContent('Servers')

    // RRF score badges (4-decimal format, same as per-project search)
    expect(screen.getByTestId('search-score-1')).toHaveTextContent('0.0328')
    expect(screen.getByTestId('search-score-2')).toHaveTextContent('0.0164')

    // Excerpts present
    expect(screen.getByTestId('search-excerpt-1')).toHaveTextContent('WebSockets for push notifications')
  })

  it('should show the result counter', async () => {
    const { useGlobalSearch } = await import('../hooks/useGlobalSearch')
    vi.mocked(useGlobalSearch).mockReturnValue({ results: sampleResults, isLoading: false, hasSearched: true })

    renderSearchPage()

    // WIKI4AI-73: EN default catalog
    expect(screen.getByTestId('search-results-count')).toHaveTextContent('2 results')
  })

  it('should show the empty state when nothing matches', async () => {
    const { useGlobalSearch } = await import('../hooks/useGlobalSearch')
    vi.mocked(useGlobalSearch).mockReturnValue({ results: [], isLoading: false, hasSearched: true })

    renderSearchPage()

    await waitFor(() => {
      // WIKI4AI-73: EN default catalog
      expect(screen.getByText('No documents match your search')).toBeInTheDocument()
    })
  })

  it('should show the semantic-unavailable banner when the sidecar is down', async () => {
    const { useGlobalSearch } = await import('../hooks/useGlobalSearch')
    vi.mocked(useGlobalSearch).mockReturnValue({ results: sampleResults, isLoading: false, hasSearched: true })
    const { useEmbeddingStatus } = await import('../hooks/useEmbeddingStatus')
    vi.mocked(useEmbeddingStatus).mockReturnValue({ data: { available: false, model: 'x', dim: 1024 } } as any)

    renderSearchPage()

    expect(screen.getByTestId('semantic-unavailable-banner')).toBeInTheDocument()
  })

  it('should show the semantic-active badge when the sidecar is up', async () => {
    const { useGlobalSearch } = await import('../hooks/useGlobalSearch')
    vi.mocked(useGlobalSearch).mockReturnValue({ results: sampleResults, isLoading: false, hasSearched: true })
    const { useEmbeddingStatus } = await import('../hooks/useEmbeddingStatus')
    vi.mocked(useEmbeddingStatus).mockReturnValue({ data: { available: true, model: 'x', dim: 1024 } } as any)

    renderSearchPage()

    expect(screen.getByTestId('semantic-status-badge')).toBeInTheDocument()
  })

  it('should show the hint state for queries shorter than 2 characters', async () => {
    const { useGlobalSearch } = await import('../hooks/useGlobalSearch')
    vi.mocked(useGlobalSearch).mockReturnValue({ results: [], isLoading: false, hasSearched: false })

    renderSearchPage({ route: '/search?q=x' })

    // WIKI4AI-73: EN default catalog
    expect(screen.getByText(/Start typing at least 2 characters/)).toBeInTheDocument()
  })

  it('should navigate to the document viewer when a result is clicked', async () => {
    const user = userEvent.setup()
    const { useGlobalSearch } = await import('../hooks/useGlobalSearch')
    vi.mocked(useGlobalSearch).mockReturnValue({ results: sampleResults, isLoading: false, hasSearched: true })

    renderSearchPage()

    await user.click(screen.getByText('Push Notifications Design'))

    await waitFor(() => {
      expect(screen.getByTestId('document-viewer-stub')).toBeInTheDocument()
    })
  })
})
