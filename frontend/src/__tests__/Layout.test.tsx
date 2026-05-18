/**
 * Tests for Layout component
 */

import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import Layout from '../components/Layout'

// Mock the Sidebar component
vi.mock('../components/Sidebar', () => ({
  default: () => <nav data-testid="sidebar">Mocked Sidebar</nav>,
}))

function renderWithProviders(ui: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: 0 },
    },
  })
  
  return render(
    <QueryClientProvider client={queryClient}>
      {ui}
    </QueryClientProvider>,
  )
}

describe('Layout', () => {
  it('should render sidebar component', () => {
    renderWithProviders(
      <MemoryRouter>
        <Layout />
      </MemoryRouter>
    )

    expect(screen.getByTestId('sidebar')).toBeInTheDocument()
  })

  it('should render children when provided', () => {
    renderWithProviders(
      <MemoryRouter>
        <Layout>
          <div data-testid="main-content">Main Content</div>
        </Layout>
      </MemoryRouter>
    )

    expect(screen.getByTestId('main-content')).toBeInTheDocument()
    expect(screen.getByText('Main Content')).toBeInTheDocument()
  })

  it('should render Outlet when no children provided', () => {
    renderWithProviders(
      <MemoryRouter initialEntries={['/test']}>
        <Routes>
          <Route path="/test" element={<Layout />}>
            <Route index element={<div data-testid="outlet-content">Outlet Content</div>} />
          </Route>
        </Routes>
      </MemoryRouter>
    )

    expect(screen.getByTestId('outlet-content')).toBeInTheDocument()
  })

  it('should have correct flex layout structure', () => {
    const { container } = renderWithProviders(
      <MemoryRouter>
        <Layout>
          <div>Content</div>
        </Layout>
      </MemoryRouter>
    )

    // Main wrapper should be flex with h-screen
    const mainWrapper = container.firstChild as HTMLElement
    expect(mainWrapper).toHaveClass('flex')
    expect(mainWrapper).toHaveClass('h-screen')
  })

  it('should have scrollable main content area', () => {
    const { container } = renderWithProviders(
      <MemoryRouter>
        <Layout>
          <div>Main</div>
        </Layout>
      </MemoryRouter>
    )

    // Find the main element
    const mainElement = container.querySelector('main')
    expect(mainElement).toBeInTheDocument()
    expect(mainElement).toHaveClass('overflow-y-auto')
  })
})
