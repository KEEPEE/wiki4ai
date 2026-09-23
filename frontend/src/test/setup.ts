/**
 * Test setup file for Vitest + React Testing Library
 */
import '@testing-library/jest-dom'

// WIKI4AI-73: initialize i18next so components render with the EN default
// catalog in tests (mirrors anonymous/first-run behavior).
import '../i18n'

// Mock window.matchMedia for responsive tests
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }),
})

// Mock IntersectionObserver
window.IntersectionObserver = class IntersectionObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
} as any

// Mock ResizeObserver (used by GraphView for container sizing)
window.ResizeObserver = class ResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
} as any

// Declare global for fetch mocking in tests
declare const global: any
