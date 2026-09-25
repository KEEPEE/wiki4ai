import { useState, useCallback, useLayoutEffect } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import { useAuth } from '../contexts/AuthContext';
import AmbientBackground from '../components/AmbientBackground';
import { signalContentReady } from '../utils/appReady';
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
  t: TFunction,
): FieldError {
  const errors: FieldError = {
    username: null,
    email: null,
    password: null,
  };

  if (username.length < 3) {
    errors.username = t('register.validationUsernameTooShort');
  }

  const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
  if (!emailRegex.test(email)) {
    errors.email = t('register.validationInvalidEmail');
  }

  if (password.length < 8) {
    errors.password = t('register.validationPasswordTooShort');
  }

  return errors;
}

export default function Register() {
  // WIKI4AI-82: standalone route (no Layout) — hide the global splash as soon
  // as this page shell renders so no two spinners are ever visible at once.
  useLayoutEffect(() => {
    signalContentReady();
  }, []);

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
  const { t } = useTranslation();

  const handleSubmit = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();
      setServerError(null);

      // Client-side validation
      const errors = validateForm(username, email, password, t);
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
          setServerError(err.message || t('register.registrationFailed'));
        } else {
          setServerError(t('register.registrationFailed'));
        }
      } finally {
        setIsLoading(false);
      }
    },
    [username, email, password, register, navigate, t],
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
            <h1 className="auth-title">{t('register.title')}</h1>
            <p className="auth-subtitle" data-testid="register-disabled">
              {t('register.disabled')}
            </p>
            <p className="auth-footer">
              {t('auth.haveAccount')}{' '}
              <Link to="/login" className="auth-link">
                {t('auth.loginHere')}
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
          <h1 className="auth-title">{t('register.title')}</h1>

          {serverError && (
            <div className="auth-error" role="alert" data-testid="register-server-error">
              {serverError}
            </div>
          )}

          <form onSubmit={handleSubmit} className="auth-form" data-testid="register-form">
            <div className="form-group">
              <label htmlFor="reg-username" className="form-label">
                {t('auth.username')}
              </label>
              <input
                id="reg-username"
                type="text"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                className={`form-input ${fieldErrors.username ? 'form-input-error' : ''}`}
                placeholder={t('register.usernamePlaceholder')}
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
                {t('register.email')}
              </label>
              <input
                id="reg-email"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className={`form-input ${fieldErrors.email ? 'form-input-error' : ''}`}
                placeholder={t('register.emailPlaceholder')}
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
                {t('auth.password')}
              </label>
              <input
                id="reg-password"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className={`form-input ${fieldErrors.password ? 'form-input-error' : ''}`}
                placeholder={t('register.passwordPlaceholder')}
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
              {isLoading ? t('register.creatingAccount') : t('register.submit')}
            </button>
          </form>

          <p className="auth-footer">
            {t('auth.haveAccount')}{' '}
            <Link to="/login" className="auth-link">
              {t('auth.loginHere')}
            </Link>
          </p>
        </div>
      </div>
    </>
  );
}
