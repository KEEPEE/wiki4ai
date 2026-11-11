import { QueryClient } from '@tanstack/react-query'

/**
 * The application's single React Query cache.
 *
 * It lives in a module rather than being constructed inside `main.tsx` so that code
 * outside the React tree can reach it — specifically `AuthContext`, which has to drop
 * the cache when the identity behind it changes (WIKI4AI-200).
 *
 * The alternative, `useQueryClient()`, would force every test that renders
 * `<AuthProvider>` to wrap it in a `<QueryClientProvider>` first. Eleven existing test
 * files render `AuthProvider` directly, and none of them care about React Query. A
 * module-level instance keeps the auth tests as they are without weakening the
 * production behaviour, and there is exactly one cache per app instance either way —
 * `main.tsx` is the only other place that touches it.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 1000 * 60 * 5, // 5 minutes
      retry: 2,
    },
  },
})

/**
 * Drop every cached server response.
 *
 * Called when the identity changes — a login, a logout, an expired token, a refresh
 * that failed — because a cached list is a list somebody fetched. `staleTime` is five
 * minutes, so without this the dashboard of the account you just signed into can be
 * served from the cache of the account you were using a minute ago.
 *
 * That is not a theoretical concern for the projects list: since WIKI4AI-200 the order
 * and the pin flags in `GET /api/v1/projects` are the CALLER's, so the same URL now
 * legitimately returns a different body for a different user. A cache keyed only by
 * `{sort, q, visibility}` cannot tell those two responses apart.
 */
export function clearServerCache(): void {
  queryClient.clear()
}
