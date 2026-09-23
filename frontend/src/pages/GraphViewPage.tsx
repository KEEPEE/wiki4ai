/**
 * Graph View Page - displays the document graph visualization for a project.
 * Fetches documents from the API and renders them as an interactive force-directed graph.
 */

import React from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useProjects } from '../hooks/useProjects';
import { useDocuments } from '../hooks/useDocuments';
import GraphView from '../components/GraphView';
import { useTranslation } from 'react-i18next';
import './ProjectDetail.css'; // Reuse project detail styles for breadcrumb

const GraphViewPage: React.FC = () => {
  const { slug } = useParams<{ slug: string }>();
  const navigate = useNavigate();
  const { t } = useTranslation();
  const { projects, isLoading: loadingProjects } = useProjects();
  const { documents, isLoading: loadingDocuments } = useDocuments(slug ?? '');

  // Find the project by slug
  const project = projects.find((p) => p.slug === slug);

  if (loadingProjects || loadingDocuments) {
    return (
      <div className="project-detail">
        <nav className="breadcrumb" aria-label={t('graph.breadcrumbAria')}>
          <a href="/">{t('graph.dashboard')}</a>
          <span className="separator">&rsaquo;</span>
          <a href={`/projects/${slug}`}>{project?.name ?? t('graph.project')}</a>
          <span className="separator">&rsaquo;</span>
          <span className="current">{t('graph.graphTab')}</span>
        </nav>
        <div className="loading-state"><div className="spinner" /><p>{t('graph.loadingGraph')}</p></div>
      </div>
    );
  }

  if (!project) {
    return (
      <div className="project-detail error">{t('graph.projectNotFound')}</div>
    );
  }

  const handleNodeClick = (docSlug: string) => {
    navigate(`/projects/${slug}/documents/${docSlug}`);
  };

  return (
    <div className="project-detail">
      {/* Breadcrumb Navigation */}
      <nav className="breadcrumb" aria-label={t('graph.breadcrumbAria')}>
        <a href="/">{t('graph.dashboard')}</a>
        <span className="separator">&rsaquo;</span>
        <a href={`/projects/${slug}`}>{project.name}</a>
        <span className="separator">&rsaquo;</span>
        <span className="current">{t('graph.graphTab')}</span>
      </nav>

      <header className="detail-header">
        <h1>{t('graph.documentGraphTitle', { name: project.name })}</h1>
        <p className="description">{t('graph.pageSubtitle')}</p>
      </header>

      {/* Graph Visualization */}
      <GraphView documents={documents} onNodeClick={handleNodeClick} />
    </div>
  );
};

export default GraphViewPage;
