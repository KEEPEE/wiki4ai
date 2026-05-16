/**
 * Document Editor page component.
 * Provides a markdown editor with live preview, split view, auto-save, and save functionality.
 * Supports both creating new documents and editing existing ones.
 */

import React, { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import ReactMarkdown from 'react-markdown';
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

const DocumentEditor: React.FC<DocumentEditorProps> = ({ initialContent = '', onSave }) => {
  const { slug: projectSlug, docId: docSlug } = useParams<{ slug: string; docId: string }>();
  const navigate = useNavigate();
  const isEditing = !!docSlug;

  // Document data from API (for editing existing document)
  const [title, setTitle] = useState('');
  const [content, setContent] = useState(initialContent);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveStatus, setSaveStatus] = useState<'idle' | 'saved' | 'error'>('idle');
  const [viewMode, setViewMode] = useState<ViewMode>('split');

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

  // Auto-save debounced changes
  const handleAutoSave = useCallback(async () => {
    if (!projectSlug || !debouncedTitle.trim()) return;

    try {
      setSaving(true);
      if (isEditing) {
        await updateDocument({ docSlug: docSlug!, data: { title: debouncedTitle, content: debouncedContent } });
      } else {
        const newDoc = await createDocument({ title: debouncedTitle, content: debouncedContent });
        // Navigate to the newly created document's viewer
        navigate(`/projects/${projectSlug}/documents/${newDoc.title.toLowerCase().replace(/[^a-z0-9\s-]/g, '').replace(/\s+/g, '-')}`);
      }
      setSaveStatus('saved');
      setTimeout(() => setSaveStatus('idle'), 2000);
    } catch (err) {
      console.error('Auto-save failed:', err);
      setSaveStatus('error');
    } finally {
      setSaving(false);
    }
  }, [projectSlug, debouncedTitle, debouncedContent, isEditing, docSlug, updateDocument, createDocument, navigate]);

  // Trigger auto-save when debounced values change
  useEffect(() => {
    if (debouncedContent !== content || debouncedTitle !== title) {
      handleAutoSave();
    }
  }, [handleAutoSave, debouncedContent, debouncedTitle, content, title]);

  const handleManualSave = async () => {
    if (!projectSlug || !title.trim()) return;

    try {
      setSaving(true);
      setSaveStatus('idle');
      if (isEditing) {
        await updateDocument({ docSlug: docSlug!, data: { title, content } });
      } else {
        const newDoc = await createDocument({ title, content });
        // Navigate to the newly created document's viewer
        navigate(`/projects/${projectSlug}/documents/${newDoc.title.toLowerCase().replace(/[^a-z0-9\s-]/g, '').replace(/\s+/g, '-')}`);
      }
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

  const handleBack = () => {
    if (isEditing && projectSlug && docSlug) {
      navigate(`/projects/${projectSlug}/documents/${docSlug}`);
    } else {
      navigate(-1); // Go back to previous page
    }
  };

  const handlePreviewToggle = () => {
    setViewMode((prev) => (prev === 'split' ? 'edit' : prev === 'edit' ? 'preview' : 'split'));
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
          {/* View mode toggle */}
          <button
            onClick={handlePreviewToggle}
            className={`btn-secondary view-mode-btn ${viewMode === 'split' ? 'active' : ''}`}
            title={viewMode === 'edit' ? 'Zobraziť náhľad' : viewMode === 'preview' ? 'Zobraziť split' : 'Zobraziť editor'}
          >
            {viewMode === 'edit' ? '👁 Náhľad' : viewMode === 'preview' ? '⬜ Split' : '✏️ Editor'}
          </button>

          {/* Save button */}
          <button
            onClick={handleManualSave}
            disabled={saving || !title.trim()}
            className={`btn-primary save-btn ${saving ? 'saving' : ''}`}
          >
            {saving ? 'Ukladá sa...' : 'Uložiť'}
          </button>

          {/* Back button */}
          <button onClick={handleBack} className="btn-secondary back-btn">
            ← Späť
          </button>
        </div>
      </header>

      {/* Save status indicator */}
      {saveStatus !== 'idle' && (
        <div className={`save-status ${saveStatus}`}>
          {saveStatus === 'saved' ? '✓ Uložené' : saveStatus === 'error' ? '✗ Chyba pri ukladaní' : ''}
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
    </div>
  );
};

export default DocumentEditor;
