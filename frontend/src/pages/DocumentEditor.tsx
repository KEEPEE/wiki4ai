/**
 * Document Editor page component.
 * Provides a markdown editor with live preview, split view, auto-save, and save functionality.
 * Supports both creating new documents and editing existing ones.
 */

import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import MarkdownEditor from '../components/MarkdownEditor';
import MarkdownPreview from '../components/MarkdownPreview';
import BackButton from '../components/BackButton';
import Breadcrumb from '../components/Breadcrumb';
import DocumentLinks from '../components/DocumentLinks';
import { documentApi } from '../services/documentApi';
import { useDocuments } from '../hooks/useDocuments';
import { useProjects } from '../hooks/useProjects';
import { useDebounce } from '../hooks/useDebounce';
import './DocumentEditor.css';

type ViewMode = 'edit' | 'preview' | 'split';

interface DocumentEditorProps {
  initialContent?: string;
  onSave?: (content: string) => void;
}

const AUTO_SAVE_DELAY_DEFAULT = 2000; // Default auto-save every 2 seconds

/**
 * Calculate the number of words in a text string.
 * Words are defined as sequences of non-whitespace characters.
 */
function getWordCount(text: string): number {
  const trimmed = text.trim();
  if (trimmed.length === 0) return 0;
  return trimmed.split(/\s+/).length;
}

/**
 * Calculate the number of characters in a text string.
 */
function getCharCount(text: string): number {
  return text.length;
}

