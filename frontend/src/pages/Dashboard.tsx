/**
 * Dashboard page component.
 * Displays a list of all projects in cards with the option to create new ones.
 * Uses React Query (TanStack Query) for data fetching and cache management.
 */

import React, { useState, useCallback, useMemo, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useQueryClient } from '@tanstack/react-query';
import { useProjects } from '../hooks/useProjects';
import { useDebounce } from '../hooks/useDebounce';
import type { Project, ProjectDTO, Visibility } from '../types/project';
import { ToastContainer, type ToastItem } from '../components/Toast';
// WIKI4AI-87: SVG icons (no emoji — the container has no emoji font)
import { EditIcon, TrashIcon, LockIcon } from '../components/icons';
// WIKI4AI-100: shared Public/Private visibility toggle
import VisibilityToggle from '../components/VisibilityToggle';
// WIKI4AI-186: pin a project to the top of the list
import PinButton from '../components/PinButton';
import { usePinToggle, isForbiddenError } from '../hooks/usePinToggle';
import { comparePinned } from '../utils/pinSort';
// WIKI4AI-187: sort menu + server-side filters + drag&drop ordering
import SortSelect from '../components/SortSelect';
import FilterChips, { type ActiveFilterChip } from '../components/FilterChips';
import DragHandle from '../components/DragHandle';
import { useSortPreference } from '../hooks/useSortPreference';
import { useDragReorder } from '../hooks/useDragReorder';
import {
  DEFAULT_PROJECT_SORT,
  PROJECT_SORT_STORAGE_KEY,
  hasActiveFilter,
  type VisibilityFilter,
} from '../utils/listSort';
import type { ReorderBody } from '../utils/dragOrder';
import { projectApi } from '../services/projectApi';
import './Dashboard.css';
import { useDialog } from '../hooks/useDialog';

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
        <span className="subproject-name">
          {project.name}
          {/* WIKI4AI-100: lock badge on private subprojects */}
          {project.visibility === 'private' && (
            <span className="lock-badge" title={t('dashboard.privateBadge')} data-testid={`subproject-lock-${project.id}`}>
              <LockIcon size={12} />
            </span>
          )}
        </span>
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
  const handleBackdropClick = (e: React.MouseEvent<HTMLDialogElement>) => {
    if (e.target === e.currentTarget) {
      onCancel();
    }
  };

  /* Modal je mounted iba kym je otvoreny, preto `open === true`.
   * useDialog zaruva showModal() (top layer, focus trap, ::backdrop)
   * a pri unmounte close() — to vrati focus prvku pred modalom. */
  const dialogRef = useDialog<HTMLDialogElement>(true);

  return (
    <dialog
      ref={dialogRef}
      className="modal-backdrop"
      onClick={handleBackdropClick}
      onCancel={onCancel}
      aria-labelledby="delete-modal-title"
      data-testid="delete-modal"
    >
      <div className="modal-content modal-delete">
        <button type="button" className="modal-close" onClick={onCancel} aria-label={t('common.close')}>×</button>
        <h3 id="delete-modal-title" data-testid="delete-modal-title">{t('dashboard.deleteProjectTitle')}</h3>
        <p className="delete-warning" data-testid="delete-warning-text">
          {t('dashboard.deleteWarningStart')} &ldquo;<strong>{project.name}</strong>&rdquo;?{' '}
          {t('dashboard.deleteWarningEnd')}
        </p>
        {deleteError && <p className="state-error">{deleteError}</p>}
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
    </dialog>
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
  const handleBackdropClick = (e: React.MouseEvent<HTMLDialogElement>) => {
    if (e.target === e.currentTarget) {
      onCancel();
    }
  };

  /* Modal je mounted iba kym je otvoreny, preto `open === true`.
   * useDialog zaruva showModal() (top layer, focus trap, ::backdrop)
   * a pri unmounte close() — to vrati focus prvku pred modalom. */
  const dialogRef = useDialog<HTMLDialogElement>(true);

  return (
    <dialog
      ref={dialogRef}
      className="modal-backdrop"
      onClick={handleBackdropClick}
      onCancel={onCancel}
      aria-labelledby="edit-modal-title"
      data-testid="edit-modal"
    >
      <div className="modal-content">
        <button type="button" className="modal-close" onClick={onCancel} aria-label={t('common.close')}>×</button>
        <h3 id="edit-modal-title">{t('dashboard.editProjectTitle')}</h3>
        <form onSubmit={handleSave}>
          <label htmlFor="edit-name">{t('dashboard.projectName')}</label>
          <input
            id="edit-name"
            className="form-input"
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
            className="form-textarea"
            value={editDescription}
            onChange={(e) => setEditDescription(e.target.value)}
            rows={3}
            data-testid="edit-description-input"
          />

          {saveError && <p className="state-error">{saveError}</p>}

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
    </dialog>
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
  // WIKI4AI-100: default visibility for new projects is public (backend parity)
  const [visibility, setVisibility] = useState<Visibility>('public');
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
      // WIKI4AI-100: include the chosen visibility in the create payload
      await onCreate({ name: name.trim(), description: description.trim() || undefined, visibility });
    } catch (err) {
      setError(err instanceof Error ? err.message : t('dashboard.createFailed'));
    }
  };

  // Handle backdrop click to close
  const handleBackdropClick = (e: React.MouseEvent<HTMLDialogElement>) => {
    if (e.target === e.currentTarget) {
      onCancel();
    }
  };

  /* Modal je mounted iba kym je otvoreny, preto `open === true`.
   * useDialog zaruva showModal() (top layer, focus trap, ::backdrop)
   * a pri unmounte close() — to vrati focus prvku pred modalom. */
  const dialogRef = useDialog<HTMLDialogElement>(true);

  return (
    <dialog
      ref={dialogRef}
      className="modal-backdrop"
      onClick={handleBackdropClick}
      onCancel={onCancel}
      aria-labelledby="create-modal-title"
      data-testid="create-modal"
    >
      <div className="modal-content">
        <button type="button" className="modal-close" onClick={onCancel} aria-label={t('common.close')} data-testid="create-modal-close">×</button>
        <h3 id="create-modal-title">{t('dashboard.createNewProject')}</h3>
        {/* WIKI4AI-88: visible labels (login pattern) instead of placeholder-only */}
        <form onSubmit={handleSubmit} noValidate>
          <label htmlFor="create-name">{t('dashboard.projectName')}</label>
          <input
            id="create-name"
            className="form-input"
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
            className="form-textarea"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            rows={3}
            placeholder={t('dashboard.descriptionPlaceholder')}
            data-testid="create-description-input"
          />

          {/* WIKI4AI-100: visibility toggle (default Public) with a short hint */}
          <div className="visibility-field">
            <span className="visibility-label">{t('dashboard.visibilityLabel')}</span>
            <VisibilityToggle
              value={visibility}
              onChange={setVisibility}
              testIdPrefix="create-project"
            />
            <p className="form-hint visibility-hint" data-testid="create-visibility-hint">
              {t('dashboard.visibilityHint')}
            </p>
          </div>

          {error && <p className="state-error" data-testid="create-error">{error}</p>}

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
    </dialog>
  );
};

