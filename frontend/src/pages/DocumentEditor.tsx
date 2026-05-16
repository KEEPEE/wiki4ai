/**
 * Document Editor page component.
 * Provides a markdown editor with live preview and save functionality.
 */

import React, { useState } from 'react';
import { useParams } from 'react-router-dom';
import MarkdownViewer from '../components/MarkdownViewer';
import './DocumentEditor.css';

interface DocumentEditorProps {
  initialContent?: string;
  onSave?: (content: string) => void;
}

const DocumentEditor: React.FC<DocumentEditorProps> = ({ initialContent = '', onSave }) => {
  const { docId } = useParams<{ slug: string; docId: string }>();
  const [content, setContent] = useState(initialContent);
  const [isPreview, setIsPreview] = useState(false);

  const handleSave = async () => {
    if (onSave) {
      await onSave(content);
    }
    // TODO: Call document API to save the content
  };

  return (
    <div className="document-editor">
      <header className="editor-header">
        <h1>{docId ? 'Edit Document' : 'New Document'}</h1>
        <div className="editor-actions">
          <button onClick={() => setIsPreview(!isPreview)} className="btn-secondary">
            {isPreview ? 'Edit' : 'Preview'}
          </button>
          <button onClick={handleSave} className="btn-primary">Save</button>
        </div>
      </header>

      <div className="editor-body">
        {!isPreview && (
          <textarea
            className="markdown-input"
            value={content}
            onChange={(e) => setContent(e.target.value)}
            placeholder="Write your markdown here..."
            spellCheck={false}
          />
        )}
        {isPreview && (
          <div className="preview-container">
            <MarkdownViewer content={content || initialContent} />
          </div>
        )}
      </div>
    </div>
  );
};

export default DocumentEditor;
