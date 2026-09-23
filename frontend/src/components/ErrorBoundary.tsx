import { Component, type ErrorInfo, type ReactNode } from 'react'
import i18n from '../i18n' // WIKI4AI-73: class component — use the i18n instance directly

interface ErrorBoundaryProps {
  children?: ReactNode
  /** Optional custom fallback UI. Defaults to a full-screen error screen with a reload button. */
  fallback?: ReactNode
}

interface ErrorBoundaryState {
  hasError: boolean
  error: Error | null
}

/**
 * Error boundary that prevents a runtime render error — most importantly a
 * failed lazy chunk load (WIKI4AI-27 / WIKI4AI-38) — from unmounting the
 * entire React tree and leaving the user with a black screen.
 *
 * On error it renders a fallback UI ("Niečo sa pokazilo") with an
 * "Obnoviť stránku" button that performs a full page reload, which restores
 * the app on the same deep URL (fresh module registry + fresh chunk fetch).
 */
export default class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { hasError: false, error: null }

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { hasError: true, error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('ErrorBoundary caught an error:', error, info.componentStack)
  }

  private handleReload = () => {
    window.location.reload()
  }

  render() {
    if (this.state.hasError) {
      if (this.props.fallback) {
        return this.props.fallback
      }
      // NOTE: styled with inline styles on purpose — this codebase's builds do
      // not generate Tailwind utilities (no @tailwindcss/vite plugin; see
      // WIKI4AI-38), so utility classes in JSX would render unstyled.
      return (
        <div
          style={{
            minHeight: '100vh',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            padding: '32px 16px',
          }}
        >
          <div
            style={{
              maxWidth: 520,
              width: '100%',
              textAlign: 'center',
              background: 'var(--dark-2, #12121f)',
              border: '1px solid rgba(0, 240, 255, 0.18)',
              borderRadius: 12,
              padding: 32,
            }}
          >
            <h1 style={{ fontSize: 24, fontWeight: 700, color: '#ff6b6b', margin: '0 0 16px' }}>
              {i18n.t('errorBoundary.title')}
            </h1>
            <p style={{ color: 'rgba(255, 255, 255, 0.7)', lineHeight: 1.6, margin: '0 0 16px' }}>
              {i18n.t('errorBoundary.message')}
            </p>
            {this.state.error && (
              <pre
                style={{
                  background: 'var(--dark-1, #0a0a12)',
                  border: '1px solid rgba(255, 107, 107, 0.35)',
                  borderRadius: 8,
                  padding: 12,
                  fontSize: 13,
                  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                  color: '#ff9f9f',
                  textAlign: 'left',
                  overflow: 'auto',
                  maxHeight: 192,
                  margin: '0 0 16px',
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-word',
                }}
              >
                {this.state.error.message}
              </pre>
            )}
            <button
              onClick={this.handleReload}
              style={{
                marginTop: 8,
                padding: '10px 24px',
                background: 'var(--primary, #00f0ff)',
                color: '#0a0a12',
                fontWeight: 700,
                fontSize: 15,
                border: 'none',
                borderRadius: 8,
                cursor: 'pointer',
              }}
            >
              {i18n.t('errorBoundary.reload')}
            </button>
          </div>
        </div>
      )
    }
    return this.props.children
  }
}
