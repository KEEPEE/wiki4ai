import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';

interface ProtectedRouteProps {
  children: React.ReactNode;
}

/**
 * Wraps pages that require authentication.
 * - If user is authenticated → render children
 * - If not authenticated on an initialized instance → redirect to /login with ?redirect=current-path
 * - WIKI4AI-69: if not authenticated on a FRESH instance (no accounts yet) →
 *   redirect to the first-run setup form at /setup instead of /login
 * - While auth state or the instance status probe is still loading → show a spinner
 */
export default function ProtectedRoute({ children }: ProtectedRouteProps) {
  const { isAuthenticated, isLoading, instanceInitialized } = useAuth();
  const location = useLocation();

  // While checking auth state from localStorage (or the /auth/status probe),
  // prevent flicker. The status probe must resolve before we can decide between
  // /setup and /login for anonymous visitors.
  if (isLoading || instanceInitialized === null) {
    return (
      <div className="loading-state" style={{ display: 'flex', justifyContent: 'center', minHeight: '60vh' }}>
        <div className="spinner" />
      </div>
    );
  }

  if (!isAuthenticated) {
    // Encode the current path so user can return after login/setup
    const redirectUrl = encodeURIComponent(location.pathname + location.search);
    if (instanceInitialized === false) {
      // Fresh instance — first-run setup creates the initial ADMIN account.
      return <Navigate to={`/setup?redirect=${redirectUrl}`} replace />;
    }
    return <Navigate to={`/login?redirect=${redirectUrl}`} replace />;
  }

  return <>{children}</>;
}
