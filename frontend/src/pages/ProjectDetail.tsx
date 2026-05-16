/**
 * Project Detail page component.
 * Displays a list of documents within a project with the option to create new ones.
 */

import React, { useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { useProjects } from '../hooks/useProjects';
import './ProjectDetail.css';

const ProjectDetail: React.FC = () => {
  const { slug } = useParams<{ slug: string }>();
  const { projects, loading, error } = useProjects();
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [newTitle, setNewTitle] = useState('');

  const project = projects.find((p) => p.slug === slug);

  if (loading) return <div className="project-detail">Loading...</div>;
  if (error) return <div className="project-detail error">{error}</div>;
  if (!project) return <div className="project-detail">Project not found.</div>;

  const handleCreateDocument = async () => {
    if (!newTitle.trim()) return;
    // TODO: Call document API to create the document
    setNewTitle('');
    setShowCreateForm(false);
  };

  return (
    <div className="project-detail">
      <header className="detail-header">
        <Link to="/" className="back-link">&larr; Back to Dashboard</Link>
        <h1>{project.name}</h1>
        {project.description && <p className="description">{project.description}</p>}
        <button onClick={() => setShowCreateForm(!showCreateForm)} className="btn-primary">
          + New Document
        </button>
      </header>

      {showCreateForm && (
        <form onSubmit={(e) => { e.preventDefault(); handleCreateDocument(); }} className="create-form">
          <input
            type="text"
            placeholder="Document title"
            value={newTitle}
            onChange={(e) => setNewTitle(e.target.value)}
            required
          />
          <button type="submit" className="btn-primary">Create</button>
        </form>
      )}

      <div className="documents-list">
        <h2>Documents (0)</h2>
        {/* TODO: Fetch and display documents from the API */}
        <p className="empty-state">No documents yet. Create your first document!</p>
      </div>
    </div>
  );
};

export default ProjectDetail;
