import React, { useState } from 'react';
import { useVault } from '../contexts/VaultContext';
import { cryptoApi } from '../services/cryptoApi';

const VaultSetupScreen: React.FC = () => {
  const { setupVault, isLoading, error } = useVault();
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!password || !confirmPassword) return;
    if (password !== confirmPassword) return;
    if (password.length < 8) return;

    const encoder = new TextEncoder();
    const passwordBuffer = encoder.encode(password);

    const hashBuffer = await cryptoApi.sha256(passwordBuffer);
    const hashArray = Array.from(new Uint8Array(hashBuffer));
    const masterPasswordHash = hashArray.map(b => b.toString(16).padStart(2, '0')).join('');

    await setupVault(masterPasswordHash);
  };

  const passwordsMatch = password && confirmPassword && password === confirmPassword;
  const isStrongEnough = password.length >= 8;

  return (
    <div className="vault-setup-screen">
      <div className="vault-setup-card">
        <div className="vault-setup-icon">
          <svg width="48" height="48" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z" />
          </svg>
        </div>

        <h1 className="vault-setup-title">Set up your Vault</h1>
        <p className="vault-setup-description">
          Create a master password to encrypt and protect your vault entries. This password is used locally to decrypt your data and cannot be recovered if lost.
        </p>

        {error && (
          <div className="vault-error-message">{error}</div>
        )}

        <form onSubmit={handleSubmit} className="vault-setup-form">
          <div className="vault-input-group">
            <label htmlFor="master-password" className="vault-label">Master Password</label>
            <div className="vault-input-wrapper">
              <input
                id="master-password"
                type={showPassword ? 'text' : 'password'}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="Enter master password (min 8 characters)"
                data-testid="vault-setup-password-input"
                className="vault-input"
                required
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

          <div className="vault-input-group">
            <label htmlFor="confirm-password" className="vault-label">Confirm Password</label>
            <input
              id="confirm-password"
              type={showPassword ? 'text' : 'password'}
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
              placeholder="Confirm master password"
              data-testid="vault-setup-confirm-input"
              className={`vault-input ${!passwordsMatch && confirmPassword ? 'vault-input-error' : ''}`}
              required
            />
          </div>

          <div className="vault-password-requirements">
            {!isStrongEnough && (
              <span className="vault-requirement vault-requirement-fail">At least 8 characters</span>
            )}
            {isStrongEnough && (
              <span className="vault-requirement vault-requirement-pass">✓ At least 8 characters</span>
            )}
            {!passwordsMatch && confirmPassword && (
              <span className="vault-requirement vault-requirement-fail">Passwords must match</span>
            )}
            {passwordsMatch && (
              <span className="vault-requirement vault-requirement-pass">✓ Passwords match</span>
            )}
          </div>

          <button
            type="submit"
            disabled={isLoading || !isStrongEnough || !passwordsMatch}
            data-testid="vault-setup-submit-button"
            className={`vault-primary-button ${isLoading ? 'vault-button-loading' : ''}`}
          >
            {isLoading ? 'Setting up...' : 'Set up Vault'}
          </button>
        </form>

        <p className="vault-security-note">
          🔒 Your master password is encrypted locally and never stored in plain text on our servers.
        </p>
      </div>
    </div>
  );
};

export default VaultSetupScreen;
