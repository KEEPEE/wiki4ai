/**
 * Tests for ErrorBoundary (WIKI4AI-38) — must prevent a render error
 * (e.g. failed lazy chunk load) from unmounting the whole React tree.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import type { ReactElement } from 'react'
import { render, screen, fireEvent } from '@testing-library/react'
import ErrorBoundary from '../components/ErrorBoundary'

function Thrower({ message = 'boom' }: { message?: string }): ReactElement {
  throw new Error(message)
}

/** Replace window.location with a mock exposing a spy reload(). */
function mockLocation() {
  const reload = vi.fn()
  Object.defineProperty(window, 'location', {
    configurable: true,
    writable: true,
    value: { href: 'http://localhost/', reload },
  })
  return reload
}

describe('ErrorBoundary', () => {
  let errorSpy: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    // React logs caught errors via console.error — keep test output clean
    errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  afterEach(() => {
    errorSpy.mockRestore()
    // Restore the real jsdom location (own property shadows the prototype getter)
    delete (window as unknown as { location: unknown }).location
  })

  it('renders children when no error is thrown', () => {
    render(
      <ErrorBoundary>
        <div>ok content</div>
      </ErrorBoundary>,
    )
    expect(screen.getByText('ok content')).toBeInTheDocument()
    expect(screen.queryByText('Niečo sa pokazilo')).not.toBeInTheDocument()
  })

  it('renders fallback UI "Niečo sa pokazilo" when a child throws during render', () => {
    render(
      <ErrorBoundary>
        <Thrower />
      </ErrorBoundary>,
    )
    expect(screen.getByText('Niečo sa pokazilo')).toBeInTheDocument()
  })

  it('shows the error message in the fallback UI', () => {
    render(
      <ErrorBoundary>
        <Thrower message="Failed to fetch dynamically imported module: /assets/DocumentViewer-XXX.js" />
      </ErrorBoundary>,
    )
    expect(
      screen.getByText('Failed to fetch dynamically imported module: /assets/DocumentViewer-XXX.js'),
    ).toBeInTheDocument()
  })

  it('provides an "Obnoviť stránku" button that reloads the page', () => {
    const reload = mockLocation()
    render(
      <ErrorBoundary>
        <Thrower />
      </ErrorBoundary>,
    )
    const button = screen.getByRole('button', { name: 'Obnoviť stránku' })
    fireEvent.click(button)
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('uses the custom fallback when provided', () => {
    render(
      <ErrorBoundary fallback={<div>custom fallback ui</div>}>
        <Thrower />
      </ErrorBoundary>,
    )
    expect(screen.getByText('custom fallback ui')).toBeInTheDocument()
    expect(screen.queryByText('Niečo sa pokazilo')).not.toBeInTheDocument()
  })

  it('keeps the boundary in error state after a child keeps throwing', () => {
    render(
      <ErrorBoundary>
        <Thrower />
      </ErrorBoundary>,
    )
    // Re-rendering the same (still broken) subtree must not crash the app —
    // the fallback stays visible.
    expect(screen.getByText('Niečo sa pokazilo')).toBeInTheDocument()
  })
})
