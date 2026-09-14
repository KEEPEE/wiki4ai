import { BrowserRouter, Routes, Route } from 'react-router-dom'
import { Suspense, type ReactElement } from 'react'
import { AuthProvider } from './contexts/AuthContext'
import { VaultProvider } from './contexts/VaultContext'
import Layout from './components/Layout'
import ProtectedRoute from './components/ProtectedRoute'
import LoadingScreen from './components/LoadingScreen'
import ErrorBoundary from './components/ErrorBoundary'
import { lazyWithRetry } from './utils/lazyWithRetry'
import Dashboard from './pages/Dashboard'
import ProjectDetail from './pages/ProjectDetail'
import ProjectSettings from './pages/ProjectSettings'
import Login from './pages/Login'
import Register from './pages/Register'
import ProfilePage from './pages/ProfilePage'
import AdminUsersPage from './pages/AdminUsersPage'
import VaultPage from './pages/VaultPage'

// Lazy-load components that have heavy dependencies.
// lazyWithRetry (WIKI4AI-38) adds one retry + a guarded full reload so a failed
// chunk load can never leave the user with a black screen.
const GraphViewPage = lazyWithRetry(() => import('./pages/GraphViewPage'))
const DocumentViewer = lazyWithRetry(() => import('./pages/DocumentViewer'))
const DocumentEditor = lazyWithRetry(() => import('./pages/DocumentEditor'))

function LazyRouteFallback({ label = 'Loading...' }: { label?: string }) {
  return (
    <div className="loading-state">
      <div className="spinner" />
      <p>{label}</p>
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

                {/* Main app routes with Layout - all protected */}
                <Route path="/" element={<ProtectedRoute><Layout /></ProtectedRoute>}>
                  <Route index element={<Dashboard />} />
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
                      <LazyRoute label="Loading graph...">
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
              </Routes>
            </ErrorBoundary>
          </VaultProvider>
        </AuthProvider>
      </BrowserRouter>
    </>
  )
}

export default App
