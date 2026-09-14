/**
 * Breadcrumb navigation component — Nexaverse glassmorphism style.
 * 
 * Features:
 * - Glass panel with backdrop-filter blur(20px)
 * - Pill shape (border-radius: 50px)
 * - Gradient hover effect on document link
 * - Neon glow on active/hover state
 * - Outfit font for project slug, Syne font for document title
 */

import React from 'react';
import { Link } from 'react-router-dom';
import './Breadcrumb.css';

interface BreadcrumbProps {
  /** URL-friendly slug of the project */
  projectSlug: string;
  /** Display name / title of the current document */
  documentTitle?: string;
  /**
   * Optional ancestor chain for nested projects (WIKI4AI-31), ordered from
   * the root down to the direct parent of `projectSlug`. When omitted, the
   * breadcrumb shows only Dashboard → project → document.
   */
  ancestors?: { slug: string; name: string }[];
}

/**
 * Breadcrumb component renders a glassmorphism-styled navigation trail.
 * 
 * Structure: Dashboard → {projectSlug} → {documentTitle}
 * 
 * The container uses:
 * - Background: var(--glass-bg) (rgba(255,255,255,0.03))
 * - Backdrop filter: blur(20px)
 * - Border: 1px solid var(--glass-border)
 * - Border radius: 50px (pill shape)
 * 
 * Each breadcrumb item is separated by a → character styled with rgba(255,255,255,0.3).
 * The document title uses Syne font and gets a neon glow on hover.
 */
const Breadcrumb: React.FC<BreadcrumbProps> = ({ projectSlug, documentTitle, ancestors }) => {
  return (
    <nav className="breadcrumb" aria-label="Breadcrumb">
      <ol className="breadcrumb-list">
        {/* Dashboard home link */}
        <li className="breadcrumb-item">
          <Link to="/" className="breadcrumb-link breadcrumb-home">
            Dashboard
          </Link>
        </li>

        {/* Ancestor projects for nested hierarchies (WIKI4AI-31) */}
        {(ancestors ?? []).map((ancestor) => (
          <React.Fragment key={ancestor.slug}>
            <li className="breadcrumb-separator" aria-hidden="true">→</li>
            <li className="breadcrumb-item">
              <Link
                to={`/projects/${ancestor.slug}`}
                className="breadcrumb-link breadcrumb-project"
                data-testid={`breadcrumb-ancestor-${ancestor.slug}`}
              >
                {ancestor.name}
              </Link>
            </li>
          </React.Fragment>
        ))}

        {/* Separator */}
        <li className="breadcrumb-separator" aria-hidden="true">→</li>

        {/* Project slug link — Outfit font, muted color */}
        <li className="breadcrumb-item">
          <Link
            to={`/projects/${projectSlug}`}
            className="breadcrumb-link breadcrumb-project"
          >
            {projectSlug}
          </Link>
        </li>

        {/* Separator */}
        <li className="breadcrumb-separator" aria-hidden="true">→</li>

        {/* Current document title — Syne font, highlighted color */}
        <li
          className="breadcrumb-item breadcrumb-current"
          aria-current="page"
        >
          <span className="breadcrumb-doc-title">
            {documentTitle || projectSlug}
          </span>
        </li>
      </ol>
    </nav>
  );
};

export default Breadcrumb;
