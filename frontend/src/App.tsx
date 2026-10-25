import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Suspense, type ReactElement } from 'react'
import { AuthProvider } from './contexts/AuthContext'
import { VaultProvider } from './contexts/VaultContext'
import Layout from './components/Layout'
import ProtectedRoute from './components/ProtectedRoute'
import LoadingScreen from './components/LoadingScreen'
import ErrorBoundary from './components/ErrorBoundary'
import { lazyWithRetry } from './utils/lazyWithRetry'
import Dashboard from './pages/Dashboard'
import SearchPage from './pages/SearchPage'
import ProjectDetail from './pages/ProjectDetail'
import ProjectSettings from './pages/ProjectSettings'
import Login from './pages/Login'
import Register from './pages/Register'
import SetupPage from './pages/SetupPage'
import ProfilePage from './pages/ProfilePage'
import AdminUsersPage from './pages/AdminUsersPage'
import VaultPage from './pages/VaultPage'
// WIKI4AI-108: the catch-all page. Eager on purpose — a lazy chunk that fails to
// load is how a user ends up on a blank screen again, and this page is the one
// route that must always render.
import NotFoundPage from './pages/NotFoundPage'

// Lazy-load components that have heavy dependencies.
// lazyWithRetry (WIKI4AI-38) adds one retry + a guarded full reload so a failed
// chunk load can never leave the user with a black screen.
const GraphViewPage = lazyWithRetry(() => import('./pages/GraphViewPage'))
const DocumentViewer = lazyWithRetry(() => import('./pages/DocumentViewer'))
const DocumentEditor = lazyWithRetry(() => import('./pages/DocumentEditor'))
// WIKI4AI-97: calendar page (month view + event form)
const CalendarPage = lazyWithRetry(() => import('./pages/CalendarPage'))
// WIKI4AI-161: coordination board — status columns, lease countdown, capacity, agents
const BoardPage = lazyWithRetry(() => import('./pages/BoardPage'))
// WIKI4AI-162: work item detail — timeline, comments by kind, handoff card, reservations
const WorkItemDetailPage = lazyWithRetry(() => import('./pages/WorkItemDetailPage'))
// WIKI4AI-163: agents — project role, lease-expiry count and token rotation
const AgentsPage = lazyWithRetry(() => import('./pages/AgentsPage'))
// WIKI4AI-164: capacity vs live reservations, TTL countdown, manual release
const ResourcesPage = lazyWithRetry(() => import('./pages/ResourcesPage'))
// WIKI4AI-164: time in state, cycle time and lease expiries, all straight from /metrics/actors
const MetricsPage = lazyWithRetry(() => import('./pages/MetricsPage'))

function LazyRouteFallback({ label }: { label?: string }) {
  const { t } = useTranslation()
  return (
    <div className="loading-state">
      <div className="spinner" />
      <p>{label ?? t('app.loading')}</p>
    </div>
  )
}

/**
 * Suspense + ErrorBoundary wrapper for a lazy route (WIKI4AI-38).
 * A failed chunk load is caught by the boundary and shows a fallback UI with a
 * reload button instead of unmounting the whole React tree.
 */
function LazyRoute({ children, label }: { children: ReactElement; label?: string }) {
  return (
    <ErrorBoundary>
      <Suspense fallback={<LazyRouteFallback label={label} />}>
        {children}
      </Suspense>
    </ErrorBoundary>
  )
}

