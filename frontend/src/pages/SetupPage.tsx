import { useState, useCallback } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import { setup as apiSetup } from '../services/authApi';
import AmbientBackground from '../components/AmbientBackground';
import './Login.css';

/** Validation result for a single field */
interface FieldError {
  [field: string]: string | null;
}

/** Validate the first-run setup form fields (WIKI4AI-69). */
function validateForm(username: string, password: string, confirm: string): FieldError {
  const errors: FieldError = {
    username: null,
    password: null,
    confirm: null,
  };

  if (username.length < 2) {
    errors.username = 'Username must be at least 2 characters';
  }

  if (password.length < 8) {
    errors.password = 'Password must be at least 8 characters';
  }

  if (confirm !== password) {
    errors.confirm = 'Passwords do not match';
  }

  return errors;
}

/**
 * WIKI4AI-69: first-run setup page.
 * Shown to an unauthenticated visitor on an instance that has no accounts yet
 * (GET /api/v1/auth/status → initialized=false). The account created here
 * becomes the first ADMIN of the instance; afterwards this route redirects to
 * /login and the setup endpoint is closed by the backend (403).
 */
export default function SetupPage() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [fieldErrors, setFieldErrors] = useState<FieldError>({
    username: null,
    password: null,
    confirm: null,
  });
  const [serverError, setServerError] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const { isAuthenticated, instanceInitialized, markInstanceInitialized } = useAuth();
  const navigate = useNavigate();

  // NOTE: all hooks (useState/useCallback) must run before any conditional return
  // below — react-hooks/rules-of-hooks.
  const handleSubmit = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();
      setServerError(null);

      const errors = validateForm(username, password, confirm);
      if (errors.username || errors.password || errors.confirm) {
        setFieldErrors(errors);
        return;
      }
      setFieldErrors({ username: null, password: null, confirm: null });

      setIsLoading(true);

      try {
        await apiSetup(username, password);
        // The instance is now initialized — update local state (so /login does not
        // bounce back here) and go to the login page.
        markInstanceInitialized();
        navigate('/login');
      } catch (err) {
        if (err instanceof Error) {
          setServerError(err.message || 'Setup failed. Please try again.');
        } else {
          setServerError('Setup failed. Please try again.');
        }
      } finally {
        setIsLoading(false);
      }
    },
    [username, password, confirm, markInstanceInitialized, navigate],
  );

  // Already authenticated → straight into the app.
  if (isAuthenticated) {
    return <Navigate to="/" replace />;
  }

  // Instance already initialized → setup is gone, go to login.
  if (instanceInitialized === true) {
    return <Navigate to="/login" replace />;
  }

  // Status probe still in flight → avoid flashing the form on an initialized instance.
  if (instanceInitialized === null) {
    return (
      <>
        <AmbientBackground />
        <div className="auth-page">
          <div className="auth-card">
            <div className="loading-state" style={{ display: 'flex', justifyContent: 'center' }}>
              <div className="spinner" />
            </div>
          </div>
        </div>
      </>
    );
  }

  return (
    <>
      <AmbientBackground />
      <div className="auth-page">
        <div className="auth-card">
          <h1 className="auth-title">First-time setup</h1>
          <p className="auth-subtitle" data-testid="setup-subtitle">
            This instance has no accounts yet. The account you create now will become
            the administrator.
          </p>

          {serverError && (
            <div className="auth-error" role="alert" data-testid="setup-error">
              {serverError}
            </div>
          )}

          <form onSubmit={handleSubmit} className="auth-form" data-testid="setup-form">
            <div className="form-group">
              <label htmlFor="setup-username" className="form-label">
                Username
              </label>
              <input
                id="setup-username"
                type="text"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                className={`form-input ${fieldErrors.username ? 'form-input-error' : ''}`}
                placeholder="Choose a username (min 2 chars)"
                required
                autoComplete="username"
                data-testid="setup-username"
              />
              {fieldErrors.username && (
                <span className="field-error" data-testid="setup-username-error">
                  {fieldErrors.username}
                </span>
              )}
            </div>

            <div className="form-group">
              <label htmlFor="setup-password" className="form-label">
                Password
              </label>
              <input
                id="setup-password"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className={`form-input ${fieldErrors.password ? 'form-input-error' : ''}`}
                placeholder="Min 8 characters"
                required
                autoComplete="new-password"
                data-testid="setup-password"
              />
              {fieldErrors.password && (
                <span className="field-error" data-testid="setup-password-error">
                  {fieldErrors.password}
                </span>
              )}
            </div>

            <div className="form-group">
              <label htmlFor="setup-confirm" className="form-label">
                Confirm password
              </label>
              <input
                id="setup-confirm"
                type="password"
                value={confirm}
                onChange={(e) => setConfirm(e.target.value)}
                className={`form-input ${fieldErrors.confirm ? 'form-input-error' : ''}`}
                placeholder="Repeat the password"
                required
                autoComplete="new-password"
                data-testid="setup-confirm"
              />
              {fieldErrors.confirm && (
                <span className="field-error" data-testid="setup-confirm-error">
                  {fieldErrors.confirm}
                </span>
              )}
            </div>

            <button
              type="submit"
              className="btn btn-primary auth-submit-btn"
              disabled={isLoading}
              data-testid="setup-submit"
            >
              {isLoading ? 'Creating admin account...' : 'Create admin account'}
            </button>
          </form>

          <p className="auth-footer">
            Already have an account?{' '}
            <Link to="/login" className="auth-link">
              Login here
            </Link>
          </p>
        </div>
      </div>
    </>
  );
}
