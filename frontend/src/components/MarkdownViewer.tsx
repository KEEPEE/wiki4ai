/**
 * Markdown Viewer component.
 * Renders markdown content via MarkdownPreview (with Mermaid support) and
 * supports wiki-style links [[Document]] with click handling.
 * Supports both raw markdown and pre-rendered HTML from the backend.
 */

import React, { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import MarkdownPreview from './MarkdownPreview';
import './MarkdownViewer.css';

interface MarkdownViewerProps {
  /** Raw markdown content or pre-rendered HTML string */
  content: string;
  /** Extracted wiki-style link targets (e.g., ['Introduction', 'Architecture']) */
  wikiLinks?: string[];
  /** Callback when a wiki link is clicked */
  onLinkClick?: (slug: string) => void;
}

/**
 * Slugify a document title into a URL-friendly slug.
 * Converts "My Document" → "my-document", handles Slovak diacritics.
 */
function slugify(text: string): string {
  return text
    .toLowerCase()
    .replace(/[áä]/g, 'a')
    .replace(/č/g, 'c')
    .replace(/[ďđ]/g, 'd')
    .replace(/[éèêë]/g, 'e')
    .replace(/[íìîï]/g, 'i')
    .replace(/[ĺľ]/g, 'l')
    .replace(/ň/g, 'n')
    .replace(/[óòôö]/g, 'o')
    .replace(/ŕ/g, 'r')
    .replace(/š/g, 's')
    .replace(/ť/g, 't')
    .replace(/[úùûü]/g, 'u')
    .replace(/[ýỳÿ]/g, 'y')
    .replace(/ž/g, 'z')
    .replace(/[^a-z0-9\s-]/g, '')
    .replace(/\s+/g, '-')
    .replace(/-+/g, '-')
    .trim()
    .replace(/^-|-$/g, '');
}

/**
 * Extract wiki-style link targets from raw markdown content.
 * Returns an array of document names found in [[Document]] syntax.
 */
function extractWikiLinks(content: string): string[] {
  const matches = content.match(/\[\[([^\]]+)\]\]/g);
  if (!matches) return [];
  return [...new Set(matches.map(m => m.slice(2, -2)))];
}

/**
 * Preprocess markdown content: convert [[Wiki Link]] syntax to HTML anchor tags
 * that MarkdownPreview (via react-markdown) will render inline.
 */
function preprocessWikiLinks(content: string): string {
  return content.replace(/\[\[([^\]]+)\]\]/g, '<a href="#" class="wiki-link" data-wiki-target="$1">$1</a>');
}

const MarkdownViewer: React.FC<MarkdownViewerProps> = ({
  content,
  wikiLinks: propWikiLinks,
  onLinkClick,
}) => {
  const { t } = useTranslation();

  // Extract wiki links from raw markdown if not provided via props
  const wikiLinks = useMemo(
    () => propWikiLinks ?? extractWikiLinks(content),
    [propWikiLinks, content]
  );

  // Determine rendering mode: HTML vs markdown vs empty
  const renderedContent = useMemo(() => {
    if (!content) {
      return null;
    }

    // Check if content looks like pre-rendered HTML (contains HTML tags but not markdown headings)
    if (/<[a-z][\s\S]*>/i.test(content) && !content.includes('#')) {
      return <div dangerouslySetInnerHTML={{ __html: content }} />;
    }

    // Preprocess wiki links then render via MarkdownPreview (with Mermaid support)
    const processedContent = preprocessWikiLinks(content);
    return <MarkdownPreview content={processedContent} className="markdown-content" />;
  }, [content]);

  const handleWikiLinkClick = (target: string, e: React.MouseEvent) => {
    e.preventDefault();
    if (onLinkClick) {
      onLinkClick(slugify(target));
    }
  };

  return (
    <div className="markdown-viewer">
      {/* Main content area — markdown or HTML */}
      <div
        className="content-area"
        onClick={(e) => {
          const target = e.target as HTMLElement;
          if (target.classList.contains('wiki-link')) {
            const wikiTarget = target.getAttribute('data-wiki-target');
            if (wikiTarget) {
              handleWikiLinkClick(wikiTarget, e);
            }
          }
        }}
      >
        {renderedContent}
      </div>

      {/* Links section — extracted wiki links as clickable chips */}
      {wikiLinks.length > 0 && (
        <div className="links-section">
          <h3>{t('markdown.linksTitle')}</h3>
          <ul>
            {wikiLinks.map((link) => (
              <li key={link}>
                <a
                  href="#"
                  className="wiki-link"
                  data-wiki-target={link}
                  onClick={(e) => handleWikiLinkClick(link, e)}
                >
                  {link}
                </a>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
};

export default MarkdownViewer;
