import { useState, useCallback, useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../contexts/AuthContext';
import { apiGet, apiPost, apiPut, apiDelete } from '../services/apiClient';
import { applyUserLanguage, isSupportedLanguage, SUPPORTED_LANGUAGES } from '../i18n';
import './Profile.css';

const API_BASE_URL = import.meta.env.VITE_API_URL || '/api/v1';

// WIKI4AI-73: mirrors USER_INFO_KEY in AuthContext — kept in sync so a language
// change is applied instantly on reload, before the /auth/me re-sync returns.
const USER_INFO_KEY = 'wiki4ai_user_info';

// ── Storage keys for generated API tokens (persist across logout/login) ────
const GENERATED_TOKEN_KEY = 'wiki4ai_generated_api_token';

interface StoredToken {
  tokenId: number;
  name: string;
  accessToken: string;
  expiresAt: string | null;
  createdAt: string;
}

const STORED_NAMED_TOKENS_KEY = 'wiki4ai_stored_named_tokens';

interface UserProfile {
  id: number;
  username: string;
  email: string;
  role: string;
  /** WIKI4AI-73: saved UI language preference. */
  language?: 'en' | 'sk';
  createdAt: string;
}

interface ProfileUpdateRequest {
  username?: string;
  email?: string;
  currentPassword?: string;
  newPassword?: string;
  /** WIKI4AI-73: UI language preference ('en' | 'sk'). */
  language?: string;
}

interface ApiTokenInfo {
  tokenId: number;
  name: string;
  expiresAt: string | null;
  createdAt: string;
}

// ── Unified token card data (combines stored + API tokens) ────────────
interface TokenCardData {
  tokenId: number;
  name: string;
  accessToken?: string; // only available for stored tokens
  expiresAt: string | null;
  createdAt: string;
}

export default function ProfilePage() {
  const { t, i18n } = useTranslation();
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  // WIKI4AI-73: language-aware date formatting (matches the dashboard pattern)
  const locale = i18n.language === 'sk' ? 'sk-SK' : 'en-GB';

  // Profile data from API (may have more fields than context user)
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [isLoadingProfile, setIsLoadingProfile] = useState(true);

  // WIKI4AI-73: UI language preference
  const [language, setLanguage] = useState<string>(user?.language ?? 'en');
  const [isUpdatingLanguage, setIsUpdatingLanguage] = useState(false);

  // Edit form state
  const [editUsername, setEditUsername] = useState('');
  const [editEmail, setEditEmail] = useState('');
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [updateMessage, setUpdateMessage] = useState<string | null>(null);
  const [updateError, setUpdateError] = useState<string | null>(null);
  const [isUpdating, setIsUpdating] = useState(false);

  // Token generation state (single token for backward compatibility)
  const [generatedToken, setGeneratedToken] = useState<string | null>(null);
  const [tokenCopied, setTokenCopied] = useState(false);
  const [isGeneratingToken, setIsGeneratingToken] = useState(false);

  // Token expiration settings (for single token generation)
  const [expiresAt, setExpiresAt] = useState<string>('');
  const [useCustomExpiry, setUseCustomExpiry] = useState(false);

  // Named API tokens state
  const [apiTokens, setApiTokens] = useState<ApiTokenInfo[]>([]);
  const [isFetchingTokens, setIsFetchingTokens] = useState(false);
  
  // New named token form state
  const [newTokenName, setNewTokenName] = useState('');
  const [newTokenExpiresAt, setNewTokenExpiresAt] = useState<string>('');
  const [useNewTokenExpiry, setUseNewTokenExpiry] = useState(false);
  const [isCreatingNamedToken, setIsCreatingNamedToken] = useState(false);

  // Stored named tokens (persist in localStorage for copy anytime)
  const [storedTokens, setStoredTokens] = useState<StoredToken[]>([]);

  // Load profile, tokens and stored named tokens on mount (and restore stored generated token)
  useEffect(() => {
    const loadProfile = async () => {
      try {
        const data = await apiGet<UserProfile>(`${API_BASE_URL}/auth/me`);
        setProfile(data);
        setEditUsername(data.username);
        setEditEmail(data.email);
        if (data.language) {
          setLanguage(data.language);
        }
      } catch {
        setUpdateError(t('profile.loadFailed'));
      } finally {
        setIsLoadingProfile(false);
      }
    };

    const restoreToken = () => {
      const storedToken = localStorage.getItem(GENERATED_TOKEN_KEY);
      if (storedToken) {
        setGeneratedToken(storedToken);
      }
    };

    const loadStoredNamedTokens = () => {
      try {
        const stored = localStorage.getItem(STORED_NAMED_TOKENS_KEY);
        if (stored) {
          setStoredTokens(JSON.parse(stored));
        }
      } catch {
        // Corrupted data — ignore
      }
    };

    loadProfile();
    restoreToken();
    loadStoredNamedTokens();
  }, []);

  // Load API tokens when profile is loaded
  useEffect(() => {
    const loadApiTokens = async () => {
      if (!profile?.id) return;
      
      setIsFetchingTokens(true);
      try {
        const tokens = await apiGet<ApiTokenInfo[]>(`${API_BASE_URL}/auth/tokens`);
        setApiTokens(Array.isArray(tokens) ? tokens : []);
      } catch {
        // Silently fail - tokens are optional, keep existing list
      } finally {
        setIsFetchingTokens(false);
      }
    };

    loadApiTokens();
  }, [profile?.id]);

  const handleUpdateProfile = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();
      setUpdateMessage(null);
      setUpdateError(null);
      setIsUpdating(true);

      const body: ProfileUpdateRequest = {};
      if (editUsername.trim()) body.username = editUsername.trim();
      if (editEmail.trim()) body.email = editEmail.trim();
      if (currentPassword) body.currentPassword = currentPassword;
      if (newPassword) body.newPassword = newPassword;

      try {
        const updatedProfile = await apiPut<UserProfile>(`${API_BASE_URL}/auth/me`, body);
        setProfile(updatedProfile);
        setUpdateMessage(t('profile.updated'));
        // Clear password fields after successful update
        setCurrentPassword('');
        setNewPassword('');
      } catch (err) {
        const message = err instanceof Error ? err.message : t('profile.updateFailed');
        setUpdateError(message);
      } finally {
        setIsUpdating(false);
      }
    },
    [editUsername, editEmail, currentPassword, newPassword, t],
  );

  // WIKI4AI-73: switch UI language — apply immediately, persist via PUT /auth/me.
  const handleLanguageChange = useCallback(
    async (lang: string) => {
      if (!isSupportedLanguage(lang) || lang === language) return;

      setIsUpdatingLanguage(true);
      const previous = language;
      setLanguage(lang);
      applyUserLanguage(lang); // instant UI switch, no reload

      try {
        await apiPut<UserProfile>(`${API_BASE_URL}/auth/me`, { language: lang });
        // Refresh the cached user so a page reload applies the new language
        // instantly, before the /auth/me re-sync in AuthContext returns.
        const cached = localStorage.getItem(USER_INFO_KEY);
        if (cached) {
          try {
            const parsed = JSON.parse(cached);
            parsed.language = lang;
            localStorage.setItem(USER_INFO_KEY, JSON.stringify(parsed));
          } catch {
            // Corrupted cache — the /auth/me re-sync will fix it on next load.
          }
        }
      } catch {
        // Roll back the UI switch if persistence failed.
        setLanguage(previous);
        applyUserLanguage(previous);
        setUpdateError(t('profile.languageSaveFailed'));
      } finally {
        setIsUpdatingLanguage(false);
      }
    },
    [language, t],
  );

  // Generate a single token (backward compatible)
  const handleGenerateToken = useCallback(async () => {
    setIsGeneratingToken(true);
    try {
      const requestBody: { expiresAt?: string } = {};
      if (useCustomExpiry && expiresAt) {
        requestBody.expiresAt = expiresAt;
      }

      const response = await apiPost<{ accessToken: string }>(
        `${API_BASE_URL}/auth/token`, 
        Object.keys(requestBody).length > 0 ? requestBody : undefined
      );
      
      setGeneratedToken(response.accessToken);
      localStorage.setItem(GENERATED_TOKEN_KEY, response.accessToken);
      setTokenCopied(false);
    } catch {
      setUpdateError(t('profile.generateTokenFailed'));
    } finally {
      setIsGeneratingToken(false);
    }
  }, [useCustomExpiry, expiresAt, t]);

  // Generate a named API token
  const handleCreateNamedToken = useCallback(async () => {
    if (!newTokenName.trim()) return;
    
    setIsCreatingNamedToken(true);
    try {
      const requestBody: { name: string; expiresAt?: string } = {
        name: newTokenName.trim(),
      };

      if (useNewTokenExpiry && newTokenExpiresAt) {
        requestBody.expiresAt = newTokenExpiresAt;
      }

      const response = await apiPost<{ accessToken: string; tokenId: number }>(
        `${API_BASE_URL}/auth/token/named`, 
        requestBody
      );

      // Save to localStorage so user can copy it anytime
      const newStoredToken: StoredToken = {
        tokenId: response.tokenId,
        name: newTokenName.trim(),
        accessToken: response.accessToken,
        expiresAt: useNewTokenExpiry && newTokenExpiresAt ? newTokenExpiresAt : null,
        createdAt: new Date().toISOString(),
      };

      const updatedStored = [...storedTokens, newStoredToken];
      setStoredTokens(updatedStored);
      localStorage.setItem(STORED_NAMED_TOKENS_KEY, JSON.stringify(updatedStored));

      // Show the generated token once (like before)
      setGeneratedToken(response.accessToken);
      
      // Refresh the tokens list
      if (profile?.id) {
        const tokens = await apiGet<ApiTokenInfo[]>(`${API_BASE_URL}/auth/tokens`);
        setApiTokens(tokens);
      }

      // Reset form
      setNewTokenName('');
      setNewTokenExpiresAt('');
      setUseNewTokenExpiry(false);
    } catch (err) {
      const message = err instanceof Error ? err.message : t('profile.createTokenFailed');
      setUpdateError(message);
    } finally {
      setIsCreatingNamedToken(false);
    }
  }, [newTokenName, newTokenExpiresAt, useNewTokenExpiry, profile?.id, storedTokens, t]);

  // ── Computed: merge stored tokens + API tokens into unified list ────
  const tokenCards = useMemo(() => {
    if (storedTokens.length === 0 && apiTokens.length === 0) return [];

    // Build a map from stored tokens (they have accessToken)
    const storedMap = new Map<number, StoredToken>();
    storedTokens.forEach((t) => storedMap.set(t.tokenId, t));

    // Start with API tokens (no accessToken available)
    const cards: TokenCardData[] = apiTokens.map((t) => ({
      tokenId: t.tokenId,
      name: t.name,
      expiresAt: t.expiresAt ? new Date(t.expiresAt).toISOString() : null,
      createdAt: t.createdAt,
    }));

    // Overlay stored tokens (they have accessToken for copy)
    storedMap.forEach((stored) => {
      const idx = cards.findIndex((c) => c.tokenId === stored.tokenId);
      if (idx >= 0) {
        cards[idx] = { ...cards[idx], accessToken: stored.accessToken };
      } else {
        // Stored token not in API list yet — add it
        cards.push({
          tokenId: stored.tokenId,
          name: stored.name,
          accessToken: stored.accessToken,
          expiresAt: stored.expiresAt ? new Date(stored.expiresAt).toISOString() : null,
          createdAt: stored.createdAt,
        });
      }
    });

    return cards;
  }, [storedTokens, apiTokens]);

  // ── Format helpers ────────────────────────────────────────────────
  const formatExpiration = (expiresAt: string | null): string => {
    if (!expiresAt) return t('profile.never');
    try {
      return new Date(expiresAt).toLocaleDateString(locale);
    } catch {
      return expiresAt;
    }
  };

  const isTokenExpired = (expiresAt: string | null): boolean => {
    if (!expiresAt) return false;
    try {
      return new Date(expiresAt) < new Date();
    } catch {
      return false;
    }
  };

  // ── Token preview (first 20 chars + ellipsis) ─────────────────────
  const tokenPreview = (token: string): string => {
    if (!token) return '';
    return token.length > 20 ? `${token.slice(0, 20)}…` : token;
  };

  // ── Copy handler with per-token feedback ───────────────────────────
  const [copiedTokenId, setCopiedTokenId] = useState<number | null>(null);

  const handleCopyStoredToken = useCallback((tokenId: number, accessToken: string) => {
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(accessToken).then(() => {
        setCopiedTokenId(tokenId);
        setTimeout(() => setCopiedTokenId(null), 2000);
      });
    } else {
      const textarea = document.createElement('textarea');
      textarea.value = accessToken;
      textarea.style.position = 'fixed';
      textarea.style.opacity = '0';
      document.body.appendChild(textarea);
      textarea.select();
      try {
        document.execCommand('copy');
        setCopiedTokenId(tokenId);
        setTimeout(() => setCopiedTokenId(null), 2000);
      } finally {
        document.body.removeChild(textarea);
      }
    }
  }, []);

  // ── Delete handler with toast ─────────────────────────────────────
  const [deleteToast, setDeleteToast] = useState<string | null>(null);

  const handleDeleteToken = useCallback(async (tokenId: number, tokenName: string) => {
    try {
      await apiDelete(`${API_BASE_URL}/auth/token/${tokenId}`);

      // Remove from localStorage too
      const updatedStored = storedTokens.filter((t) => t.tokenId !== tokenId);
      setStoredTokens(updatedStored);
      localStorage.setItem(STORED_NAMED_TOKENS_KEY, JSON.stringify(updatedStored));

      // Refresh the tokens list
      if (profile?.id) {
        const tokens = await apiGet<ApiTokenInfo[]>(`${API_BASE_URL}/auth/tokens`);
        setApiTokens(tokens);
      }

      setDeleteToast(t('profile.tokenDeleted', { name: tokenName }));
      setTimeout(() => setDeleteToast(null), 3000);
    } catch (err) {
      const message = err instanceof Error ? err.message : t('profile.deleteTokenFailed');
      
      // If the API returns 404, the token is already gone from DB — still clean localStorage
      if (message.includes('404')) {
        const updatedStored = storedTokens.filter((t) => t.tokenId !== tokenId);
        setStoredTokens(updatedStored);
        localStorage.setItem(STORED_NAMED_TOKENS_KEY, JSON.stringify(updatedStored));

        // Refresh the tokens list to remove it from UI
        if (profile?.id) {
          const tokens = await apiGet<ApiTokenInfo[]>(`${API_BASE_URL}/auth/tokens`);
          setApiTokens(tokens);
        }

        setDeleteToast(t('profile.tokenAlreadyDeleted', { name: tokenName }));
        setTimeout(() => setDeleteToast(null), 3000);
      } else {
        setUpdateError(message);
      }
    }
  }, [profile?.id, storedTokens, t]);

  const handleLogout = useCallback(() => {
    logout();
    navigate('/login');
  }, [logout, navigate]);

  // ── Legacy token copy (for the quick generate flow) ────────────────
  const handleCopyLegacyToken = useCallback(() => {
    if (!generatedToken) return;
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(generatedToken).then(() => {
        setTokenCopied(true);
        setTimeout(() => setTokenCopied(false), 2000);
      });
    } else {
      const textarea = document.createElement('textarea');
      textarea.value = generatedToken;
      textarea.style.position = 'fixed';
      textarea.style.opacity = '0';
      document.body.appendChild(textarea);
      textarea.select();
      try {
        document.execCommand('copy');
        setTokenCopied(true);
        setTimeout(() => setTokenCopied(false), 2000);
      } finally {
        document.body.removeChild(textarea);
      }
    }
  }, [generatedToken]);

  if (isLoadingProfile) {
    return (
      <div className="profile-page">
        <div className="loading-state">
          <div className="spinner" />
          <p>{t('profile.loading')}</p>
        </div>
      </div>
    );
  }

  const displayUser = profile || user;

  return (
    <div className="profile-page">
      <h1 className="page-title">{t('profile.title')}</h1>

      {/* Current Profile Info */}
      <section className="profile-section" data-testid="profile-info-section">
        <h2 className="section-title">{t('profile.infoTitle')}</h2>
        <div className="profile-info-card">
          <div className="info-row">
            <span className="info-label">{t('profile.usernameLabel')}:</span>
            <span className="info-value" data-testid="profile-username">{displayUser?.username ?? '—'}</span>
          </div>
          <div className="info-row">
            <span className="info-label">{t('profile.emailLabel')}:</span>
            <span className="info-value" data-testid="profile-email">{displayUser?.email ?? '—'}</span>
          </div>
          <div className="info-row">
            <span className="info-label">{t('profile.roleLabel')}:</span>
            <span className={`info-value role-badge role-${(displayUser as UserProfile)?.role?.toLowerCase() || 'user'}`} data-testid="profile-role">
              {(displayUser as UserProfile)?.role ?? (displayUser?.username ? 'USER' : '—')}
            </span>
          </div>
        </div>
      </section>

      {/* WIKI4AI-73: Language preference */}
      <section className="profile-section" data-testid="language-section">
        <h2 className="section-title">{t('profile.languageTitle')}</h2>
        <p className="section-description">{t('profile.languageDesc')}</p>
        <div className="form-group">
          <label htmlFor="profile-language" className="form-label">{t('profile.languageLabel')}</label>
          <select
            id="profile-language"
            className="form-input"
            value={language}
            onChange={(e) => handleLanguageChange(e.target.value)}
            disabled={isUpdatingLanguage}
            data-testid="language-select"
          >
            {SUPPORTED_LANGUAGES.map((code) => (
              <option key={code} value={code}>
                {t(code === 'en' ? 'profile.languageEn' : 'profile.languageSk')}
              </option>
            ))}
          </select>
        </div>
      </section>

      {/* Edit Profile Form */}
      <section className="profile-section" data-testid="edit-profile-section">
        <h2 className="section-title">{t('profile.editTitle')}</h2>

        {updateMessage && (
          <div className="success-message" role="alert" data-testid="update-success">
            {updateMessage}
          </div>
        )}
        {updateError && (
          <div className="error-message" role="alert" data-testid="update-error">
            {updateError}
          </div>
        )}

        <form onSubmit={handleUpdateProfile} className="profile-form" data-testid="edit-profile-form">
          <div className="form-group">
            <label htmlFor="profile-username" className="form-label">{t('profile.usernameField')}</label>
            <input
              id="profile-username"
              type="text"
              value={editUsername}
              onChange={(e) => setEditUsername(e.target.value)}
              className="form-input"
              placeholder={t('profile.usernamePlaceholder')}
              data-testid="edit-username"
            />
          </div>

          <div className="form-group">
            <label htmlFor="profile-email" className="form-label">{t('profile.emailField')}</label>
            <input
              id="profile-email"
              type="email"
              value={editEmail}
              onChange={(e) => setEditEmail(e.target.value)}
              className="form-input"
              placeholder={t('profile.emailPlaceholder')}
              data-testid="edit-email"
            />
          </div>

          <div className="form-group">
            <label htmlFor="current-password" className="form-label">{t('profile.currentPasswordField')}</label>
            <input
              id="current-password"
              type="password"
              value={currentPassword}
              onChange={(e) => setCurrentPassword(e.target.value)}
              className="form-input"
              placeholder={t('profile.currentPasswordPlaceholder')}
              data-testid="edit-current-password"
            />
          </div>

          <div className="form-group">
            <label htmlFor="new-password" className="form-label">{t('profile.newPasswordField')}</label>
            <input
              id="new-password"
              type="password"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              className="form-input"
              placeholder={t('profile.newPasswordPlaceholder')}
              data-testid="edit-new-password"
            />
          </div>

          <button
            type="submit"
            className="btn btn-primary"
            disabled={isUpdating}
            data-testid="update-profile-btn"
          >
            {isUpdating ? t('project.saving') : t('project.saveChanges')}
          </button>
        </form>
      </section>

      {/* API Token Section */}
      <section className="profile-section" data-testid="api-token-section">
        <h2 className="section-title">{t('profile.apiTokensTitle')}</h2>
        <p className="section-description">{t('profile.apiTokensDesc')}</p>

        {/* Named Token Creation Form */}
        <div className="form-group" style={{ marginTop: '16px' }}>
          <h3 style={{ fontSize: '14px', marginBottom: '8px' }}>{t('profile.createNewToken')}</h3>

          <div className="form-group">
            <label htmlFor="token-name" className="form-label">{t('profile.tokenNameField')}</label>
            <input
              id="token-name"
              type="text"
              value={newTokenName}
              onChange={(e) => setNewTokenName(e.target.value)}
              className="form-input"
              placeholder={t('profile.tokenNamePlaceholder')}
              data-testid="api-token-name"
            />
          </div>

          <div className="form-group">
            <label htmlFor="token-expiry" className="form-label">{t('profile.expirationField')}</label>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <input
                type="checkbox"
                id="use-new-token-expiry"
                checked={useNewTokenExpiry}
                onChange={(e) => setUseNewTokenExpiry(e.target.checked)}
                style={{ width: 'auto', marginRight: '8px' }}
              />
              <span style={{ fontSize: '14px', color: '#6b7280' }}>{t('profile.setCustomExpiry')}</span>
            </div>

            {useNewTokenExpiry && (
              <input
                id="token-expiry"
                type="datetime-local"
                value={newTokenExpiresAt}
                onChange={(e) => setNewTokenExpiresAt(e.target.value)}
                className="form-input"
                style={{ marginTop: '8px' }}
                data-testid="api-token-expiry-input"
              />
            )}

            <p style={{ fontSize: '12px', color: '#6b7280', marginTop: '4px' }}>
              {useNewTokenExpiry && newTokenExpiresAt
                ? t('profile.expiresAtHint', { when: new Date(newTokenExpiresAt).toLocaleString(locale) })
                : t('profile.neverExpiresHint')}
            </p>
          </div>

          <button
            onClick={handleCreateNamedToken}
            className="btn btn-secondary"
            disabled={isCreatingNamedToken || !newTokenName.trim()}
            data-testid="create-named-token-btn"
          >
            {isCreatingNamedToken ? t('profile.creating') : t('profile.createNamedToken')}
          </button>
        </div>

        {/* Unified Token Cards */}
        <div className="token-cards" data-testid="api-tokens-list">
          {isFetchingTokens && storedTokens.length === 0 ? (
            <p style={{ fontSize: '14px', color: '#6b7280' }}>{t('profile.loadingTokens')}</p>
          ) : tokenCards.length === 0 ? (
            <p style={{ fontSize: '14px', color: '#6b7280' }}>{t('profile.noTokensYet')}</p>
          ) : (
              tokenCards.map((token) => {
                const hasAccessToken = !!token.accessToken;
                return (
                  <div
                    key={`${token.tokenId}-${token.name}`}
                    className={`token-card ${isTokenExpired(token.expiresAt) ? 'expired' : ''}`}
                  >
                    <div className="token-card-info">
                      <div className="token-card-name">{token.name}</div>
                      {hasAccessToken && (
                        <div className="token-card-preview" title={token.accessToken!}>
                          {tokenPreview(token.accessToken!)}
                      </div>
                    )}
                    <div className="token-card-meta">
                      <span>{t('profile.createdLabel')}: {new Date(token.createdAt).toLocaleDateString(locale)}</span>
                      <span className={isTokenExpired(token.expiresAt) ? 'expired-text' : ''}>
                        {t('profile.expiresLabel')}: {formatExpiration(token.expiresAt)}
                      </span>
                    </div>
                  </div>

                  <div className="token-card-actions">
                    {hasAccessToken && (
                      <button
                        onClick={() => handleCopyStoredToken(token.tokenId, token.accessToken!)}
                        className={`btn-copy-token ${copiedTokenId === token.tokenId ? 'copied' : ''}`}
                        data-testid={`copy-stored-token-${token.tokenId}`}
                      >
                        {copiedTokenId === token.tokenId ? t('profile.copied') : t('profile.copy')}
                      </button>
                    )}
                    <button
                      onClick={() => handleDeleteToken(token.tokenId, token.name)}
                      className="btn-delete-token"
                      data-testid={`delete-token-${token.tokenId}`}
                    >
                      {t('common.delete')}
                    </button>
                  </div>
                </div>
              );
            })
          )}
        </div>

        {/* Legacy single token generation (backward compatible) */}
        <div style={{ marginTop: '24px', borderTop: '1px solid var(--glass-border)', paddingTop: '16px' }}>
          <h3 style={{ fontSize: '14px', marginBottom: '8px' }}>{t('profile.quickGenerateTitle')}</h3>

          {/* Expiration date picker */}
          <div className="form-group">
            <label htmlFor="token-expiry" className="form-label">{t('profile.tokenExpirationField')}</label>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <input
                type="checkbox"
                id="use-custom-expiry"
                checked={useCustomExpiry}
                onChange={(e) => setUseCustomExpiry(e.target.checked)}
                style={{ width: 'auto', marginRight: '8px' }}
              />
              <span style={{ fontSize: '14px', color: '#6b7280' }}>{t('profile.setCustomExpiry')}</span>
            </div>

            {useCustomExpiry && (
              <input
                id="token-expiry"
                type="datetime-local"
                value={expiresAt}
                onChange={(e) => setExpiresAt(e.target.value)}
                className="form-input"
                style={{ marginTop: '8px' }}
                data-testid="token-expiry-input"
              />
            )}

            <p style={{ fontSize: '12px', color: '#6b7280', marginTop: '4px' }}>
              {useCustomExpiry && expiresAt
                ? t('profile.expiresAtHint', { when: new Date(expiresAt).toLocaleString(locale) })
                : t('profile.neverExpiresHint')}
            </p>
          </div>

          <button
            onClick={handleGenerateToken}
            className="btn btn-secondary"
            disabled={isGeneratingToken || (useCustomExpiry && !expiresAt)}
            data-testid="generate-token-btn"
          >
            {isGeneratingToken ? t('profile.generating') : t('profile.generateNewToken')}
          </button>

          {/* Legacy token display */}
          {generatedToken && !newTokenName && (
            <div className="token-display" data-testid="legacy-token-display">
              <code>{generatedToken.slice(0, 40)}...</code>
              <button
                onClick={handleCopyLegacyToken}
                className="btn btn-small copy-btn"
                data-testid="copy-token-btn"
              >
                {tokenCopied ? t('profile.copied') : t('profile.copy')}
              </button>
            </div>
          )}
        </div>
      </section>

      {/* Delete toast */}
      {deleteToast && (
        <div style={{ position: 'fixed', bottom: '24px', right: '24px', zIndex: 1000, background: '#1f2937', color: '#fff', padding: '12px 16px', borderRadius: '8px', boxShadow: '0 4px 12px rgba(0,0,0,0.3)' }}>
          <span style={{ fontSize: '14px' }}>{deleteToast}</span>
        </div>
      )}

      {/* Logout */}
      <section className="profile-section profile-danger-zone">
        <h2 className="section-title section-danger">{t('project.dangerZone')}</h2>
        <p className="section-description">{t('profile.logoutDesc')}</p>
        <button
          onClick={handleLogout}
          className="btn btn-danger"
          data-testid="profile-logout-btn"
        >
          {t('layout.logout')}
        </button>
      </section>
    </div>
  );
}
