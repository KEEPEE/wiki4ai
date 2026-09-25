import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useVault } from '../contexts/VaultContext';
import { cryptoApi } from '../services/cryptoApi';
// WIKI4AI-87: SVG icons (no emoji — the container has no emoji font)
import { EyeIcon, EyeOffIcon, LockIcon } from './icons';

const VaultSetupScreen: React.FC<{ isReinit?: boolean }> = ({ isReinit = false }) => {
  const { t } = useTranslation();
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

        <h1 className="vault-setup-title">{isReinit ? t('vault.setupTitleReinit') : t('vault.setupTitle')}</h1>
        <p className="vault-setup-description">
          {isReinit
            ? t('vault.setupDescReinit')
            : t('vault.setupDesc')}
        </p>

        {error && (
          <div className="vault-error-message">{error}</div>
        )}

        <form onSubmit={handleSubmit} className="vault-setup-form">
          <div className="vault-input-group">
            <label htmlFor="master-password" className="vault-label">{t('vault.masterPasswordLabel')}</label>
            <div className="vault-input-wrapper">
              <input
                id="master-password"
                type={showPassword ? 'text' : 'password'}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder={t('vault.masterPasswordPlaceholder')}
                data-testid="vault-setup-password-input"
                className="vault-input"
                required
              />
              <button
                type="button"
                onClick={() => setShowPassword(!showPassword)}
                className="vault-toggle-visibility"
                aria-label={showPassword ? t('vault.hidePassword') : t('vault.showPassword')}
              >
                {showPassword ? <EyeOffIcon size={18} /> : <EyeIcon size={18} />}
              </button>
            </div>
          </div>

          <div className="vault-input-group">
            <label htmlFor="confirm-password" className="vault-label">{t('vault.confirmPasswordLabel')}</label>
            <input
              id="confirm-password"
              type={showPassword ? 'text' : 'password'}
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
              placeholder={t('vault.confirmPasswordPlaceholder')}
              data-testid="vault-setup-confirm-input"
              className={`vault-input ${!passwordsMatch && confirmPassword ? 'vault-input-error' : ''}`}
              required
            />
          </div>

          <div className="vault-password-requirements">
            {!isStrongEnough && (
              <span className="vault-requirement vault-requirement-fail">{t('vault.reqMinChars')}</span>
            )}
            {isStrongEnough && (
              <span className="vault-requirement vault-requirement-pass">✓ {t('vault.reqMinChars')}</span>
            )}
            {!passwordsMatch && confirmPassword && (
              <span className="vault-requirement vault-requirement-fail">{t('vault.reqMatch')}</span>
            )}
            {passwordsMatch && (
              <span className="vault-requirement vault-requirement-pass">✓ {t('vault.reqMatchPass')}</span>
            )}
          </div>

          <button
            type="submit"
            disabled={isLoading || !isStrongEnough || !passwordsMatch}
            data-testid="vault-setup-submit-button"
            className={`vault-primary-button ${isLoading ? 'vault-button-loading' : ''}`}
          >
            {isLoading ? t('vault.settingUp') : t('vault.setupButton')}
          </button>
        </form>

        <p className="vault-security-note">
          <LockIcon size={14} /> {t('vault.securityNote')}
        </p>
      </div>
    </div>
  );
};

export default VaultSetupScreen;
