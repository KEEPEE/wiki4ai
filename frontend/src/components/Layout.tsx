import { Outlet } from 'react-router-dom'
import Sidebar from './Sidebar'

interface LayoutProps {
  children?: React.ReactNode
}

export default function Layout({ children }: LayoutProps) {
  return (
    <div className="flex h-screen bg-gray-50 dark:bg-gray-900">
      {/* Sidebar with project navigation */}
      <Sidebar />

      {/* Main Content Area */}
      <main className="flex-1 overflow-auto p-4 sm:p-6 lg:pl-72">
        {children || <Outlet />}
      </main>
    </div>
  )
}
