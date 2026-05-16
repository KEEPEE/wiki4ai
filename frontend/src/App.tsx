import { BrowserRouter, Routes, Route } from 'react-router-dom'
import Layout from './components/Layout'
import Dashboard from './pages/Dashboard'
import ProjectDetail from './pages/ProjectDetail'
import DocumentEditor from './pages/DocumentEditor'
import DocumentViewer from './pages/DocumentViewer'

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Layout />}>
          <Route index element={<Dashboard />} />
          <Route path="projects/:slug" element={<ProjectDetail />} />
          {/* Document Viewer - reads document content with rendered markdown */}
          <Route path="projects/:slug/documents/:docId/view" element={<DocumentViewer />} />
          {/* Document Editor - edit or create documents (catches docId for editing) */}
          <Route path="projects/:slug/documents/:docId/edit" element={<DocumentEditor />} />
          {/* Fallback: navigate to viewer when no action specified */}
          <Route path="projects/:slug/documents/:docId" element={<DocumentViewer />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}

export default App
