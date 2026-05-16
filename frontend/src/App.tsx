import { BrowserRouter, Routes, Route } from 'react-router-dom'
import Layout from './components/Layout'
import Dashboard from './pages/Dashboard'
import ProjectDetail from './pages/ProjectDetail'
import DocumentEditor from './pages/DocumentEditor'

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Layout />}>
          <Route index element={<Dashboard />} />
          <Route path="projects/:slug" element={<ProjectDetail />} />
          <Route path="projects/:slug/documents/:docId?" element={<DocumentEditor />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}

export default App
