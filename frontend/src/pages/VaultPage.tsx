import React, { useState, useMemo, useCallback } from 'react';
import { useVaultEntries } from '../hooks/useVaultEntries';
import type { VaultEntry, VaultEntryData } from '../types/vault';
import './VaultPage.css';

const VAULT_MASTER_PASSWORD_KEY = 'wiki4ai_vault_master_password';
const VAULT_SALT_KEY = 'wiki4ai_vault_salt';

function getVaultConfig() {
  const masterPassword = localStorage.getItem(VAULT_MASTER_PASSWORD_KEY);
  const saltB64 = localStorage.getItem(VAULT_SALT_KEY);
  if (!masterPassword || !saltB64) return null;
  try {
    const saltBytes = Uint8Array.from(atob(saltB64), (c) => c.charCodeAt(0));
    return { masterPassword, salt: saltBytes };
  } catch {
    return null;
  }
}

function initVaultConfig(): { masterPassword: string; salt: Uint8Array } {
  const masterPassword = crypto.randomUUID();
  const salt = crypto.getRandomValues(new Uint8Array(16));
  localStorage.setItem(VAULT_MASTER_PASSWORD_KEY, masterPassword);
  localStorage.setItem(VAULT_SALT_KEY, btoa(String.fromCharCode(...salt)));
  return { masterPassword, salt };
}

interface GroupedEntries {
  [groupPath: string]: VaultEntry[];
}

