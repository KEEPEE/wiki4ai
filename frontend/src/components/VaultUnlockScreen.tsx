import React, { useState } from 'react';
import { useVault } from '../contexts/VaultContext';

const VaultUnlockScreen: React.FC = () => {
  const { unlock, isLoading, error } = useVault();
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!password) return;

    const encoder = new TextEncoder();
    const passwordBuffer = encoder.encode(password);

    const hashBuffer = await crypto.subtle.digest('SHA-256', passwordBuffer);
    const hashArray = Array.from(new Uint8Array(hashBuffer));
    const masterPasswordHash = hashArray.map(b => b.toString(16).padStart(2, '0')).join('');

    await unlock(masterPasswordHash);
  };

  return (
    <div className="vault-unlock-screen">
      <div className="vault-unlock-card">
        <div className="vault-unlock-icon">
          <svg width="48" height="48" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z" />
          </svg>
        </div>

        <h1 className="vault-unlock-title">Unlock your Vault</h1>
        <p className="vault-unlock-description">
          Enter your master password to access your vault entries.
        </p>

        {error && (
          <div className="vault-error-message">{error}</div>
        )}

        <form onSubmit={handleSubmit} className="vault-unlock-form">
          <div className="vault-input-group">
            <label htmlFor="unlock-password" className="vault-label">Master Password</label>
            <div className="vault-input-wrapper">
              <input
                id="unlock-password"
                type={showPassword ? 'text' : 'password'}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="Enter master password"
                data-testid="vault-unlock-password-input"
                className="vault-input"
                required
                autoFocus
              />
              <button
                type="button"
                onClick={() => setShowPassword(!showPassword)}
                className="vault-toggle-visibility"
                aria-label={showPassword ? 'Hide password' : 'Show password'}
              >
                {showPassword ? '🙈' : '👁️'}
              </button>
            </div>
          </div>

          <button
            type="submit"
            disabled={isLoading || !password}
            data-testid="vault-unlock-submit-button"
            className={`vault-primary-button ${isLoading ? 'vault-button-loading' : ''}`}
          >
            {isLoading ? 'Unlocking...' : 'Unlock'}
          </button>
        </form>

        <p className="vault-security-note">
          🔒 Your password is used locally to decrypt your vault data.
        </p>
      </div>
    </div>
  );
};

export default VaultUnlockScreen;
