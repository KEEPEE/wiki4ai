import { useState, useCallback, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import { apiGet, apiPost, apiPut, apiDelete } from '../services/apiClient';
import './Profile.css';

const API_BASE_URL = import.meta.env.VITE_API_URL || '/api/v1';

// ── Storage key for generated API tokens (persists across logout/login) ────
const GENERATED_TOKEN_KEY = 'wiki4ai_generated_api_token';

interface UserProfile {
  id: number;
  username: string;
  email: string;
  role: string;
  createdAt: string;
}

interface ProfileUpdateRequest {
  username?: string;
  email?: string;
  currentPassword?: string;
  newPassword?: string;
}

interface ApiTokenInfo {
  tokenId: number;
  name: string;
  expiresAt: string | null;
  createdAt: string;
}

export default function ProfilePage() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

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

  // Load profile and tokens on mount (and restore stored generated token)
  useEffect(() => {
    const loadProfile = async () => {
      try {
        const data = await apiGet<UserProfile>(`${API_BASE_URL}/auth/me`);
        setProfile(data);
        setEditUsername(data.username);
        setEditEmail(data.email);
      } catch {
        setUpdateError('Failed to load profile');
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

    loadProfile();
    restoreToken();
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
        setUpdateMessage('Profile updated successfully');
        // Clear password fields after successful update
        setCurrentPassword('');
        setNewPassword('');
      } catch (err) {
        const message = err instanceof Error ? err.message : 'Failed to update profile';
        setUpdateError(message);
      } finally {
        setIsUpdating(false);
      }
    },
    [editUsername, editEmail, currentPassword, newPassword],
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
      setUpdateError('Failed to generate token');
    } finally {
      setIsGeneratingToken(false);
    }
  }, [useCustomExpiry, expiresAt]);

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
      const message = err instanceof Error ? err.message : 'Failed to create token';
      setUpdateError(message);
    } finally {
      setIsCreatingNamedToken(false);
    }
  }, [newTokenName, newTokenExpiresAt, useNewTokenExpiry, profile?.id]);

  // Delete a named API token
  const handleDeleteApiToken = useCallback(async (tokenId: number) => {
    if (!window.confirm('Are you sure you want to delete this token?')) return;
    
    try {
      await apiDelete(`${API_BASE_URL}/auth/token/${tokenId}`);
      
      // Refresh the tokens list
      if (profile?.id) {
        const tokens = await apiGet<ApiTokenInfo[]>(`${API_BASE_URL}/auth/tokens`);
        setApiTokens(tokens);
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to delete token';
      setUpdateError(message);
    }
  }, [profile?.id]);

  const copyToClipboard = useCallback((text: string) => {
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(text).then(() => {
        setTokenCopied(true);
        setTimeout(() => setTokenCopied(false), 2000);
      }).catch(() => {
        const textarea = document.createElement('textarea');
        textarea.value = text;
        textarea.style.position = 'fixed';
        textarea.style.opacity = '0';
        document.body.appendChild(textarea);
        textarea.select();
        try {
          document.execCommand('copy');
          setTokenCopied(true);
          setTimeout(() => setTokenCopied(false), 2000);
        } catch {
          setUpdateError('Failed to copy token');
        } finally {
          document.body.removeChild(textarea);
        }
      });
    } else {
      const textarea = document.createElement('textarea');
      textarea.value = text;
      textarea.style.position = 'fixed';
      textarea.style.opacity = '0';
      document.body.appendChild(textarea);
      textarea.select();
      try {
        document.execCommand('copy');
        setTokenCopied(true);
        setTimeout(() => setTokenCopied(false), 2000);
      } catch {
        setUpdateError('Failed to copy token');
      } finally {
        document.body.removeChild(textarea);
      }
    }
  }, []);

  const handleCopyToken = useCallback(() => {
    if (generatedToken) {
      copyToClipboard(generatedToken);
    }
  }, [generatedToken, copyToClipboard]);

  const handleLogout = useCallback(() => {
    logout();
    navigate('/login');
  }, [logout, navigate]);

  // Format expiration date for display
  const formatExpiration = (expiresAt: string | null): string => {
    if (!expiresAt) return 'Never';
    try {
      return new Date(expiresAt).toLocaleString();
    } catch {
      return expiresAt;
    }
  };

  // Check if token is expired
  const isTokenExpired = (expiresAt: string | null): boolean => {
    if (!expiresAt) return false;
    try {
      return new Date(expiresAt) < new Date();
    } catch {
      return false;
    }
  };

  if (isLoadingProfile) {
    return (
      <div className="profile-page">
        <div className="loading-state">
          <div className="spinner" />
          <p>Loading profile...</p>
        </div>
      </div>
    );
  }

  const displayUser = profile || user;

  return (
    <div className="profile-page">
      <h1 className="page-title">My Profile</h1>

      {/* Current Profile Info */}
      <section className="profile-section" data-testid="profile-info-section">
        <h2 className="section-title">Profile Information</h2>
        <div className="profile-info-card">
          <div className="info-row">
            <span className="info-label">Username:</span>
            <span className="info-value" data-testid="profile-username">{displayUser?.username ?? '—'}</span>
          </div>
          <div className="info-row">
            <span className="info-label">Email:</span>
            <span className="info-value" data-testid="profile-email">{displayUser?.email ?? '—'}</span>
          </div>
          <div className="info-row">
            <span className="info-label">Role:</span>
            <span className={`info-value role-badge role-${(displayUser as UserProfile)?.role?.toLowerCase() || 'user'}`} data-testid="profile-role">
              {(displayUser as UserProfile)?.role ?? (displayUser?.username ? 'USER' : '—')}
            </span>
          </div>
        </div>
      </section>

      {/* Edit Profile Form */}
      <section className="profile-section" data-testid="edit-profile-section">
        <h2 className="section-title">Edit Profile</h2>

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
            <label htmlFor="profile-username" className="form-label">Username</label>
            <input
              id="profile-username"
              type="text"
              value={editUsername}
              onChange={(e) => setEditUsername(e.target.value)}
              className="form-input"
              placeholder="New username"
              data-testid="edit-username"
            />
          </div>

          <div className="form-group">
            <label htmlFor="profile-email" className="form-label">Email</label>
            <input
              id="profile-email"
              type="email"
              value={editEmail}
              onChange={(e) => setEditEmail(e.target.value)}
              className="form-input"
              placeholder="New email"
              data-testid="edit-email"
            />
          </div>

          <div className="form-group">
            <label htmlFor="current-password" className="form-label">Current Password</label>
            <input
              id="current-password"
              type="password"
              value={currentPassword}
              onChange={(e) => setCurrentPassword(e.target.value)}
              className="form-input"
              placeholder="Required to change password"
              data-testid="edit-current-password"
            />
          </div>

          <div className="form-group">
            <label htmlFor="new-password" className="form-label">New Password</label>
            <input
              id="new-password"
              type="password"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              className="form-input"
              placeholder="Min 3 characters"
              data-testid="edit-new-password"
            />
          </div>

          <button
            type="submit"
            className="btn btn-primary"
            disabled={isUpdating}
            data-testid="update-profile-btn"
          >
            {isUpdating ? 'Saving...' : 'Save Changes'}
          </button>
        </form>
      </section>

      {/* API Token Section */}
      <section className="profile-section" data-testid="api-token-section">
        <h2 className="section-title">API Tokens</h2>
        <p className="section-description">
          Generate JWT access tokens for API integrations or MCP server connections.
          You can create multiple named tokens and manage them here.
        </p>

        {/* Named Token Creation Form */}
        <div className="form-group" style={{ marginTop: '16px' }}>
          <h3 style={{ fontSize: '14px', marginBottom: '8px' }}>Create New Token</h3>
          
          <div className="form-group">
            <label htmlFor="token-name" className="form-label">Token Name</label>
            <input
              id="token-name"
              type="text"
              value={newTokenName}
              onChange={(e) => setNewTokenName(e.target.value)}
              className="form-input"
              placeholder="e.g., MCP Server, CI/CD Pipeline"
              data-testid="api-token-name"
            />
          </div>

          <div className="form-group">
            <label htmlFor="token-expiry" className="form-label">Expiration</label>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <input
                type="checkbox"
                id="use-new-token-expiry"
                checked={useNewTokenExpiry}
                onChange={(e) => setUseNewTokenExpiry(e.target.checked)}
                style={{ width: 'auto', marginRight: '8px' }}
              />
              <span style={{ fontSize: '14px', color: '#6b7280' }}>Set custom expiration date</span>
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
                ? `Token will expire at ${new Date(newTokenExpiresAt).toLocaleString()}`
                : 'Leave unchecked for a token that never expires'}
            </p>
          </div>

          <button
            onClick={handleCreateNamedToken}
            className="btn btn-secondary"
            disabled={isCreatingNamedToken || !newTokenName.trim()}
            data-testid="create-named-token-btn"
          >
            {isCreatingNamedToken ? 'Creating...' : 'Create Named Token'}
          </button>
        </div>

        {/* Existing Tokens List */}
        <div style={{ marginTop: '24px' }}>
          <h3 style={{ fontSize: '14px', marginBottom: '8px' }}>Your Tokens</h3>
          
          {isFetchingTokens ? (
            <p style={{ fontSize: '14px', color: '#6b7280' }}>Loading tokens...</p>
          ) : apiTokens.length === 0 ? (
            <p style={{ fontSize: '14px', color: '#6b7280' }}>No API tokens yet. Create one above.</p>
          ) : (
            <div data-testid="api-tokens-list">
              {apiTokens.map((token) => (
                <div
                  key={token.tokenId}
                  className={`profile-info-card ${isTokenExpired(token.expiresAt) ? 'expired-token' : ''}`}
                  style={{ marginBottom: '8px', padding: '12px', borderRadius: '8px', border: '1px solid #e5e7eb' }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <div>
                      <strong>{token.name}</strong>
                      <div style={{ fontSize: '12px', color: '#6b7280', marginTop: '4px' }}>
                        Created: {new Date(token.createdAt).toLocaleString()}
                      </div>
                      <div style={{ fontSize: '12px', color: isTokenExpired(token.expiresAt) ? '#dc2626' : '#6b7280', marginTop: '2px' }}>
                        Expires: {formatExpiration(token.expiresAt)}
                        {isTokenExpired(token.expiresAt) && ' (expired)'}
                      </div>
                    </div>
                    <button
                      onClick={() => handleDeleteApiToken(token.tokenId)}
                      className="btn btn-small btn-danger"
                      style={{ fontSize: '12px', padding: '4px 8px' }}
                      data-testid={`delete-token-${token.tokenId}`}
                    >
                      Delete
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Generated Token Display (for newly created tokens) */}
        {generatedToken && (
          <div style={{ marginTop: '16px' }}>
            <h3 style={{ fontSize: '14px', marginBottom: '8px' }}>New Token</h3>
            <div className="token-display" data-testid="new-token-display">
              <code>{generatedToken.slice(0, 40)}...</code>
              <button
                onClick={handleCopyToken}
                className="btn btn-small copy-btn"
                data-testid="copy-new-token-btn"
              >
                {tokenCopied ? '✓ Copied!' : 'Copy'}
              </button>
            </div>
          </div>
        )}

        {/* Legacy single token generation (backward compatible) */}
        <div style={{ marginTop: '24px', borderTop: '1px solid #e5e7eb', paddingTop: '16px' }}>
          <h3 style={{ fontSize: '14px', marginBottom: '8px' }}>Quick Generate Token</h3>
          
          {/* Expiration date picker */}
          <div className="form-group">
            <label htmlFor="token-expiry" className="form-label">Token Expiration</label>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <input
                type="checkbox"
                id="use-custom-expiry"
                checked={useCustomExpiry}
                onChange={(e) => setUseCustomExpiry(e.target.checked)}
                style={{ width: 'auto', marginRight: '8px' }}
              />
              <span style={{ fontSize: '14px', color: '#6b7280' }}>Set custom expiration date</span>
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
                ? `Token will expire at ${new Date(expiresAt).toLocaleString()}`
                : 'Leave unchecked for a token that never expires'}
            </p>
          </div>

          <button
            onClick={handleGenerateToken}
            className="btn btn-secondary"
            disabled={isGeneratingToken || (useCustomExpiry && !expiresAt)}
            data-testid="generate-token-btn"
          >
            {isGeneratingToken ? 'Generating...' : 'Generate New Token'}
          </button>

          {/* Legacy token display */}
          {generatedToken && !newTokenName && (
            <div className="token-display" data-testid="legacy-token-display">
              <code>{generatedToken.slice(0, 40)}...</code>
              <button
                onClick={handleCopyToken}
                className="btn btn-small copy-btn"
                data-testid="copy-token-btn"
              >
                {tokenCopied ? '✓ Copied!' : 'Copy'}
              </button>
            </div>
          )}
        </div>
      </section>

      {/* Logout */}
      <section className="profile-section profile-danger-zone">
        <h2 className="section-title section-danger">Danger Zone</h2>
        <p className="section-description">Log out of your account on this device.</p>
        <button
          onClick={handleLogout}
          className="btn btn-danger"
          data-testid="profile-logout-btn"
        >
          Logout
        </button>
      </section>
    </div>
  );
}
