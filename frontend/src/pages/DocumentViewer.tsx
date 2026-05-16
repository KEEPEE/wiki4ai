/**
 * Document Viewer page component.
 * Displays a rendered markdown document with wiki-style links and breadcrumb navigation.
 */

import React, { useState, useEffect } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import MarkdownViewer from '../components/MarkdownViewer';
import { documentApi } from '../services/documentApi';
import './DocumentViewer.css';

interface DocumentViewerProps {
  projectSlug?: string;
}

const DocumentViewer: React.FC<DocumentViewerProps> = ({ projectSlug: propProjectSlug }) => {
  const { slug: paramProjectSlug, docId: paramDocSlug } = useParams<{ slug: string; docId: string }>();
  const navigate = useNavigate();

  const projectSlug = propProjectSlug || paramProjectSlug || '';
  const docSlug = paramDocSlug || '';

  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  const [wikiLinks, setWikiLinks] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!projectSlug || !docSlug) return;

    const loadDocument = async () => {
      setLoading(true);
      setError(null);
      try {
        const data = await documentApi.getContent(projectSlug, docSlug);
        setTitle(data.title);
        setContent(data.content);
        setWikiLinks(data.wikiLinks || []);
      } catch (err) {
        const message = err instanceof Error ? err.message : 'Failed to load document';
        setError(message);
      } finally {
        setLoading(false);
      }
    };

    loadDocument();
  }, [projectSlug, docSlug]);

  const handleLinkClick = (linkSlug: string) => {
    navigate(`/projects/${projectSlug}/documents/${linkSlug}`);
  };

  const handleEdit = () => {
    navigate(`/projects/${projectSlug}/documents/${docSlug}/edit`);
  };

  if (!projectSlug || !docSlug) {
    return (
      <div className="document-viewer">
        <p>Document not found.</p>
      </div>
    );
  }

  if (loading) {
    return (
      <div className="document-viewer">
        <div className="loading-indicator">Loading document...</div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="document-viewer">
        <div className="error-message">
          <p>Error: {error}</p>
          <button onClick={() => navigate(`/projects/${projectSlug}`)} className="btn-secondary">
            Back to Project
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="document-viewer">
      {/* Breadcrumb Navigation */}
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <ol>
          <li>
            <Link to="/">Dashboard</Link>
          </li>
          <li>
            <Link to={`/projects/${projectSlug}`}>{projectSlug}</Link>
          </li>
          <li className="current" aria-current="page">{title || docSlug}</li>
        </ol>
      </nav>

      {/* Document Header */}
      <header className="document-header">
        <h1>{title || docSlug}</h1>
        <div className="document-actions">
          <button onClick={handleEdit} className="btn-primary">
            Upraviť
          </button>
        </div>
      </header>

      {/* Document Content */}
      <div className="document-content">
        <MarkdownViewer content={content} wikiLinks={wikiLinks} onLinkClick={handleLinkClick} />
      </div>
    </div>
  );
};

export default DocumentViewer;
