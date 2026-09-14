/**
 * Document Editor page component.
 * Provides a markdown editor with live preview, split view, auto-save, and save functionality.
 * Supports both creating new documents and editing existing ones.
 */

import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
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
  const { slug: projectSlug, docId: docSlug } = useParams<{ slug: string; docId: string }>();
  const navigate = useNavigate();
  const isEditing = !!docSlug;

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

  // React Query hooks for document management
  const { updateDocument, createDocument } = useDocuments(projectSlug || '');

  // Debounced content for auto-save
  const debouncedContent = useDebounce(content, autosaveInterval);
  const debouncedTitle = useDebounce(title, autosaveInterval);

  // Load existing document data when editing
  useEffect(() => {
    if (!isEditing || !projectSlug || !docSlug) return;

    const loadDocument = async () => {
      setLoading(true);
      try {
        const doc = await documentApi.get(projectSlug, docSlug);
        setTitle(doc.title || '');
        setContent(doc.content || initialContent);
        setLastSavedTitle(doc.title || '');
        setLastSavedContent(doc.content || initialContent);
        setSaveStatus('idle');
      } catch (err) {
        const message = err instanceof Error ? err.message : 'Failed to load document';
        console.error('Error loading document:', message);
        setSaveStatus('error');
      } finally {
        setLoading(false);
      }
    };

    loadDocument();
  }, [isEditing, projectSlug, docSlug, initialContent]);

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
      setTimeout(() => setSaveStatus('idle'), 2000);
    } catch (err) {
      console.error('Auto-save failed:', err);
      setSaveStatus('error');
    } finally {
      setSaving(false);
    }
  }, [projectSlug, debouncedTitle, debouncedContent, isEditing, docSlug, updateDocument, createDocument, navigate]);

  // Trigger auto-save when debounced values change (and autosave is enabled)
  useEffect(() => {
    if (!debouncedTitle.trim() || !autosaveEnabled) return;
    handleAutoSave();
  }, [handleAutoSave, debouncedContent, debouncedTitle, autosaveEnabled]);

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
      setTimeout(() => setSaveStatus('idle'), 2000);

      // Call external onSave callback if provided
      if (onSave) {
        await onSave(content);
      }
    } catch (err) {
      console.error('Manual save failed:', err);
      setSaveStatus('error');
    } finally {
      setSaving(false);
    }
  };

  const viewModes: ViewMode[] = ['edit', 'preview', 'split'];

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
          <h1>Loading...</h1>
        </header>
        <div className="loading-indicator editor-loading">Loading document...</div>
      </div>
    );
  }

  return (
    <div className="document-editor">
      {/* Breadcrumb Navigation */}
      {projectSlug && (
        <Breadcrumb projectSlug={projectSlug} documentTitle={title || docSlug || 'Nový dokument'} ancestors={ancestors} />
      )}

      {/* Header with title input and actions */}
      <header className="editor-header">
        <input
          type="text"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          placeholder={isEditing ? 'Upraviť názov dokumentu...' : 'Názov nového dokumentu...'}
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
                aria-label="Enable autosave"
              />
              <span className="toggle-slider"></span>
            </label>
            <select 
              className="autosave-dropdown" 
              value={autosaveInterval} 
              onChange={(e) => handleIntervalChange(e.target.value)}
              aria-label="Autosave interval"
            >
              <option value={1000}>1s</option>
              <option value={2000}>2s</option>
              <option value={5000}>5s</option>
              <option value={10000}>10s</option>
              <option value={30000}>30s</option>
            </select>
            {saveStatus !== 'idle' && (
              <div className={`save-status-compact ${saveStatus}`} data-testid="save-status">
                {saveStatus === 'saving' ? 'Ukladá sa...' : saveStatus === 'saved' ? 'Uložené' : saveStatus === 'unsaved' ? 'Neuložené zmeny' : saveStatus === 'error' ? 'Chyba' : ''}
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
                aria-label={`${mode.charAt(0).toUpperCase() + mode.slice(1)} view`}
                title={`Zobraziť ako ${mode === 'edit' ? 'editor' : mode === 'preview' ? 'náhľad' : 'split'}`}
              >
                {mode === 'edit' ? 'Edit' : mode === 'preview' ? 'Preview' : 'Split'}
              </button>
            ))}
          </div>

          {/* Save button */}
          <button
            onClick={handleManualSave}
            disabled={saving || !title.trim()}
            className={`btn-primary save-btn ${saving ? 'saving' : ''}`}
          >
            {saving ? 'Ukladá sa...' : 'Uložiť'}
          </button>

          {/* Back button */}
          <BackButton to={`/projects/${projectSlug}/documents/${docSlug}`} label="Späť" />
        </div>
      </header>

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
              <p className="empty-preview">Začnite písať markdown pre náhľad...</p>
            )}
          </div>
        )}
      </div>

      {/* Footer with word/character count */}
      <footer className="editor-footer">
        <span className="word-count">{getWordCount(content)} words</span>
        <span className="char-count">{getCharCount(content)} characters</span>
      </footer>

      {/* Document Links Management - only when editing existing document */}
      {isEditing && projectSlug && docSlug && (
        <DocumentLinks projectSlug={projectSlug} docSlug={docSlug} />
      )}
    </div>
  );
};

export default DocumentEditor;
