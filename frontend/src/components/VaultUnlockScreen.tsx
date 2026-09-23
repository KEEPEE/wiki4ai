import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useVault } from '../contexts/VaultContext';
import { cryptoApi } from '../services/cryptoApi';

const VaultUnlockScreen: React.FC = () => {
  const { t } = useTranslation();
  const { unlock, isLoading, error } = useVault();
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!password) return;

    const encoder = new TextEncoder();
    const passwordBuffer = encoder.encode(password);

    const hashBuffer = await cryptoApi.sha256(passwordBuffer);
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

        <h1 className="vault-unlock-title">{t('vault.unlockTitle')}</h1>
        <p className="vault-unlock-description">
          {t('vault.unlockDesc')}
        </p>

        {error && (
          <div className="vault-error-message">{error}</div>
        )}

        <form onSubmit={handleSubmit} className="vault-unlock-form">
          <div className="vault-input-group">
            <label htmlFor="unlock-password" className="vault-label">{t('vault.masterPasswordLabel')}</label>
            <div className="vault-input-wrapper">
              <input
                id="unlock-password"
                type={showPassword ? 'text' : 'password'}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder={t('vault.unlockPasswordPlaceholder')}
                data-testid="vault-unlock-password-input"
                className="vault-input"
                required
                autoFocus
              />
              <button
                type="button"
                onClick={() => setShowPassword(!showPassword)}
                className="vault-toggle-visibility"
                aria-label={showPassword ? t('vault.hidePassword') : t('vault.showPassword')}
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
            {isLoading ? t('vault.unlocking') : t('vault.unlockButton')}
          </button>
        </form>

        <p className="vault-security-note">
          🔒 {t('vault.unlockSecurityNote')}
        </p>
      </div>
    </div>
  );
};

export default VaultUnlockScreen;
