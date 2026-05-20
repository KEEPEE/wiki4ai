import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';

interface ProtectedRouteProps {
  children: React.ReactNode;
}

/**
 * Wraps pages that require authentication.
 * - If user is authenticated → render children
 * - If not authenticated → redirect to /login with ?redirect=current-path
 * - While auth state is still loading → show nothing (or a spinner)
 */
export default function ProtectedRoute({ children }: ProtectedRouteProps) {
  const { isAuthenticated, isLoading } = useAuth();
  const location = useLocation();

  // While checking auth state from localStorage, prevent flicker
  if (isLoading) {
    return (
      <div className="loading-state" style={{ display: 'flex', justifyContent: 'center', minHeight: '60vh' }}>
        <div className="spinner" />
      </div>
    );
  }

  if (!isAuthenticated) {
    // Encode the current path so user can return after login
    const redirectUrl = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/login?redirect=${redirectUrl}`} replace />;
  }

  return <>{children}</>;
}
