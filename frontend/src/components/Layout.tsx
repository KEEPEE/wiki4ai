import { Outlet } from 'react-router-dom'
import { useState, useCallback } from 'react'
import Sidebar from './Sidebar'

interface LayoutProps {
  children?: React.ReactNode
}

export default function Layout({ children }: LayoutProps) {
  const [isSidebarOpen, setIsSidebarOpen] = useState(false)

  const handleToggleSidebar = useCallback(() => {
    setIsSidebarOpen(prev => !prev)
  }, [])

  return (
    <div className={`flex h-screen w-full bg-gray-50 dark:bg-gray-900 overflow-hidden ${isSidebarOpen ? 'lg:flex' : ''}`}>
      {/* Sidebar - controlled by toggle state */}
      <Sidebar isOpen={isSidebarOpen} onToggle={handleToggleSidebar} />

      {/* Main Content Area */}
      <main className="flex-1 overflow-y-auto overflow-x-hidden min-w-0">
        <div className="max-w-7xl mx-auto w-full">
          {children || <Outlet />}
        </div>
      </main>
    </div>
  )
}
