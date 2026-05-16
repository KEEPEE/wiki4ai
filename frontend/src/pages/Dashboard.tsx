/**
 * Dashboard page component.
 * Displays a list of all projects with the option to create new ones.
 */

import React, { useState } from 'react';
import { useProjects } from '../hooks/useProjects';
import type { ProjectDTO } from '../types/project';
import './Dashboard.css';

const Dashboard: React.FC = () => {
  const { projects, loading, error, createProject } = useProjects();
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [newName, setNewName] = useState('');
  const [newDescription, setNewDescription] = useState('');

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newName.trim()) return;

    const dto: ProjectDTO = { name: newName.trim(), description: newDescription.trim() || undefined };
    await createProject(dto);
    setNewName('');
    setNewDescription('');
    setShowCreateForm(false);
  };

  return (
    <div className="dashboard">
      <header className="dashboard-header">
        <h1>Wiki4AI Projects</h1>
        <button onClick={() => setShowCreateForm(!showCreateForm)} className="btn-primary">
          {showCreateForm ? 'Cancel' : '+ New Project'}
        </button>
      </header>

      {showCreateForm && (
        <form onSubmit={handleCreate} className="create-form">
          <h3>Create New Project</h3>
          <input
            type="text"
            placeholder="Project name"
            value={newName}
            onChange={(e) => setNewName(e.target.value)}
            required
          />
          <textarea
            placeholder="Description (optional)"
            value={newDescription}
            onChange={(e) => setNewDescription(e.target.value)}
            rows={3}
          />
          <button type="submit" className="btn-primary">Create</button>
        </form>
      )}

      {loading && <p>Loading projects...</p>}
      {error && <p className="error">{error}</p>}

      {!loading && !error && (
        <div className="projects-grid">
          {projects.length === 0 ? (
            <p className="empty-state">No projects yet. Create your first project!</p>
          ) : (
            projects.map((project) => (
              <div key={project.id} className="project-card">
                <h3>{project.name}</h3>
                {project.description && <p>{project.description}</p>}
                <span className="slug">@{project.slug}</span>
              </div>
            ))
          )}
        </div>
      )}
    </div>
  );
};

export default Dashboard;
