/**
 * Project Settings page component.
 * Allows editing project name/description and deleting the project.
 */

import React, { useState } from 'react';
import { useParams, Link, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import BackButton from '../components/BackButton';
import { useProjects } from '../hooks/useProjects';
import type { ProjectDTO } from '../types/project';
import './ProjectSettings.css';

const ProjectSettings: React.FC = () => {
  const { t } = useTranslation();
  const { slug } = useParams<{ slug: string }>();
  const navigate = useNavigate();
  const { projects, isLoading: loadingProjects, updateProject, deleteProject, isUpdating, isDeleting } = useProjects();

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [parentSlug, setParentSlug] = useState<string | null>(null);
  const [parentInitialized, setParentInitialized] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);

  // Find the project by slug
  const project = projects.find((p) => p.slug === slug);

  // Pre-fill form when project is loaded
  React.useEffect(() => {
    if (project && !name) {
      setName(project.name);
      setDescription(project.description ?? '');
    }
    if (project && !parentInitialized) {
      setParentSlug(project.parentSlug ?? null);
      setParentInitialized(true);
    }
  }, [project, parentInitialized]);

  // Projects that can become the new parent: everything except self and its
  // own descendants (moving under a descendant would create a cycle).
  const parentOptions = React.useMemo(() => {
    if (!project) return [];
    const descendants = new Set<string>();
    const collect = (slug: string) => {
      for (const child of projects.filter((p) => p.parentSlug === slug)) {
        if (!descendants.has(child.slug)) {
          descendants.add(child.slug);
          collect(child.slug);
        }
      }
    };
    collect(project.slug);
    return projects.filter((p) => p.id !== project.id && !descendants.has(p.slug));
  }, [projects, project]);

  const originalParentSlug = project?.parentSlug ?? null;

  if (loadingProjects) return <div className="project-settings"><div className="loading-state"><div className="spinner" /><p>{t('common.loading')}</p></div></div>;
  if (!project) return <div className="project-settings error">{t('project.notFound')}</div>;

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return;

    setSaveError(null);
    try {
      const dto: ProjectDTO = { name: name.trim(), description: description.trim() || undefined };
      // Move semantics (WIKI4AI-30): only send parentId when it actually changed.
      // Absent key = no move; explicit null = move back to root.
      if (parentSlug !== originalParentSlug) {
        const newParent = parentSlug ? projects.find((p) => p.slug === parentSlug) : undefined;
        dto.parentId = newParent ? newParent.id : null;
      }
      await updateProject({ id: project.id, dto });
      // Navigate back to project detail after successful save
      navigate(`/projects/${slug}`);
    } catch (err) {
      setSaveError(err instanceof Error ? err.message : t('project.saveFailed'));
    }
  };

  const handleDelete = async () => {
    setDeleteError(null);
    try {
      await deleteProject(project.id);
      navigate('/');
    } catch (err) {
      setDeleteError(err instanceof Error ? err.message : t('dashboard.deleteFailed'));
    }
  };

  return (
    <div className="project-settings">
      {/* Breadcrumb Navigation */}
      <nav className="breadcrumb" aria-label={t('breadcrumb.ariaLabel')}>
        <Link to="/">{t('breadcrumb.dashboard')}</Link>
        <span className="separator">&rsaquo;</span>
        <Link to={`/projects/${slug}`}>{project.name}</Link>
        <span className="separator">&rsaquo;</span>
        <span className="current">{t('project.settings')}</span>
      </nav>

      {/* Back Button — glassmorphism pill with neon hover */}
      <div className="settings-back-btn" data-testid="back-button-container">
        <BackButton to={`/projects/${slug}`} label={t('project.backToProject')} />
      </div>

      <header className="settings-header">
        <h1>{t('project.pageTitle')}</h1>
        <p className="settings-subtitle">{t('project.subtitle')}</p>
      </header>

      {/* Edit Project Form */}
      <section className="settings-section">
        <h2>{t('dashboard.editProjectTitle')}</h2>
        <form onSubmit={handleSave} className="settings-form" data-testid="settings-form">
          <div className="form-group">
            <label htmlFor="project-name">{t('dashboard.projectName')}</label>
            <input
              id="project-name"
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              required
              data-testid="project-name-input"
              placeholder={t('project.namePlaceholder')}
            />
          </div>

          <div className="form-group">
            <label htmlFor="project-description">{t('dashboard.description')}</label>
            <textarea
              id="project-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              data-testid="project-description-input"
              placeholder={t('project.descPlaceholder')}
              rows={4}
            />
          </div>

          {/* Parent project selector — move in hierarchy (WIKI4AI-31) */}
          <div className="form-group">
            <label htmlFor="project-parent">{t('project.parentLabel')}</label>
            <select
              id="project-parent"
              value={parentSlug ?? ''}
              onChange={(e) => setParentSlug(e.target.value || null)}
              data-testid="project-parent-select"
            >
              <option value="">{t('project.rootOption')}</option>
              {parentOptions.map((p) => (
                <option key={p.id} value={p.slug}>
                  {p.name}
                </option>
              ))}
            </select>
            <p className="form-hint">
              {t('project.parentHint')}
            </p>
          </div>

          {saveError && <p className="error" data-testid="save-error">{saveError}</p>}

          <div className="form-actions">
            <Link to={`/projects/${slug}`} className="btn-secondary">{t('common.cancel')}</Link>
            <button type="submit" className="btn-primary" disabled={isUpdating} data-testid="save-button">
              {isUpdating ? t('dashboard.saving') : t('project.saveChanges')}
            </button>
          </div>
        </form>
      </section>

      {/* Danger Zone */}
      <section className="settings-section danger-zone">
        <h2>{t('project.dangerZone')}</h2>
        <p className="danger-description">
          {t('project.dangerText')}
        </p>
        <button
          onClick={() => setShowDeleteConfirm(true)}
          className="btn-danger"
          disabled={isDeleting}
          data-testid="delete-button"
        >
          {t('dashboard.deleteProjectTitle')}
        </button>

        {deleteError && <p className="error" data-testid="delete-error">{deleteError}</p>}
      </section>

      {/* Delete Confirmation Modal */}
      {showDeleteConfirm && (
        <div className="modal-backdrop" data-testid="delete-modal">
          <div className="modal-content">
            <button
              onClick={() => setShowDeleteConfirm(false)}
              className="modal-close"
              aria-label={t('common.close')}
            >
              &times;
            </button>
            <h3>{t('dashboard.deleteProjectTitle')}</h3>
            <p className="delete-warning">
              {t('project.deleteConfirmStart')} <strong>"{project.name}"</strong>?{' '}
              {t('project.deleteConfirmEnd')}
            </p>
            <div className="form-actions form-actions-delete">
              <button
                onClick={handleDelete}
                className="btn-danger"
                disabled={isDeleting}
                data-testid="confirm-delete-button"
              >
                {isDeleting ? t('dashboard.deleting') : t('project.confirmDelete')}
              </button>
              <button
                onClick={() => setShowDeleteConfirm(false)}
                className="btn-secondary"
                data-testid="cancel-delete-button"
              >
                {t('common.cancel')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default ProjectSettings;
