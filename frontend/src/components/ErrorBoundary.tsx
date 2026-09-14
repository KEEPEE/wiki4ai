import { Component, type ErrorInfo, type ReactNode } from 'react'

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
      return (
        <div className="min-h-screen flex items-center justify-center bg-gray-50 p-8">
          <div className="max-w-md text-center">
            <h1 className="text-2xl font-bold text-red-600 mb-4">Niečo sa pokazilo</h1>
            <p className="text-gray-700 mb-4">
              Pri načítaní stránky došlo k chybe — napríklad zlyhalo načítanie časti aplikácie.
            </p>
            {this.state.error && (
              <pre className="bg-white p-4 rounded border border-red-200 text-left overflow-auto max-h-48 text-sm text-red-800 mb-4">
                {this.state.error.message}
              </pre>
            )}
            <button
              onClick={this.handleReload}
              className="mt-2 px-4 py-2 bg-blue-600 text-white rounded hover:bg-blue-700"
            >
              Obnoviť stránku
            </button>
          </div>
        </div>
      )
    }
    return this.props.children
  }
}
