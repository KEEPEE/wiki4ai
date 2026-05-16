/**
 * Project Detail page component.
 * Displays a list of documents within a project with the option to create new ones.
 */

import React, { useState } from 'react';
import { useParams, Link, useNavigate } from 'react-router-dom';
import { useProjects } from '../hooks/useProjects';
import { useDocuments } from '../hooks/useDocuments';
import type { CreateDocumentDto } from '../types/document';
import './ProjectDetail.css';

const ProjectDetail: React.FC = () => {
  const { slug } = useParams<{ slug: string }>();
  const navigate = useNavigate();
  const { projects, isLoading: loadingProjects } = useProjects();
  const {
    documents,
    isLoading: loadingDocuments,
    createDocument,
    deleteDocument,
    isCreating,
    isDeleting,
  } = useDocuments(slug ?? '');

  const [showCreateForm, setShowCreateForm] = useState(false);
  const [newTitle, setNewTitle] = useState('');
  const [createError, setCreateError] = useState<string | null>(null);

  // Find the project by slug
  const project = projects.find((p) => p.slug === slug);

  if (loadingProjects) return <div className="project-detail"><div className="loading-state"><div className="spinner" /><p>Loading...</p></div></div>;
  if (!project) return <div className="project-detail error">Project not found.</div>;

  const handleCreateDocument = async () => {
    if (!newTitle.trim()) return;

    setCreateError(null);
    try {
      const dto: CreateDocumentDto = { title: newTitle.trim(), content: '' };
      await createDocument(dto);
      setNewTitle('');
      setShowCreateForm(false);
    } catch (err) {
      setCreateError(err instanceof Error ? err.message : 'Failed to create document');
    }
  };

  const handleDeleteDocument = async (docSlug: string) => {
    if (!window.confirm(`Are you sure you want to delete "${docSlug}"?`)) return;
    try {
      await deleteDocument(docSlug);
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to delete document');
    }
  };

  const handleViewDocument = (docSlug: string) => {
    navigate(`/projects/${slug}/documents/${docSlug}`);
  };

  const formatDate = (dateString: string): string => {
    try {
      const date = new Date(dateString);
      return date.toLocaleDateString('sk-SK', {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      });
    } catch {
      return dateString;
    }
  };

  return (
    <div className="project-detail">
      {/* Breadcrumb Navigation */}
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Dashboard</Link>
        <span className="separator">&rsaquo;</span>
        <span className="current">{project.name}</span>
      </nav>

      <header className="detail-header">
        <h1>{project.name}</h1>
        {project.description && <p className="description">{project.description}</p>}
        <button onClick={() => setShowCreateForm(!showCreateForm)} className="btn-primary">
          + Nový dokument
        </button>
      </header>

      {/* Create Document Form */}
      {showCreateForm && (
        <form onSubmit={(e) => { e.preventDefault(); handleCreateDocument(); }} className="create-form">
          <input
            type="text"
            placeholder="Názov dokumentu"
            value={newTitle}
            onChange={(e) => setNewTitle(e.target.value)}
            required
            autoFocus
          />
          {createError && <p className="error">{createError}</p>}
          <div className="form-actions">
            <button type="submit" className="btn-primary" disabled={isCreating}>
              {isCreating ? 'Vytváram...' : 'Vytvoriť'}
            </button>
            <button type="button" onClick={() => setShowCreateForm(false)} className="btn-secondary">
              Zrušiť
            </button>
          </div>
        </form>
      )}

      {/* Documents List */}
      <div className="documents-list">
        <h2>Dokumenty ({documents.length})</h2>

        {loadingDocuments ? (
          <div className="loading-state"><div className="spinner" /><p>Načítavam dokumenty...</p></div>
        ) : documents.length === 0 ? (
          <p className="empty-state">Žiadne dokumenty. Vytvorte prvý dokument!</p>
        ) : (
          <ul className="document-items">
            {documents.map((doc) => (
              <li key={doc.id} className="document-item">
                <div className="doc-info" onClick={() => handleViewDocument(doc.title.toLowerCase().replace(/\s+/g, '-'))}>
                  <span className="doc-title">{doc.title}</span>
                  <span className="doc-slug">@{doc.title.toLowerCase().replace(/\s+/g, '-')}</span>
                </div>
                <div className="doc-meta">
                  <span className="doc-date">{formatDate(doc.updatedAt)}</span>
                  <button
                    onClick={(e) => { e.stopPropagation(); handleDeleteDocument(doc.title.toLowerCase().replace(/\s+/g, '-')); }}
                    className="btn-delete"
                    disabled={isDeleting}
                    title="Vymazať dokument"
                  >
                    &times;
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
};

export default ProjectDetail;
