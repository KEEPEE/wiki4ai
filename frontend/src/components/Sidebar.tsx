import { Link } from 'react-router-dom'
import { useProjects } from '../hooks/useProjects'

export default function Sidebar() {
  const { projects, loading } = useProjects()

  return (
    <aside className="w-64 bg-white dark:bg-gray-800 border-r border-gray-200 dark:border-gray-700 flex flex-col">
      {/* Logo / Brand */}
      <div className="p-4 border-b border-gray-200 dark:border-gray-700">
        <Link to="/" className="flex items-center gap-2">
          <span className="text-xl font-bold text-indigo-600 dark:text-indigo-400">Wiki4AI</span>
        </Link>
      </div>

      {/* Navigation */}
      <nav className="flex-1 overflow-y-auto p-4">
        <Link
          to="/"
          className="block px-3 py-2 rounded-md text-sm font-medium text-gray-700 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700 mb-2"
        >
          Dashboard
        </Link>

        <div className="mt-4">
          <h3 className="text-xs uppercase tracking-wider text-gray-500 dark:text-gray-400 font-semibold px-3 mb-2">
            Projects
          </h3>
          {loading ? (
            <p className="px-3 text-sm text-gray-400">Loading...</p>
          ) : projects.length === 0 ? (
            <p className="px-3 text-sm text-gray-400 italic">No projects yet</p>
          ) : (
            projects.map((project) => (
              <Link
                key={project.id}
                to={`/projects/${project.slug}`}
                className="block px-3 py-2 rounded-md text-sm text-gray-600 dark:text-gray-400 hover:bg-gray-100 dark:hover:bg-gray-700 truncate"
              >
                {project.name}
              </Link>
            ))
          )}
        </div>
      </nav>

      {/* Footer */}
      <div className="p-4 border-t border-gray-200 dark:border-gray-700">
        <Link
          to="/"
          className="text-xs text-gray-500 hover:text-gray-700 dark:hover:text-gray-300"
        >
          Wiki4AI v0.0.1
        </Link>
      </div>
    </aside>
  )
}
