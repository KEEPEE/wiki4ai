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
    <div>
      {/* Ambient Background — fixed behind all content */}
      <AmbientBackground />

      {/* Main Content Area */}
      <main>
        {/* Top bar with brand and auth controls — glassmorphism header */}
        <header
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
          <Link to="/" data-testid="nav-logo">
            <WikiLogo size={56} />
          </Link>

          {/* Auth section */}
          <div data-testid="auth-section">
            {isAuthenticated && user ? (
              <>
                <Link
                  to="/vault"
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
                  data-testid="nav-profile"
                >
                  {user.username}
                </Link>
                <button
                  onClick={handleLogout}
                  style={{
                    background: '#ef4444',
                  }}
                  data-testid="logout-button"
                >
                  Logout
                </button>
              </>
            ) : (
              <nav>
                <Link
                  to="/login"
                  style={{
                    background: '#3b82f6',
                  }}
                  data-testid="nav-login"
                >
                  Login
                </Link>
                <Link
                  to="/register"
                  data-testid="nav-register"
                >
                  Register
                </Link>
              </nav>
            )}
          </div>
        </header>

        {/* Page content — flex column so child pages can use flex: 1 to fill height */}
        <div>
          {children || <Outlet />}
        </div>
      </main>
    </div>
  );
}
