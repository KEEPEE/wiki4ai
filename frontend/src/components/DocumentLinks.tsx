/**
 * Document Links component.
 * Displays and manages links between documents within a project.
 * Shows outgoing links (documents this document links to) and incoming links (backlinks).
 */

import React, { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { documentApi } from '../services/documentApi';
import './DocumentLinks.css';

interface DocumentLinkItem {
  id: number;
  title: string;
  slug?: string;
}

interface DocumentLinksProps {
  projectSlug: string;
  docSlug: string;
  onRefresh?: () => void;
}

const DocumentLinks: React.FC<DocumentLinksProps> = ({ projectSlug, docSlug, onRefresh }) => {
  const { t } = useTranslation();
  const [outgoingLinks, setOutgoingLinks] = useState<DocumentLinkItem[]>([]);
  const [backlinks, setBacklinks] = useState<DocumentLinkItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [addingLink, setAddingLink] = useState<string>('');
  const [newLinkTarget, setNewLinkTarget] = useState('');

  useEffect(() => {
    if (!projectSlug || !docSlug) return;

    const loadLinks = async () => {
      setLoading(true);
      try {
        const [outgoing, incoming] = await Promise.all([
          documentApi.getLinks(projectSlug, docSlug),
          documentApi.getBacklinks(projectSlug, docSlug),
        ]);
        setOutgoingLinks(outgoing.map((d) => ({ id: d.id, title: d.title, slug: d.slug })));
        setBacklinks(incoming.map((d) => ({ id: d.id, title: d.title, slug: d.slug })));
      } catch (err) {
        console.error('Error loading links:', err);
      } finally {
        setLoading(false);
      }
    };

    loadLinks();
  }, [projectSlug, docSlug]);

  const handleAddLink = async () => {
    if (!projectSlug || !docSlug || !newLinkTarget.trim()) return;

    try {
      setAddingLink(newLinkTarget);
      // Parse target document ID from the input (could be "id:123" or just "123")
      const targetId = parseInt(newLinkTarget.replace(/^id:/, ''), 10);
      if (!isNaN(targetId)) {
        await documentApi.addLink(projectSlug, docSlug, targetId);

        // Refresh the links
        const outgoing = await documentApi.getLinks(projectSlug, docSlug);
        setOutgoingLinks(outgoing.map((d) => ({ id: d.id, title: d.title, slug: d.slug })));

        if (onRefresh) onRefresh();
      }
    } catch (err) {
      console.error('Error adding link:', err);
    } finally {
      setAddingLink('');
      setNewLinkTarget('');
    }
  };

  const handleRemoveLink = async (targetDocId: number) => {
    if (!projectSlug || !docSlug) return;

    try {
      await documentApi.removeLink(projectSlug, docSlug, targetDocId);

      // Refresh the links
      const outgoing = await documentApi.getLinks(projectSlug, docSlug);
      setOutgoingLinks(outgoing.map((d) => ({ id: d.id, title: d.title, slug: d.slug })));

      if (onRefresh) onRefresh();
    } catch (err) {
      console.error('Error removing link:', err);
    }
  };

  return (
    <div className="document-links">
      <h3 className="document-links-title">🔗 {t('doclinks.title')}</h3>

      {/* Outgoing links section */}
      <section className="links-section">
        <h4 className="links-section-title">{t('doclinks.outgoing')}</h4>
        {loading ? (
          <p className="links-loading">{t('doclinks.loading')}</p>
        ) : outgoingLinks.length === 0 ? (
          <p className="links-empty">{t('doclinks.noLinks')}</p>
        ) : (
          <ul className="links-list">
            {outgoingLinks.map((link) => (
              <li key={link.id} className="link-item">
                <a
                  href={`/projects/${projectSlug}/documents/${link.slug || link.id}`}
                  className="link-item-title"
                >
                  {link.title}
                </a>
                <button
                  onClick={() => handleRemoveLink(link.id)}
                  className="btn-link-remove"
                  title={t('doclinks.removeTitle')}
                >
                  ✕
                </button>
              </li>
            ))}
          </ul>
        )}

        {/* Add link form */}
        <form
          onSubmit={(e) => {
            e.preventDefault();
            handleAddLink();
          }}
          className="add-link-form"
        >
          <input
            type="text"
            value={newLinkTarget}
            onChange={(e) => setNewLinkTarget(e.target.value)}
            placeholder={t('doclinks.idPlaceholder')}
            className="add-link-input"
          />
          <button
            type="submit"
            disabled={!!addingLink || !newLinkTarget.trim()}
            className={`btn-primary add-link-btn ${addingLink ? 'loading' : ''}`}
          >
            {addingLink ? t('doclinks.adding') : `+ ${t('doclinks.addLink')}`}
          </button>
        </form>
      </section>

      {/* Backlinks section */}
      <section className="links-section">
        <h4 className="links-section-title">{t('doclinks.backlinks')}</h4>
        {loading ? (
          <p className="links-loading">{t('doclinks.loading')}</p>
        ) : backlinks.length === 0 ? (
          <p className="links-empty">{t('doclinks.noBacklinks')}</p>
        ) : (
          <ul className="links-list">
            {backlinks.map((link) => (
              <li key={link.id} className="link-item">
                <a
                  href={`/projects/${projectSlug}/documents/${link.slug || link.id}`}
                  className="link-item-title"
                >
                  {link.title}
                </a>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
};

export default DocumentLinks;
