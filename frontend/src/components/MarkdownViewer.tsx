/**
 * Markdown Viewer component.
 * Renders markdown content with support for wiki-style links [[Document]].
 * Supports both raw markdown (parsed client-side) and pre-rendered HTML from the backend.
 */

import React, { useMemo } from 'react';
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
 * Simple markdown parser that handles basic syntax and wiki-style links.
 * In production, consider using a library like react-markdown or marked.
 */
function parseMarkdown(content: string): React.ReactNode[] {
  if (!content) return [<p key="empty" className="empty-content">No content</p>];

  const lines = content.split('\n');
  const elements: React.ReactNode[] = [];
  let inCodeBlock = false;
  let codeContent: string[] = [];
  let keyIndex = 0;

  for (const line of lines) {
    // Code blocks
    if (line.startsWith('```')) {
      if (inCodeBlock) {
        elements.push(
          <pre key={`code-${keyIndex++}`} className="code-block">
            <code>{codeContent.join('\n')}</code>
          </pre>
        );
        codeContent = [];
        inCodeBlock = false;
      } else {
        if (elements.length > 0 && elements[elements.length - 1] !== '\n') {
          elements.push('\n');
        }
        inCodeBlock = true;
      }
      continue;
    }

    if (inCodeBlock) {
      codeContent.push(line);
      continue;
    }

    // Headings
    if (line.startsWith('### ')) {
      elements.push(<h3 key={`h3-${keyIndex++}`}>{parseInline(line.slice(4))}</h3>);
      continue;
    }
    if (line.startsWith('## ')) {
      elements.push(<h2 key={`h2-${keyIndex++}`}>{parseInline(line.slice(3))}</h2>);
      continue;
    }
    if (line.startsWith('# ')) {
      elements.push(<h1 key={`h1-${keyIndex++}`}>{parseInline(line.slice(2))}</h1>);
      continue;
    }

    // Horizontal rule
    if (line.trim() === '---') {
      elements.push(<hr key={`hr-${keyIndex++}`} />);
      continue;
    }

    // Unordered list
    if (line.match(/^\s*[-*+]\s/)) {
      const itemText = line.replace(/^\s*[-*+]\s/, '');
      elements.push(<li key={`li-${keyIndex++}`}>{parseInline(itemText)}</li>);
      continue;
    }

    // Ordered list
    if (line.match(/^\s*\d+\.\s/)) {
      const itemText = line.replace(/^\s*\d+\.\s/, '');
      elements.push(<li key={`oli-${keyIndex++}`} className="ordered-list-item">
        {parseInline(itemText)}
      </li>);
      continue;
    }

    // Blockquote
    if (line.startsWith('> ')) {
      elements.push(
        <blockquote key={`bq-${keyIndex++}`}>
          {parseInline(line.slice(2))}
        </blockquote>
      );
      continue;
    }

    // Empty line
    if (line.trim() === '') {
      continue;
    }

    // Regular paragraph
    elements.push(<p key={`p-${keyIndex++}`}>{parseInline(line)}</p>);
  }

  // Wrap consecutive <li> elements in <ul>
  const wrapped: React.ReactNode[] = [];
  let listItems: React.ReactNode[] = [];
  for (const el of elements) {
    if (React.isValidElement(el)) {
      const props = el.props as { className?: string };
      if (props?.className === 'ordered-list-item') {
        listItems.push(el);
      } else {
        if (listItems.length > 0) {
          wrapped.push(<ul key={`ul-${keyIndex++}`}>{listItems}</ul>);
          listItems = [];
        }
        wrapped.push(el);
      }
    } else {
      wrapped.push(el);
    }
  }
  if (listItems.length > 0) {
    wrapped.push(<ul key={`ul-${keyIndex++}`}>{listItems}</ul>);
  }

  return wrapped.length > 0 ? wrapped : [<p key="empty">No content</p>];
}

/**
 * Parse inline markdown: bold, italic, code, and wiki links.
 */
function parseInline(text: string): React.ReactNode {
  // Split by wiki-style links [[Document]]
  const parts = text.split(/\[\[([^\]]+)\]\]/g);
  const result: React.ReactNode[] = [];

  parts.forEach((part, index) => {
    if (index % 2 === 1) {
      // This is a wiki link target
      result.push(
        <a key={`link-${index}`} href="#" className="wiki-link" data-wiki-target={part}>
          {part}
        </a>
      );
    } else {
      // Regular text - parse bold and italic
      const segments = part.split(/(\*\*[^*]+\*\*|\*[^*]+\*|`[^`]+`)/g);
      segments.forEach((seg, segIndex) => {
        if (seg.startsWith('**') && seg.endsWith('**')) {
          result.push(<strong key={`b-${index}-${segIndex}`}>{seg.slice(2, -2)}</strong>);
        } else if (seg.startsWith('*') && seg.endsWith('*')) {
          result.push(<em key={`i-${index}-${segIndex}`}>{seg.slice(1, -1)}</em>);
        } else if (seg.startsWith('`') && seg.endsWith('`')) {
          result.push(
            <code key={`c-${index}-${segIndex}`}>{seg.slice(1, -1)}</code>
          );
        } else {
          result.push(seg);
        }
      });
    }
  });

  return result;
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

const MarkdownViewer: React.FC<MarkdownViewerProps> = ({
  content,
  wikiLinks: propWikiLinks,
  onLinkClick,
}) => {
  // Extract wiki links from raw markdown if not provided via props
  const wikiLinks = useMemo(
    () => propWikiLinks ?? extractWikiLinks(content),
    [propWikiLinks, content]
  );

  // Parse markdown to React elements (for raw markdown) or render HTML directly
  const renderedContent = useMemo(() => {
    // Check if content looks like pre-rendered HTML (contains HTML tags)
    if (/<[a-z][\s\S]*>/i.test(content) && !content.includes('#')) {
      return <div dangerouslySetInnerHTML={{ __html: content }} />;
    }
    // Otherwise parse as markdown
    return <>{parseMarkdown(content)}</>;
  }, [content]);

  const handleWikiLinkClick = (target: string, e: React.MouseEvent) => {
    e.preventDefault();
    if (onLinkClick) {
      onLinkClick(slugify(target));
    }
  };

  return (
    <div className="markdown-viewer">
      <div className="markdown-content">{renderedContent}</div>

      {/* Clickable wiki links rendered as inline elements */}
      <div
        className="wiki-links-container"
        onClick={(e) => {
          const target = e.target as HTMLElement;
          if (target.classList.contains('wiki-link')) {
            const wikiTarget = target.getAttribute('data-wiki-target');
            if (wikiTarget) {
              handleWikiLinkClick(wikiTarget, e);
            }
          }
        }}
      />

      {/* Links section */}
      {wikiLinks.length > 0 && (
        <div className="links-section">
          <h3>Prepojenia</h3>
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
