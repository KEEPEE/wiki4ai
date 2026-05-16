/**
 * Dashboard page component.
 * Displays a list of all projects in cards with the option to create new ones.
 * Uses React Query (TanStack Query) for data fetching and cache management.
 */

import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useProjects } from '../hooks/useProjects';
import type { ProjectDTO } from '../types/project';
import './Dashboard.css';

const Dashboard: React.FC = () => {
  const navigate = useNavigate();
  const {
    projects,
    isLoading,
    error,
    createProject,
    isCreating,
  } = useProjects();

  const [showCreateForm, setShowCreateForm] = useState(false);
  const [newName, setNewName] = useState('');
  const [newDescription, setNewDescription] = useState('');
  const [createError, setCreateError] = useState<string | null>(null);

  const handleCardClick = (slug: string) => {
    navigate(`/projects/${slug}`);
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
    } catch (err) {
      setCreateError(err instanceof Error ? err.message : 'Failed to create project');
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

  return (
    <div className="dashboard">
      <header className="dashboard-header">
        <h1>Wiki4AI Projects</h1>
        <button onClick={() => setShowCreateForm(!showCreateForm)} className="btn-primary">
          {showCreateForm ? 'Cancel' : '+ New Project'}
        </button>
      </header>

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
          <button type="submit" className="btn-primary" disabled={isCreating}>
            {isCreating ? 'Creating...' : 'Create'}
          </button>
        </form>
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
            <p className="empty-state">
              No projects yet.{' '}
              {!showCreateForm && (
                <button onClick={() => setShowCreateForm(true)} className="btn-primary">
                  Create your first project!
                </button>
              )}
            </p>
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
    </div>
  );
};

export default Dashboard;
