/**
 * Project Detail page component.
 * Displays a list of documents within a project with the option to create new ones.
 */

import React, { useState, useRef, useCallback, useMemo } from 'react';
import { useParams, Link, useNavigate } from 'react-router-dom';
import { useProjects } from '../hooks/useProjects';
import { useDocuments, useSearchDocuments } from '../hooks/useDocuments';
import { useDebounce } from '../hooks/useDebounce';
import { projectApi } from '../services/projectApi';
import { generateSlug } from '../utils/slugify';
import type { CreateDocumentDto } from '../types/document';
import type { Project } from '../types/project';
import './ProjectDetail.css';

type TabType = 'documents' | 'graph';

const ALLOWED_EXTENSIONS = ['.md', '.markdown'];

const ProjectDetail: React.FC = () => {
  const { slug } = useParams<{ slug: string }>();
  const navigate = useNavigate();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const { projects, isLoading: loadingProjects, createProject, isCreating: isCreatingSubproject } = useProjects();
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

  // Export state
  const [isExporting, setIsExporting] = useState(false);

  // Find the project by slug
  const project = projects.find((p) => p.slug === slug);

  // Breadcrumb ancestor chain (root first), built by walking parentSlug (WIKI4AI-31).
  const ancestors = useMemo<Project[]>(() => {
    if (!project?.parentSlug) return [];
    const bySlug = new Map(projects.map((p) => [p.slug, p]));
    const chain: Project[] = [];
    let cursor = bySlug.get(project.parentSlug);
    // Cap the walk defensively (max hierarchy depth is 5).
    while (cursor && chain.length < 10) {
      chain.unshift(cursor);
      cursor = cursor.parentSlug ? bySlug.get(cursor.parentSlug) : undefined;
    }
    return chain;
  }, [projects, project]);

  // Direct subprojects of the current project (WIKI4AI-31).
  const subprojects = useMemo(
    () => projects.filter((p) => p.parentSlug === slug),
    [projects, slug],
  );

  // Subproject creation form state
  const [showSubprojectForm, setShowSubprojectForm] = useState(false);
  const [newSubprojectName, setNewSubprojectName] = useState('');
  const [subprojectError, setSubprojectError] = useState<string | null>(null);

  const handleCreateSubproject = async () => {
    if (!newSubprojectName.trim() || !project) return;
    setSubprojectError(null);
    try {
      await createProject({ name: newSubprojectName.trim(), parentId: project.id });
      setNewSubprojectName('');
      setShowSubprojectForm(false);
    } catch (err) {
      setSubprojectError(err instanceof Error ? err.message : 'Failed to create subproject');
    }
  };

  // Determine which documents to display: search results if searching, otherwise all documents
  const displayDocuments = hasSearched ? searchResults : documents;

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

  // Export handler
  const handleExport = async () => {
    if (!slug || isExporting) return;
    setIsExporting(true);
    try {
      const blob = await projectApi.exportProject(slug);
      // Create a download link and trigger it
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${slug}.zip`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to export project');
    } finally {
      setIsExporting(false);
    }
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

  // Early returns placed after ALL hook calls (useState/useMemo/useCallback above)
  // so the hook order is identical on every render — a cold load (direct URL /
  // hard refresh) previously hit these before the drag-handler useCallbacks and
  // crashed with React error #310 (invalid hook call).
  if (loadingProjects) return <div className="project-detail"><div className="loading-state"><div className="spinner" /><p>Loading...</p></div></div>;
  if (!project) return <div className="project-detail error">Project not found.</div>;

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

      {/* Breadcrumb Navigation — full chain for nested projects (WIKI4AI-31) */}
      <nav className="breadcrumb" aria-label="Breadcrumb" data-testid="project-breadcrumb">
        <Link to="/">Dashboard</Link>
        {ancestors.map((ancestor) => (
          <React.Fragment key={ancestor.id}>
            <span className="separator">&rsaquo;</span>
            <Link to={`/projects/${ancestor.slug}`} data-testid={`breadcrumb-link-${ancestor.slug}`}>
              {ancestor.name}
            </Link>
          </React.Fragment>
        ))}
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
          <button
            onClick={handleExport}
            className="btn-secondary btn-export"
            disabled={isExporting}
            data-testid="export-button"
            title="Export project as ZIP"
          >
            {isExporting ? '⏳ Exporting...' : '📦 Export'}
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

      {/* Subprojects (WIKI4AI-31) */}
      <section className="subprojects-section" data-testid="subprojects-section">
        <div className="subprojects-header">
          <h2>Podprojekty {subprojects.length > 0 && <span className="subprojects-count">({subprojects.length})</span>}</h2>
          <button
            onClick={() => setShowSubprojectForm((v) => !v)}
            className="btn-secondary btn-new-subproject"
            data-testid="create-subproject-button"
          >
            {showSubprojectForm ? '✕ Zrušiť' : '+ Nový subprojekt'}
          </button>
        </div>

        {showSubprojectForm && (
          <form
            onSubmit={(e) => {
              e.preventDefault();
              handleCreateSubproject();
            }}
            className="create-form subproject-create-form"
            data-testid="subproject-create-form"
          >
            <input
              type="text"
              placeholder="Názov subprojektu"
              value={newSubprojectName}
              onChange={(e) => setNewSubprojectName(e.target.value)}
              required
              autoFocus
              data-testid="subproject-name-input"
            />
            {subprojectError && <p className="error" data-testid="subproject-error">{subprojectError}</p>}
            <div className="form-actions">
              <button type="submit" className="btn-primary" disabled={isCreatingSubproject || !newSubprojectName.trim()}>
                {isCreatingSubproject ? 'Vytváram...' : 'Vytvoriť subprojekt'}
              </button>
            </div>
          </form>
        )}

        {subprojects.length > 0 && (
          <ul className="subproject-list">
            {subprojects.map((sp) => (
              <li key={sp.id} className="subproject-entry" data-testid={`subproject-entry-${sp.slug}`}>
                <Link to={`/projects/${sp.slug}`} className="subproject-link">
                  ↳ {sp.name}
                </Link>
                <span className="badge">{sp.documentCount} docs</span>
              </li>
            ))}
          </ul>
        )}
      </section>

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
        <div key={`form-${Date.now()}`} className="tab-content">
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
        </div>
      )}

      {/* Documents List */}
      {activeTab === 'documents' && (
        <div key={`docs-${Date.now()}`} className="tab-content">
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
                {displayDocuments.map((doc) => {
                  const slug = doc.slug || generateSlug(doc.title);
                  return (
                    <li key={doc.id} className="document-item">
                      <div className="doc-info" onClick={() => handleViewDocument(slug)}>
                        <span className="doc-title">{doc.title}</span>
                        <span className="doc-slug">@{slug}</span>
                      </div>
                      <div className="doc-meta">
                        <span className="doc-date">{formatDate(doc.updatedAt)}</span>
                        <button
                          onClick={(e) => { e.stopPropagation(); handleDeleteDocument(slug); }}
                          className="btn-delete"
                          disabled={isDeleting}
                          title="Vymazať dokument"
                        >
                          &times;
                        </button>
                      </div>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        </div>
      )}

      {/* Graph Tab Content */}
      {activeTab === 'graph' && (
        <div key={`graph-${Date.now()}`} className="tab-content">
          <Link to={`/projects/${slug}/graph`} className="graph-redirect">
            <div className="graph-preview">
              <h2>Document Graph</h2>
              <p>View the interactive graph visualization of document connections.</p>
              <span className="btn-primary">Open Graph View &rarr;</span>
            </div>
          </Link>
        </div>
      )}
    </div>
  );
};

export default ProjectDetail;
