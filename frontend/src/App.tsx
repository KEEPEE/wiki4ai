import { BrowserRouter, Routes, Route } from 'react-router-dom'
import Layout from './components/Layout'

// Placeholder pages - will be implemented in future stories
function Dashboard() {
  return (
    <div className="space-y-6">
      <h2 className="text-2xl font-bold text-gray-900 dark:text-white">Dashboard</h2>
      <p className="text-gray-600 dark:text-gray-400">Zoznam projektov bude zobrazený tu.</p>
    </div>
  )
}

function ProjectDetail() {
  return (
    <div className="space-y-6">
      <h2 className="text-2xl font-bold text-gray-900 dark:text-white">Project Detail</h2>
      <p className="text-gray-600 dark:text-gray-400">Detail projektu a zoznam dokumentov.</p>
    </div>
  )
}

function DocumentViewer() {
  return (
    <div className="space-y-6">
      <h2 className="text-2xl font-bold text-gray-900 dark:text-white">Document Viewer</h2>
      <p className="text-gray-600 dark:text-gray-400">Zobrazenie dokumentu.</p>
    </div>
  )
}

function DocumentEditor() {
  return (
    <div className="space-y-6">
      <h2 className="text-2xl font-bold text-gray-900 dark:text-white">Document Editor</h2>
      <p className="text-gray-600 dark:text-gray-400">Editor dokumentu.</p>
    </div>
  )
}

function GraphVisualization() {
  return (
    <div className="space-y-6">
      <h2 className="text-2xl font-bold text-gray-900 dark:text-white">Graph Visualization</h2>
      <p className="text-gray-600 dark:text-gray-400">Graf prepojení medzi dokumentmi.</p>
    </div>
  )
}

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Layout />}>
          <Route index element={<Dashboard />} />
          <Route path="project/:projectId" element={<ProjectDetail />} />
          <Route path="document/:documentId" element={<DocumentViewer />} />
          <Route path="editor/:documentId?" element={<DocumentEditor />} />
          <Route path="graph" element={<GraphVisualization />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}

export default App
