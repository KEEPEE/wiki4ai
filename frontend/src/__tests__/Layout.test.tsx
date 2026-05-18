/**
 * Tests for Layout component
 */

import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import Layout from '../components/Layout'

// Mock the Sidebar to avoid fetching issues in layout tests
vi.mock('../components/Sidebar', () => ({
  default: function MockSidebar() {
    return <div data-testid="mock-sidebar">Mocked Sidebar</div>
  },
}))

describe('Layout', () => {
  it('should render sidebar and main content area', () => {
    render(
      <MemoryRouter>
        <Routes>
          <Route path="/" element={<Layout />}>
            <Route index element={<div>Main Content</div>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    )

    expect(screen.getByText('Main Content')).toBeInTheDocument()
  })

  it('should render children in main content area', () => {
    render(
      <MemoryRouter>
        <Routes>
          <Route path="/" element={<Layout />}>
            <Route index element={<div>Test Child</div>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    )

    expect(screen.getByText('Test Child')).toBeInTheDocument()
  })
})
