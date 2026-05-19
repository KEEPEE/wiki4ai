/**
 * Document Viewer page component.
 * Displays a rendered markdown document with wiki-style links and breadcrumb navigation.
 */

import React, { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import MarkdownViewer from '../components/MarkdownViewer';
import { documentApi } from '../services/documentApi';
import type { Document } from '../types/document';
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
  const [backlinks, setBacklinks] = useState<Document[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadDocument = useCallback(async () => {
    if (!projectSlug || !docSlug) return;

    setLoading(true);
    setError(null);
    try {
      const data = await documentApi.getContent(projectSlug, docSlug);
      setTitle(data.title);
      setContent(data.content);
      setWikiLinks(data.wikiLinks || []);

      // Fetch backlinks in parallel after content loads
      try {
        const backlinkDocs = await documentApi.getBacklinks(projectSlug, docSlug);
        setBacklinks(backlinkDocs);
      } catch {
        // Silently ignore backlink errors - non-critical feature
        setBacklinks([]);
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to load document';
      setError(message);
    } finally {
      setLoading(false);
    }
  }, [projectSlug, docSlug]);

  useEffect(() => {
    loadDocument();
  }, [loadDocument]);

  const handleLinkClick = (linkSlug: string) => {
    navigate(`/projects/${projectSlug}/documents/${linkSlug}`);
  };

  const handleBacklinkClick = (backlinkDoc: Document) => {
    if (backlinkDoc.slug) {
      navigate(`/projects/${projectSlug}/documents/${backlinkDoc.slug}`);
    } else {
      // Fallback: search for the document by title in the current project view
      navigate(`/projects/${projectSlug}`);
    }
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

      {/* Backlinks Section - Linked from */}
      <section className="backlinks-section" aria-label="Linked from">
        <h3>🔗 Linked from ({backlinks.length})</h3>
        {backlinks.length > 0 ? (
          <ul className="backlinks-list">
            {backlinks.map((doc) => (
              <li key={doc.id} className="backlink-item">
                <button
                  className="backlink-link"
                  onClick={() => handleBacklinkClick(doc)}
                  type="button"
                >
                  {doc.title}
                </button>
              </li>
            ))}
          </ul>
        ) : (
          <p className="no-backlinks">No documents link to this page</p>
        )}
      </section>
    </div>
  );
};

export default DocumentViewer;
