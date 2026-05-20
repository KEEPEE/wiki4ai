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

  return (
    <div className="flex h-screen w-full bg-gray-50 dark:bg-gray-900 overflow-hidden">
      {/* Main Content Area */}
      <main className="flex-1 overflow-y-auto overflow-x-hidden min-w-0 flex flex-col">
        {/* Top bar with brand and auth controls */}
        <header className="sticky top-0 z-30 bg-white/80 dark:bg-gray-900/80 backdrop-blur-sm border-b border-gray-200 dark:border-gray-700 px-4 py-2 flex items-center justify-between">
          <Link to="/" className="text-sm font-semibold text-gray-700 dark:text-gray-300 flex-shrink-0 hover:underline">
            Wiki4AI
          </Link>

          {/* Auth section */}
          <div className="flex items-center gap-3" data-testid="auth-section">
            {isAuthenticated && user ? (
              <>
                {(user as any).role === 'ADMIN' && (
                  <Link
                    to="/admin/users"
                    className="text-xs px-3 py-1.5 rounded-md bg-amber-600 hover:bg-amber-700 text-white font-medium transition-colors flex-shrink-0"
                    data-testid="nav-admin-users"
                  >
                    Admin
                  </Link>
                )}
                <Link
                  to="/profile"
                  className="text-sm text-gray-600 dark:text-gray-400 flex-shrink-0 hover:underline"
                  data-testid="nav-profile"
                >
                  {user.username}
                </Link>
                <button
                  onClick={handleLogout}
                  className="text-xs px-3 py-1.5 rounded-md bg-red-500 hover:bg-red-600 text-white font-medium transition-colors flex-shrink-0"
                  data-testid="logout-button"
                >
                  Logout
                </button>
              </>
            ) : (
              <nav className="flex items-center gap-2">
                <Link
                  to="/login"
                  className="text-xs px-3 py-1.5 rounded-md bg-blue-500 hover:bg-blue-600 text-white font-medium transition-colors"
                  data-testid="nav-login"
                >
                  Login
                </Link>
                <Link
                  to="/register"
                  className="text-xs px-3 py-1.5 rounded-md border border-gray-300 dark:border-gray-600 text-gray-700 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-800 font-medium transition-colors"
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
