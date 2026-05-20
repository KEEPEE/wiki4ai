import { useState, useCallback } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import './Login.css';

export default function Login() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const { login } = useAuth();
  const navigate = useNavigate();

  const handleSubmit = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();
      setError(null);
      setIsLoading(true);

      try {
        await login(username, password);
        navigate('/');
      } catch (err) {
        if (err instanceof Error) {
          setError(err.message || 'Invalid credentials');
        } else {
          setError('Login failed. Please try again.');
        }
      } finally {
        setIsLoading(false);
      }
    },
    [username, password, login, navigate],
  );

  return (
    <div className="auth-page">
      <div className="auth-card">
        <h1 className="auth-title">Login to Wiki4AI</h1>

        {error && (
          <div className="auth-error" role="alert" data-testid="login-error">
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit} className="auth-form" data-testid="login-form">
          <div className="form-group">
            <label htmlFor="username" className="form-label">
              Username
            </label>
            <input
              id="username"
              type="text"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              className="form-input"
              placeholder="Enter your username"
              required
              autoComplete="username"
              data-testid="login-username"
            />
          </div>

          <div className="form-group">
            <label htmlFor="password" className="form-label">
              Password
            </label>
            <input
              id="password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="form-input"
              placeholder="Enter your password"
              required
              autoComplete="current-password"
              data-testid="login-password"
            />
          </div>

          <button
            type="submit"
            className="btn btn-primary auth-submit-btn"
            disabled={isLoading}
            data-testid="login-submit"
          >
            {isLoading ? 'Logging in...' : 'Login'}
          </button>
        </form>

        <p className="auth-footer">
          Don&apos;t have an account?{' '}
          <Link to="/register" className="auth-link">
            Register here
          </Link>
        </p>
      </div>
    </div>
  );
}
