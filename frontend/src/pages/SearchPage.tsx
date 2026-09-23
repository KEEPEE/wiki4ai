/**
 * Global (cross-project) search page (WIKI4AI-61).
 * Hybrid (text + semantic) search across ALL wiki projects with project
 * attribution per hit. Reuses the existing search/result CSS classes from
 * ProjectDetail.css and the embedding-status badge (WIKI4AI-36).
 */

import React, { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useGlobalSearch } from '../hooks/useGlobalSearch';
import { useEmbeddingStatus } from '../hooks/useEmbeddingStatus';
import { useDebounce } from '../hooks/useDebounce';
import { generateSlug } from '../utils/slugify';
import { useTranslation } from 'react-i18next';
import './ProjectDetail.css'; // shared search-bar / document-items / score-badge classes
import './SearchPage.css';

const SearchPage: React.FC = () => {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { t } = useTranslation();

  // Local input state, seeded from ?q= and kept in sync when the parameter
  // changes (e.g. a fresh header search while already on /search).
  const [query, setQuery] = useState(searchParams.get('q') ?? '');
  useEffect(() => {
    const param = searchParams.get('q');
    if (param !== null) setQuery(param);
  }, [searchParams]);

  const debouncedQuery = useDebounce(query, 300);
  const { results, isLoading: searching, hasSearched } = useGlobalSearch(debouncedQuery);

  // Semantic (hybrid) search availability — WIKI4AI-36, same badge as ProjectDetail.
  const { data: embeddingStatus } = useEmbeddingStatus();

  const formatDate = (dateString: string): string => {
    try {
      const date = new Date(dateString);
      return date.toLocaleDateString('sk-SK', {
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

  const handleViewDocument = (projectSlug: string, docSlug: string) => {
    navigate(`/projects/${projectSlug}/documents/${docSlug}`);
  };

  return (
    <div className="search-page">
      <h1 className="search-page-title">{t('search.title')}</h1>
      <p className="search-page-subtitle">
        {t('search.subtitle')}
      </p>

      {/* Search bar — reuses ProjectDetail .search-bar/.search-input classes */}
      <div className="search-bar">
        <input
          type="text"
          placeholder={t('search.searchPlaceholder')}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          className="search-input"
          data-testid="global-search-page-input"
        />
        {query && (
          <button
            type="button"
            onClick={() => setQuery('')}
            className="search-clear-btn"
            aria-label={t('search.clearSearchAria')}
            data-testid="clear-global-search-button"
          >
            &times;
          </button>
        )}
      </div>

      {/* Semantic search availability hint (WIKI4AI-36) */}
      {hasSearched && embeddingStatus && !embeddingStatus.available && (
        <div className="semantic-unavailable-banner" role="status" data-testid="semantic-unavailable-banner">
          {t('search.semanticUnavailable')}
        </div>
      )}
      {hasSearched && embeddingStatus?.available && (
        <span className="semantic-status-badge" data-testid="semantic-status-badge">
          {t('search.semanticActive')}
        </span>
      )}

      {searching ? (
        <div className="loading-state"><div className="spinner" /><p>{t('search.searching')}</p></div>
      ) : hasSearched && results.length === 0 ? (
        <p className="empty-state">{t('search.noResults')}</p>
      ) : !hasSearched ? (
        <p className="empty-state">{t('search.startTypingHint')}</p>
      ) : (
        <>
          <p className="search-results-count" data-testid="search-results-count">
            {t('search.resultsCount', { count: results.length })}
          </p>
          <ul className="document-items">
            {results.map((doc) => {
              const docSlug = doc.slug || generateSlug(doc.title);
              return (
                <li key={doc.id} className="document-item search-result-item">
                  <div className="search-result-headline" onClick={() => handleViewDocument(doc.projectSlug, docSlug)}>
                    <span className="doc-title">{doc.title}</span>
                    {doc.score != null && (
                      <span
                        className="doc-score"
                        title={t('search.scoreTitle')}
                        data-testid={`search-score-${doc.id}`}
                      >
                        {doc.score.toFixed(4)}
                      </span>
                    )}
                    <span
                      className="project-badge"
                      title={t('search.projectBadge', { project: doc.projectName || doc.projectSlug })}
                      data-testid={`project-badge-${doc.id}`}
                    >
                      {doc.projectName || doc.projectSlug}
                    </span>
                  </div>
                  {doc.excerpt && (
                    <p className="doc-excerpt" data-testid={`search-excerpt-${doc.id}`}>
                      {doc.excerpt}
                    </p>
                  )}
                  <div className="doc-meta">
                    <span className="doc-slug">@{docSlug}</span>
                    <span className="doc-date">{formatDate(doc.updatedAt)}</span>
                  </div>
                </li>
              );
            })}
          </ul>
        </>
      )}
    </div>
  );
};

export default SearchPage;
