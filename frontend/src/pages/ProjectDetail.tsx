/**
 * Project Detail page component.
 * Displays a list of documents within a project with the option to create new ones.
 */

import React, { useState, useRef, useCallback } from 'react';
import { useParams, Link, useNavigate } from 'react-router-dom';
import { useProjects } from '../hooks/useProjects';
import { useDocuments, useSearchDocuments } from '../hooks/useDocuments';
import { useDebounce } from '../hooks/useDebounce';
import type { CreateDocumentDto } from '../types/document';
import './ProjectDetail.css';

type TabType = 'documents' | 'graph';

const ALLOWED_EXTENSIONS = ['.md', '.markdown'];

const ProjectDetail: React.FC = () => {
  const { slug } = useParams<{ slug: string }>();
  const navigate = useNavigate();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const { projects, isLoading: loadingProjects } = useProjects();
  const {
    documents,
    isLoading: loadingDocuments,
    createDocument,
    deleteDocument,
    uploadDocument,
    isCreating,
    isDeleting,
    isUploading,
  } = useDocuments(slug ?? '');

  // Search state
  const [searchQuery, setSearchQuery] = useState('');
  const debouncedSearchQuery = useDebounce(searchQuery, 300);
  const { searchResults, isLoading: searching, hasSearched } = useSearchDocuments(
    slug ?? '',
    debouncedSearchQuery,
  );

  const [activeTab, setActiveTab] = useState<TabType>('documents');
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [newTitle, setNewTitle] = useState('');
  const [createError, setCreateError] = useState<string | null>(null);

  // Upload state
  const [uploadProgress, setUploadProgress] = useState(0);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [isDragOver, setIsDragOver] = useState(false);

  // Find the project by slug
  const project = projects.find((p) => p.slug === slug);

  // Determine which documents to display: search results if searching, otherwise all documents
  const displayDocuments = hasSearched ? searchResults : documents;

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

  // Upload handlers
  const isValidMarkdownFile = (file: File): boolean => {
    const ext = '.' + file.name.split('.').pop()?.toLowerCase();
    return ALLOWED_EXTENSIONS.includes(ext);
  };

  const handleUpload = async (file: File) => {
    if (!slug) return;
    setUploadError(null);
    setUploadProgress(0);

    // Simulate progress since fetch doesn't support upload progress natively
    const progressInterval = setInterval(() => {
      setUploadProgress((prev) => Math.min(prev + 10, 90));
    }, 200);

    try {
      await uploadDocument(file);
      clearInterval(progressInterval);
      setUploadProgress(100);
      // Reset progress after a short delay
      setTimeout(() => setUploadProgress(0), 1500);
    } catch (err) {
      clearInterval(progressInterval);
      setUploadProgress(0);
      setUploadError(err instanceof Error ? err.message : 'Failed to upload file');
    }
  };

  const handleFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    if (!isValidMarkdownFile(file)) {
      setUploadError('Please select a .md or .markdown file');
      return;
    }

    handleUpload(file);
    // Reset input so the same file can be selected again
    e.target.value = '';
  };

  const handleImportClick = () => {
    fileInputRef.current?.click();
  };

  // Drag & drop handlers
  const handleDragEnter = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragOver(true);
  }, []);

  const handleDragLeave = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragOver(false);
  }, []);

  const handleDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
  }, []);

  const handleDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragOver(false);

    const files = e.dataTransfer.files;
    if (!files || files.length === 0) return;

    const file = files[0];
    if (!isValidMarkdownFile(file)) {
      setUploadError('Please drop a .md or .markdown file');
      return;
    }

    handleUpload(file);
  }, [slug, isUploading]);

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
    <div
      className={`project-detail${isDragOver ? ' drag-over' : ''}`}
      onDragEnter={handleDragEnter}
      onDragLeave={handleDragLeave}
      onDragOver={handleDragOver}
      onDrop={handleDrop}
    >
      {/* Drag & Drop Overlay */}
      {isDragOver && (
        <div className="drag-overlay">
          <p>📁 Drop your .md file here</p>
        </div>
      )}

      {/* Hidden File Input */}
      <input
        ref={fileInputRef}
        type="file"
        accept=".md,.markdown"
        onChange={handleFileSelect}
        className="hidden-file-input"
        data-testid="import-file-input"
        aria-hidden="true"
        tabIndex={-1}
        style={{ display: 'none' }}
      />

      {/* Breadcrumb Navigation */}
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Dashboard</Link>
        <span className="separator">&rsaquo;</span>
        <span className="current">{project.name}</span>
      </nav>

      <header className="detail-header">
        <h1>{project.name}</h1>
        {project.description && <p className="description">{project.description}</p>}
        <div className="header-actions">
          <button onClick={() => setShowCreateForm(!showCreateForm)} className="btn-primary" disabled={activeTab !== 'documents'}>
            + Nový dokument
          </button>
          <button
            onClick={handleImportClick}
            className="btn-secondary btn-import"
            disabled={isUploading || activeTab !== 'documents'}
            data-testid="import-file-button"
            title="Import .md file"
          >
            📁 Import file
          </button>
          <Link
            to={`/projects/${slug}/settings`}
            className="btn-secondary btn-settings"
            data-testid="settings-link"
            title="Project settings"
          >
            ⚙️ Settings
          </Link>
        </div>
      </header>

      {/* Upload Progress Bar */}
      {uploadProgress > 0 && (
        <div className="upload-progress-container">
          <div className="upload-progress-bar" style={{ width: `${uploadProgress}%` }} />
          <span className="upload-progress-text">{uploadProgress === 100 ? '✓ Uploaded!' : `Uploading... ${uploadProgress}%`}</span>
        </div>
      )}

      {/* Upload Error */}
      {uploadError && (
        <div className="upload-error" data-testid="upload-error">
          {uploadError}
          <button onClick={() => setUploadError(null)} className="dismiss-btn">&times;</button>
        </div>
      )}

      {/* Tabs Navigation */}
      <div className="tabs">
        <button
          className={`tab ${activeTab === 'documents' ? 'active' : ''}`}
          onClick={() => setActiveTab('documents')}
        >
          Dokumenty ({documents.length})
        </button>
        <Link
          to={`/projects/${slug}/graph`}
          className={`tab ${activeTab === 'graph' ? 'active' : ''}`}
          onClick={() => setActiveTab('graph')}
        >
          Graf
        </Link>
      </div>

      {/* Create Document Form */}
      {showCreateForm && activeTab === 'documents' && (
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
      {activeTab === 'documents' && (
        <div className="documents-list">
          <h2>Dokumenty ({hasSearched ? searchResults.length : documents.length})</h2>

          {/* Search Bar */}
          <div className="search-bar">
            <input
              type="text"
              placeholder="Search documents..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="search-input"
              data-testid="document-search-input"
            />
            {searchQuery && (
              <button
                type="button"
                onClick={() => setSearchQuery('')}
                className="search-clear-btn"
                aria-label="Clear search"
                data-testid="clear-search-button"
              >
                &times;
              </button>
            )}
          </div>

          {loadingDocuments && !hasSearched ? (
            <div className="loading-state"><div className="spinner" /><p>Načítavam dokumenty...</p></div>
          ) : searching ? (
            <div className="loading-state"><div className="spinner" /><p>Searching...</p></div>
          ) : hasSearched && searchResults.length === 0 ? (
            <p className="empty-state">No documents match your search</p>
          ) : !hasSearched && displayDocuments.length === 0 ? (
            <p className="empty-state">Žiadne dokumenty. Vytvorte prvý dokument!</p>
          ) : (
            <ul className="document-items">
              {displayDocuments.map((doc) => (
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
      )}

      {/* Graph Tab Content */}
      {activeTab === 'graph' && (
        <Link to={`/projects/${slug}/graph`} className="graph-redirect">
          <div className="graph-preview">
            <h2>Document Graph</h2>
            <p>View the interactive graph visualization of document connections.</p>
            <span className="btn-primary">Open Graph View &rarr;</span>
          </div>
        </Link>
      )}
    </div>
  );
};

export default ProjectDetail;
