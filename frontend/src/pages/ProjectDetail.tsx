/**
 * Project Detail page component.
 * Displays a list of documents within a project with the option to create new ones.
 */

import React, { useState, useRef, useCallback, useMemo, useEffect } from 'react';
import { useParams, Link, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useQueryClient } from '@tanstack/react-query';
import { useProjects } from '../hooks/useProjects';
import { useDocuments, useSearchDocuments } from '../hooks/useDocuments';
import { useEmbeddingStatus } from '../hooks/useEmbeddingStatus';
import { useDebounce } from '../hooks/useDebounce';
import { projectApi } from '../services/projectApi';
import { generateSlug } from '../utils/slugify';
import type { CreateDocumentDto } from '../types/document';
import type { Project, Visibility } from '../types/project';
// WIKI4AI-87: SVG icons (no emoji — the container has no emoji font)
import { FolderIcon, DownloadIcon, ClockIcon, SettingsIcon, LockIcon } from '../components/icons';
// WIKI4AI-100: shared Public/Private visibility toggle
import VisibilityToggle from '../components/VisibilityToggle';
// WIKI4AI-186: pin projects/documents to the top + Toast feedback
import PinButton from '../components/PinButton';
import { usePinToggle, isForbiddenError } from '../hooks/usePinToggle';
import { comparePinned } from '../utils/pinSort';
// WIKI4AI-187: sort menu + drag&drop ordering of the document list
import SortSelect from '../components/SortSelect';
import FilterChips, { type ActiveFilterChip } from '../components/FilterChips';
import DragHandle from '../components/DragHandle';
import { useSortPreference } from '../hooks/useSortPreference';
import { useDragReorder } from '../hooks/useDragReorder';
import {
  DEFAULT_DOCUMENT_SORT,
  DOCS_SORT_STORAGE_KEY,
} from '../utils/listSort';
import type { ReorderBody } from '../utils/dragOrder';
import { ToastContainer, type ToastItem } from '../components/Toast';
import { documentApi } from '../services/documentApi';
import type { Document } from '../types/document';
import './ProjectDetail.css';

type TabType = 'documents' | 'graph';

const ALLOWED_EXTENSIONS = ['.md', '.markdown'];

// WIKI4AI-179: simulated upload progress ramp. The cadence and the ramp are
// unchanged from the old `setInterval` version (200 ms per tick, +10 % per
// tick, capped at 90 % until the upload resolves) — only the mechanism is
// different: a chain of one-shot `setTimeout`s instead of an interval.
const PROGRESS_TICK_MS = 200;
const PROGRESS_STEP = 10;
const PROGRESS_CEILING = 90;

