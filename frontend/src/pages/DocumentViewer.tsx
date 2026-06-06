/**
 * Document Viewer page component.
 * Displays a rendered markdown document with wiki-style links and breadcrumb navigation.
 * Fetches raw markdown directly for reliable client-side rendering.
 */

import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import ReactMarkdown from 'react-markdown';
import BackButton from '../components/BackButton';
import Breadcrumb from '../components/Breadcrumb';
import { documentApi } from '../services/documentApi';
import type { Document } from '../types/document';
import './DocumentViewer.css';

interface DocumentViewerProps {
  projectSlug?: string;
}

/**
 * Extract wiki-style link targets from raw markdown content.
 * Returns an array of document names found in [[Document]] syntax.
 */
function extractWikiLinks(content: string): string[] {
  const matches = content.match(/\[\[([^\]]+)\]\]/g);
  if (!matches) return [];
  return [...new Set(matches.map(m => m.slice(2, -2)))];
}

/**
 * Slugify a document title into a URL-friendly slug.
 */
function slugify(text: string): string {
  return text
    .toLowerCase()
    .replace(/[áä]/g, 'a')
    .replace(/č/g, 'c')
    .replace(/[ďđ]/g, 'd')
    .replace(/[éèêë]/g, 'e')
    .replace(/[íìîï]/g, 'i')
    .replace(/[ĺľ]/g, 'l')
    .replace(/ň/g, 'n')
    .replace(/[óòôö]/g, 'o')
    .replace(/ŕ/g, 'r')
    .replace(/š/g, 's')
    .replace(/ť/g, 't')
    .replace(/[úùûü]/g, 'u')
    .replace(/[ýỳÿ]/g, 'y')
    .replace(/ž/g, 'z')
    .replace(/[^a-z0-9\s-]/g, '')
    .replace(/\s+/g, '-')
    .replace(/-+/g, '-')
    .trim()
    .replace(/^-|-$/g, '');
}

const DocumentViewer: React.FC<DocumentViewerProps> = ({ projectSlug: propProjectSlug }) => {
  const { slug: paramProjectSlug, docId: paramDocSlug } = useParams<{ slug: string; docId: string }>();
  const navigate = useNavigate();

  const projectSlug = propProjectSlug || paramProjectSlug || '';
  const docSlug = paramDocSlug || '';

  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  const [backlinks, setBacklinks] = useState<Document[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Extract wiki links from raw content client-side
  const wikiLinks = useMemo(() => extractWikiLinks(content), [content]);

  const loadDocument = useCallback(async () => {
    if (!projectSlug || !docSlug) return;

    setLoading(true);
    setError(null);
    try {
      // Fetch raw markdown directly - more reliable than /content endpoint
      const doc = await documentApi.get(projectSlug, docSlug);
      setTitle(doc.title || '');
      setContent(doc.content || '');

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
      <Breadcrumb projectSlug={projectSlug} documentTitle={title || docSlug} />

      {/* Document Header */}
      <header className="document-header">
        <h1>{title || docSlug}</h1>
        <div className="document-actions">
          <BackButton to={`/projects/${projectSlug}`} label="Späť na projekt" />
          <button onClick={handleEdit} className="btn-primary">
            Upraviť
          </button>
        </div>
      </header>

      {/* Document Content — rendered markdown preview */}
      <div className="document-content">
        {content.trim() ? (
          <div className="markdown-viewer">
            <ReactMarkdown>{content}</ReactMarkdown>
          </div>
        ) : (
          <p className="empty-preview">This document has no content yet. Click "Upraviť" to add content.</p>
        )}
      </div>

      {/* Wiki Links Section */}
      {wikiLinks.length > 0 && (
        <section className="backlinks-section" aria-label="Wiki Links">
          <h3>🔗 Prepojenia ({wikiLinks.length})</h3>
          <ul className="backlinks-list">
            {wikiLinks.map((link) => (
              <li key={link} className="backlink-item">
                <button
                  className="backlink-link"
                  onClick={() => handleLinkClick(slugify(link))}
                  type="button"
                >
                  {link}
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}

      {/* Backlinks Section - Linked from (read-only) */}
      <section className="backlinks-section" aria-label="Linked from">
        <h3>📎 Linked from ({backlinks.length})</h3>
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
