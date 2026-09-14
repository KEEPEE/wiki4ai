/**
 * Tests for lazyWithRetry (WIKI4AI-38) — retry wrapper around React.lazy that
 * prevents a failed chunk load from taking down the whole app:
 *   fail → 1 clean retry → fail again → one guarded full reload (sessionStorage
 *   flag against loops) → ErrorBoundary fallback UI.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { Suspense } from 'react'
import { render, screen } from '@testing-library/react'
import ErrorBoundary from '../components/ErrorBoundary'
import { lazyWithRetry, CHUNK_RELOAD_FLAG } from '../utils/lazyWithRetry'

function LazyContent() {
  return <div>lazy content</div>
}

const moduleMock = { default: LazyContent } as unknown as { default: React.ComponentType<unknown> }

function mockLocation() {
  const reload = vi.fn()
  Object.defineProperty(window, 'location', {
    configurable: true,
    writable: true,
    value: { href: 'http://localhost/', reload },
  })
  return reload
}

/** Render a lazy component the same way App.tsx does (boundary + suspense). */
function renderLazy(Component: React.LazyExoticComponent<React.ComponentType<unknown>>) {
  return render(
    <ErrorBoundary>
      <Suspense fallback={<div>loading...</div>}>
        <Component />
      </Suspense>
    </ErrorBoundary>,
  )
}

describe('lazyWithRetry', () => {
  let errorSpy: ReturnType<typeof vi.spyOn>
  let reload: ReturnType<typeof mockLocation>

  beforeEach(() => {
    errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    reload = mockLocation()
    sessionStorage.clear()
  })

  afterEach(() => {
    errorSpy.mockRestore()
    delete (window as unknown as { location: unknown }).location
    sessionStorage.clear()
  })

  it('resolves and renders the component when the import succeeds immediately', async () => {
    const factory = vi.fn().mockResolvedValue(moduleMock)
    const LazyComp = lazyWithRetry(() => factory())
    renderLazy(LazyComp)
    expect(await screen.findByText('lazy content')).toBeInTheDocument()
    expect(factory).toHaveBeenCalledTimes(1)
    expect(reload).not.toHaveBeenCalled()
    expect(sessionStorage.getItem(CHUNK_RELOAD_FLAG)).toBeNull()
  })

  it('retries once on failure and renders when the retry succeeds', async () => {
    const factory = vi.fn()
      .mockRejectedValueOnce(new Error('Failed to fetch dynamically imported module: /assets/DocumentViewer-XXX.js'))
      .mockResolvedValueOnce(moduleMock)
    const LazyComp = lazyWithRetry(() => factory())
    renderLazy(LazyComp)
    expect(await screen.findByText('lazy content')).toBeInTheDocument()
    expect(factory).toHaveBeenCalledTimes(2) // initial attempt + 1 retry
    expect(reload).not.toHaveBeenCalled()
    expect(sessionStorage.getItem(CHUNK_RELOAD_FLAG)).toBeNull()
  })

  it('on double failure: sets the sessionStorage flag, reloads once and shows the ErrorBoundary fallback (no black screen)', async () => {
    const factory = vi.fn().mockRejectedValue(new Error('Failed to fetch dynamically imported module: /assets/DocumentViewer-XXX.js'))
    const LazyComp = lazyWithRetry(() => factory())
    renderLazy(LazyComp)
    // The ErrorBoundary fallback is shown — NOT an empty #root
    expect(await screen.findByText('Niečo sa pokazilo')).toBeInTheDocument()
    expect(factory).toHaveBeenCalledTimes(2) // initial attempt + 1 retry
    expect(reload).toHaveBeenCalledTimes(1)
    expect(sessionStorage.getItem(CHUNK_RELOAD_FLAG)).toBe('1')
  })

  it('when the reload flag is already set: still retries once but performs NO second auto-reload — error goes to the ErrorBoundary', async () => {
    sessionStorage.setItem(CHUNK_RELOAD_FLAG, '1') // e.g. after an earlier auto-reload that did not help
    const factory = vi.fn().mockRejectedValue(new Error('Failed to fetch dynamically imported module: /assets/DocumentViewer-XXX.js'))
    const LazyComp = lazyWithRetry(() => factory())
    renderLazy(LazyComp)
    expect(await screen.findByText('Niečo sa pokazilo')).toBeInTheDocument()
    expect(factory).toHaveBeenCalledTimes(2) // initial attempt + 1 retry (retry is always allowed)
    expect(reload).not.toHaveBeenCalled() // no second auto-reload — loop protection
  })

  it('clears the reload flag when an import succeeds', async () => {
    sessionStorage.setItem(CHUNK_RELOAD_FLAG, '1')
    const factory = vi.fn().mockResolvedValue(moduleMock)
    const LazyComp = lazyWithRetry(() => factory())
    renderLazy(LazyComp)
    expect(await screen.findByText('lazy content')).toBeInTheDocument()
    expect(sessionStorage.getItem(CHUNK_RELOAD_FLAG)).toBeNull() // flag cleared → auto-reload armed again
  })

  it('clears the reload flag after a successful retry', async () => {
    sessionStorage.setItem(CHUNK_RELOAD_FLAG, '1')
    const factory = vi.fn()
      .mockRejectedValueOnce(new Error('network hiccup'))
      .mockResolvedValueOnce(moduleMock)
    const LazyComp = lazyWithRetry(() => factory())
    renderLazy(LazyComp)
    expect(await screen.findByText('lazy content')).toBeInTheDocument()
    expect(sessionStorage.getItem(CHUNK_RELOAD_FLAG)).toBeNull()
  })
})
