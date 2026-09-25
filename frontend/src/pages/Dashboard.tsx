/**
 * Dashboard page component.
 * Displays a list of all projects in cards with the option to create new ones.
 * Uses React Query (TanStack Query) for data fetching and cache management.
 */

import React, { useState, useCallback, useMemo, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useProjects } from '../hooks/useProjects';
import { useDebounce } from '../hooks/useDebounce';
import type { Project, ProjectDTO } from '../types/project';
import { ToastContainer, type ToastItem } from '../components/Toast';
// WIKI4AI-87: SVG icons (no emoji — the container has no emoji font)
import { EditIcon, TrashIcon } from '../components/icons';
import './Dashboard.css';

/**
 * Recursive subproject row rendered inside a parent project card (WIKI4AI-31).
 * Subprojects get a distinct ↳ marker and indentation per depth level;
 * branches with their own children can be collapsed/expanded.
 */
interface SubprojectRowProps {
  project: Project;
  depth: number;
  childrenByParent: Map<string, Project[]>;
  onNavigate: (slug: string) => void;
}

const SubprojectRow: React.FC<SubprojectRowProps> = ({ project, depth, childrenByParent, onNavigate }) => {
  const { t } = useTranslation();
  const [collapsed, setCollapsed] = useState(false);
  const children = childrenByParent.get(project.slug) ?? [];
  const hasChildren = children.length > 0;

  return (
    <div className="subproject-branch">
      <div
        className="subproject-row"
        style={{ marginLeft: `${(depth - 1) * 18}px` }}
        role="link"
        tabIndex={0}
        onClick={(e) => {
          e.stopPropagation(); // don't trigger the parent card navigation
          onNavigate(project.slug);
        }}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            e.stopPropagation();
            onNavigate(project.slug);
          }
        }}
        data-testid={`subproject-item-${project.slug}`}
      >
        <span className="subproject-icon" aria-hidden="true">↳</span>
        <span className="subproject-name">{project.name}</span>
        <span className="badge subproject-badge">{t('dashboard.docsCount', { count: project.documentCount })}</span>
        {hasChildren && (
          <button
            type="button"
            className="subproject-toggle"
            onClick={(e) => {
              e.stopPropagation();
              setCollapsed((c) => !c);
            }}
            aria-label={collapsed ? t('dashboard.expandProject', { name: project.name }) : t('dashboard.collapseProject', { name: project.name })}
            data-testid={`subproject-toggle-${project.slug}`}
          >
            {collapsed ? '▸' : '▾'}
          </button>
        )}
      </div>
      {!collapsed &&
        children.map((child) => (
          <SubprojectRow
            key={child.id}
            project={child}
            depth={depth + 1}
            childrenByParent={childrenByParent}
            onNavigate={onNavigate}
          />
        ))}
    </div>
  );
};

/** Delete Confirmation Dialog component */
interface DeleteConfirmationDialogProps {
  project: { id: number; name: string };
  onConfirm: (id: number) => Promise<void>;
  onCancel: () => void;
  isDeleting: boolean;
}

const DeleteConfirmationDialog: React.FC<DeleteConfirmationDialogProps> = ({ project, onConfirm, onCancel, isDeleting }) => {
  const { t } = useTranslation();
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const handleConfirm = async () => {
    setDeleteError(null);
    try {
      await onConfirm(project.id);
    } catch (err) {
      setDeleteError(err instanceof Error ? err.message : t('dashboard.deleteFailed'));
    }
  };

  // Handle backdrop click to close
  const handleBackdropClick = (e: React.MouseEvent<HTMLDivElement>) => {
    if (e.target === e.currentTarget) {
      onCancel();
    }
  };

  return (
    <div className="modal-backdrop" onClick={handleBackdropClick} data-testid="delete-modal">
      <div className="modal-content modal-delete" role="dialog" aria-labelledby="delete-modal-title">
        <button type="button" className="modal-close" onClick={onCancel} aria-label={t('common.close')}>×</button>
        <h3 id="delete-modal-title" data-testid="delete-modal-title">{t('dashboard.deleteProjectTitle')}</h3>
        <p className="delete-warning" data-testid="delete-warning-text">
          {t('dashboard.deleteWarningStart')} &ldquo;<strong>{project.name}</strong>&rdquo;?{' '}
          {t('dashboard.deleteWarningEnd')}
        </p>
        {deleteError && <p className="error">{deleteError}</p>}
        <div className="form-actions form-actions-delete">
          <button
            type="button"
            onClick={handleConfirm}
            className="btn-danger"
            disabled={isDeleting}
            data-testid="delete-confirm-button"
          >
            {isDeleting ? t('dashboard.deleting') : t('common.delete')}
          </button>
          <button type="button" onClick={onCancel} className="btn-secondary" data-testid="delete-cancel-button">
            {t('common.cancel')}
          </button>
        </div>
      </div>
    </div>
  );
};

