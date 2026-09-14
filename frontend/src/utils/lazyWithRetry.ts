import { lazy, type ComponentType, type LazyExoticComponent } from 'react'

/** sessionStorage flag that prevents an infinite auto-reload loop (WIKI4AI-38). */
export const CHUNK_RELOAD_FLAG = '__chunkReloaded'

function readFlag(): boolean {
  try {
    return sessionStorage.getItem(CHUNK_RELOAD_FLAG) === '1'
  } catch {
    // sessionStorage unavailable (e.g. blocked storage) — auto-reload stays best-effort
    return false
  }
}

function writeFlag(value: '1' | null): void {
  try {
    if (value === null) {
      sessionStorage.removeItem(CHUNK_RELOAD_FLAG)
    } else {
      sessionStorage.setItem(CHUNK_RELOAD_FLAG, value)
    }
  } catch {
    // ignore — flag is an optimization against reload loops, not a hard requirement
  }
}

/**
 * Drop-in replacement for `React.lazy()` that makes lazy chunk loads resilient
 * (WIKI4AI-27 / WIKI4AI-38). Without it, a single failed chunk fetch (network
 * hiccup, or a stale index.html pointing at removed hashed chunks after a
 * deploy) throws during render and — with no error boundary — React unmounts
 * the whole tree: the user sees a black screen.
 *
 * Behaviour:
 * - first failure  → one clean retry of the dynamic import
 * - second failure → full page reload, but only ONCE per session: a flag in
 *   sessionStorage (`__chunkReloaded`) guards the auto-reload against an
 *   infinite loop. When the flag is already set (we already reloaded once)
 *   the error is simply re-thrown so an <ErrorBoundary> can show its fallback
 *   UI with a manual "Obnoviť stránku" button.
 * - success        → the flag is cleared, restoring the one-time auto-reload
 *   for future failures.
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any -- mirrors React.lazy's own signature
export function lazyWithRetry<T extends ComponentType<any>>(
  factory: () => Promise<{ default: T }>,
): LazyExoticComponent<T> {
  return lazy(async () => {
    const attempt = async () => {
      const module = await factory()
      writeFlag(null) // success → auto-reload is armed again for future failures
      return module
    }
    try {
      return await attempt()
    } catch (firstError) {
      try {
        return await attempt() // one clean retry (transient hiccup may have cleared)
      } catch (secondError) {
        if (!readFlag()) {
          writeFlag('1')
          window.location.reload()
        }
        // Re-throw so the ErrorBoundary can render its fallback UI in case the
        // reload does not take effect (e.g. tests, or the chunk is still broken).
        throw secondError
      }
    }
  })
}
