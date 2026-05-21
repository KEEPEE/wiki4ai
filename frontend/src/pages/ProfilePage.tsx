import { useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import { apiGet, apiPost, apiPut } from '../services/apiClient';
import './Profile.css';

const API_BASE_URL = import.meta.env.VITE_API_URL || '/api/v1';

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

  // Token generation state
  const [generatedToken, setGeneratedToken] = useState<string | null>(null);
  const [tokenCopied, setTokenCopied] = useState(false);
  const [isGeneratingToken, setIsGeneratingToken] = useState(false);

  // Load profile on mount
  useCallback(async () => {
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
  }, []);

  // Trigger the load on mount
  useState(() => {
    (async () => {
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
    })();
  });

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

  const handleGenerateToken = useCallback(async () => {
    setIsGeneratingToken(true);
    try {
      const response = await apiPost<{ accessToken: string }>(`${API_BASE_URL}/auth/token`);
      setGeneratedToken(response.accessToken);
      setTokenCopied(false);
    } catch {
      setUpdateError('Failed to generate token');
    } finally {
      setIsGeneratingToken(false);
    }
  }, []);

  const copyToClipboard = useCallback((text: string) => {
    // Try modern Clipboard API first; fall back to execCommand for non-secure contexts (HTTP on IP)
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(text).then(() => {
        setTokenCopied(true);
        setTimeout(() => setTokenCopied(false), 2000);
      }).catch(() => {
        // Clipboard API failed — use fallback below
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
        <h2 className="section-title">API Token</h2>
        <p className="section-description">
          Generate a new JWT access token for API integrations or MCP server connections.
        </p>

        <button
          onClick={handleGenerateToken}
          className="btn btn-secondary"
          disabled={isGeneratingToken}
          data-testid="generate-token-btn"
        >
          {isGeneratingToken ? 'Generating...' : 'Generate New Token'}
        </button>

        {generatedToken && (
          <div className="token-display" data-testid="token-display">
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