function App() {
  const { t } = useTranslation()
  return (
    <>
      <LoadingScreen />
      <BrowserRouter>
        <AuthProvider>
          <VaultProvider>
            {/* Outer boundary: safety net for Layout and non-lazy pages (WIKI4AI-38) */}
            <ErrorBoundary>
              <Routes>
                {/* Auth pages - standalone, no Layout wrapper */}
                <Route path="/login" element={<Login />} />
                <Route path="/register" element={<Register />} />
                {/* WIKI4AI-69: first-run setup (shown only while no account exists) */}
                <Route path="/setup" element={<SetupPage />} />

                {/* WIKI4AI-108: `/admin` was never a route — only `/admin/users` and the
                    other admin sub-paths exist. A hard navigation to `/admin` therefore
                    matched nothing and rendered an empty screen. Redirect to the admin
                    page instead: it is the only admin landing page the app has, and the
                    nav menu already points there (UserMenu.tsx). Kept OUTSIDE the
                    protected tree so the redirect happens before auth is consulted —
                    `/admin` can no longer render nothing for anybody. */}
                <Route path="/admin" element={<Navigate to="/admin/users" replace />} />

                {/* Main app routes with Layout - all protected */}
                <Route path="/" element={<ProtectedRoute><Layout /></ProtectedRoute>}>
                  <Route index element={<Dashboard />} />
                  {/* Global (cross-project) search — WIKI4AI-61 */}
                  <Route path="search" element={<SearchPage />} />
                  {/* WIKI4AI-97: calendar — month view with events */}
                  <Route
                    path="calendar"
                    element={
                      <LazyRoute label={t('app.loading')}>
                        <CalendarPage />
                      </LazyRoute>
                    }
                  />
                  {/* WIKI4AI-161: coordination board — status columns, lease countdown,
                      capacity and live agents (10s polling, no WebSocket/SSE) */}
                  <Route
                    path="board"
                    element={
                      <LazyRoute label={t('app.loading')}>
                        <BoardPage />
                      </LazyRoute>
                    }
                  />
                  {/* WIKI4AI-162: work item detail — timeline, comments by kind,
                      handoff card and the reservations held for this item */}
                  <Route
                    path="board/:id"
                    element={
                      <LazyRoute label={t('app.loading')}>
                        <WorkItemDetailPage />
                      </LazyRoute>
                    }
                  />
                  {/* WIKI4AI-163: agents — handle, kind, project role, expired-lease
                      count from /metrics/actors, and token rotate / cancel */}
                  <Route
                    path="agents"
                    element={
                      <LazyRoute label={t('app.loading')}>
                        <AgentsPage />
                      </LazyRoute>
                    }
                  />
                  {/* WIKI4AI-164: capacity vs live reservations, TTL countdown,
                      manual release (idempotent 204) */}
                  <Route
                    path="resources"
                    element={
                      <LazyRoute label={t('app.loading')}>
                        <ResourcesPage />
                      </LazyRoute>
                    }
                  />
                  {/* WIKI4AI-164: time in state, cycle time and lease expiries —
                      every value is a field of ActorMetricsResponseDTO */}
                  <Route
                    path="metrics"
                    element={
                      <LazyRoute label={t('app.loading')}>
                        <MetricsPage />
                      </LazyRoute>
                    }
                  />
                  <Route path="vault" element={<VaultPage />} />
                  <Route path="profile" element={<ProfilePage />} />
                  <Route path="admin/users" element={<AdminUsersPage />} />
                  <Route path="projects/:slug" element={<ProjectDetail />} />
                  {/* Project Settings - edit project name, description and delete */}
                  <Route path="projects/:slug/settings" element={<ProjectSettings />} />
                  {/* Graph Visualization - shows document connections as a force-directed graph */}
                  <Route
                    path="projects/:slug/graph"
                    element={
                      <LazyRoute label={t('app.loadingGraph')}>
                        <GraphViewPage />
                      </LazyRoute>
                    }
                  />
                  {/* Document Viewer - reads document content with rendered markdown */}
                  <Route
                    path="projects/:slug/documents/:docId/view"
                    element={
                      <LazyRoute>
                        <DocumentViewer />
                      </LazyRoute>
                    }
                  />
                  {/* Document Editor - edit or create documents (catches docId for editing) */}
                  <Route
                    path="projects/:slug/documents/:docId/edit"
                    element={
                      <LazyRoute>
                        <DocumentEditor />
                      </LazyRoute>
                    }
                  />
                  {/* Fallback: navigate to viewer when no action specified */}
                  <Route
                    path="projects/:slug/documents/:docId"
                    element={
                      <LazyRoute>
                        <DocumentViewer />
                      </LazyRoute>
                    }
                  />
                </Route>

                {/* WIKI4AI-108: catch-all. Without it React Router matched nothing,
                    logged "No routes matched location" and committed an EMPTY tree —
                    no exception, so ErrorBoundary (WIKI4AI-27/38) correctly stayed out
                    and the user got a black screen with no explanation. Every typo,
                    every broken link and every stale bookmark lands here.
                    Standalone (no Layout, no ProtectedRoute), placed exactly like
                    /login: an anonymous visitor gets an explanation and a way home
                    instead of a silent bounce to the login form. */}
                <Route path="*" element={<NotFoundPage />} />
              </Routes>
            </ErrorBoundary>
          </VaultProvider>
        </AuthProvider>
      </BrowserRouter>
    </>
  )
}

export default App