const ProjectDetail: React.FC = () => {
  const { t, i18n } = useTranslation();
  const { slug } = useParams<{ slug: string }>();
  const navigate = useNavigate();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const { projects, isLoading: loadingProjects, createProject, isCreating: isCreatingSubproject } = useProjects();
  // WIKI4AI-187: the sort menu is a backend parameter (`?sort=`), persisted per
  // user in localStorage. `size: 100` is the backend's maximum page — drag &
  // drop can only reorder what is loaded, so the list asks for as much as the
  // API allows and says so when the project is bigger (see `dndTruncated`).
  const { sort: docSort, setSort: setDocSort } = useSortPreference(
    DOCS_SORT_STORAGE_KEY,
    DEFAULT_DOCUMENT_SORT,
  );
  const {
    documents,
    documentsTotal,
    isLoading: loadingDocuments,
    createDocument,
    deleteDocument,
    uploadDocument,
    isCreating,
    isDeleting,
    isUploading,
  } = useDocuments(slug ?? '', { sort: docSort, size: 100 });

  // WIKI4AI-186: pinning for this page. Both lists use the same optimistic
  // helper: the click re-sorts immediately, the pin response (which does NOT
  // bump the document `version` nor `updatedAt`) becomes the new state, and a
  // rejection — typically 403 for a missing MANAGE/UPDATE permission — rolls
  // the item back and only shows a Toast.
  const queryClient = useQueryClient();
  // After a successful pin the affected list is refetched, so the final order
  // is exactly the server's (`pinned DESC` + its own sort key).
  const refreshProjects = useCallback(
    () => queryClient.invalidateQueries({ queryKey: ['projects'] }),
    [queryClient],
  );
  const refreshDocuments = useCallback(
    () => queryClient.invalidateQueries({ queryKey: ['documents', slug] }),
    [queryClient, slug],
  );

  const projectPinApi = useMemo(
    () => ({ pin: projectApi.pinProject, unpin: projectApi.unpinProject }),
    [],
  );
  const {
    list: orderedProjects,
    togglePin: toggleProjectPin,
    isPending: isProjectPinPending,
  } = usePinToggle(projects, projectPinApi, { onSettled: refreshProjects });

  const documentPinApi = useMemo(
    () => ({
      pin: (docSlug: string) => documentApi.pinDocument(slug ?? '', docSlug),
      unpin: (docSlug: string) => documentApi.unpinDocument(slug ?? '', docSlug),
    }),
    [slug],
  );
  const {
    list: orderedDocuments,
    togglePin: toggleDocumentPin,
    isPending: isDocumentPinPending,
    withOverrides,
  } = usePinToggle<Document>(documents, documentPinApi, { onSettled: refreshDocuments });

  // WIKI4AI-186: pin feedback as Toast (the page used bare `alert()` for the
  // destructive actions; a pin is not destructive so it gets a quiet Toast).
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const addToast = useCallback((message: string, type: 'success' | 'error') => {
    setToasts((prev) => [...prev, { id: Date.now() + Math.random(), message, type }]);
  }, []);
  const dismissToast = useCallback((id: number) => {
    setToasts((prev) => prev.filter((item) => item.id !== id));
  }, []);

  const pinErrorMessage = useCallback(
    (err: unknown, kind: 'project' | 'document') =>
      isForbiddenError(err)
        ? t(kind === 'project' ? 'pin.forbiddenProject' : 'pin.forbiddenDocument')
        : t('pin.failed', { message: err instanceof Error ? err.message : String(err) }),
    [t],
  );

  const handleToggleDocumentPin = useCallback(
    async (doc: Document) => {
      const wasPinned = !!doc.pinned;
      try {
        await toggleDocumentPin(doc);
        addToast(t(wasPinned ? 'pin.unpinnedDocument' : 'pin.pinnedDocument', { name: doc.title }), 'success');
      } catch (err) {
        addToast(pinErrorMessage(err, 'document'), 'error');
      }
    },
    [addToast, pinErrorMessage, t, toggleDocumentPin],
  );

  // `handleToggleProjectPin` needs the resolved `project`, so it is declared
  // below, next to it.

  // Search state
  const [searchQuery, setSearchQuery] = useState('');
  const debouncedSearchQuery = useDebounce(searchQuery, 300);
  const { searchResults, isLoading: searching, hasSearched } = useSearchDocuments(
    slug ?? '',
    debouncedSearchQuery,
  );

  // Semantic (hybrid) search availability — WIKI4AI-36. When the embedding
  // sidecar is down the backend falls back to text-only results; the UI shows
  // a hint so the user knows why semantic matches are missing.
  const { data: embeddingStatus } = useEmbeddingStatus();

  const [activeTab, setActiveTab] = useState<TabType>('documents');
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [newTitle, setNewTitle] = useState('');
  // WIKI4AI-100: visibility for the document being created (default public)
  const [newDocVisibility, setNewDocVisibility] = useState<Visibility>('public');
  const [createError, setCreateError] = useState<string | null>(null);

  // Upload state
  const [uploadProgress, setUploadProgress] = useState(0);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [isDragOver, setIsDragOver] = useState(false);

  // WIKI4AI-179: every timer this page schedules is tracked, and none of them
  // may outlive the page. The progress `setInterval` was cleared only inside
  // the try/catch *after* the await, so unmounting during an upload left it
  // running — each later tick called `setUploadProgress` on an unmounted
  // component. One-shot chained `setTimeout` + this cleanup closes that hole.
  const timersRef = useRef<number[]>([]);
  const isMountedRef = useRef(true);

  const scheduleTimer = useCallback((fn: () => void, ms: number) => {
    const id = window.setTimeout(() => {
      timersRef.current = timersRef.current.filter((timerId) => timerId !== id);
      fn();
    }, ms);
    timersRef.current.push(id);
  }, []);

  const clearTimers = useCallback(() => {
    timersRef.current.forEach((timerId) => window.clearTimeout(timerId));
    timersRef.current = [];
  }, []);

  useEffect(() => {
    isMountedRef.current = true;
    return () => {
      isMountedRef.current = false;
      clearTimers();
    };
  }, [clearTimers]);

  // Export state
  const [isExporting, setIsExporting] = useState(false);

  // Find the project by slug. WIKI4AI-186: taken from `orderedProjects` so the
  // header pin reflects the optimistic state, not a stale fetch.
  const project = orderedProjects.find((p) => p.slug === slug);

  const handleToggleProjectPin = useCallback(async () => {
    if (!project) return;
    const wasPinned = !!project.pinned;
    try {
      await toggleProjectPin(project);
      addToast(t(wasPinned ? 'pin.unpinnedProject' : 'pin.pinnedProject', { name: project.name }), 'success');
    } catch (err) {
      addToast(pinErrorMessage(err, 'project'), 'error');
    }
  }, [addToast, pinErrorMessage, project, t, toggleProjectPin]);

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
      setSubprojectError(err instanceof Error ? err.message : t('project.subprojectCreateFailed'));
    }
  };

  // Determine which documents to display: search results if searching, otherwise all documents.
  // WIKI4AI-186: the plain list is ordered pinned-first exactly like the server
  // list; search results keep their relevance order but still carry the pin state.
  const displayDocuments = hasSearched ? withOverrides(searchResults) : orderedDocuments;

  // ── WIKI4AI-187: manual order (drag & drop) ───────────────────────────────
  //
  // Same rules as the project grid: `comparePinned` confirms the manual order
  // the server returned (pinned first, then the opaque sortOrder) and keeps the
  // pinned block separate, because a pinned document may only move inside it.
  const manualDocuments = useMemo(
    () =>
      docSort === 'manual' && !hasSearched
        ? [...displayDocuments].sort(comparePinned)
        : displayDocuments,
    [displayDocuments, docSort, hasSearched],
  );

  /**
   * The backend clamps `size` to 100, so a bigger project is only partially
   * loaded and only that part can be reordered — the message keeps that honest
   * instead of letting a drop look like it did nothing.
   */
  const dndTruncated = documentsTotal > manualDocuments.length;
  // The document search box is the hybrid (semantic) search endpoint, i.e. a
  // server-side filter: while it is active the list on screen is a result set,
  // not the manual order, so a drop would write an order nobody is looking at.
  const dndEnabled = docSort === 'manual' && !hasSearched;

  const sendDocumentReorder = useCallback(
    async (doc: Document, body: ReorderBody) => {
      await documentApi.reorderDocument(slug ?? '', doc.slug ?? generateSlug(doc.title), body);
      addToast(t('listSort.toastReordered'), 'success');
    },
    [addToast, slug, t],
  );

  const handleDocumentReorderError = useCallback(
    (err: unknown) => {
      addToast(
        isForbiddenError(err)
          ? t('listSort.reorderForbiddenDocument')
          : t('listSort.toastReorderFailed', {
              message: err instanceof Error ? err.message : String(err),
            }),
        'error',
      );
    },
    [addToast, t],
  );

  const docDnd = useDragReorder<Document>({
    list: manualDocuments,
    enabled: dndEnabled,
    reorder: sendDocumentReorder,
    onReordered: refreshDocuments,
    onError: handleDocumentReorderError,
  });

  const docFilters: ActiveFilterChip[] = [];
  if (hasSearched) {
    docFilters.push({
      id: 'q',
      label: t('listSort.filterQuery', { q: debouncedSearchQuery.trim() }),
      onClear: () => setSearchQuery(''),
    });
  }

  const handleCreateDocument = async () => {
    if (!newTitle.trim()) return;

    setCreateError(null);
    try {
      // WIKI4AI-100: include the chosen visibility in the create payload
      const dto: CreateDocumentDto = { title: newTitle.trim(), content: '', visibility: newDocVisibility };
      await createDocument(dto);
      setNewTitle('');
      setNewDocVisibility('public'); // reset for the next document
      setShowCreateForm(false);
    } catch (err) {
      setCreateError(err instanceof Error ? err.message : t('project.docCreateFailed'));
    }
  };

  const handleDeleteDocument = async (docSlug: string) => {
    if (!window.confirm(t('project.deleteDocConfirm', { name: docSlug }))) return;
    try {
      await deleteDocument(docSlug);
    } catch (err) {
      alert(err instanceof Error ? err.message : t('project.docDeleteFailed'));
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

    // Simulate progress since fetch doesn't support upload progress natively.
    // WIKI4AI-179: a one-shot `setTimeout` chain, not an interval — the next
    // tick is scheduled only while the previous one actually ran, so the chain
    // is cancellable by `clearTimers()` (upload finished) or by the unmount
    // cleanup (page gone). Timing is identical to the old interval: first tick
    // at 200 ms, +10 % per tick, held at 90 % until the upload resolves.
    let simulated = 0;
    const tick = () => {
      simulated = Math.min(simulated + PROGRESS_STEP, PROGRESS_CEILING);
      setUploadProgress(simulated);
      if (simulated < PROGRESS_CEILING) {
        scheduleTimer(tick, PROGRESS_TICK_MS);
      }
    };
    scheduleTimer(tick, PROGRESS_TICK_MS);

    try {
      await uploadDocument(file);
      // WIKI4AI-179: the upload may resolve after this page is gone.
      if (!isMountedRef.current) return;
      clearTimers();
      setUploadProgress(100);
      // Reset progress after a short delay
      scheduleTimer(() => setUploadProgress(0), 1500);
    } catch (err) {
      if (!isMountedRef.current) return;
      clearTimers();
      setUploadProgress(0);
      setUploadError(err instanceof Error ? err.message : t('project.uploadFailed'));
    }
  };

  const handleFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    if (!isValidMarkdownFile(file)) {
      setUploadError(t('project.invalidFileSelect'));
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
      alert(err instanceof Error ? err.message : t('project.exportFailed'));
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
      setUploadError(t('project.invalidFileDrop'));
      return;
    }

    handleUpload(file);
  }, [slug, isUploading]);

  // WIKI4AI-73: dates follow the active UI language.
  const formatDate = (dateString: string): string => {
    try {
      const date = new Date(dateString);
      return date.toLocaleDateString(i18n.language === 'sk' ? 'sk-SK' : 'en-GB', {
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
  if (loadingProjects) return <div className="project-detail"><div className="loading-state"><div className="spinner" /><p>{t('common.loading')}</p></div></div>;
  if (!project) return <div className="project-detail error">{t('project.notFound')}</div>;

  return (
    <div
      className={`project-detail${isDragOver ? ' drag-over' : ''}`}
      onDragEnter={handleDragEnter}
      onDragLeave={handleDragLeave}
      onDragOver={handleDragOver}
      onDrop={handleDrop}
    >
      {/* WIKI4AI-186: pin/unpin feedback */}
      <ToastContainer toasts={toasts} onDismiss={dismissToast} />

      {/* Drag & Drop Overlay */}
      {isDragOver && (
        <div className="drag-overlay">
          {/* WIKI4AI-87: SVG folder icon (emoji rendered as tofu in container) */}
          <FolderIcon size={32} className="drag-overlay-icon" />
          <p>{t('project.dropFileHere')}</p>
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
      <nav className="breadcrumb" aria-label={t('breadcrumb.ariaLabel')} data-testid="project-breadcrumb">
        <Link to="/">{t('breadcrumb.dashboard')}</Link>
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

      <header className={`detail-header${project.pinned ? ' detail-header-pinned' : ''}`}>
        <div className="detail-title-row">
          <h1>{project.name}</h1>
          {/* WIKI4AI-186: pin this project to the top of the dashboard grid */}
          <PinButton
            pinned={!!project.pinned}
            onToggle={handleToggleProjectPin}
            name={project.name}
            kind="project"
            className="header-pin"
            disabled={isProjectPinPending(project.slug)}
            data-testid={`pin-project-${project.slug}`}
          />
        </div>
        {project.description && <p className="description">{project.description}</p>}
        <div className="header-actions">
          <button onClick={() => setShowCreateForm(!showCreateForm)} className="btn-primary" disabled={activeTab !== 'documents'}>
            + {t('project.newDocument')}
          </button>
          <button
            onClick={handleImportClick}
            className="btn-secondary btn-import"
            disabled={isUploading || activeTab !== 'documents'}
            data-testid="import-file-button"
            title={t('project.importTitle')}
          >
            <FolderIcon size={16} /> {t('project.importFile')}
          </button>
          <button
            onClick={handleExport}
            className="btn-secondary btn-export"
            disabled={isExporting}
            data-testid="export-button"
            title={t('project.exportTitle')}
          >
            {isExporting ? (
              <>
                <ClockIcon size={16} /> {t('project.exporting')}
              </>
            ) : (
              <>
                <DownloadIcon size={16} /> {t('project.export')}
              </>
            )}
          </button>
          <Link
            to={`/projects/${slug}/settings`}
            className="btn-secondary btn-settings"
            data-testid="settings-link"
            title={t('project.settingsTitle')}
          >
            <SettingsIcon size={16} /> {t('project.settings')}
          </Link>
        </div>
      </header>

      {/* Subprojects (WIKI4AI-31) */}
      <section className="subprojects-section" data-testid="subprojects-section">
        <div className="subprojects-header">
          <h2>{t('project.subprojects')} {subprojects.length > 0 && <span className="subprojects-count">({subprojects.length})</span>}</h2>
          <button
            onClick={() => setShowSubprojectForm((v) => !v)}
            className="btn-secondary btn-new-subproject"
            data-testid="create-subproject-button"
          >
            {showSubprojectForm ? `✕ ${t('common.cancel')}` : `+ ${t('project.newSubproject')}`}
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
              className="form-input"
              type="text"
              placeholder={t('project.subprojectNamePlaceholder')}
              value={newSubprojectName}
              onChange={(e) => setNewSubprojectName(e.target.value)}
              required
              autoFocus
              data-testid="subproject-name-input"
            />
            {subprojectError && <p className="state-error" data-testid="subproject-error">{subprojectError}</p>}
            <div className="form-actions">
              <button type="submit" className="btn-primary" disabled={isCreatingSubproject || !newSubprojectName.trim()}>
                {isCreatingSubproject ? t('project.creating') : t('project.createSubproject')}
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
                  {/* WIKI4AI-100: lock badge on private subprojects */}
                  {sp.visibility === 'private' && (
                    <span className="lock-badge" title={t('dashboard.privateBadge')} data-testid={`subproject-lock-${sp.id}`}>
                      <LockIcon size={12} />
                    </span>
                  )}
                </Link>
                <span className="badge">{t('dashboard.docsCount', { count: sp.documentCount })}</span>
              </li>
            ))}
          </ul>
        )}

        {/* WIKI4AI-88: empty state — bare header alone was confusing */}
        {subprojects.length === 0 && !showSubprojectForm && (
          <p className="subprojects-empty" data-testid="subprojects-empty">
            {t('project.noSubprojects')}
          </p>
        )}
      </section>

      {/* Upload Progress Bar */}
      {uploadProgress > 0 && (
        <div className="upload-progress-container">
          <div className="upload-progress-bar" style={{ width: `${uploadProgress}%` }} />
          <span className="upload-progress-text">{uploadProgress === 100 ? `✓ ${t('project.uploaded')}` : t('project.uploading', { percent: uploadProgress })}</span>
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
          {t('project.documentsTab', { count: documents.length })}
        </button>
        <Link
          to={`/projects/${slug}/graph`}
          className={`tab ${activeTab === 'graph' ? 'active' : ''}`}
          onClick={() => setActiveTab('graph')}
        >
          {t('project.graphTab')}
        </Link>
      </div>

      {/* Create Document Form */}
      {showCreateForm && activeTab === 'documents' && (
        <div key={`form-${Date.now()}`} className="tab-content">
          <form onSubmit={(e) => { e.preventDefault(); handleCreateDocument(); }} className="create-form">
            <input
              className="form-input"
              type="text"
              placeholder={t('project.docNamePlaceholder')}
              value={newTitle}
              onChange={(e) => setNewTitle(e.target.value)}
              required
              autoFocus
            />
            {/* WIKI4AI-100: visibility toggle for the new document (default Public) */}
            <div className="doc-create-visibility">
              <VisibilityToggle
                value={newDocVisibility}
                onChange={setNewDocVisibility}
                testIdPrefix="doc-create"
              />
              <p className="form-hint">{t('project.docVisibilityHint')}</p>
            </div>
            {createError && <p className="state-error">{createError}</p>}
            <div className="form-actions">
              <button type="submit" className="btn-primary" disabled={isCreating}>
                {isCreating ? t('project.creating') : t('common.create')}
              </button>
              <button type="button" onClick={() => setShowCreateForm(false)} className="btn-secondary">
                {t('common.cancel')}
              </button>
            </div>
          </form>
        </div>
      )}

      {/* Documents List — WIKI4AI-187: the tab key used to be
          `docs-${Date.now()}`, which remounted the whole tab on EVERY render —
          that destroys the row a drag is hovering over (and the focus of the
          search input while typing). A stable key keeps the nodes drag&drop is
          bound to alive. */}
      {activeTab === 'documents' && (
        <div key="docs-tab" className="tab-content">
          <div className="documents-list">
            <h2>{t('project.documentsHeading', { count: hasSearched ? searchResults.length : documents.length })}</h2>

            {/* Search Bar */}
            <div className="project-search-bar">
              <input
                type="text"
                placeholder={t('project.searchDocuments')}
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="project-search-input"
                data-testid="document-search-input"
              />
              {searchQuery && (
                <button
                  type="button"
                  onClick={() => setSearchQuery('')}
                  className="project-search-clear-btn"
                  aria-label={t('dashboard.clearSearch')}
                  data-testid="clear-search-button"
                >
                  &times;
                </button>
              )}
            </div>

            {/* WIKI4AI-187: sort menu — sent to the backend as ?sort= (the
                server owns the ordering, the UI only renders it). */}
            <div className="list-toolbar" data-testid="document-toolbar">
              <SortSelect value={docSort} onChange={setDocSort} labelKey="listSort.documentsLabel" />
            </div>

            <FilterChips filters={docFilters} onClearAll={() => setSearchQuery('')} />

            {/* Why the handles are locked right now (requirement 3) */}
            {!dndEnabled && !loadingDocuments && (
              <p className="dnd-status" data-testid="dnd-status" role="status">
                {hasSearched ? (
                  t('listSort.lockedFilter')
                ) : (
                  <>
                    {t('listSort.lockedSort')}{' '}
                    <button
                      type="button"
                      onClick={() => setDocSort('manual')}
                      data-testid="dnd-switch-manual"
                    >
                      {t('listSort.switchToManual')}
                    </button>
                  </>
                )}
              </p>
            )}

            {/* The page window is capped at 100 documents by the backend, so a
                bigger project can only be reordered inside what is loaded. */}
            {dndEnabled && dndTruncated && (
              <p className="dnd-status" data-testid="dnd-truncated" role="status">
                {t('listSort.truncated', { total: documentsTotal, loaded: manualDocuments.length })}
              </p>
            )}


            {/* Semantic search availability hint (WIKI4AI-36) */}
            {hasSearched && embeddingStatus && !embeddingStatus.available && (
              <div className="semantic-unavailable-banner" role="status" data-testid="semantic-unavailable-banner">
                {t('project.semanticUnavailable')}
              </div>
            )}
            {hasSearched && embeddingStatus?.available && (
              <span className="semantic-status-badge" data-testid="semantic-status-badge">
                {t('project.semanticActive')}
              </span>
            )}

            {loadingDocuments && !hasSearched ? (
              <div className="loading-state"><div className="spinner" /><p>{t('project.loadingDocuments')}</p></div>
            ) : searching ? (
              <div className="loading-state"><div className="spinner" /><p>{t('project.searching')}</p></div>
            ) : hasSearched && searchResults.length === 0 ? (
              <p className="state-empty">{t('project.noDocMatches')}</p>
            ) : !hasSearched && displayDocuments.length === 0 ? (
              <p className="state-empty">{t('project.emptyDocs')}</p>
            ) : (
              <ul className="document-items">
                {docDnd.displayList.map((doc, index) => {
                  const slug = doc.slug || generateSlug(doc.title);
                  return (
                    <li
                      key={doc.id}
                      className={`document-item${doc.pinned ? ' document-item-pinned' : ''}${
                        docDnd.isDragging(index) ? ' is-dragging' : ''
                      }${docDnd.isOver(index) ? (docDnd.dropEdge === 'above' ? ' drop-above' : ' drop-below') : ''}`}
                      data-pinned={doc.pinned ? 'true' : undefined}
                      /* WIKI4AI-187: the row is the drop target and the drag image */
                      data-dnd-row
                      data-dnd-enabled={dndEnabled ? 'true' : 'false'}
                      data-testid={`document-item-${slug}`}
                      onDragOver={(e) => docDnd.dragOver(index, e)}
                      onDrop={(e) => {
                        e.preventDefault();
                        // The page also has a .md file-drop zone; a reorder drop
                        // must not reach it as if it carried a file.
                        e.stopPropagation();
                        void docDnd.drop(index);
                      }}
                    >
                      {/* WIKI4AI-187: drag handle — the drag source, so the row
                          itself stays a link. Locked unless the list shows the
                          manual, unfiltered order; clicking it switches there. */}
                      <DragHandle
                        testId={`drag-handle-doc-${slug}`}
                        enabled={dndEnabled}
                        title={
                          dndEnabled
                            ? t('listSort.dragHint')
                            : hasSearched
                              ? t('listSort.lockedFilter')
                              : t('listSort.switchToManual')
                        }
                        ariaLabel={t('listSort.dragAria', { name: doc.title })}
                        onDragStart={(e) => docDnd.startDrag(index, e)}
                        onDragEnd={docDnd.cancelDrag}
                        onActivate={() => setDocSort('manual')}
                      />
                      <div className="doc-info" onClick={() => handleViewDocument(slug)}>
                        <span className="doc-title">
                          {doc.title}
                          {/* WIKI4AI-100: lock icon on private documents */}
                          {doc.visibility === 'private' && (
                            <span className="doc-lock-badge" title={t('project.privateDocBadge')} data-testid={`doc-lock-${doc.id}`}>
                              <LockIcon size={12} />
                            </span>
                          )}
                        </span>
                        <span className="doc-slug">@{slug}</span>
                        {hasSearched && doc.score != null && (
                          <span
                            className="doc-score"
                            title={t('project.scoreTitle')}
                            data-testid={`search-score-${doc.id}`}
                          >
                            {doc.score.toFixed(4)}
                          </span>
                        )}
                      </div>
                      <div className="doc-meta">
                        <span className="doc-date">{formatDate(doc.updatedAt)}</span>
                        {/* WIKI4AI-186: pin the document to the top of this project */}
                        <PinButton
                          pinned={!!doc.pinned}
                          onToggle={() => handleToggleDocumentPin(doc)}
                          name={doc.title}
                          kind="document"
                          className="pin-button-sm"
                          disabled={isDocumentPinPending(slug)}
                          data-testid={`pin-doc-${slug}`}
                        />
                        <button
                          onClick={(e) => { e.stopPropagation(); handleDeleteDocument(slug); }}
                          className="btn-delete"
                          disabled={isDeleting}
                          title={t('project.deleteDocTitle')}
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
              <h2>{t('graph.documentGraph')}</h2>
              <p>{t('project.graphPreviewText')}</p>
              <span className="btn-primary">{t('project.openGraphView')} &rarr;</span>
            </div>
          </Link>
        </div>
      )}
    </div>
  );
};

export default ProjectDetail;
