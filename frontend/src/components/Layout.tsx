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

  const handleCloseSidebar = useCallback(() => {
    setIsSidebarOpen(false)
  }, [])

  return (
    <div className="flex h-screen w-full bg-gray-50 dark:bg-gray-900 overflow-hidden">
      {/* Sidebar - hidden by default, slides in when hamburger is clicked */}
      <Sidebar isOpen={isSidebarOpen} onClose={handleCloseSidebar} />

      {/* Overlay backdrop - shown when sidebar is open */}
      {isSidebarOpen && (
        <div
          className="fixed inset-0 bg-black/50 z-40 transition-opacity"
          onClick={handleCloseSidebar}
          aria-hidden="true"
        />
      )}

      {/* Main Content Area */}
      <main className="flex-1 overflow-y-auto overflow-x-hidden min-w-0 flex flex-col">
        {/* Unified top bar - visible on all screen sizes */}
        <header className="sticky top-0 z-30 bg-white/80 dark:bg-gray-900/80 backdrop-blur-sm border-b border-gray-200 dark:border-gray-700 px-4 py-2 flex items-center gap-3">
          {/* Hamburger button - always visible */}
          <button
            type="button"
            onClick={handleToggleSidebar}
            className="p-2 rounded-lg text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200 hover:bg-gray-100 dark:hover:bg-gray-700 transition-colors flex-shrink-0"
            aria-label={isSidebarOpen ? 'Zavrieť menu' : 'Otvoriť menu'}
          >
            <svg width="24" height="24" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              {isSidebarOpen ? (
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
              ) : (
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 6h16M4 12h16M4 18h16" />
              )}
            </svg>
          </button>

          {/* Brand */}
          <span className="text-sm font-semibold text-gray-700 dark:text-gray-300 flex-shrink-0">Wiki4AI</span>
        </header>

        {/* Page content */}
        <div className="flex-1 max-w-7xl mx-auto w-full p-4">
          {children || <Outlet />}
        </div>
      </main>
    </div>
  )
}