const DocumentEditor: React.FC<DocumentEditorProps> = ({ initialContent = '', onSave }) => {
  const { t } = useTranslation();
  const { slug: projectSlug, docId: docSlug } = useParams<{ slug: string; docId: string }>();
  const navigate = useNavigate();
  const isEditing = !!docSlug;

  // WIKI4AI-79: the editor page must be bounded to the viewport via
  // `height: calc(100vh - var(--w4a-nav-height))` (see .document-editor in
  // DocumentEditor.css). The --w4a-nav-height variable itself is measured and
  // set by Layout.tsx (WIKI4AI-82 moved it there so viewport-bounded pages
  // other than the editor — e.g. the graph page — can consume it too).

  // Ancestor chain for nested-project breadcrumbs (WIKI4AI-31)
  const { projects } = useProjects();
  const ancestors = useMemo(() => {
    if (!projectSlug) return [];
    const bySlug = new Map(projects.map((p) => [p.slug, p]));
    const project = bySlug.get(projectSlug);
    if (!project?.parentSlug) return [];
    const chain: { slug: string; name: string }[] = [];
    let cursor = bySlug.get(project.parentSlug);
    while (cursor && chain.length < 10) {
      chain.unshift({ slug: cursor.slug, name: cursor.name });
      cursor = cursor.parentSlug ? bySlug.get(cursor.parentSlug) : undefined;
    }
    return chain;
  }, [projects, projectSlug]);

  // Document data from API (for editing existing document)
  const [title, setTitle] = useState('');
  const [content, setContent] = useState(initialContent);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveStatus, setSaveStatus] = useState<'idle' | 'saved' | 'saving' | 'unsaved' | 'error'>('idle');
  const [autosaveEnabled, setAutosaveEnabled] = useState(() => {
    const saved = localStorage.getItem('wiki4ai-autosave-enabled');
    return saved === null ? true : saved === 'true';
  });
  const [autosaveInterval, setAutosaveInterval] = useState(() => {
    const saved = localStorage.getItem('wiki4ai-autosave-interval');
    return saved === null ? AUTO_SAVE_DELAY_DEFAULT : parseInt(saved, 10);
  });
  const [viewMode, setViewMode] = useState<ViewMode>('split');

  // Track last saved content to detect unsaved changes
  const [lastSavedContent, setLastSavedContent] = useState(initialContent);
  const [lastSavedTitle, setLastSavedTitle] = useState('');

  // WIKI4AI-72: true after a save failed with 409 (document modified by another
  // session). Autosave is suspended while active; cleared on successful reload/save.
  const [conflictActive, setConflictActive] = useState(false);

  // WIKI4AI-83: message when loading an existing document failed (e.g. 404 for a
  // non-existent slug). Renders a full error panel instead of leaving an editable
  // empty editor with only the tiny "Error" status pill.
  const [loadError, setLoadError] = useState<string | null>(null);

  // React Query hooks for document management
  const { updateDocument, createDocument } = useDocuments(projectSlug || '');

  // Debounced content for auto-save
  const debouncedContent = useDebounce(content, autosaveInterval);
  const debouncedTitle = useDebounce(title, autosaveInterval);

  // Load existing document data when editing (reused by the conflict banner's reload action)
  const loadDocument = useCallback(async () => {
    if (!projectSlug || !docSlug) return;
    setLoading(true);
    setLoadError(null);
    try {
      const doc = await documentApi.get(projectSlug, docSlug);
      setTitle(doc.title || '');
      setContent(doc.content || initialContent);
      setLastSavedTitle(doc.title || '');
      setLastSavedContent(doc.content || initialContent);
      setSaveStatus('idle');
      setConflictActive(false);
    } catch (err) {
      const message = err instanceof Error ? err.message : t('viewer.loadFailed');
      console.error('Error loading document:', message);
      // WIKI4AI-83: surface a clear, usable error state (full panel with a way
      // back to the project) instead of only the tiny status pill.
      setLoadError(message);
      setSaveStatus('error');
    } finally {
      setLoading(false);
    }
  }, [projectSlug, docSlug, initialContent, t]);

  useEffect(() => {
    if (!isEditing) return;
    loadDocument();
  }, [isEditing, loadDocument]);

  // Detect unsaved changes: when content/title differs from last saved
  useEffect(() => {
    if (loading) return;
    const hasUnsavedChanges =
      content !== lastSavedContent || title !== lastSavedTitle;
    if (hasUnsavedChanges && saveStatus === 'idle') {
      setSaveStatus('unsaved');
    }
  }, [content, title, lastSavedContent, lastSavedTitle, loading, saveStatus]);

  // Auto-save debounced changes
  const handleAutoSave = useCallback(async () => {
    if (!projectSlug || !debouncedTitle.trim()) return;
    // WIKI4AI-72: never auto-retry while a version conflict is active — the user
    // must reload and reapply their changes on the fresh content.
    if (conflictActive) return;
    // WIKI4AI-72: skip no-op saves (e.g. right after loading a document). A PUT of
    // unchanged content would still bump the document version and could trigger
    // spurious 409 conflicts for other sessions editing the same document.
    if (isEditing && debouncedTitle === lastSavedTitle && debouncedContent === lastSavedContent) return;

    try {
      setSaving(true);
      setSaveStatus('saving');
      if (isEditing) {
        await updateDocument({ docSlug: docSlug!, data: { title: debouncedTitle, content: debouncedContent } });
      } else {
        const newDoc = await createDocument({ title: debouncedTitle, content: debouncedContent });
        // Navigate to the newly created document's viewer
        navigate(`/projects/${projectSlug}/documents/${newDoc.title.toLowerCase().replace(/[^a-z0-9\s-]/g, '').replace(/\s+/g, '-')}`);
      }
      setLastSavedTitle(debouncedTitle);
      setLastSavedContent(debouncedContent);
      setSaveStatus('saved');
      setConflictActive(false);
      setTimeout(() => setSaveStatus('idle'), 2000);
    } catch (err) {
      console.error('Auto-save failed:', err);
      if (err instanceof Error && err.message.startsWith('409')) {
        setConflictActive(true);
      }
      setSaveStatus('error');
    } finally {
      setSaving(false);
    }
  }, [projectSlug, debouncedTitle, debouncedContent, isEditing, docSlug, updateDocument, createDocument, navigate, conflictActive, lastSavedTitle, lastSavedContent]);

  // Trigger auto-save when debounced values change (and autosave is enabled)
  useEffect(() => {
    if (!debouncedTitle.trim() || !autosaveEnabled || conflictActive) return;
    handleAutoSave();
  }, [handleAutoSave, debouncedContent, debouncedTitle, autosaveEnabled, conflictActive]);

  const handleManualSave = async () => {
    if (!projectSlug || !title.trim()) return;

    try {
      setSaving(true);
      setSaveStatus('saving');
      if (isEditing) {
        await updateDocument({ docSlug: docSlug!, data: { title, content } });
      } else {
        const newDoc = await createDocument({ title, content });
        // Navigate to the newly created document's viewer
        navigate(`/projects/${projectSlug}/documents/${newDoc.title.toLowerCase().replace(/[^a-z0-9\s-]/g, '').replace(/\s+/g, '-')}`);
      }
      setLastSavedTitle(title);
      setLastSavedContent(content);
      setSaveStatus('saved');
      setConflictActive(false);
      setTimeout(() => setSaveStatus('idle'), 2000);

      // Call external onSave callback if provided
      if (onSave) {
        await onSave(content);
      }
    } catch (err) {
      console.error('Manual save failed:', err);
      if (err instanceof Error && err.message.startsWith('409')) {
        setConflictActive(true);
      }
      setSaveStatus('error');
    } finally {
      setSaving(false);
    }
  };

  const viewModes: ViewMode[] = ['edit', 'preview', 'split'];

  // WIKI4AI-73: localized labels per view mode (button text, tooltip word, aria label).
  const viewModeLabels: Record<ViewMode, { button: string; word: string }> = {
    edit: { button: t('editor.btnEdit'), word: t('editor.modeEdit') },
    preview: { button: t('editor.btnPreview'), word: t('editor.modePreview') },
    split: { button: t('editor.btnSplit'), word: t('editor.modeSplit') },
  };

  const handleViewModeChange = (mode: ViewMode) => {
    setViewMode(mode);
  };

  const toggleAutosave = () => {
    setAutosaveEnabled((prev) => {
      const next = !prev;
      localStorage.setItem('wiki4ai-autosave-enabled', String(next));
      return next;
    });
  };

  const handleIntervalChange = (val: string) => {
    const interval = parseInt(val, 10);
    setAutosaveInterval(interval);
    localStorage.setItem('wiki4ai-autosave-interval', String(interval));
  };

  if (loading) {
    return (
      <div className="document-editor">
        <header className="editor-header">
          <h1>{t('common.loading')}</h1>
        </header>
        <div className="loading-indicator editor-loading">{t('viewer.loading')}</div>
      </div>
    );
  }

  // WIKI4AI-83: document failed to load (e.g. 404) → full error panel with a way
  // back to the project, mirroring DocumentViewer's error state. The editor is
  // intentionally not rendered: an empty editable buffer for a non-existent doc
  // would invite saving garbage under the typed slug.
  if (loadError) {
    return (
      <div className="document-editor">
        <div className="editor-error-state" data-testid="editor-load-error">
          <div className="editor-error-panel">
            <p>{t('viewer.errorLabel')}: {loadError}</p>
            <button onClick={() => navigate(`/projects/${projectSlug}`)} className="btn-secondary">
              {t('project.backToProject')}
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="document-editor">
      {/* Breadcrumb Navigation */}
      {projectSlug && (
        <Breadcrumb projectSlug={projectSlug} documentTitle={title || docSlug || t('editor.newDocFallback')} ancestors={ancestors} />
      )}

      {/* Header with title input and actions */}
      <header className="editor-header">
        <input
          type="text"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          placeholder={isEditing ? t('editor.titlePlaceholderEdit') : t('editor.titlePlaceholderNew')}
          className="editor-title-input"
        />

        <div className="editor-actions">
          {/* Autosave Controls */}
          <div className="autosave-controls">
            <label className="autosave-toggle">
              <input
                type="checkbox"
                checked={autosaveEnabled}
                onChange={toggleAutosave}
                aria-label={t('editor.autosaveAria')}
              />
              <span className="toggle-slider"></span>
            </label>
            <select 
              className="autosave-dropdown" 
              value={autosaveInterval} 
              onChange={(e) => handleIntervalChange(e.target.value)}
              aria-label={t('editor.intervalAria')}
            >
              <option value={1000}>1s</option>
              <option value={2000}>2s</option>
              <option value={5000}>5s</option>
              <option value={10000}>10s</option>
              <option value={30000}>30s</option>
            </select>
            {saveStatus !== 'idle' && (
              <div className={`save-status-compact ${saveStatus}`} data-testid="save-status">
                {saveStatus === 'saving' ? t('editor.statusSaving') : saveStatus === 'saved' ? t('editor.statusSaved') : saveStatus === 'unsaved' ? t('editor.statusUnsaved') : saveStatus === 'error' ? t('common.error') : ''}
              </div>
            )}
          </div>

          {/* View mode toggle — three pill buttons */}
          <div className="view-mode-toggle" data-testid="view-mode-toggle">
            {viewModes.map((mode) => (
              <button
                key={mode}
                onClick={() => handleViewModeChange(mode)}
                className={`view-mode-btn ${viewMode === mode ? 'active' : ''}`}
                aria-label={t('editor.ariaView', { mode: viewModeLabels[mode].word })}
                title={t('editor.showAs', { mode: viewModeLabels[mode].word })}
              >
                {viewModeLabels[mode].button}
              </button>
            ))}
          </div>

          {/* Save button */}
          <button
            onClick={handleManualSave}
            disabled={saving || !title.trim()}
            className={`btn-primary save-btn ${saving ? 'saving' : ''}`}
          >
            {saving ? t('editor.statusSaving') : t('common.save')}
          </button>

          {/* Back button */}
          <BackButton to={`/projects/${projectSlug}/documents/${docSlug}`} label={t('common.back')} />
        </div>
      </header>

      {/* WIKI4AI-72: version conflict banner (no auto-retry — user reloads and reapplies) */}
      {conflictActive && isEditing && (
        <div className="conflict-banner" data-testid="conflict-banner" role="alert">
          <span>{t('editor.conflictMessage')}</span>
          <button onClick={loadDocument} className="btn-secondary conflict-reload-btn" data-testid="conflict-reload">
            {t('editor.reload')}
          </button>
        </div>
      )}

       {/* Editor body */}
       <div className={`editor-body view-mode-${viewMode}`}>
        {/* Editor pane */}
        {(viewMode === 'edit' || viewMode === 'split') && (
          <div className="editor-pane">
            <MarkdownEditor value={content} onChange={(val) => setContent(val || '')} />
          </div>
        )}

        {/* Preview pane */}
        {(viewMode === 'preview' || viewMode === 'split') && (
          <div className="preview-pane">
            {content.trim() ? (
              <MarkdownPreview content={content} />
            ) : (
              <p className="empty-preview">{t('editor.emptyPreview')}</p>
            )}
          </div>
        )}
      </div>

      {/* Footer with word/character count */}
      <footer className="editor-footer">
        <span className="word-count">{t('editor.wordsCount', { count: getWordCount(content) })}</span>
        <span className="char-count">{t('editor.charsCount', { count: getCharCount(content) })}</span>
      </footer>

      {/* Document Links Management - only when editing existing document */}
      {isEditing && projectSlug && docSlug && (
        <DocumentLinks projectSlug={projectSlug} docSlug={docSlug} />
      )}
    </div>
  );
};

export default DocumentEditor;
