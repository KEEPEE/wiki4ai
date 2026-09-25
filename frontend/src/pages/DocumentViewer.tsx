/**
 * Document Viewer page component.
 * Displays a rendered markdown document with wiki-style links and breadcrumb navigation.
 * Fetches raw markdown directly for reliable client-side rendering.
 */

import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import MarkdownPreview from '../components/MarkdownPreview';
import BackButton from '../components/BackButton';
import Breadcrumb from '../components/Breadcrumb';
import { useProjects } from '../hooks/useProjects';
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
  const { t } = useTranslation();
  const { slug: paramProjectSlug, docId: paramDocSlug } = useParams<{ slug: string; docId: string }>();
  const navigate = useNavigate();

  const projectSlug = propProjectSlug || paramProjectSlug || '';
  const docSlug = paramDocSlug || '';

  // Ancestor chain for nested-project breadcrumbs (WIKI4AI-31)
  const { projects } = useProjects();
  const ancestors = useMemo(() => {
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
      const message = err instanceof Error ? err.message : t('viewer.loadFailed');
      setError(message);
    } finally {
      setLoading(false);
    }
  }, [projectSlug, docSlug, t]);

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
        <p>{t('viewer.notFound')}</p>
      </div>
    );
  }

  if (loading) {
    return (
      <div className="document-viewer">
        <div className="loading-indicator">{t('viewer.loading')}</div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="document-viewer">
        <div className="error-message">
          <p>{t('viewer.errorLabel')}: {error}</p>
          <button onClick={() => navigate(`/projects/${projectSlug}`)} className="btn-secondary">
            {t('project.backToProject')}
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="document-viewer">
      {/* Breadcrumb Navigation */}
      <Breadcrumb projectSlug={projectSlug} documentTitle={title || docSlug} ancestors={ancestors} />

      {/* Document Header — WIKI4AI-84: long titles ellipsis-truncate (see
          .document-header h1 in DocumentViewer.css); full text via tooltip. */}
      <header className="document-header">
        <h1 title={title || docSlug}>{title || docSlug}</h1>
        <div className="document-actions">
          <BackButton to={`/projects/${projectSlug}`} label={t('project.backToProject')} />
          <button onClick={handleEdit} className="btn-primary">
            {t('viewer.edit')}
          </button>
        </div>
      </header>

      {/* Document Content — rendered markdown preview */}
      <div className="document-content">
        {content.trim() ? (
          <MarkdownPreview content={content} />
        ) : (
          <p className="empty-preview">{t('viewer.emptyContent')}</p>
        )}
      </div>

      {/* Wiki Links Section */}
      {wikiLinks.length > 0 && (
        <section className="backlinks-section" aria-label={t('viewer.wikiLinksAria')}>
          <h3>🔗 {t('viewer.linksHeading', { count: wikiLinks.length })}</h3>
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
      <section className="backlinks-section" aria-label={t('viewer.linkedFromAria')}>
        <h3>📎 {t('viewer.linkedFromHeading', { count: backlinks.length })}</h3>
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
          <p className="no-backlinks">{t('viewer.noBacklinks')}</p>
        )}
      </section>
    </div>
  );
};

export default DocumentViewer;
