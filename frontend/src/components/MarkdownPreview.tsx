/**
 * MarkdownPreview component.
 * Enhanced markdown renderer with Mermaid diagram support and GFM (GitHub Flavored Markdown).
 *
 * Uses react-markdown + remark-gfm for full markdown parsing, and integrates
 * the MermaidDiagram component for ```mermaid` code blocks via custom ReactMarkdown components.
 */

import React from 'react';
import ReactMarkdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import MermaidDiagram from './MermaidDiagram';
import './MarkdownPreview.css';

interface MarkdownPreviewProps {
  /** Raw markdown content to render */
  content?: string;
  /** Optional CSS class names for the root container */
  className?: string;
}

/**
 * Check if a <pre> element contains a mermaid code block.
 * ReactMarkdown adds className="language-mermaid" on the inner <code>.
 */
function isMermaidBlock(preElement: React.ReactElement): boolean {
  const children = (preElement.props as Record<string, unknown>)?.children;
  if (!children) return false;

  // children can be a single element or an array
  const items = Array.isArray(children) ? children : [children];
  for (const child of items) {
    if (
      React.isValidElement(child) &&
      child.type === 'code' &&
      typeof (child.props as Record<string, unknown>)?.className === 'string' &&
      ((child.props as Record<string, unknown>).className as string).includes('language-mermaid')
    ) {
      return true;
    }
  }
  return false;
}

/**
 * Extract the mermaid code string from a <pre><code> element.
 */
function extractMermaidCode(preElement: React.ReactElement): string {
  const children = (preElement.props as Record<string, unknown>)?.children;
  if (!children) return '';

  const items = Array.isArray(children) ? children : [children];
  for (const child of items) {
    if (React.isValidElement(child) && child.type === 'code') {
      // The code content is in child.props.children
      const codeContent = (child.props as Record<string, unknown>)?.children;
      if (typeof codeContent === 'string') return codeContent;
      if (Array.isArray(codeContent)) return codeContent.join('');
    }
  }
  return '';
}

/**
 * Custom ReactMarkdown components that intercept mermaid code blocks.
 */
function buildCustomComponents(): Components {
  return {
    pre({ children, ...rest }) {
      const preElement = children as React.ReactElement;
      if (isMermaidBlock(preElement)) {
        const code = extractMermaidCode(preElement);
        return <MermaidDiagram code={code} />;
      }
      // Default rendering for non-mermaid code blocks
      return <pre {...rest}>{children}</pre>;
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
