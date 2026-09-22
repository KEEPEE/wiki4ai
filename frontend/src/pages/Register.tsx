import { useState, useCallback } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import AmbientBackground from '../components/AmbientBackground';
import './Login.css';

/** Validation result for a single field */
interface FieldError {
  [field: string]: string | null;
}

/** Validate the registration form fields */
function validateForm(
  username: string,
  email: string,
  password: string,
): FieldError {
  const errors: FieldError = {
    username: null,
    email: null,
    password: null,
  };

  if (username.length < 3) {
    errors.username = 'Username must be at least 3 characters';
  }

  const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
  if (!emailRegex.test(email)) {
    errors.email = 'Please enter a valid email address';
  }

  if (password.length < 8) {
    errors.password = 'Password must be at least 8 characters';
  }

  return errors;
}

export default function Register() {
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [fieldErrors, setFieldErrors] = useState<FieldError>({
    username: null,
    email: null,
    password: null,
  });
  const [serverError, setServerError] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const { register, instanceInitialized, registrationOpen } = useAuth();
  const navigate = useNavigate();

  const handleSubmit = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();
      setServerError(null);

      // Client-side validation
      const errors = validateForm(username, email, password);
      if (errors.username || errors.email || errors.password) {
        setFieldErrors(errors);
        return;
      }
      setFieldErrors({ username: null, email: null, password: null });

      setIsLoading(true);

      try {
        await register(username, email, password);
        // Registration successful - redirect to login page
        navigate('/login');
      } catch (err) {
        if (err instanceof Error) {
          setServerError(err.message || 'Registration failed. Please try again.');
        } else {
          setServerError('Registration failed. Please try again.');
        }
      } finally {
        setIsLoading(false);
      }
    },
    [username, email, password, register, navigate],
  );

  // WIKI4AI-69: on a fresh instance the first account must be created through the
  // setup flow (it becomes ADMIN). Sending /register visitors to /setup preserves
  // the "first account = ADMIN" invariant — otherwise an anonymous visitor could
  // register a plain USER first and permanently close the setup endpoint.
  if (instanceInitialized === false) {
    return <Navigate to="/setup" replace />;
  }

  // WIKI4AI-70: the instance status probe is still in flight — wait for it so we
  // do not flash the form on an instance where registration is closed.
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

  // WIKI4AI-70: registration closed on this instance (default after the first
  // account exists). Show a clear message instead of a dead form.
  if (!registrationOpen) {
    return (
      <>
        <AmbientBackground />
        <div className="auth-page">
          <div className="auth-card">
            <h1 className="auth-title">Create Account</h1>
            <p className="auth-subtitle" data-testid="register-disabled">
              Registration is disabled on this instance.
            </p>
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

  return (
    <>
      <AmbientBackground />
      <div className="auth-page">
        <div className="auth-card">
          <h1 className="auth-title">Create Account</h1>

          {serverError && (
            <div className="auth-error" role="alert" data-testid="register-server-error">
              {serverError}
            </div>
          )}

          <form onSubmit={handleSubmit} className="auth-form" data-testid="register-form">
            <div className="form-group">
              <label htmlFor="reg-username" className="form-label">
                Username
              </label>
              <input
                id="reg-username"
                type="text"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                className={`form-input ${fieldErrors.username ? 'form-input-error' : ''}`}
                placeholder="Choose a username (min 3 chars)"
                required
                autoComplete="username"
                data-testid="register-username"
              />
              {fieldErrors.username && (
                <span className="field-error" data-testid="register-username-error">
                  {fieldErrors.username}
                </span>
              )}
            </div>

            <div className="form-group">
              <label htmlFor="reg-email" className="form-label">
                Email
              </label>
              <input
                id="reg-email"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className={`form-input ${fieldErrors.email ? 'form-input-error' : ''}`}
                placeholder="your@email.com"
                required
                autoComplete="email"
                data-testid="register-email"
              />
              {fieldErrors.email && (
                <span className="field-error" data-testid="register-email-error">
                  {fieldErrors.email}
                </span>
              )}
            </div>

            <div className="form-group">
              <label htmlFor="reg-password" className="form-label">
                Password
              </label>
              <input
                id="reg-password"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className={`form-input ${fieldErrors.password ? 'form-input-error' : ''}`}
                placeholder="Min 8 characters"
                required
                autoComplete="new-password"
                data-testid="register-password"
              />
              {fieldErrors.password && (
                <span className="field-error" data-testid="register-password-error">
                  {fieldErrors.password}
                </span>
              )}
            </div>

            <button
              type="submit"
              className="btn btn-primary auth-submit-btn"
              disabled={isLoading}
              data-testid="register-submit"
            >
              {isLoading ? 'Creating account...' : 'Register'}
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
