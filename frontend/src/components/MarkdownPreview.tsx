/**
 * MarkdownPreview component.
 * Enhanced markdown renderer with Mermaid + PlantUML diagram support and GFM (GitHub Flavored Markdown).
 *
 * Uses react-markdown + remark-gfm for full markdown parsing, and integrates
 * the MermaidDiagram component for ```mermaid` code blocks and the PlantUmlDiagram
 * component for ```plantuml` code blocks (rendered via self-hosted kroki, WIKI4AI-63)
 * via custom ReactMarkdown components.
 */

import React from 'react';
import ReactMarkdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import MermaidDiagram from './MermaidDiagram';
import PlantUmlDiagram from './PlantUmlDiagram';
import UploadedImage from './UploadedImage';
import './MarkdownPreview.css';

interface MarkdownPreviewProps {
  /** Raw markdown content to render */
  content?: string;
  /** Optional CSS class names for the root container */
  className?: string;
}

/**
 * Locate the inner <code> element among the children passed to a custom `pre`
 * component by react-markdown.
 *
 * IMPORTANT (WIKI4AI-62): react-markdown v10 calls the custom `pre` component
 * with props whose `children` IS the inner <code> React element itself (not a
 * wrapping <pre>). Older code inspected `.props.children` of that argument —
 * i.e. the plain text string of the code — which never passes
 * React.isValidElement(), so mermaid detection always returned false and
 * diagrams were rendered as raw text.
 *
 * The wrapped case (an element containing a <code> child) is still handled so
 * renderers/mocks that pass an extra level keep working.
 */
function findCodeElement(children: React.ReactNode): React.ReactElement | null {
  const items = Array.isArray(children) ? children : [children];
  for (const item of items) {
    if (!React.isValidElement(item)) continue;

    // Production shape (react-markdown v10): the <code> element is passed directly.
    if (item.type === 'code') return item;

    // Defensive shape: an intermediate element wraps the <code>.
    const inner = (item.props as Record<string, unknown>)?.children;
    if (!inner) continue;
    const innerItems = Array.isArray(inner) ? inner : [inner];
    for (const innerItem of innerItems) {
      if (React.isValidElement(innerItem) && innerItem.type === 'code') return innerItem;
    }
  }
  return null;
}

/**
 * Check whether a <code> element is a mermaid code block.
 * ReactMarkdown adds className="language-mermaid" on the inner <code>.
 */
function isMermaidBlock(codeElement: React.ReactElement | null): boolean {
  if (!codeElement) return false;
  const className = (codeElement.props as Record<string, unknown>)?.className;
  return typeof className === 'string' && className.includes('language-mermaid');
}

/**
 * Check whether a <code> element is a plantuml code block (WIKI4AI-63).
 * ReactMarkdown adds className="language-plantuml" on the inner <code>.
 */
function isPlantUmlBlock(codeElement: React.ReactElement | null): boolean {
  if (!codeElement) return false;
  const className = (codeElement.props as Record<string, unknown>)?.className;
  return typeof className === 'string' && className.includes('language-plantuml');
}

/**
 * Extract the code string from a <code> element.
 */
function extractCode(codeElement: React.ReactElement | null): string {
  if (!codeElement) return '';
  // The code content is in codeElement.props.children
  const codeContent = (codeElement.props as Record<string, unknown>)?.children;
  if (typeof codeContent === 'string') return codeContent;
  if (Array.isArray(codeContent)) return codeContent.join('');
  return '';
}

/**
 * Custom ReactMarkdown components that intercept mermaid and plantuml code
 * blocks, plus uploaded images (WIKI4AI-64).
 */
function buildCustomComponents(): Components {
  return {
    pre({ children, ...rest }) {
      const codeElement = findCodeElement(children);
      if (isMermaidBlock(codeElement)) {
        const code = extractCode(codeElement);
        return <MermaidDiagram code={code} />;
      }
      if (isPlantUmlBlock(codeElement)) {
        const code = extractCode(codeElement);
        return <PlantUmlDiagram code={code} />;
      }
      // Default rendering for other code blocks
      return <pre {...rest}>{children}</pre>;
    },
    /**
     * WIKI4AI-64 (Cesta A): uploaded images are stored at relative URLs of the
     * form /images/{projectSlug}/{uuid}.{ext} and served ONLY to authenticated
     * users. A plain <img src> cannot send the Bearer header, so UploadedImage
     * fetches them through the auth API client and renders via a blob URL.
     * External http(s) URLs pass through unchanged (no behaviour change).
     */
    img(props) {
      return <UploadedImage {...props} />;
    },
  };
}

const MarkdownPreview: React.FC<MarkdownPreviewProps> = ({ content, className }) => {
  const customComponents = buildCustomComponents();

  if (!content) {
    return (
      <div className={`markdown-preview ${className ?? ''}`}>
        <p className="markdown-preview__empty">No content</p>
      </div>
    );
  }

  return (
    <div className={`markdown-preview ${className ?? ''}`}>
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={customComponents}>
        {content}
      </ReactMarkdown>
    </div>
  );
};

export default MarkdownPreview;
