/**
 * Dashboard page component.
 * Displays a list of all projects in cards with the option to create new ones.
 * Uses React Query (TanStack Query) for data fetching and cache management.
 */

import React, { useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { useProjects } from '../hooks/useProjects';
import type { ProjectDTO } from '../types/project';
import './Dashboard.css';

/** Toast notification component */
interface Toast {
  id: number;
  message: string;
  type: 'success' | 'error';
}

const ToastContainer: React.FC<{ toasts: Toast[]; onDismiss: (id: number) => void }> = ({ toasts, onDismiss }) => {
  return (
    <div className="toast-container" data-testid="toast-container">
      {toasts.map((toast) => (
        <div key={toast.id} className={`toast toast-${toast.type}`} role="alert">
          <span>{toast.message}</span>
          <button
            type="button"
            className="toast-dismiss"
            onClick={() => onDismiss(toast.id)}
            aria-label="Dismiss notification"
          >
            ×
          </button>
        </div>
      ))}
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
  const [editName, setEditName] = useState(project.name);
  const [editDescription, setEditDescription] = useState(project.description || '');
  const [saveError, setSaveError] = useState<string | null>(null);

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editName.trim()) return;

    setSaveError(null);
    try {
      const dto: ProjectDTO = {
        name: editName.trim(),
        description: editDescription.trim() || undefined,
      };
      await onSave(project.id, dto);
    } catch (err) {
      setSaveError(err instanceof Error ? err.message : 'Failed to update project');
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
        <button type="button" className="modal-close" onClick={onCancel} aria-label="Close modal">×</button>
        <h3 id="edit-modal-title">Edit Project</h3>
        <form onSubmit={handleSave}>
          <label htmlFor="edit-name">Project Name</label>
          <input
            id="edit-name"
            type="text"
            value={editName}
            onChange={(e) => setEditName(e.target.value)}
            required
            autoFocus
            data-testid="edit-name-input"
          />

          <label htmlFor="edit-description">Description</label>
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
              {isSaving ? 'Saving...' : 'Save'}
            </button>
            <button type="button" onClick={onCancel} className="btn-secondary" data-testid="edit-cancel-button">
              Cancel
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

const Dashboard: React.FC = () => {
  const navigate = useNavigate();
  const {
    projects,
    isLoading,
    error,
    createProject,
    updateProject,
    isCreating,
    isUpdating,
  } = useProjects();

  const [showCreateForm, setShowCreateForm] = useState(false);
  const [newName, setNewName] = useState('');
  const [newDescription, setNewDescription] = useState('');
  const [createError, setCreateError] = useState<string | null>(null);

  // Edit state
  const [editingProject, setEditingProject] = useState<{ id: number; name: string; description: string | null } | null>(null);

  // Toast state
  const [toasts, setToasts] = useState<Toast[]>([]);
  let toastIdCounter = 0;

  const addToast = useCallback((message: string, type: 'success' | 'error') => {
    const id = ++toastIdCounter;
    setToasts((prev) => [...prev, { id, message, type }]);
    // Auto-dismiss after 3 seconds
    setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id));
    }, 3000);
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
    addToast('Project updated successfully', 'success');
  };

  const handleCancelEdit = () => {
    setEditingProject(null);
  };

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newName.trim()) return;

    setCreateError(null);
    try {
      const dto: ProjectDTO = { name: newName.trim(), description: newDescription.trim() || undefined };
      await createProject(dto);
      setNewName('');
      setNewDescription('');
      setShowCreateForm(false);
      addToast('Project created successfully', 'success');
    } catch (err) {
      const errorMsg = err instanceof Error ? err.message : 'Failed to create project';
      setCreateError(errorMsg);
      addToast(errorMsg, 'error');
    }
  };

  const formatDate = (dateString: string): string => {
    try {
      const date = new Date(dateString);
      return date.toLocaleDateString('sk-SK', {
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

  return (
    <div className="dashboard">
      {/* Toast Notifications */}
      <ToastContainer toasts={toasts} onDismiss={dismissToast} />

      {/* Hero Section */}
      <section className="hero-section">
        <h1>Wiki4AI Projects</h1>
        <p className="hero-subtitle">Manage and organize your AI documentation projects</p>
        {projects.length > 0 && (
          <div className="stats-row">
            <div className="stat-item">
              <span className="stat-number">{projects.length}</span>
              <span className="stat-label">Projects</span>
            </div>
            <div className="stat-divider" />
            <div className="stat-item">
              <span className="stat-number">{totalDocs}</span>
              <span className="stat-label">Documents</span>
            </div>
          </div>
        )}
      </section>

      {/* Create Project Form */}
      {showCreateForm && (
        <form onSubmit={handleCreate} className="create-form">
          <h3>Create New Project</h3>
          <input
            type="text"
            placeholder="Project name"
            value={newName}
            onChange={(e) => setNewName(e.target.value)}
            required
            autoFocus
          />
          <textarea
            placeholder="Description (optional)"
            value={newDescription}
            onChange={(e) => setNewDescription(e.target.value)}
            rows={3}
          />
          {createError && <p className="error">{createError}</p>}
          <div className="form-actions">
            <button type="submit" className="btn-primary" disabled={isCreating}>
              {isCreating ? 'Creating...' : 'Create'}
            </button>
            <button type="button" onClick={() => setShowCreateForm(false)} className="btn-secondary">
              Cancel
            </button>
          </div>
        </form>
      )}

      {/* Action Button */}
      {!showCreateForm && (
        <button onClick={() => setShowCreateForm(true)} className="btn-create-new">
          + New Project
        </button>
      )}

      {/* Loading State */}
      {isLoading && (
        <div className="loading-state">
          <div className="spinner" />
          <p>Loading projects...</p>
        </div>
      )}

      {/* Error State */}
      {error && !isLoading && (
        <div className="error-state">
          <p className="error">{error.message}</p>
          <button onClick={() => window.location.reload()} className="btn-secondary">
            Retry
          </button>
        </div>
      )}

      {/* Projects Grid */}
      {!isLoading && (
        <div className="projects-grid">
          {projects.length === 0 ? (
            <div className="empty-state">
              <svg style={{ width: 64, height: 64 }} className="text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z" />
              </svg>
              <p className="empty-text">No projects yet.</p>
              {!showCreateForm && (
                <button onClick={() => setShowCreateForm(true)} className="btn-primary">
                  Create your first project!
                </button>
              )}
            </div>
          ) : (
            projects.map((project) => (
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
                <button
                  type="button"
                  className="edit-button"
                  onClick={(e) => handleEditClick(e, project)}
                  aria-label={`Edit ${project.name}`}
                  data-testid={`edit-button-${project.id}`}
                >
                  ✏️
                </button>
                <div className="card-header">
                  <h3>{project.name}</h3>
                  <span className="badge">{project.documentCount} docs</span>
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
              </div>
            ))
          )}
        </div>
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
    </div>
  );
};

export default Dashboard;
