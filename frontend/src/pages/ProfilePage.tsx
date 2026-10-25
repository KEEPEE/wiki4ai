import { useState, useCallback, useEffect, useMemo, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { apiGet, apiPost, apiPut } from '../services/apiClient';
import { listTokens, createToken, rotateToken, deleteToken } from '../services/tokenApi';
import type { TokenInfo } from '../services/tokenApi';
import {
  readStoredTokenMenu,
  writeStoredTokenMenu,
  clearLegacyGeneratedTokenPlaintext,
} from '../utils/tokenStorage';
import type { StoredTokenMenuEntry } from '../utils/tokenStorage';
import './Profile.css';

const API_BASE_URL = import.meta.env.VITE_API_URL || '/api/v1';

/**
 * WIKI4AI-168: the plaintext of a named token is NOT stored anywhere. It exists
 * only in this component's state, between the create/rotate response and either
 * a page reload or an unmount. What survives in localStorage is the token menu
 * (id, name, scopes, dates, display prefix, hashed flag) — see utils/tokenStorage.
 */
type StoredToken = StoredTokenMenuEntry;

/**
 * Plaintext handed to the user exactly once. `rotated` only changes which
 * wording the panel uses; the lifetime rule is the same for both.
 */
interface OneTimeToken {
  tokenId: number;
  name: string;
  token: string;
  rotated: boolean;
}

/**
 * WIKI4AI-138: scopes offered when creating a token. They mirror the backend's
 * allowed set (`read`, `write`, `admin`) and its default (`read` + `write`).
 * They are always sent as an array — the backend rejects a string with a JSON
 * parse error (500).
 */
const TOKEN_SCOPE_OPTIONS = ['read', 'write', 'admin'] as const;
const DEFAULT_TOKEN_SCOPES: string[] = ['read', 'write'];

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

/**
 * WIKI4AI-138: the token list now comes from `GET /api/v1/tokens`, which is the only
 * listing that says whether a token is hashed (`hashed: true`) or a legacy clear-text
 * JWT (`hashed: false`). The legacy `GET /auth/tokens` has no such field, so the
 * deprecation banner of this story could not be built on it.
 *
 * The API field is called `id`; the whole page (and localStorage) keeps calling it
 * `tokenId`, so `fromApiToken` maps one onto the other.
 */
interface ApiTokenInfo {
  tokenId: number;
  name: string;
  expiresAt: string | null;
  createdAt: string;
  /** false = legacy clear-text JWT: never expires, cannot be revoked or rotated. */
  hashed: boolean;
  /** Display handle of a hashed token; null for legacy tokens. */
  prefix: string | null;
  /** Granted scopes; empty for legacy tokens. */
  scopes: string[];
  /** Set when the token was revoked. */
  revokedAt: string | null;
  /** Last authenticated request seen for this token (throttled). */
  lastUsedAt: string | null;
}

/** Map the API shape (`id`) onto the shape the rest of the page uses (`tokenId`). */
function fromApiToken(token: TokenInfo): ApiTokenInfo {
  return {
    tokenId: token.id,
    name: token.name,
    expiresAt: token.expiresAt ?? null,
    createdAt: token.createdAt,
    hashed: token.hashed === true,
    prefix: token.prefix ?? null,
    scopes: Array.isArray(token.scopes) ? token.scopes : [],
    revokedAt: token.revokedAt ?? null,
    lastUsedAt: token.lastUsedAt ?? null,
  };
}

// ── Unified token card data (combines stored menu + API tokens) ────────────
// WIKI4AI-168: no `accessToken` field here on purpose. A card can only ever show
// what the listing returns, which is the display prefix — never the secret.
interface TokenCardData {
  tokenId: number;
  name: string;
  expiresAt: string | null;
  createdAt: string;
  hashed: boolean;
  prefix: string | null;
  scopes: string[];
  revokedAt: string | null;
  lastUsedAt: string | null;
}

export default function ProfilePage() {
  const { t, i18n } = useTranslation();

  // WIKI4AI-73: language-aware date formatting (matches the dashboard pattern)
  const locale = i18n.language === 'sk' ? 'sk-SK' : 'en-GB';

  // Profile data from API (may have more fields than context user)
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [isLoadingProfile, setIsLoadingProfile] = useState(true);

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
  // WIKI4AI-138: scopes are sent as an array (a string would be a 500 server-side).
  const [selectedScopes, setSelectedScopes] = useState<string[]>([...DEFAULT_TOKEN_SCOPES]);

  // WIKI4AI-138 + WIKI4AI-168: plaintext of a freshly created or rotated token.
  // Shown exactly once, held only in state, cleared on unmount. Nothing else in
  // this page can reach it — the token cards read the API listing, not this value.
  const [oneTimeToken, setOneTimeToken] = useState<OneTimeToken | null>(null);
  const [oneTimeCopied, setOneTimeCopied] = useState(false);
  const [isRotatingTokenId, setIsRotatingTokenId] = useState<number | null>(null);

  // Stored named tokens: the menu only (WIKI4AI-168 — no plaintext in localStorage).
  const [storedTokens, setStoredTokens] = useState<StoredToken[]>([]);

  // WIKI4AI-175: every timer started here is tracked so none of them can outlive
  // the component. An untracked timer firing after teardown is the known flake.
  const timersRef = useRef<number[]>([]);
  const scheduleTimer = useCallback((fn: () => void, ms: number) => {
    const id = window.setTimeout(() => {
      timersRef.current = timersRef.current.filter((timerId) => timerId !== id);
      fn();
    }, ms);
    timersRef.current.push(id);
  }, []);

  // Load profile and the stored token menu on mount.
  // WIKI4AI-168: no plaintext is restored, because none may be stored. Older
  // builds did store it — `readStoredTokenMenu()` rewrites the key without it,
  // and the deprecated quick-generate key is dropped outright.
  useEffect(() => {
    const loadProfile = async () => {
      try {
        const data = await apiGet<UserProfile>(`${API_BASE_URL}/auth/me`);
        setProfile(data);
        setEditUsername(data.username);
        setEditEmail(data.email);
      } catch {
        setUpdateError(t('profile.loadFailed'));
      } finally {
        setIsLoadingProfile(false);
      }
    };

    const loadStoredNamedTokens = () => {
      setStoredTokens(readStoredTokenMenu());
    };

    loadProfile();
    loadStoredNamedTokens();
    clearLegacyGeneratedTokenPlaintext();
  }, []);

  // WIKI4AI-168: the plaintext leaves the page with the page. WIKI4AI-175: no
  // timer of this page may fire after it is gone.
  useEffect(() => {
    return () => {
      timersRef.current.forEach((timerId) => window.clearTimeout(timerId));
      timersRef.current = [];
      setOneTimeToken(null);
      setGeneratedToken(null);
    };
  }, []);

  // WIKI4AI-138: every token read goes through the new hashed-token listing
  // (GET /api/v1/tokens) — it is the only one that reports `hashed`.
  const refreshTokenList = useCallback(async () => {
    setIsFetchingTokens(true);
    try {
      const tokens = await listTokens();
      setApiTokens(Array.isArray(tokens) ? tokens.map(fromApiToken) : []);
    } catch {
      // Silently fail - tokens are optional, keep existing list
    } finally {
      setIsFetchingTokens(false);
    }
  }, []);

  // Load API tokens when profile is loaded
  useEffect(() => {
    if (!profile?.id) return;
    refreshTokenList();
  }, [profile?.id, refreshTokenList]);

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

  // WIKI4AI-85: the UI language switch moved out of this page into the top-nav
  // user menu (see components/UserMenu.tsx + hooks/useUserLanguage.ts).

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
      
      // WIKI4AI-168: state only. This flow used to persist the plaintext JWT under
      // `wiki4ai_generated_api_token`; that key is now cleared on mount instead.
      setGeneratedToken(response.accessToken);
      setTokenCopied(false);
    } catch {
      setUpdateError(t('profile.generateTokenFailed'));
    } finally {
      setIsGeneratingToken(false);
    }
  }, [useCustomExpiry, expiresAt, t]);

  // Generate a named API token.
  // WIKI4AI-138: this used `POST /auth/token/named`, which mints a legacy clear-text
  // JWT — infinite lifetime, no scopes, not revocable. It now uses
  // `POST /api/v1/tokens`, so every token the UI creates is hashed, scoped and
  // revocable.
  // WIKI4AI-168: the plaintext arrives exactly once and is kept in state only, for
  // this mount. What goes to localStorage is the menu — id, name, scopes, dates,
  // prefix, hashed flag — and never the secret.
  const handleCreateNamedToken = useCallback(async () => {
    if (!newTokenName.trim()) return;

    setIsCreatingNamedToken(true);
    try {
      const expiresAt = useNewTokenExpiry && newTokenExpiresAt ? newTokenExpiresAt : undefined;
      const response = await createToken(newTokenName.trim(), [...selectedScopes], expiresAt);

      const metadata = response.metadata;
      const tokenName = metadata.name ?? newTokenName.trim();

      const newStoredToken: StoredToken = {
        tokenId: metadata.id,
        name: tokenName,
        expiresAt: metadata.expiresAt ?? null,
        createdAt: metadata.createdAt ?? new Date().toISOString(),
        hashed: true,
        prefix: metadata.prefix ?? null,
        scopes: Array.isArray(metadata.scopes) ? [...metadata.scopes] : [],
      };

      const updatedStored = [...storedTokens, newStoredToken];
      setStoredTokens(updatedStored);
      writeStoredTokenMenu(updatedStored);

      // The single chance to see and copy this token.
      setOneTimeToken({ tokenId: metadata.id, name: tokenName, token: response.token, rotated: false });
      setOneTimeCopied(false);

      // Refresh the tokens list
      if (profile?.id) {
        await refreshTokenList();
      }

      // Reset form
      setNewTokenName('');
      setNewTokenExpiresAt('');
      setUseNewTokenExpiry(false);
      setSelectedScopes([...DEFAULT_TOKEN_SCOPES]);
    } catch (err) {
      const message = err instanceof Error ? err.message : t('profile.createTokenFailed');
      setUpdateError(message);
    } finally {
      setIsCreatingNamedToken(false);
    }
  }, [newTokenName, newTokenExpiresAt, useNewTokenExpiry, selectedScopes, profile?.id, storedTokens, refreshTokenList, t]);

  // ── Computed: merge stored menu + API tokens into unified list ──────
  const tokenCards = useMemo(() => {
    if (storedTokens.length === 0 && apiTokens.length === 0) return [];

    // Build a map from the stored menu (metadata only — WIKI4AI-168).
    const storedMap = new Map<number, StoredToken>();
    storedTokens.forEach((t) => storedMap.set(t.tokenId, t));

    // Start with API tokens. `hashed` comes straight from the API — it is the only
    // source of truth for legacy vs hashed, and the API is the only source of a
    // display prefix. It never returns a plaintext or a hash.
    const cards: TokenCardData[] = apiTokens.map((t) => ({
      tokenId: t.tokenId,
      name: t.name,
      expiresAt: t.expiresAt ? new Date(t.expiresAt).toISOString() : null,
      createdAt: t.createdAt,
      hashed: t.hashed,
      prefix: t.prefix,
      scopes: t.scopes,
      revokedAt: t.revokedAt,
      lastUsedAt: t.lastUsedAt,
    }));

    // Fill in tokens the listing has not returned yet (just created, or created on
    // another device before this cache existed). Still metadata only.
    storedMap.forEach((stored) => {
      const idx = cards.findIndex((c) => c.tokenId === stored.tokenId);
      if (idx >= 0) {
        cards[idx] = {
          ...cards[idx],
          prefix: cards[idx].prefix ?? stored.prefix,
          scopes: cards[idx].scopes.length > 0 ? cards[idx].scopes : stored.scopes,
        };
      } else {
        // A stored entry saved before WIKI4AI-138 has no `hashed` flag; those are
        // the legacy clear-text JWTs, so an unknown flag is treated as legacy.
        cards.push({
          tokenId: stored.tokenId,
          name: stored.name,
          expiresAt: stored.expiresAt ? new Date(stored.expiresAt).toISOString() : null,
          createdAt: stored.createdAt,
          hashed: stored.hashed === true,
          prefix: stored.prefix,
          scopes: stored.scopes,
          revokedAt: null,
          lastUsedAt: null,
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

  // ── Copy helper (WIKI4AI-168) ───────────────────────────────────────
  //
  // There is no "copy a stored token" action any more: a stored token has no
  // plaintext to copy. Copy exists only for a secret that is currently in this
  // component's state — a freshly created or freshly rotated token — and the
  // textarea fallback is fed from that state, never from storage.
  const copySecret = useCallback(
    (secret: string, onCopied: () => void) => {
      if (navigator.clipboard?.writeText) {
        navigator.clipboard
          .writeText(secret)
          .then(onCopied)
          .catch(() => {
            // Clipboard permission denied — the token is still on screen to copy by hand.
          });
        return;
      }

      const textarea = document.createElement('textarea');
      textarea.value = secret;
      textarea.style.position = 'fixed';
      textarea.style.opacity = '0';
      document.body.appendChild(textarea);
      textarea.select();
      try {
        document.execCommand('copy');
        onCopied();
      } finally {
        // The fallback textarea holds the plaintext for the length of one call.
        textarea.value = '';
        document.body.removeChild(textarea);
      }
    },
    [],
  );

  // ── Delete handler with toast ─────────────────────────────────────
  const [deleteToast, setDeleteToast] = useState<string | null>(null);

  // WIKI4AI-138: this used `DELETE /auth/token/{id}`, which hard-deletes the row and
  // never evicts the backend validation cache — a hashed token could keep working
  // for up to 60 s after being "deleted". `DELETE /api/v1/tokens/{id}` soft-revokes
  // the row AND evicts the cache, so the token stops working immediately.
  const handleDeleteToken = useCallback(async (tokenId: number, tokenName: string) => {
    // WIKI4AI-168: revoking a token also drops its menu entry and any plaintext
    // of that token still held in state — a revoked secret must not stay reachable.
    const dropStoredToken = () => {
      const updatedStored = storedTokens.filter((t) => t.tokenId !== tokenId);
      setStoredTokens(updatedStored);
      writeStoredTokenMenu(updatedStored);
      setOneTimeToken((current) => (current && current.tokenId === tokenId ? null : current));
    };

    try {
      await deleteToken(tokenId);

      // Remove the menu entry too.
      dropStoredToken();

      // Refresh the tokens list
      if (profile?.id) {
        await refreshTokenList();
      }

      setDeleteToast(t('profile.tokenDeleted', { name: tokenName }));
      scheduleTimer(() => setDeleteToast(null), 3000);
    } catch (err) {
      const message = err instanceof Error ? err.message : t('profile.deleteTokenFailed');

      // If the API returns 404, the token is already gone from DB — still clean the menu
      if (message.includes('404')) {
        dropStoredToken();

        // Refresh the tokens list to remove it from UI
        if (profile?.id) {
          await refreshTokenList();
        }

        setDeleteToast(t('profile.tokenAlreadyDeleted', { name: tokenName }));
        scheduleTimer(() => setDeleteToast(null), 3000);
      } else {
        setUpdateError(message);
      }
    }
  }, [profile?.id, storedTokens, refreshTokenList, scheduleTimer, t]);

  // ── Rotate handler (WIKI4AI-138, endpoint from WIKI4AI-137) ───────
  //
  // Rotation is only defined for hashed tokens: a legacy row holds a clear-text JWT
  // whose text cannot be re-issued, so the backend answers 400. The button is
  // therefore rendered for `hashed === true` only, and the handler keeps a guard so
  // a stale UI state surfaces as an error message instead of an unhandled failure.
  const handleRotateToken = useCallback(async (tokenId: number, tokenName: string, hashed: boolean) => {
    if (!hashed) {
      setUpdateError(t('profile.rotateLegacyError', { name: tokenName }));
      return;
    }

    setIsRotatingTokenId(tokenId);
    try {
      const response = await rotateToken(tokenId);
      const metadata = response.metadata;
      const rotatedName = metadata.name ?? tokenName;

      // WIKI4AI-168: the old token is dead, so its menu entry goes; the replacement
      // is stored as metadata only. Its plaintext is never written to storage — it
      // lives in `oneTimeToken` until this page is left.
      const withoutOld = storedTokens.filter((t) => t.tokenId !== tokenId);
      const rotatedStored: StoredToken = {
        tokenId: metadata.id,
        name: rotatedName,
        expiresAt: metadata.expiresAt ?? null,
        createdAt: metadata.createdAt ?? new Date().toISOString(),
        hashed: true,
        prefix: metadata.prefix ?? null,
        scopes: Array.isArray(metadata.scopes) ? [...metadata.scopes] : [],
      };
      const updatedStored = [...withoutOld, rotatedStored];
      setStoredTokens(updatedStored);
      writeStoredTokenMenu(updatedStored);

      // Show the new plaintext exactly once.
      setOneTimeToken({ tokenId: metadata.id, name: rotatedName, token: response.token, rotated: true });
      setOneTimeCopied(false);

      if (profile?.id) {
        await refreshTokenList();
      }

      setDeleteToast(t('profile.tokenRotated', { name: tokenName }));
      scheduleTimer(() => setDeleteToast(null), 3000);
    } catch (err) {
      const message = err instanceof Error ? err.message : t('profile.rotateTokenFailed');
      // 400 = the row turned out to be a legacy token, 409 = already revoked,
      // 404 = not the caller's token. All of them are shown, never thrown.
      setUpdateError(`${t('profile.rotateTokenFailed')}: ${message}`);
    } finally {
      setIsRotatingTokenId(null);
    }
  }, [profile?.id, storedTokens, refreshTokenList, scheduleTimer, t]);

  // ── Copy handler for the one-time plaintext (created or rotated) ────
  // WIKI4AI-168: this is the only copy action for a named token, and it can only
  // run while the secret is still in state. After a reload there is nothing to copy.
  const handleCopyOneTimeToken = useCallback(() => {
    if (!oneTimeToken) return;
    copySecret(oneTimeToken.token, () => {
      setOneTimeCopied(true);
      scheduleTimer(() => setOneTimeCopied(false), 2000);
    });
  }, [oneTimeToken, copySecret, scheduleTimer]);

  // WIKI4AI-85: logout moved to the top-nav user menu (single location).

  // ── Legacy token copy (for the quick generate flow) ────────────────
  // The value comes from state; this flow no longer persists it (WIKI4AI-168).
  const handleCopyLegacyToken = useCallback(() => {
    if (!generatedToken) return;
    copySecret(generatedToken, () => {
      setTokenCopied(true);
      scheduleTimer(() => setTokenCopied(false), 2000);
    });
  }, [generatedToken, copySecret, scheduleTimer]);

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

  // WIKI4AI-85: `user` from AuthContext is no longer needed here — the profile
  // API response carries all displayed fields (username/email/role).
  const displayUser = profile;

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

      {/* WIKI4AI-85: language preference moved to the top-nav user menu. */}

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

          {/* WIKI4AI-138: scopes are sent to POST /api/v1/tokens as an array.
              A string there is a JSON parse error (500), so the UI never sends one. */}
          <div className="form-group">
            <span className="form-label">{t('profile.tokenScopesField')}</span>
            <div className="token-scopes" data-testid="api-token-scopes">
              {TOKEN_SCOPE_OPTIONS.map((scope) => (
                <label key={scope} className="scope-option">
                  <input
                    type="checkbox"
                    checked={selectedScopes.includes(scope)}
                    onChange={(e) =>
                      setSelectedScopes((prev) =>
                        e.target.checked
                          ? [...prev, scope]
                          : prev.filter((s) => s !== scope),
                      )
                    }
                    style={{ width: 'auto', marginRight: '6px' }}
                    data-testid={`token-scope-${scope}`}
                  />
                  <span>{t(`profile.scope.${scope}`)}</span>
                </label>
              ))}
            </div>
            <p style={{ fontSize: '12px', color: '#6b7280', marginTop: '4px' }}>
              {t('profile.tokenScopesHint')}
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
                // WIKI4AI-138: the deprecation banner is driven by `hashed === false`
                // alone. A hashed token may also have no expiry (expiresAt === null)
                // and must NOT be flagged — legacy is the API flag, not the absence
                // of an expiration date.
                const isLegacy = token.hashed === false;
                const isRevoked = token.revokedAt !== null && token.revokedAt !== undefined;
                return (
                  <div
                    key={`${token.tokenId}-${token.name}`}
                    className={`token-card ${isTokenExpired(token.expiresAt) ? 'expired' : ''} ${isLegacy ? 'legacy' : ''} ${isRevoked ? 'revoked' : ''}`}
                  >
                    <div className="token-card-info">
                      <div className="token-card-name">{token.name}</div>
                      {/* WIKI4AI-168: a card shows the display prefix the listing
                          returns — never the secret, and never a `title` carrying it.
                          The tooltip is the token name. */}
                      {token.prefix && (
                        <div className="token-card-preview" title={token.name}>
                          w4a_{token.prefix}…
                        </div>
                      )}
                      {!token.prefix && token.hashed && (
                        <div className="token-card-preview token-card-preview-muted" title={token.name}>
                          {t('profile.tokenSecretNotStored')}
                        </div>
                      )}
                    <div className="token-card-meta">
                      <span>{t('profile.createdLabel')}: {new Date(token.createdAt).toLocaleDateString(locale)}</span>
                      <span className={isTokenExpired(token.expiresAt) ? 'expired-text' : ''}>
                        {t('profile.expiresLabel')}: {formatExpiration(token.expiresAt)}
                      </span>
                      {token.scopes.length > 0 && (
                        <span>{t('profile.scopesLabel')}: {token.scopes.join(', ')}</span>
                      )}
                      {isRevoked && (
                        <span className="revoked-text" data-testid={`token-revoked-${token.tokenId}`}>
                          {t('profile.revokedLabel')}
                        </span>
                      )}
                    </div>
                  </div>

                  <div className="token-card-actions">
                    {/* WIKI4AI-168: no "copy stored token" button. A stored token has
                        no plaintext in the browser to copy — rotate it to get a new
                        one, which is shown once. */}
                    {/* Rotation exists only for hashed tokens — a legacy JWT row cannot
                        be rotated: the backend answers 400 for it. */}
                    {token.hashed && !isRevoked && (
                      <button
                        onClick={() => handleRotateToken(token.tokenId, token.name, token.hashed)}
                        className="btn-rotate-token"
                        disabled={isRotatingTokenId !== null}
                        data-testid={`rotate-token-${token.tokenId}`}
                      >
                        {isRotatingTokenId === token.tokenId ? t('profile.rotating') : t('profile.rotateToken')}
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

                  {/* WIKI4AI-138: deprecation banner. Bound to `hashed === false` from the API,
                      never to a missing expiresAt — a hashed token may legitimately have no expiry. */}
                  {isLegacy && (
                    <div
                      className="token-legacy-banner"
                      role="alert"
                      data-testid={`legacy-token-banner-${token.tokenId}`}
                    >
                    <span className="token-legacy-banner-icon" aria-hidden="true">⚠️</span>
                    <span className="token-legacy-banner-text">
                      <strong>{t('profile.legacyTokenBadge')}</strong>
                      {' — '}
                      {t('profile.legacyTokenBanner')}
                    </span>
                    </div>
                  )}
                </div>
              );
            })
          )}
        </div>

        {/* WIKI4AI-138 + WIKI4AI-168: plaintext of a created or rotated token,
            shown exactly once. It lives only in this component's state — the
            backend keeps just its SHA-256 digest, and the browser keeps nothing,
            so this panel is the only chance to copy it. Reload and it is gone. */}
        {oneTimeToken && (
          <div
            className={`one-time-token-display${oneTimeToken.rotated ? ' rotated-token-display' : ''}`}
            data-testid={oneTimeToken.rotated ? 'rotated-token-display' : 'one-time-token-display'}
          >
            <p
              className={`one-time-token-warning${oneTimeToken.rotated ? ' rotated-token-warning' : ''}`}
              role="alert"
              data-testid={oneTimeToken.rotated ? 'rotated-token-warning' : 'one-time-token-warning'}
            >
              {oneTimeToken.rotated
                ? t('profile.rotatedTokenWarning')
                : t('profile.oneTimeTokenWarning')}
            </p>
            <div className="token-display">
              <code data-testid={oneTimeToken.rotated ? 'rotated-token-value' : 'one-time-token-value'}>
                {oneTimeToken.token}
              </code>
              <button
                onClick={handleCopyOneTimeToken}
                className="btn btn-small copy-btn"
                data-testid={oneTimeToken.rotated ? 'copy-rotated-token-btn' : 'copy-one-time-token-btn'}
              >
                {oneTimeCopied ? t('profile.copied') : t('profile.copy')}
              </button>
            </div>
          </div>
        )}

        {/* Legacy single token generation (backward compatible) */}
        <div style={{ marginTop: '24px', borderTop: '1px solid var(--glass-border)', paddingTop: '16px' }}>
          <h3 style={{ fontSize: '14px', marginBottom: '8px' }}>{t('profile.quickGenerateTitle')}</h3>
          {/* WIKI4AI-138: this flow still issues a legacy clear-text JWT (kept for
              backward compatibility). It is labelled as deprecated so nobody creates
              an unrevocable token by accident. */}
          <p className="legacy-flow-note">{t('profile.quickGenerateLegacyNote')}</p>

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

      {/* WIKI4AI-85: logout lives in the top-nav user menu (single location). */}
    </div>
  );
}
