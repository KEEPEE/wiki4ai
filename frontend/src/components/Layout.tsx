import { useState, useLayoutEffect } from 'react';
import { Outlet, Link, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../contexts/AuthContext';
import AmbientBackground from './AmbientBackground';
import WikiLogo from './WikiLogo';
import UserMenu from './UserMenu';
import { signalContentReady } from '../utils/appReady';
import './WikiLogo.css';
import './GlobalSearchBar.css';
import './Layout.css';

interface LayoutProps {
  children?: React.ReactNode;
}

export default function Layout({ children }: LayoutProps) {
  const { user, isAuthenticated } = useAuth();
  const navigate = useNavigate();
  const { t } = useTranslation();

  // WIKI4AI-82: signal that the app shell has rendered so the global splash
  // hands off to page-level loaders (single visible spinner). useLayoutEffect
  // fires before paint, so the splash fade and the page loader never overlap.
  useLayoutEffect(() => {
    signalContentReady();
  }, []);

  // WIKI4AI-82: expose the sticky top-nav height as --w4a-nav-height for
  // viewport-bounded pages (moved here from DocumentEditor, which was the only
  // consumer — GraphViewPage now needs it too so its graph container can fill
  // the remaining viewport height without page scroll).
  useLayoutEffect(() => {
    const nav = document.querySelector<HTMLElement>('main > header');
    if (!nav) return;
    const update = () => {
      document.documentElement.style.setProperty('--w4a-nav-height', `${nav.offsetHeight}px`);
    };
    update();
    const observer = new ResizeObserver(update);
    observer.observe(nav);
    window.addEventListener('resize', update);
    return () => {
      observer.disconnect();
      window.removeEventListener('resize', update);
    };
  }, []);

  // Global (cross-project) search — WIKI4AI-61. Submitting navigates to /search?q=...
  const [globalQuery, setGlobalQuery] = useState('');

  const handleGlobalSearchSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const q = globalQuery.trim();
    if (q.length >= 2) {
      navigate(`/search?q=${encodeURIComponent(q)}`);
    }
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

          {/* Global (cross-project) search bar — WIKI4AI-61. Login-only endpoint,
              so the bar is only shown for authenticated users. */}
          {isAuthenticated && (
            <form
              className="global-search-bar"
              onSubmit={handleGlobalSearchSubmit}
              role="search"
              data-testid="global-search-form"
            >
              <svg className="global-search-icon" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
              </svg>
              <input
                type="text"
                className="global-search-input"
                placeholder={t('layout.searchPlaceholder')}
                value={globalQuery}
                onChange={(e) => setGlobalQuery(e.target.value)}
                aria-label={t('layout.searchAriaLabel')}
                data-testid="global-search-input"
              />
            </form>
          )}

          {/* Auth section — WIKI4AI-85: single user menu (avatar + chevron)
              for authenticated users; login/register links when anonymous. */}
          <div data-testid="auth-section">
            {isAuthenticated && user ? (
              <UserMenu />
            ) : (
              <nav className="nav-anonymous">
                <Link to="/login" className="nav-auth-link nav-auth-login" data-testid="nav-login">
                  {t('layout.login')}
                </Link>
                <Link to="/register" className="nav-auth-link nav-auth-register" data-testid="nav-register">
                  {t('layout.register')}
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
