import { Outlet, Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';

interface LayoutProps {
  children?: React.ReactNode;
}

export default function Layout({ children }: LayoutProps) {
  const { user, isAuthenticated, logout } = useAuth();
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  // Get the first letter of username for avatar
  const avatarInitial = user ? user.username.charAt(0).toUpperCase() : '';
  const isAdmin = isAuthenticated && (user as any)?.role === 'ADMIN';

  return (
    <div className="flex h-screen w-full bg-gray-50 dark:bg-gray-900 overflow-hidden">
      {/* Main Content Area */}
      <main className="flex-1 overflow-y-auto overflow-x-hidden min-w-0 flex flex-col">
        {/* Modern top bar */}
        <header className="sticky top-0 z-30 bg-white/90 dark:bg-gray-900/90 backdrop-blur-md border-b border-gray-200/60 dark:border-gray-700/60 px-4 sm:px-6 py-3 flex items-center justify-between shadow-sm">
          {/* Brand */}
          <Link
            to="/"
            className="flex items-center gap-2.5 group"
            data-discover="true"
          >
            <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-violet-500 to-indigo-600 flex items-center justify-center shadow-md group-hover:shadow-lg transition-shadow">
              <svg className="w-4.5 h-4.5 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253" />
              </svg>
            </div>
            <span className="text-lg font-bold bg-gradient-to-r from-violet-600 to-indigo-600 dark:from-violet-400 dark:to-indigo-400 bg-clip-text text-transparent">
              Wiki4AI
            </span>
          </Link>

          {/* Auth section */}
          <div className="flex items-center gap-3" data-testid="auth-section">
            {isAuthenticated && user ? (
              <div className="flex items-center gap-2">
                {/* Admin link - subtle gear icon */}
                {isAdmin && (
                  <Link
                    to="/admin/users"
                    className="group/admin p-2 rounded-lg text-gray-400 hover:text-violet-600 dark:hover:text-violet-400 hover:bg-violet-50 dark:hover:bg-violet-900/20 transition-all"
                    data-testid="nav-admin-users"
                    title="Admin panel"
                  >
                    <svg className="w-5 h-5 group-hover/admin:rotate-90 transition-transform duration-300" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                      <path strokeLinecap="round" strokeLinejoin="round" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
                      <path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
                    </svg>
                  </Link>
                )}

                {/* User avatar + username */}
                <Link
                  to="/profile"
                  className="flex items-center gap-2.5 px-2 py-1.5 rounded-lg hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors group/user"
                  data-testid="nav-profile"
                >
                  <div className="w-7 h-7 rounded-full bg-gradient-to-br from-emerald-400 to-teal-500 flex items-center justify-center shadow-sm ring-2 ring-white dark:ring-gray-800 group-hover/user:ring-violet-200 dark:group-hover/user:ring-violet-800 transition-all">
                    <span className="text-xs font-bold text-white">{avatarInitial}</span>
                  </div>
                  <span className="text-sm font-medium text-gray-700 dark:text-gray-300 group-hover/user:text-violet-600 dark:group-hover/user:text-violet-400 transition-colors hidden sm:inline">
                    {user.username}
                  </span>
                </Link>

                {/* Logout - clean icon button */}
                <button
                  onClick={handleLogout}
                  className="p-2 rounded-lg text-gray-400 hover:text-red-500 dark:hover:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/20 transition-all"
                  data-testid="logout-button"
                  title="Logout"
                >
                  <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M17.982 18.72A18.096 18.096 0 0019.5 14.25a18.096 18.096 0 00-1.518-5.47m-3.77-2.42A12.05 12.05 0 009.75 3c-1.38 0-2.68.32-3.88.9M4.25 12a18.096 18.096 0 01-.522-5.47A18.096 18.096 0 002.25 14.25m15.732-6.72a12.05 12.05 0 01-3.77 2.42M6.75 18.75c.93.392 1.929.612 2.97.612.547 0 1.08-.054 1.594-.155" />
                  </svg>
                </button>
              </div>
            ) : (
              <nav className="flex items-center gap-2">
                <Link
                  to="/login"
                  className="px-4 py-2 rounded-lg bg-gradient-to-r from-violet-500 to-indigo-600 hover:from-violet-600 hover:to-indigo-700 text-white text-sm font-medium shadow-md hover:shadow-lg transition-all"
                  data-testid="nav-login"
                >
                  Login
                </Link>
                <Link
                  to="/register"
                  className="px-4 py-2 rounded-lg border border-gray-200 dark:border-gray-700 text-gray-600 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-gray-800 text-sm font-medium transition-all"
                  data-testid="nav-register"
                >
                  Register
                </Link>
              </nav>
            )}
          </div>
        </header>

        {/* Page content */}
        <div className="flex-1 max-w-7xl mx-auto w-full p-4">
          {children || <Outlet />}
        </div>
      </main>
    </div>
  );
}
