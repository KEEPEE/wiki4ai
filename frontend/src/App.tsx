import { BrowserRouter, Routes, Route } from 'react-router-dom'
import { Suspense, lazy } from 'react'
import Layout from './components/Layout'
import Dashboard from './pages/Dashboard'
import ProjectDetail from './pages/ProjectDetail'

// Lazy-load components that have heavy dependencies (e.g., react-force-graph requires AFRAME)
const GraphViewPage = lazy(() => import('./pages/GraphViewPage'))
const DocumentViewer = lazy(() => import('./pages/DocumentViewer'))
const DocumentEditor = lazy(() => import('./pages/DocumentEditor'))

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Layout />}>
          <Route index element={<Dashboard />} />
          <Route path="projects/:slug" element={<ProjectDetail />} />
          {/* Graph Visualization - shows document connections as a force-directed graph */}
          <Route
            path="projects/:slug/graph"
            element={
              <Suspense fallback={<div className="loading-state"><div className="spinner" /><p>Loading graph...</p></div>}>
                <GraphViewPage />
              </Suspense>
            }
          />
          {/* Document Viewer - reads document content with rendered markdown */}
          <Route
            path="projects/:slug/documents/:docId/view"
            element={
              <Suspense fallback={<div className="loading-state"><div className="spinner" /><p>Loading...</p></div>}>
                <DocumentViewer />
              </Suspense>
            }
          />
          {/* Document Editor - edit or create documents (catches docId for editing) */}
          <Route
            path="projects/:slug/documents/:docId/edit"
            element={
              <Suspense fallback={<div className="loading-state"><div className="spinner" /><p>Loading...</p></div>}>
                <DocumentEditor />
              </Suspense>
            }
          />
          {/* Fallback: navigate to viewer when no action specified */}
          <Route
            path="projects/:slug/documents/:docId"
            element={
              <Suspense fallback={<div className="loading-state"><div className="spinner" /><p>Loading...</p></div>}>
                <DocumentViewer />
              </Suspense>
            }
          />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}

export default App