const VaultPage: React.FC = () => {
  const config = useMemo(() => getVaultConfig() ?? initVaultConfig(), []);
  const { entries, isLoading, error, createEntry } = useVaultEntries(config);

  const [searchQuery, setSearchQuery] = useState('');
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [selectedGroup, setSelectedGroup] = useState<string | null>(null);
  const [copiedId, setCopiedId] = useState<number | null>(null);

  // Form state
  const [newTitle, setNewTitle] = useState('');
  const [newUrl, setNewUrl] = useState('');
  const [newGroupPath, setNewGroupPath] = useState('');
  const [newUsername, setNewUsername] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [newNotes, setNewNotes] = useState('');
  const [createError, setCreateError] = useState<string | null>(null);

  // Group entries by group_path
  const groupedEntries = useMemo<GroupedEntries>(() => {
    const groups: GroupedEntries = {};
    for (const entry of entries) {
      const path = entry.groupPath || '/';
      if (!groups[path]) groups[path] = [];
      groups[path].push(entry);
    }
    return groups;
  }, [entries]);

  // Filter entries based on search query and selected group
  const filteredGroups = useMemo<GroupedEntries>(() => {
    const result: GroupedEntries = {};
    const query = searchQuery.toLowerCase().trim();

    for (const [groupPath, groupEntries] of Object.entries(groupedEntries)) {
      if (selectedGroup && groupPath !== selectedGroup) continue;

      const filtered = query
        ? groupEntries.filter(
            (e) =>
              e.title.toLowerCase().includes(query) ||
              (e.url?.toLowerCase().includes(query) ?? false) ||
              (e.data.username?.toLowerCase().includes(query) ?? false),
          )
        : groupEntries;

      if (filtered.length > 0) {
        result[groupPath] = filtered;
      }
    }
    return result;
  }, [groupedEntries, searchQuery, selectedGroup]);

  const allGroups = useMemo(() => Object.keys(groupedEntries).sort(), [groupedEntries]);

  // Copy password to clipboard
  const copyPassword = useCallback(async (entry: VaultEntry) => {
    try {
      await navigator.clipboard.writeText(entry.data.password);
      setCopiedId(entry.id);
      setTimeout(() => setCopiedId(null), 2000);
    } catch {
      // Fallback for older browsers
      const textarea = document.createElement('textarea');
      textarea.value = entry.data.password;
      document.body.appendChild(textarea);
      textarea.select();
      document.execCommand('copy');
      document.body.removeChild(textarea);
      setCopiedId(entry.id);
      setTimeout(() => setCopiedId(null), 2000);
    }
  }, []);

  // Handle create entry form submission
  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newTitle.trim()) return;

    setCreateError(null);
    try {
      const data: VaultEntryData = { password: newPassword };
      if (newUsername.trim()) data.username = newUsername.trim();
      if (newNotes.trim()) data.notes = newNotes.trim();

      await createEntry({
        title: newTitle.trim(),
        url: newUrl.trim() || undefined,
        groupPath: newGroupPath.trim() || undefined,
        data,
      });

      setNewTitle('');
      setNewUrl('');
      setNewGroupPath('');
      setNewUsername('');
      setNewPassword('');
      setNewNotes('');
      setShowCreateForm(false);
    } catch (err) {
      setCreateError(err instanceof Error ? err.message : 'Failed to create entry');
    }
  };

  // Loading state
  if (isLoading) {
    return (
      <div className="vault-page">
        <div className="loading-state">
          <div className="spinner" />
          <p>Loading vault...</p>
        </div>
      </div>
    );
  }

  // Error state
  if (error) {
    return (
      <div className="vault-page">
        <div className="error-state">
          <p className="error">{error.message}</p>
          <button onClick={() => window.location.reload()} className="btn-secondary">
            Retry
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="vault-page">
      {/* Header */}
      <header className="vault-header">
        <h1>Vault</h1>
        <div className="vault-actions">
          <div className="search-bar">
            <svg className="search-icon" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
            </svg>
            <input
              type="text"
              className="search-input"
              placeholder="Search entries..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              data-testid="vault-search-input"
            />
            {searchQuery && (
              <button
                type="button"
                className="search-clear"
                onClick={() => setSearchQuery('')}
                aria-label="Clear search"
                data-testid="vault-search-clear-button"
              >
                ×
              </button>
            )}
          </div>
        </div>
      </header>

      {/* Create Entry Form */}
      {showCreateForm && (
        <form onSubmit={handleCreate} className="create-form">
          <h3>Add New Entry</h3>
          <input
            type="text"
            placeholder="Title (e.g., GitHub)"
            value={newTitle}
            onChange={(e) => setNewTitle(e.target.value)}
            required
            autoFocus
            data-testid="vault-new-title-input"
          />
          <input
            type="url"
            placeholder="URL (optional)"
            value={newUrl}
            onChange={(e) => setNewUrl(e.target.value)}
            data-testid="vault-new-url-input"
          />
          <input
            type="text"
            placeholder="Group path (e.g., /Work, /Personal)"
            value={newGroupPath}
            onChange={(e) => setNewGroupPath(e.target.value)}
            data-testid="vault-new-group-path-input"
          />
          <input
            type="text"
            placeholder="Username (optional)"
            value={newUsername}
            onChange={(e) => setNewUsername(e.target.value)}
            data-testid="vault-new-username-input"
          />
          <input
            type="password"
            placeholder="Password"
            value={newPassword}
            onChange={(e) => setNewPassword(e.target.value)}
            required
            data-testid="vault-new-password-input"
          />
          <textarea
            placeholder="Notes (optional)"
            value={newNotes}
            onChange={(e) => setNewNotes(e.target.value)}
            rows={3}
            data-testid="vault-new-notes-input"
          />
          {createError && <p className="error">{createError}</p>}
          <div className="form-actions">
            <button type="submit" className="btn-primary" disabled={false}>
              Add Entry
            </button>
            <button type="button" onClick={() => setShowCreateForm(false)} className="btn-secondary">
              Cancel
            </button>
          </div>
        </form>
      )}

      {/* Main Content */}
      {!showCreateForm && (
        <>
          <button onClick={() => setShowCreateForm(true)} className="btn-create-new" data-testid="vault-add-entry-button">
            + Add New Entry
          </button>

          {entries.length === 0 ? (
            <div className="empty-state">
              <svg style={{ width: 64, height: 64 }} className="text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z" />
              </svg>
              <p className="empty-text">No entries in your vault yet.</p>
              <button onClick={() => setShowCreateForm(true)} className="btn-primary">
                Add your first entry!
              </button>
            </div>
          ) : (
            <div className="vault-layout">
              {/* Sidebar - Groups */}
              <aside className="vault-sidebar">
                <nav className="groups-nav" data-testid="vault-groups-list">
                  {allGroups.map((group) => (
                    <button
                      key={group}
                      type="button"
                      className={`group-item ${selectedGroup === group ? 'active' : ''}`}
                      onClick={() => setSelectedGroup(selectedGroup === group ? null : group)}
                      data-testid={`vault-group-${group.replace(/\//g, '-')}`}
                    >
                      {group || '/'} ({groupedEntries[group].length})
                    </button>
                  ))}
                </nav>
              </aside>

              {/* Main - Entries */}
              <main className="vault-main">
                {Object.keys(filteredGroups).length === 0 && searchQuery ? (
                  <div className="empty-state">
                    <p className="empty-text">No entries match your search</p>
                  </div>
                ) : (
                  Object.entries(filteredGroups).map(([groupPath, groupEntries]) => (
                    <section key={groupPath} className="vault-group" data-testid={`vault-group-section-${groupPath.replace(/\//g, '-')}`}>
                      <h2 className="group-title">{groupPath || '/'}</h2>
                      <div className="entries-list">
                        {groupEntries.map((entry) => (
                          <div key={entry.id} className="entry-card" data-testid={`vault-entry-${entry.id}`}>
                            <div className="entry-header">
                              <h3 className="entry-title">{entry.title}</h3>
                              <button
                                type="button"
                                className="copy-button"
                                onClick={() => copyPassword(entry)}
                                aria-label={`Copy password for ${entry.title}`}
                                data-testid={`vault-copy-password-${entry.id}`}
                              >
                                {copiedId === entry.id ? '✓' : '📋'}
                              </button>
                            </div>
                            <div className="entry-details">
                              {entry.url && (
                                <a href={entry.url} target="_blank" rel="noopener noreferrer" className="entry-url">
                                  {entry.url}
                                </a>
                              )}
                              {entry.data.username && (
                                <span className="entry-username">{entry.data.username}</span>
                              )}
                            </div>
                          </div>
                        ))}
                      </div>
                    </section>
                  ))
                )}
              </main>
            </div>
          )}
        </>
      )}
    </div>
  );
};

export default VaultPage;
