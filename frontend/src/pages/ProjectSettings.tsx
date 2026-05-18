/**
 * Project Settings page component.
 * Allows editing project name/description and deleting the project.
 */

import React, { useState } from 'react';
import { useParams, Link, useNavigate } from 'react-router-dom';
import { useProjects } from '../hooks/useProjects';
import type { ProjectDTO } from '../types/project';
import './ProjectSettings.css';

const ProjectSettings: React.FC = () => {
  const { slug } = useParams<{ slug: string }>();
  const navigate = useNavigate();
  const { projects, isLoading: loadingProjects, updateProject, deleteProject, isUpdating, isDeleting } = useProjects();

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
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
  }, [project]);

  if (loadingProjects) return <div className="project-settings"><div className="loading-state"><div className="spinner" /><p>Loading...</p></div></div>;
  if (!project) return <div className="project-settings error">Project not found.</div>;

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return;

    setSaveError(null);
    try {
      const dto: ProjectDTO = { name: name.trim(), description: description.trim() || undefined };
      await updateProject({ id: project.id, dto });
      // Navigate back to project detail after successful save
      navigate(`/projects/${slug}`);
    } catch (err) {
      setSaveError(err instanceof Error ? err.message : 'Failed to save changes');
    }
  };

  const handleDelete = async () => {
    setDeleteError(null);
    try {
      await deleteProject(project.id);
      navigate('/');
    } catch (err) {
      setDeleteError(err instanceof Error ? err.message : 'Failed to delete project');
    }
  };

  return (
    <div className="project-settings">
      {/* Breadcrumb Navigation */}
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Dashboard</Link>
        <span className="separator">&rsaquo;</span>
        <Link to={`/projects/${slug}`}>{project.name}</Link>
        <span className="separator">&rsaquo;</span>
        <span className="current">Settings</span>
      </nav>

      <header className="settings-header">
        <h1>Project Settings</h1>
        <p className="settings-subtitle">Manage your project details and settings.</p>
      </header>

      {/* Edit Project Form */}
      <section className="settings-section">
        <h2>Edit Project</h2>
        <form onSubmit={handleSave} className="settings-form" data-testid="settings-form">
          <div className="form-group">
            <label htmlFor="project-name">Project Name</label>
            <input
              id="project-name"
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              required
              data-testid="project-name-input"
              placeholder="Enter project name"
            />
          </div>

          <div className="form-group">
            <label htmlFor="project-description">Description</label>
            <textarea
              id="project-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              data-testid="project-description-input"
              placeholder="Enter project description (optional)"
              rows={4}
            />
          </div>

          {saveError && <p className="error" data-testid="save-error">{saveError}</p>}

          <div className="form-actions">
            <Link to={`/projects/${slug}`} className="btn-secondary">Cancel</Link>
            <button type="submit" className="btn-primary" disabled={isUpdating} data-testid="save-button">
              {isUpdating ? 'Saving...' : 'Save Changes'}
            </button>
          </div>
        </form>
      </section>

      {/* Danger Zone */}
      <section className="settings-section danger-zone">
        <h2>Danger Zone</h2>
        <p className="danger-description">
          Once you delete a project, there is no going back. Please be certain.
        </p>
        <button
          onClick={() => setShowDeleteConfirm(true)}
          className="btn-danger"
          disabled={isDeleting}
          data-testid="delete-button"
        >
          Delete Project
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
              aria-label="Close dialog"
            >
              &times;
            </button>
            <h3>Delete Project</h3>
            <p className="delete-warning">
              Are you sure you want to delete <strong>"{project.name}"</strong>? 
              This action cannot be undone and all documents in this project will be permanently deleted.
            </p>
            <div className="form-actions form-actions-delete">
              <button
                onClick={handleDelete}
                className="btn-danger"
                disabled={isDeleting}
                data-testid="confirm-delete-button"
              >
                {isDeleting ? 'Deleting...' : 'Yes, Delete Project'}
              </button>
              <button
                onClick={() => setShowDeleteConfirm(false)}
                className="btn-secondary"
                data-testid="cancel-delete-button"
              >
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default ProjectSettings;
