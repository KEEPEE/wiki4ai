/**
 * Document Editor page component.
 * Provides a markdown editor with live preview, split view, auto-save, and save functionality.
 * Supports both creating new documents and editing existing ones.
 */

import React, { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import ReactMarkdown from 'react-markdown';
import BackButton from '../components/BackButton';
import Breadcrumb from '../components/Breadcrumb';
import { documentApi } from '../services/documentApi';
import { useDocuments } from '../hooks/useDocuments';
import { useDebounce } from '../hooks/useDebounce';
import './DocumentEditor.css';

type ViewMode = 'edit' | 'preview' | 'split';

interface DocumentEditorProps {
  initialContent?: string;
  onSave?: (content: string) => void;
}

const AUTO_SAVE_DELAY = 2000; // Auto-save every 2 seconds after changes

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

  // Document data from API (for editing existing document)
  const [title, setTitle] = useState('');
  const [content, setContent] = useState(initialContent);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveStatus, setSaveStatus] = useState<'idle' | 'saved' | 'saving' | 'unsaved' | 'error'>('idle');
  const [viewMode, setViewMode] = useState<ViewMode>('split');

  // Track last saved content to detect unsaved changes
  const [lastSavedContent, setLastSavedContent] = useState(initialContent);
  const [lastSavedTitle, setLastSavedTitle] = useState('');

  // React Query hooks for document management
  const { updateDocument, createDocument } = useDocuments(projectSlug || '');

  // Debounced content for auto-save
  const debouncedContent = useDebounce(content, AUTO_SAVE_DELAY);
  const debouncedTitle = useDebounce(title, AUTO_SAVE_DELAY);

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

  // Trigger auto-save when debounced values change (2s after last keystroke)
  useEffect(() => {
    if (!debouncedTitle.trim()) return;
    handleAutoSave();
  }, [handleAutoSave, debouncedContent, debouncedTitle]);

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
        <Breadcrumb projectSlug={projectSlug} documentTitle={title || docSlug || 'Nový dokument'} />
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

      {/* Save status indicator */}
      {saveStatus !== 'idle' && (
        <div className={`save-status ${saveStatus}`} data-testid="save-status">
          {saveStatus === 'saving' ? 'Ukladá sa...' : saveStatus === 'saved' ? 'Uložené' : saveStatus === 'unsaved' ? 'Neuložené zmeny' : saveStatus === 'error' ? 'Chyba pri ukladaní' : ''}
        </div>
      )}

      {/* Editor body */}
      <div className={`editor-body view-mode-${viewMode}`}>
        {/* Editor pane */}
        {(viewMode === 'edit' || viewMode === 'split') && (
          <div className="editor-pane">
            <textarea
              className="markdown-input"
              value={content}
              onChange={(e) => setContent(e.target.value)}
              placeholder={isEditing ? 'Upravte markdown obsah...' : 'Napíšte markdown obsah...'}
              spellCheck={false}
            />
          </div>
        )}

        {/* Preview pane */}
        {(viewMode === 'preview' || viewMode === 'split') && (
          <div className="preview-pane">
            {content.trim() ? (
              <ReactMarkdown>{content}</ReactMarkdown>
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
    </div>
  );
};

export default DocumentEditor;
