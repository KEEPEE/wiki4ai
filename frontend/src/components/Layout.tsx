import { Outlet } from 'react-router-dom'

interface LayoutProps {
  children?: React.ReactNode
}

export default function Layout({ children }: LayoutProps) {
  return (
    <div className="flex h-screen w-full bg-gray-50 dark:bg-gray-900 overflow-hidden">
      {/* Main Content Area */}
      <main className="flex-1 overflow-y-auto overflow-x-hidden min-w-0 flex flex-col">
        {/* Top bar with brand */}
        <header className="sticky top-0 z-30 bg-white/80 dark:bg-gray-900/80 backdrop-blur-sm border-b border-gray-200 dark:border-gray-700 px-4 py-2 flex items-center gap-3">
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