/** Edit Project Modal component */
interface EditProjectModalProps {
  project: { id: number; name: string; description: string | null };
  onSave: (id: number, dto: ProjectDTO) => Promise<void>;
  onCancel: () => void;
  isSaving: boolean;
}

const EditProjectModal: React.FC<EditProjectModalProps> = ({ project, onSave, onCancel, isSaving }) => {
  const { t } = useTranslation();
  const [editName, setEditName] = useState(project.name);
  const [editDescription, setEditDescription] = useState(project.description || '');
  const [saveError, setSaveError] = useState<string | null>(null);

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    // WIKI4AI-88: JS validation + themed error (no native `required` tooltip)
    if (!editName.trim()) {
      setSaveError(t('dashboard.nameRequired'));
      return;
    }

    setSaveError(null);
    try {
      const dto: ProjectDTO = {
        name: editName.trim(),
        description: editDescription.trim() || undefined,
      };
      await onSave(project.id, dto);
    } catch (err) {
      setSaveError(err instanceof Error ? err.message : t('dashboard.updateFailed'));
    }
  };

  // Handle backdrop click to close
  const handleBackdropClick = (e: React.MouseEvent<HTMLDivElement>) => {
    if (e.target === e.currentTarget) {
      onCancel();
    }
  };

  return (
    <div className="modal-backdrop" onClick={handleBackdropClick} data-testid="edit-modal">
      <div className="modal-content" role="dialog" aria-labelledby="edit-modal-title">
        <button type="button" className="modal-close" onClick={onCancel} aria-label={t('common.close')}>×</button>
        <h3 id="edit-modal-title">{t('dashboard.editProjectTitle')}</h3>
        <form onSubmit={handleSave}>
          <label htmlFor="edit-name">{t('dashboard.projectName')}</label>
          <input
            id="edit-name"
            type="text"
            value={editName}
            onChange={(e) => {
              setEditName(e.target.value);
              if (saveError) setSaveError(null);
            }}
            autoFocus
            data-testid="edit-name-input"
          />

          <label htmlFor="edit-description">{t('dashboard.description')}</label>
          <textarea
            id="edit-description"
            value={editDescription}
            onChange={(e) => setEditDescription(e.target.value)}
            rows={3}
            data-testid="edit-description-input"
          />

          {saveError && <p className="error">{saveError}</p>}

          <div className="form-actions">
            <button type="submit" className="btn-primary" disabled={isSaving} data-testid="edit-save-button">
              {isSaving ? t('dashboard.saving') : t('common.save')}
            </button>
            <button type="button" onClick={onCancel} className="btn-secondary" data-testid="edit-cancel-button">
              {t('common.cancel')}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

/** Create Project Modal — WIKI4AI-88: proper modal via .modal-backdrop (the
 *  create form used to be an inline panel with no backdrop/X/Esc). */
interface CreateProjectModalProps {
  onCreate: (dto: ProjectDTO) => Promise<void>;
  onCancel: () => void;
  isCreating: boolean;
}

const CreateProjectModal: React.FC<CreateProjectModalProps> = ({ onCreate, onCancel, isCreating }) => {
  const { t } = useTranslation();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [error, setError] = useState<string | null>(null);
  const nameInputRef = useRef<HTMLInputElement>(null);

  // WIKI4AI-88: Escape closes the modal (parity with edit/delete modals)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCancel();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onCancel]);

  // WIKI4AI-88: focus the name input when the modal opens
  useEffect(() => {
    nameInputRef.current?.focus();
  }, []);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    // WIKI4AI-88: JS validation + themed error (no native `required` tooltip)
    if (!name.trim()) {
      setError(t('dashboard.nameRequired'));
      nameInputRef.current?.focus();
      return;
    }
    setError(null);
    try {
      await onCreate({ name: name.trim(), description: description.trim() || undefined });
    } catch (err) {
      setError(err instanceof Error ? err.message : t('dashboard.createFailed'));
    }
  };

  // Handle backdrop click to close
  const handleBackdropClick = (e: React.MouseEvent<HTMLDivElement>) => {
    if (e.target === e.currentTarget) {
      onCancel();
    }
  };

  return (
    <div className="modal-backdrop" onClick={handleBackdropClick} data-testid="create-modal">
      <div className="modal-content" role="dialog" aria-labelledby="create-modal-title">
        <button type="button" className="modal-close" onClick={onCancel} aria-label={t('common.close')} data-testid="create-modal-close">×</button>
        <h3 id="create-modal-title">{t('dashboard.createNewProject')}</h3>
        {/* WIKI4AI-88: visible labels (login pattern) instead of placeholder-only */}
        <form onSubmit={handleSubmit} noValidate>
          <label htmlFor="create-name">{t('dashboard.projectName')}</label>
          <input
            id="create-name"
            ref={nameInputRef}
            type="text"
            value={name}
            onChange={(e) => {
              setName(e.target.value);
              if (error) setError(null);
            }}
            placeholder={t('dashboard.projectNamePlaceholder')}
            data-testid="create-name-input"
          />

          <label htmlFor="create-description">{t('dashboard.description')}</label>
          <textarea
            id="create-description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            rows={3}
            placeholder={t('dashboard.descriptionPlaceholder')}
            data-testid="create-description-input"
          />

          {error && <p className="error" data-testid="create-error">{error}</p>}

          <div className="form-actions">
            <button type="submit" className="btn-primary" disabled={isCreating} data-testid="create-submit-button">
              {isCreating ? t('dashboard.creating') : t('common.create')}
            </button>
            <button type="button" onClick={onCancel} className="btn-secondary" data-testid="create-cancel-button">
              {t('common.cancel')}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

const Dashboard: React.FC = () => {
  const navigate = useNavigate();
  const { t, i18n } = useTranslation();
  const {
    projects,
    isLoading,
    error,
    createProject,
    updateProject,
    deleteProject: deleteProjectMutation,
    isCreating,
    isUpdating,
    isDeleting,
  } = useProjects();

  // WIKI4AI-88: the create form is now a modal (CreateProjectModal) — its
  // field state lives inside the component; only the open flag stays here.
  const [showCreateForm, setShowCreateForm] = useState(false);

  // Edit state
  const [editingProject, setEditingProject] = useState<{ id: number; name: string; description: string | null } | null>(null);

  // Delete state
  const [deletingProject, setDeletingProject] = useState<{ id: number; name: string } | null>(null);

  // Search state
  const [searchQuery, setSearchQuery] = useState('');
  const debouncedSearchQuery = useDebounce(searchQuery, 300);

  // Toast state
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  let toastIdCounter = 0;

  const addToast = useCallback((message: string, type: 'success' | 'error') => {
    const id = ++toastIdCounter;
    setToasts((prev) => [...prev, { id, message, type }]);
    // Auto-dismiss after 5 seconds (handled by Toast component internally)
  }, []);

  const dismissToast = useCallback((id: number) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const handleCardClick = (slug: string) => {
    navigate(`/projects/${slug}`);
  };

  const handleEditClick = (e: React.MouseEvent, project: { id: number; name: string; description: string | null }) => {
    e.stopPropagation(); // Prevent card navigation
    setEditingProject(project);
  };

  const handleSaveEdit = async (id: number, dto: ProjectDTO) => {
    await updateProject({ id, dto });
    setEditingProject(null);
    addToast(t('dashboard.toastUpdated'), 'success');
  };

  const handleCancelEdit = () => {
    setEditingProject(null);
  };

  // Delete handlers
  const handleDeleteClick = (e: React.MouseEvent, project: { id: number; name: string }) => {
    e.stopPropagation(); // Prevent card navigation
    setDeletingProject(project);
  };

  const handleConfirmDelete = async (id: number) => {
    await deleteProjectMutation(id);
    setDeletingProject(null);
    addToast(t('dashboard.toastDeleted'), 'success');
  };

  const handleCancelDelete = () => {
    setDeletingProject(null);
  };

  // WIKI4AI-88: create via modal — the modal owns validation + error display;
  // this handler closes the modal and toasts on success, rethrows on failure.
  const handleCreateSubmit = async (dto: ProjectDTO) => {
    await createProject(dto);
    setShowCreateForm(false);
    addToast(t('dashboard.toastCreated'), 'success');
  };

  const handleCancelCreate = () => {
    setShowCreateForm(false);
  };

  // WIKI4AI-73: dates follow the active UI language.
  const formatDate = (dateString: string): string => {
    try {
      const date = new Date(dateString);
      return date.toLocaleDateString(i18n.language === 'sk' ? 'sk-SK' : 'en-GB', {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
      });
    } catch {
      return dateString;
    }
  };

  // Stats calculation
  const totalDocs = projects.reduce((sum, p) => sum + (p.documentCount || 0), 0);

  // Filtered projects based on search query
  const filteredProjects = useMemo(() => {
    if (!debouncedSearchQuery.trim()) {
      return projects;
    }
    const query = debouncedSearchQuery.toLowerCase().trim();
    return projects.filter((p) => {
      const nameMatch = p.name.toLowerCase().includes(query);
      const descMatch = (p.description || '').toLowerCase().includes(query);
      return nameMatch || descMatch;
    });
  }, [projects, debouncedSearchQuery]);

  // Hierarchy grouping for the tree view (WIKI4AI-31): parentSlug -> children.
  const childrenByParent = useMemo(() => {
    const map = new Map<string, Project[]>();
    for (const p of projects) {
      if (p.parentSlug) {
        const list = map.get(p.parentSlug) ?? [];
        list.push(p);
        map.set(p.parentSlug, list);
      }
    }
    return map;
  }, [projects]);

  // Root projects for the tree view. A subproject whose parent is missing
  // (defensive edge case) is treated as a root so it never disappears from the UI.
  const rootProjects = useMemo(() => {
    const slugs = new Set(projects.map((p) => p.slug));
    return projects.filter((p) => !p.parentSlug || !slugs.has(p.parentSlug));
  }, [projects]);

  const isSearching = debouncedSearchQuery.trim().length > 0;

  return (
    <div className="dashboard">
      {/* Toast Notifications */}
      <ToastContainer toasts={toasts} onDismiss={dismissToast} />

      {/* Hero Section */}
      <section className="hero-section">
        <h1>{t('dashboard.title')}</h1>
        <p className="hero-subtitle">{t('dashboard.subtitle')}</p>
        {projects.length > 0 && (
          <div className="stats-row">
            <div className="stat-item">
              <span className="stat-number">{projects.length}</span>
              <span className="stat-label">{t('dashboard.statsProjects')}</span>
            </div>
            <div className="stat-divider" />
            <div className="stat-item">
              <span className="stat-number">{totalDocs}</span>
              <span className="stat-label">{t('dashboard.statsDocuments')}</span>
            </div>
          </div>
        )}
      </section>

      {/* Action Button */}
      <button onClick={() => setShowCreateForm(true)} className="btn-create-new">
        + {t('dashboard.newProject')}
      </button>

      {/* Search Bar */}
      {projects.length > 0 && (
        <div className="dashboard-search-bar">
          <svg className="dashboard-search-icon" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
          </svg>
          <input
            type="text"
            className="dashboard-search-input"
            placeholder={t('dashboard.searchProjects')}
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            data-testid="search-input"
          />
          {searchQuery && (
            <button
              type="button"
              className="dashboard-search-clear"
              onClick={() => setSearchQuery('')}
              aria-label={t('dashboard.clearSearch')}
              data-testid="search-clear-button"
            >
              ×
            </button>
          )}
        </div>
      )}

      {/* Results Count */}
      {projects.length > 0 && debouncedSearchQuery.trim() && (
        <p className="results-count" data-testid="results-count">
          {t('dashboard.showingResults', { shown: filteredProjects.length, total: projects.length })}
        </p>
      )}

      {/* Loading State */}
      {isLoading && (
        <div className="loading-state">
          <div className="spinner" />
          <p>{t('dashboard.loadingProjects')}</p>
        </div>
      )}

      {/* Error State */}
      {error && !isLoading && (
        <div className="error-state">
          <p className="error">{error.message}</p>
          <button onClick={() => window.location.reload()} className="btn-secondary">
            {t('dashboard.retry')}
          </button>
        </div>
      )}

      {/* Projects Grid */}
      {!isLoading && (
        <div className="projects-grid">
          {projects.length === 0 ? (
            <div className="empty-state">
              <svg style={{ width: 64, height: 64 }} fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z" />
              </svg>
              <p className="empty-text">{t('dashboard.emptyState')}</p>
              {!showCreateForm && (
                <button onClick={() => setShowCreateForm(true)} className="btn-primary">
                  {t('dashboard.createFirstProject')}
                </button>
              )}
            </div>
          ) : filteredProjects.length === 0 ? (
            <div className="empty-state">
              <svg style={{ width: 64, height: 64 }} fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M9.172 16.172a4 4 0 015.656 0M9 10h.01M15 10h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
              <p className="empty-text">{t('dashboard.noMatches')}</p>
            </div>
          ) : (
            (isSearching ? filteredProjects : rootProjects).map((project) => (
              <div
                key={project.id}
                className="project-card"
                onClick={() => handleCardClick(project.slug)}
                role="link"
                tabIndex={0}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    handleCardClick(project.slug);
                  }
                }}
              >
                <div className="card-header">
                  <h3>{project.name}</h3>
                  {/* WIKI4AI-88: in-flow actions cluster — reserved space between
                      title and badge, so hover actions never overlap the badge */}
                  <div className="card-actions">
                    <button
                      type="button"
                      className="edit-button"
                      onClick={(e) => handleEditClick(e, project)}
                      aria-label={t('dashboard.ariaEdit', { name: project.name })}
                      data-testid={`edit-button-${project.id}`}
                    >
                      <EditIcon size={15} />
                    </button>
                    <button
                      type="button"
                      className="delete-button"
                      onClick={(e) => handleDeleteClick(e, project)}
                      aria-label={t('dashboard.ariaDelete', { name: project.name })}
                      data-testid={`delete-button-${project.id}`}
                    >
                      <TrashIcon size={15} />
                    </button>
                  </div>
                  <span className="badge">{t('dashboard.docsCount', { count: project.documentCount })}</span>
                </div>
                {project.description && (
                  <p className="description" title={project.description}>
                    {project.description.length > 120
                      ? `${project.description.substring(0, 120)}...`
                      : project.description}
                  </p>
                )}
                <div className="card-footer">
                  <span className="slug">@{project.slug}</span>
                  <span className="date">{formatDate(project.createdAt)}</span>
                </div>
                {/* Nested subprojects — tree view (WIKI4AI-31) */}
                {!isSearching && (childrenByParent.get(project.slug) ?? []).length > 0 && (
                  <div className="subprojects-tree" data-testid={`subprojects-tree-${project.slug}`}>
                    {(childrenByParent.get(project.slug) ?? []).map((child) => (
                      <SubprojectRow
                        key={child.id}
                        project={child}
                        depth={1}
                        childrenByParent={childrenByParent}
                        onNavigate={handleCardClick}
                      />
                    ))}
                  </div>
                )}
              </div>
            ))
          )}
        </div>
      )}

      {/* Create Project Modal — WIKI4AI-88 */}
      {showCreateForm && (
        <CreateProjectModal
          onCreate={handleCreateSubmit}
          onCancel={handleCancelCreate}
          isCreating={isCreating}
        />
      )}

      {/* Edit Project Modal */}
      {editingProject && (
        <EditProjectModal
          project={editingProject}
          onSave={handleSaveEdit}
          onCancel={handleCancelEdit}
          isSaving={isUpdating}
        />
      )}

      {/* Delete Confirmation Dialog */}
      {deletingProject && (
        <DeleteConfirmationDialog
          project={deletingProject}
          onConfirm={handleConfirmDelete}
          onCancel={handleCancelDelete}
          isDeleting={isDeleting}
        />
      )}
    </div>
  );
};

export default Dashboard;
