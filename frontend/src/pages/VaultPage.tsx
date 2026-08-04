import React, { useState, useMemo, useCallback } from 'react';
import { useVaultEntries } from '../hooks/useVaultEntries';
import type { VaultEntry, VaultEntryData } from '../types/vault';
import VaultEntryForm from '../components/VaultEntryForm';
import type { VaultEntryFormData } from '../components/VaultEntryForm';
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
  const { entries, isLoading, error, createEntry, updateEntry } = useVaultEntries(config);

  const [searchQuery, setSearchQuery] = useState('');
  const [showForm, setShowForm] = useState(false);
  const [editingEntry, setEditingEntry] = useState<VaultEntry | null>(null);
  const [selectedGroup, setSelectedGroup] = useState<string | null>(null);
  const [copiedId, setCopiedId] = useState<number | null>(null);
  const [formError, setFormError] = useState<string | null>(null);

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

  const handleFormSubmit = async (formData: VaultEntryFormData) => {
    setFormError(null);

    try {
      const data: VaultEntryData = { password: formData.password };
      if (formData.username.trim()) data.username = formData.username.trim();
      if (formData.notes.trim()) data.notes = formData.notes.trim();

      if (editingEntry) {
        await updateEntry({
          id: editingEntry.id,
          updates: {
            title: formData.title.trim(),
            url: formData.url.trim() || undefined,
            groupPath: formData.groupPath.trim() || undefined,
            data,
          },
        });
      } else {
        await createEntry({
          title: formData.title.trim(),
          url: formData.url.trim() || undefined,
          groupPath: formData.groupPath.trim() || undefined,
          data,
        });
      }

      closeForm();
    } catch (err) {
      setFormError(err instanceof Error ? err.message : 'Failed to save entry');
    }
  };

  const closeForm = () => {
    setShowForm(false);
    setEditingEntry(null);
    setFormError(null);
  };

  const openCreateForm = () => {
    setEditingEntry(null);
    setShowForm(true);
  };

  const openEditForm = (entry: VaultEntry) => {
    setEditingEntry(entry);
    setShowForm(true);
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

      {/* Entry Form (Create/Edit) */}
      {showForm && (
        <VaultEntryForm
          entry={editingEntry}
          existingGroups={allGroups}
          onSubmit={handleFormSubmit}
          onCancel={closeForm}
          isSubmitting={false}
          error={formError}
        />
      )}

      {/* Main Content */}
      {!showForm && (
        <>
          <button onClick={openCreateForm} className="btn-create-new" data-testid="vault-add-entry-button">
            + Add New Entry
          </button>

          {entries.length === 0 ? (
            <div className="empty-state">
              <svg style={{ width: 64, height: 64 }} className="text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z" />
              </svg>
              <p className="empty-text">No entries in your vault yet.</p>
              <button onClick={openCreateForm} className="btn-primary">
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
                              <div className="entry-actions">
                                <button
                                  type="button"
                                  className="edit-button"
                                  onClick={() => openEditForm(entry)}
                                  aria-label={`Edit ${entry.title}`}
                                  data-testid={`vault-edit-entry-${entry.id}`}
                                >
                                  ✏️
                                </button>
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
