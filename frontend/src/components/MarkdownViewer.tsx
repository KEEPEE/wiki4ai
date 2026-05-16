/**
 * Markdown Viewer component.
 * Renders markdown content with support for wiki-style links [[Document]].
 */

import React, { useMemo } from 'react';
import './MarkdownViewer.css';

interface MarkdownViewerProps {
  content: string;
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

    // Empty line
    if (line.trim() === '') {
      continue;
    }

    // Regular paragraph
    elements.push(<p key={`p-${keyIndex++}`}>{parseInline(line)}</p>);
  }

  return elements.length > 0 ? elements : [<p key="empty">No content</p>];
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
        <a key={`link-${index}`} href={`/wiki/${part}`} className="wiki-link">
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

const MarkdownViewer: React.FC<MarkdownViewerProps> = ({ content }) => {
  const renderedContent = useMemo(() => parseMarkdown(content), [content]);

  return <div className="markdown-viewer">{renderedContent}</div>;
};

export default MarkdownViewer;
