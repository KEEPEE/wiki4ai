import React, { useState, useMemo, useCallback, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { useVault } from '../contexts/VaultContext';
import { useVaultEntries } from '../hooks/useVaultEntries';
import type { VaultEntry, VaultEntryData, BackendVaultEntry } from '../types/vault';
import VaultEntryForm from '../components/VaultEntryForm';
import type { VaultEntryFormData } from '../components/VaultEntryForm';
import VaultSetupScreen from '../components/VaultSetupScreen';
import VaultUnlockScreen from '../components/VaultUnlockScreen';
import { vaultApi } from '../services/vaultApi';
import { deriveKey, decrypt, base64ToBytes } from '../services/encryptionService';
import './VaultPage.css';

interface DecryptedExportEntry {
  title: string;
  username?: string;
  password: string;
  url?: string;
  groupPath?: string;
  notes?: string;
}

async function decryptEntriesForExport(backendEntries: BackendVaultEntry[], masterPassword: string, salt: Uint8Array): Promise<DecryptedExportEntry[]> {
  const key = await deriveKey(masterPassword, salt);

  return Promise.all(
    backendEntries.map(async (entry) => {
      try {
        const usernameDecrypted = entry.usernameEncrypted
          ? await decrypt(base64ToBytes(entry.usernameEncrypted.ciphertext), base64ToBytes(entry.usernameEncrypted.iv), key)
          : null;
        const passwordDecrypted = await decrypt(base64ToBytes(entry.passwordEncrypted.ciphertext), base64ToBytes(entry.passwordEncrypted.iv), key);

        let notesDecrypted: string | null;
        if (entry.notesEncrypted) {
          try {
            notesDecrypted = await decrypt(base64ToBytes(entry.notesEncrypted.ciphertext), base64ToBytes(entry.notesEncrypted.iv), key);
          } catch {
            notesDecrypted = null;
          }
        } else {
          notesDecrypted = null;
        }

        return {
          title: entry.title,
          username: usernameDecrypted ? JSON.parse(usernameDecrypted) : undefined,
          password: passwordDecrypted ? JSON.parse(passwordDecrypted) : '[decryption failed]',
          url: entry.url,
          groupPath: entry.groupPath,
          notes: notesDecrypted ? JSON.parse(notesDecrypted) : undefined,
        };
      } catch {
        return {
          title: entry.title,
          password: '[decryption failed]',
          url: entry.url,
          groupPath: entry.groupPath,
        };
      }
    }),
  );
}

function escapeCsvField(value: string | undefined): string {
  if (value === undefined || value === null) return '';
  const str = String(value);
  if (str.includes(',') || str.includes('"') || str.includes('\n')) {
    return '"' + str.replace(/"/g, '""') + '"';
  }
  return str;
}

function generateCsvContent(entries: DecryptedExportEntry[]): string {
  const headers = ['title', 'username', 'password', 'url', 'group_path', 'notes'];
  const lines = [headers.join(',')];

  for (const entry of entries) {
    const row = [
      escapeCsvField(entry.title),
      escapeCsvField(entry.username),
      escapeCsvField(entry.password),
      escapeCsvField(entry.url),
      escapeCsvField(entry.groupPath),
      escapeCsvField(entry.notes),
    ];
    lines.push(row.join(','));
  }

  return lines.join('\n');
}

function generateJsonContent(entries: DecryptedExportEntry[]): string {
  return JSON.stringify(entries, null, 2);
}

function downloadFile(content: string, filename: string, mimeType: string) {
  const blob = new Blob([content], { type: mimeType });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

interface GroupedEntries {
  [groupPath: string]: VaultEntry[];
}

const VaultPage: React.FC = () => {
  const { t } = useTranslation();
  const vault = useVault();

  useEffect(() => {
    if (!vault.isUnlocked && vault.hasMasterPasswordSet === null) {
      vault.checkStatus();
    }
  }, [vault]);

  // All hooks must be called unconditionally, BEFORE any conditional returns,
  // to avoid React error #310 (rendered more hooks than during the previous render).
  const vaultEntriesHook = useVaultEntries(vault.config ?? null);
  const { entries, isLoading, error, createEntry, updateEntry, deleteEntry } = vaultEntriesHook;

  const [searchQuery, setSearchQuery] = useState('');
  const [showForm, setShowForm] = useState(false);
  const [editingEntry, setEditingEntry] = useState<VaultEntry | null>(null);
  const [selectedGroup, setSelectedGroup] = useState<string | null>(null);
  const [copiedId, setCopiedId] = useState<number | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  // Import state
  const [showImportModal, setShowImportModal] = useState(false);
  const [importFile, setImportFile] = useState<File | null>(null);
  const [importPassword, setImportPassword] = useState('');
  const [isImporting, setIsImporting] = useState(false);
  const [importError, setImportError] = useState<string | null>(null);

  // Export state
  const [showExportMenu, setShowExportMenu] = useState(false);
  const [isExporting, setIsExporting] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);

  const exportMenuRef = useRef<HTMLDivElement>(null);

  // Close export dropdown on outside click
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (showExportMenu && exportMenuRef.current && !exportMenuRef.current.contains(event.target as Node)) {
        setShowExportMenu(false);
      }
    };

    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [showExportMenu]);

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

  // Show setup screen if no master password set yet OR vault needs re-initialization (new browser/device)
  if (!vault.isLoading && (vault.hasMasterPasswordSet === false || vault.needsReinit)) {
    return <VaultSetupScreen isReinit={vault.needsReinit} />;
  }

  // Show unlock screen if locked and has master password
  if (!vault.isUnlocked && vault.hasMasterPasswordSet === true && !vault.isLoading) {
    return <VaultUnlockScreen />;
  }

  // Show loading while checking status or setting up/unlocking
  if (vault.isLoading || vault.config === null) {
    return (
      <div className="vault-page">
        <div className="loading-state">
          <div className="spinner" />
          <p>{t('vault.loading')}</p>
        </div>
      </div>
    );
  }

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
      setFormError(err instanceof Error ? err.message : t('vault.saveFailed'));
    }
  };

  const closeForm = () => {
    setShowForm(false);
    setEditingEntry(null);
    setFormError(null);
  };

  // Import handlers
  const openImportModal = () => {
    setShowImportModal(true);
    setImportFile(null);
    setImportPassword('');
    setImportError(null);
  };

  const closeImportModal = () => {
    setShowImportModal(false);
    setImportFile(null);
    setImportPassword('');
    setImportError(null);
  };

  const handleImportSubmit = async () => {
    if (!importFile) {
      setImportError(t('vault.importSelectFile'));
      return;
    }

    if (!importPassword.trim()) {
      setImportError(t('vault.importPasswordRequired'));
      return;
    }

    setIsImporting(true);
    setImportError(null);

    try {
      const entries = await vaultApi.importFromKdbx(importFile, importPassword);

      if (entries.length === 0) {
        setImportError(t('vault.importNoEntries'));
        return;
      }

      for (const entry of entries) {
        const data: VaultEntryData = { password: entry.password };
        if (entry.username?.trim()) data.username = entry.username.trim();
        if (entry.notes?.trim()) data.notes = entry.notes.trim();

        await createEntry({
          title: entry.title || t('vault.untitled'),
          url: entry.url?.trim() || undefined,
          groupPath: entry.groupPath?.trim() || undefined,
          data,
        });
      }

      closeImportModal();
    } catch (err) {
      setImportError(err instanceof Error ? err.message : t('vault.importFailed'));
    } finally {
      setIsImporting(false);
    }
  };

  const handleExport = async (format: 'csv' | 'json') => {
    if (!vault.config || entries.length === 0) return;

    setIsExporting(true);
    setExportError(null);
    setShowExportMenu(false);

    try {
      const backendEntries = await vaultApi.getExportEntries();
      const decryptedEntries = await decryptEntriesForExport(backendEntries, vault.config.masterPassword, vault.config.salt);

      if (format === 'csv') {
        const content = generateCsvContent(decryptedEntries);
        downloadFile(content, `vault-export-${new Date().toISOString().slice(0, 10)}.csv`, 'text/csv;charset=utf-8');
      } else {
        const content = generateJsonContent(decryptedEntries);
        downloadFile(content, `vault-export-${new Date().toISOString().slice(0, 10)}.json`, 'application/json');
      }
    } catch (err) {
      setExportError(err instanceof Error ? err.message : t('vault.exportFailed'));
    } finally {
      setIsExporting(false);
    }
  };

  const openCreateForm = () => {
    setEditingEntry(null);
    setShowForm(true);
  };

  const openEditForm = (entry: VaultEntry) => {
    setEditingEntry(entry);
    setShowForm(true);
  };

  const handleDeleteEntry = async (entry: VaultEntry) => {
    if (!window.confirm(t('vault.deleteConfirm', { title: entry.title }))) return;

    setDeleteError(null);
    setDeletingId(entry.id);
    try {
      await deleteEntry(entry.id);
    } catch (err) {
      setDeleteError(err instanceof Error ? err.message : t('vault.deleteFailed'));
    } finally {
      setDeletingId(null);
    }
  };

  // Loading state
  if (isLoading) {
    return (
      <div className="vault-page">
        <div className="loading-state">
          <div className="spinner" />
          <p>{t('vault.loading')}</p>
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
            {t('dashboard.retry')}
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="vault-page">
      {/* Header */}
      <header className="vault-header">
        <h1>{t('vault.title')}</h1>
        <div className="vault-actions">
          <button onClick={openImportModal} className="btn-import" data-testid="vault-import-button">
            {t('vault.importKdbx')}
          </button>
          <div ref={exportMenuRef} className="export-menu-wrapper" style={{ position: 'relative', display: 'inline-block' }}>
            <button
              type="button"
              onClick={() => setShowExportMenu(!showExportMenu)}
              disabled={entries.length === 0 || isExporting}
              className="btn-import"
              data-testid="vault-export-button"
            >
              {t('vault.export')}
            </button>
            {showExportMenu && (
              /* WIKI4AI-89 (V-D9): themed via .export-dropdown in VaultPage.css
                 (glassmorphism vars) — no more inline white box in the dark UI. */
              <div className="export-dropdown">
                <button
                  type="button"
                  onClick={() => handleExport('csv')}
                  disabled={isExporting}
                  data-testid="vault-export-csv-button"
                >
                  {t('vault.exportCsv')}
                </button>
                <button
                  type="button"
                  onClick={() => handleExport('json')}
                  disabled={isExporting}
                  data-testid="vault-export-json-button"
                >
                  {t('vault.exportJson')}
                </button>
              </div>
            )}
          </div>
          <div className="vault-search-bar">
            <svg className="vault-search-icon" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
            </svg>
            <input
              type="text"
              className="vault-search-input"
              placeholder={t('vault.searchPlaceholder')}
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              data-testid="vault-search-input"
            />
            {searchQuery && (
              <button
                type="button"
                className="vault-search-clear"
                onClick={() => setSearchQuery('')}
                aria-label={t('dashboard.clearSearch')}
                data-testid="vault-search-clear-button"
              >
                ×
              </button>
            )}
          </div>
        </div>
      </header>

      {deleteError && (
        <div style={{ padding: '8px 16px', backgroundColor: '#fee2e2', color: '#991b1b', borderRadius: 4, marginBottom: 12 }} data-testid="vault-delete-error">
          {deleteError}
        </div>
      )}

      {exportError && (
        <div style={{ padding: '8px 16px', backgroundColor: '#fee2e2', color: '#991b1b', borderRadius: 4, marginBottom: 12 }} data-testid="vault-export-error">
          {exportError}
        </div>
      )}

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
            + {t('vault.addNewEntry')}
          </button>

          {entries.length === 0 ? (
            <div className="empty-state">
              <svg style={{ width: 64, height: 64 }} fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z" />
              </svg>
              <p className="empty-text">{t('vault.emptyState')}</p>
              <button onClick={openCreateForm} className="btn-primary">
                {t('vault.addFirstEntry')}
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
                    <p className="empty-text">{t('vault.noMatches')}</p>
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
                                  className="vault-entry-edit-button"
                                  onClick={() => openEditForm(entry)}
                                  aria-label={t('vault.ariaEdit', { title: entry.title })}
                                  data-testid={`vault-edit-entry-${entry.id}`}
                                >
                                  <svg width="16" height="16" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
                                  </svg>
                                </button>
                                <button
                                  type="button"
                                  className="vault-entry-copy-button"
                                  onClick={() => copyPassword(entry)}
                                  aria-label={t('vault.ariaCopy', { title: entry.title })}
                                  data-testid={`vault-copy-password-${entry.id}`}
                                >
                                  {copiedId === entry.id ? (
                                    <svg width="16" height="16" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                                    </svg>
                                  ) : (
                                    <svg width="16" height="16" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" />
                                    </svg>
                                  )}
                                </button>
                                <button
                                  type="button"
                                  className="vault-entry-delete-button"
                                  onClick={() => handleDeleteEntry(entry)}
                                  disabled={deletingId === entry.id}
                                  aria-label={t('vault.ariaDelete', { title: entry.title })}
                                  data-testid={`vault-delete-entry-${entry.id}`}
                                >
                                  <svg width="16" height="16" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                                  </svg>
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

      {/* Import Modal */}
      {showImportModal && (
        <div className="import-modal-overlay" data-testid="vault-import-modal">
          <div className="import-modal">
            <h2>{t('vault.importTitle')}</h2>
            <p className="import-description">{t('vault.importDescription')}</p>

            <div className="import-field">
              <label htmlFor="kdbx-file-input">{t('vault.kdbxFileLabel')}</label>
              <input
                id="kdbx-file-input"
                type="file"
                accept=".kdbx"
                onChange={(e) => setImportFile(e.target.files?.[0] || null)}
                disabled={isImporting}
                data-testid="vault-import-file-input"
              />
              {importFile && <span className="file-name">{importFile.name}</span>}
            </div>

            <div className="import-field">
              <label htmlFor="kdbx-password-input">{t('vault.databasePasswordLabel')}</label>
              <input
                id="kdbx-password-input"
                type="password"
                placeholder={t('vault.kdbxPasswordPlaceholder')}
                value={importPassword}
                onChange={(e) => setImportPassword(e.target.value)}
                disabled={isImporting}
                data-testid="vault-import-password-input"
              />
            </div>

            {importError && <p className="import-error">{importError}</p>}

            <div className="import-actions">
              <button
                type="button"
                onClick={closeImportModal}
                disabled={isImporting}
                className="btn-secondary"
                data-testid="vault-import-cancel-button"
              >
                {t('common.cancel')}
              </button>
              <button
                type="button"
                onClick={handleImportSubmit}
                disabled={!importFile || !importPassword.trim() || isImporting}
                className="btn-primary"
                data-testid="vault-import-submit-button"
              >
                {isImporting ? t('vault.importing') : t('vault.importButton')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default VaultPage;