const Dashboard: React.FC = () => {
  const navigate = useNavigate();
  const { t, i18n } = useTranslation();

  // WIKI4AI-187: sort and filters are resolved by the BACKEND. The three values
  // below are query parameters, not list mutations: changing one re-fetches
  // `GET /projects?sort=…&q=…&visibility=…`. The sort choice is a user
  // preference and survives a reload (localStorage); the filters do not — a
  // stale hidden filter is the classic way to "lose" a project from the grid.
  const { sort, setSort } = useSortPreference(PROJECT_SORT_STORAGE_KEY, DEFAULT_PROJECT_SORT);
  const [visibility, setVisibility] = useState<VisibilityFilter>('');
  const [searchQuery, setSearchQuery] = useState('');
  const debouncedSearchQuery = useDebounce(searchQuery, 300);
  const trimmedQuery = debouncedSearchQuery.trim();

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
  } = useProjects({ sort, q: trimmedQuery, visibility });

  // WIKI4AI-186: pinning a project to the top. The list is re-sorted locally
  // the moment the click happens (optimistic) and the pin response replaces
  // the guessed state with the server's `pinned`/`sortOrder`; on failure —
  // typically 403 when the user lacks MANAGE — the card rolls back and a Toast
  // explains it. The backend already returns pinned projects first, so a later
  // refetch agrees with what the UI shows.
  const queryClient = useQueryClient();
  const projectPinApi = useMemo(
    () => ({ pin: projectApi.pinProject, unpin: projectApi.unpinProject }),
    [],
  );
  // After a successful pin the list is refetched so the final order is exactly
  // the server's (`pinned DESC` + its own sort key), not our optimistic guess.
  const refreshProjects = useCallback(
    () => queryClient.invalidateQueries({ queryKey: ['projects'] }),
    [queryClient],
  );
  const {
    list: orderedProjects,
    togglePin: toggleProjectPin,
    isPending: isProjectPinPending,
  } = usePinToggle(projects, projectPinApi, { onSettled: refreshProjects });

  // WIKI4AI-88: the create form is now a modal (CreateProjectModal) — its
  // field state lives inside the component; only the open flag stays here.
  const [showCreateForm, setShowCreateForm] = useState(false);

  // Edit state
  const [editingProject, setEditingProject] = useState<{ id: number; name: string; description: string | null } | null>(null);

  // Delete state
  const [deletingProject, setDeletingProject] = useState<{ id: number; name: string } | null>(null);

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

  // WIKI4AI-186: pin/unpin a project card. The reordering already happened
  // optimistically inside `usePinToggle`; here we only report the outcome, and
  // a rejection (403 = missing MANAGE) has already been rolled back there.
  const handleToggleProjectPin = useCallback(
    async (project: Project) => {
      const wasPinned = !!project.pinned;
      try {
        await toggleProjectPin(project);
        addToast(t(wasPinned ? 'pin.unpinnedProject' : 'pin.pinnedProject', { name: project.name }), 'success');
      } catch (err) {
        addToast(
          isForbiddenError(err)
            ? t('pin.forbiddenProject')
            : t('pin.failed', { message: err instanceof Error ? err.message : String(err) }),
          'error',
        );
      }
    },
    [addToast, t, toggleProjectPin],
  );

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

  // ── WIKI4AI-187: manual order (drag & drop) ───────────────────────────────
  //
  // `comparePinned` is the manual comparator (pinned first, then the opaque
  // `sortOrder`). For `sort=manual` the backend already returns exactly that
  // order, so applying it here is an idempotent confirmation of it — and it is
  // what keeps the pinned and unpinned blocks apart for the drop logic (see
  // `utils/dragOrder`: a pinned card may only move inside the pinned block).
  const manualOrdered = useMemo(
    () => (sort === 'manual' ? [...orderedProjects].sort(comparePinned) : orderedProjects),
    [orderedProjects, sort],
  );

  // Hierarchy grouping for the tree view (WIKI4AI-31): parentSlug -> children.
  const childrenByParent = useMemo(() => {
    const map = new Map<string, Project[]>();
    for (const p of manualOrdered) {
      if (p.parentSlug) {
        const list = map.get(p.parentSlug) ?? [];
        list.push(p);
        map.set(p.parentSlug, list);
      }
    }
    return map;
  }, [manualOrdered]);

  // Root projects for the tree view. A subproject whose parent is missing
  // (defensive edge case) is treated as a root so it never disappears from the UI.
  const rootProjects = useMemo(() => {
    const slugs = new Set(manualOrdered.map((p) => p.slug));
    return manualOrdered.filter((p) => !p.parentSlug || !slugs.has(p.parentSlug));
  }, [manualOrdered]);

  const isSearching = trimmedQuery.length > 0;
  // A filter changes what the list means, and a drop writes the manual order of
  // the WHOLE list — so while any filter is active DnD is off and the page says
  // why, instead of silently reordering rows the user is not looking at.
  const filterActive = hasActiveFilter(trimmedQuery, visibility);
  const dndEnabled = sort === 'manual' && !filterActive;

  /** Send one drop to the reorder endpoint; a success gets a quiet Toast. */
  const sendProjectReorder = useCallback(
    async (project: Project, body: ReorderBody) => {
      await projectApi.reorderProject(project.slug, body);
      addToast(t('listSort.toastReordered'), 'success');
    },
    [addToast, t],
  );

  /** 403 = missing MANAGE (the endpoint requires it), anything else is raw. */
  const handleReorderError = useCallback(
    (err: unknown) => {
      addToast(
        isForbiddenError(err)
          ? t('listSort.reorderForbiddenProject')
          : t('listSort.toastReorderFailed', {
              message: err instanceof Error ? err.message : String(err),
            }),
        'error',
      );
    },
    [addToast, t],
  );

  // The drag operates on the cards that are actually rendered (roots — children
  // are nested inside their parent card and are not drop targets).
  const dnd = useDragReorder<Project>({
    list: rootProjects,
    enabled: dndEnabled,
    reorder: sendProjectReorder,
    onReordered: refreshProjects,
    onError: handleReorderError,
  });

  // While a search is active children are rendered as their own cards (a parent
  // that does not match must not swallow a child that does).
  const renderedProjects = isSearching ? manualOrdered : dnd.displayList;

  /** Chips describing the active filters, each with its own × (requirement 2). */
  const activeFilters: ActiveFilterChip[] = [];
  if (trimmedQuery) {
    activeFilters.push({
      id: 'q',
      label: t('listSort.filterQuery', { q: trimmedQuery }),
      onClear: () => setSearchQuery(''),
    });
  }
  if (visibility) {
    activeFilters.push({
      id: 'visibility',
      label: t('listSort.filterVisibility', {
        value: t(visibility === 'public' ? 'listSort.visibilityPublic' : 'listSort.visibilityPrivate'),
      }),
      onClear: () => setVisibility(''),
    });
  }
  const clearAllFilters = () => {
    setSearchQuery('');
    setVisibility('');
  };

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

      {/* Search Bar — also kept while a filter returns nothing, otherwise the
          user could not see or edit the filter that emptied the grid. */}
      {(projects.length > 0 || filterActive) && (
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

      {/* WIKI4AI-187: sort menu + visibility filter — both are server
          parameters, changing either re-fetches the list (requirement 1, 2). */}
      {(projects.length > 0 || filterActive) && (
        <div className="list-toolbar" data-testid="project-toolbar">
          <SortSelect value={sort} onChange={setSort} labelKey="listSort.projectsLabel" />
          <label className="list-toolbar-field" data-testid="visibility-filter-label">
            <span className="list-toolbar-label">{t('listSort.visibilityLabel')}</span>
            <select
              className="list-toolbar-select"
              data-testid="visibility-filter"
              value={visibility}
              onChange={(e) => setVisibility(e.target.value as VisibilityFilter)}
            >
              <option value="">{t('listSort.visibilityAll')}</option>
              <option value="public">{t('listSort.visibilityPublic')}</option>
              <option value="private">{t('listSort.visibilityPrivate')}</option>
            </select>
          </label>
        </div>
      )}

      {/* Active filters as removable chips + one "clear all" */}
      <FilterChips filters={activeFilters} onClearAll={clearAllFilters} />

      {/* Why drag & drop is off right now (requirement 3) */}
      {projects.length > 0 && !dndEnabled && (
        <p className="dnd-status" data-testid="dnd-status" role="status">
          {filterActive ? (
            t('listSort.lockedFilter')
          ) : (
            <>
              {t('listSort.lockedSort')}{' '}
              <button type="button" onClick={() => setSort('manual')} data-testid="dnd-switch-manual">
                {t('listSort.switchToManual')}
              </button>
            </>
          )}
        </p>
      )}

      {/* Results Count — the server did the filtering, so the list length IS
          the number of matches (the unfiltered total is not known here). */}
      {projects.length > 0 && filterActive && (
        <p className="results-count" data-testid="results-count">
          {t('listSort.matchCount', { shown: projects.length })}
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
          <p className="state-error">{error.message}</p>
          <button onClick={() => window.location.reload()} className="btn-secondary">
            {t('dashboard.retry')}
          </button>
        </div>
      )}

      {/* Projects Grid */}
      {!isLoading && (
        <div className="projects-grid">
          {/* "No projects yet" only when nothing is filtered — an empty result
              set from a filter is the "no matches" state, not an empty wiki. */}
          {projects.length === 0 && !filterActive ? (
            <div className="state-empty">
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
          ) : renderedProjects.length === 0 ? (
            <div className="state-empty">
              <svg style={{ width: 64, height: 64 }} fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M9.172 16.172a4 4 0 015.656 0M9 10h.01M15 10h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
              <p className="empty-text">{t('dashboard.noMatches')}</p>
            </div>
          ) : (
            renderedProjects.map((project, index) => (
              <div
                key={project.id}
                className={`project-card${project.pinned ? ' project-card-pinned' : ''}${
                  dnd.isDragging(index) ? ' is-dragging' : ''
                }${dnd.isOver(index) ? (dnd.dropEdge === 'above' ? ' drop-above' : ' drop-below') : ''}`}
                data-pinned={project.pinned ? 'true' : undefined}
                /* WIKI4AI-187: the card is the drop target (the handle is the
                   drag source) and the drag image — `[data-dnd-row]`. */
                data-dnd-row
                data-dnd-enabled={dndEnabled ? 'true' : 'false'}
                data-testid={`project-card-${project.slug}`}
                onClick={() => handleCardClick(project.slug)}
                role="link"
                tabIndex={0}
                onDragOver={(e) => dnd.dragOver(index, e)}
                onDrop={(e) => {
                  e.preventDefault(); // without this the browser never fires drop
                  e.stopPropagation();
                  void dnd.drop(index);
                }}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    handleCardClick(project.slug);
                  }
                }}
              >
                <div className="card-header">
                  {/* WIKI4AI-187: drag handle. Locked unless the list is in the
                      manual, unfiltered order — a click on a locked handle
                      switches to it (requirement 3). */}
                  <DragHandle
                    testId={`drag-handle-project-${project.slug}`}
                    enabled={dndEnabled}
                    title={
                      dndEnabled
                        ? t('listSort.dragHint')
                        : filterActive
                          ? t('listSort.lockedFilter')
                          : t('listSort.switchToManual')
                    }
                    ariaLabel={t('listSort.dragAria', { name: project.name })}
                    onDragStart={(e) => dnd.startDrag(index, e)}
                    onDragEnd={dnd.cancelDrag}
                    onActivate={() => setSort('manual')}
                  />
                  <h3>
                    {project.name}
                    {/* WIKI4AI-100: lock badge on own private projects (other
                        users' private projects never reach the UI — backend filter) */}
                    {project.visibility === 'private' && (
                      <span className="lock-badge" title={t('dashboard.privateBadge')} data-testid={`project-lock-${project.id}`}>
                        <LockIcon size={13} />
                      </span>
                    )}
                  </h3>
                  {/* WIKI4AI-186: pin to top. Deliberately outside the
                      hover-only .card-actions cluster — a pinned card must keep
                      its filled pin visible without hovering. */}
                  <PinButton
                    pinned={!!project.pinned}
                    onToggle={() => handleToggleProjectPin(project)}
                    name={project.name}
                    kind="project"
                    className="card-pin"
                    disabled={isProjectPinPending(project.slug)}
                    data-testid={`pin-project-${project.slug}`}
                  />
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
