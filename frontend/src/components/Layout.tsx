import { Outlet } from 'react-router-dom'
import Sidebar from './Sidebar'

interface LayoutProps {
  children?: React.ReactNode
}

export default function Layout({ children }: LayoutProps) {
  return (
    <div className="flex h-screen w-full bg-gray-50 dark:bg-gray-900 overflow-hidden">
      {/* Sidebar - fixed on mobile, flex child on desktop */}
      <Sidebar />

      {/* Main Content Area */}
      <main className="flex-1 overflow-y-auto overflow-x-hidden min-w-0">
        <div className="max-w-7xl mx-auto w-full">
          {children || <Outlet />}
        </div>
      </main>
    </div>
  )
}
