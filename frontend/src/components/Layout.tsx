import { Outlet, Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import AmbientBackground from './AmbientBackground';
import WikiLogo from './WikiLogo';
import './WikiLogo.css';

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
    <div className="flex h-screen w-full bg-[var(--dark-1)] overflow-hidden">
      {/* Ambient Background — fixed behind all content */}
      <AmbientBackground />

      {/* Main Content Area */}
      <main className="flex-1 overflow-y-auto overflow-x-hidden min-w-0 flex flex-col relative z-[2]">
        {/* Top bar with brand and auth controls — glassmorphism header */}
        <header
          className="sticky top-0 z-30 w-full px-4 py-2 flex items-center justify-between"
          style={{
            background: 'var(--glass-bg)',
            backdropFilter: 'blur(20px)',
            WebkitBackdropFilter: 'blur(20px)',
            borderBottom: '1px solid var(--glass-border)',
            position: 'sticky',
            top: 0,
          }}
        >
          {/* Top-edge highlight pseudo-element */}
          <div
            style={{
              position: 'absolute',
              top: 0,
              left: 0,
              right: 0,
              height: '1px',
              background: 'linear-gradient(90deg, transparent, rgba(255,255,255,0.2), transparent)',
              pointerEvents: 'none',
            }}
          />

          {/* Wiki4AI brand — hexagon SVG logo with neon glow */}
          <Link to="/" className="flex-shrink-0" data-testid="nav-logo">
            <WikiLogo size={56} />
          </Link>

          {/* Auth section */}
          <div className="flex items-center gap-3" data-testid="auth-section">
            {isAuthenticated && user ? (
              <>
                <Link
                  to="/vault"
                  className="text-xs px-3 py-1.5 rounded-md font-medium flex-shrink-0 text-white transition-all duration-[0.3s] ease hover:bg-purple-600 flex items-center gap-1.5"
                  style={{
                    background: '#8b5cf6',
                  }}
                  data-testid="nav-vault"
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M20 7h-3a2 2 0 0 0-2-2H9a2 2 0 0 0-2 2H4a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2z" />
                    <rect x="10" y="11" width="4" height="4" rx="1" />
                  </svg>
                  Vault
                </Link>
                {(user as any).role === 'ADMIN' && (
                  <Link
                    to="/admin/users"
                    className="text-xs px-3 py-1.5 rounded-md font-medium flex-shrink-0 text-white transition-all duration-[0.3s] ease"
                    style={{
                      background: '#f59e0b',
                    }}
                    data-testid="nav-admin-users"
                  >
                    Admin
                  </Link>
                )}
                <Link
                  to="/profile"
                  className="text-sm flex-shrink-0 transition-colors duration-[0.3s] ease hover:text-white text-gray-400"
                  data-testid="nav-profile"
                >
                  {user.username}
                </Link>
                <button
                  onClick={handleLogout}
                  className="text-xs px-3 py-1.5 rounded-md font-medium flex-shrink-0 text-white transition-all duration-[0.3s] ease hover:bg-red-600"
                  style={{
                    background: '#ef4444',
                  }}
                  data-testid="logout-button"
                >
                  Logout
                </button>
              </>
            ) : (
              <nav className="flex items-center gap-2">
                <Link
                  to="/login"
                  className="text-xs px-3 py-1.5 rounded-md font-medium flex-shrink-0 text-white transition-all duration-[0.3s] ease hover:bg-blue-600"
                  style={{
                    background: '#3b82f6',
                  }}
                  data-testid="nav-login"
                >
                  Login
                </Link>
                <Link
                  to="/register"
                  className="text-xs px-3 py-1.5 rounded-md font-medium flex-shrink-0 text-gray-300 transition-all duration-[0.3s] ease hover:text-white border border-white/20 hover:border-white/40"
                  data-testid="nav-register"
                >
                  Register
                </Link>
              </nav>
            )}
          </div>
        </header>

        {/* Page content — flex column so child pages can use flex: 1 to fill height */}
        <div className="flex-1 max-w-7xl mx-auto w-full p-4 relative z-[3] flex flex-col min-h-0">
          {children || <Outlet />}
        </div>
      </main>
    </div>
  );
}
