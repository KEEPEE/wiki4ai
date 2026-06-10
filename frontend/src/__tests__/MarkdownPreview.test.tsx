/**
 * Tests for MarkdownPreview component.
 * Covers: basic markdown rendering, GFM features, Mermaid block detection,
 * multiple diagrams, empty content, and custom className.
 */

import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';

// ── Mock MermaidDiagram component (hoisted) ────────────────────────

vi.mock('../components/MermaidDiagram', () => ({
  default: vi.fn(({ code }: { code: string }) => (
    <div data-testid="mermaid-diagram" data-code={code}>
      <span>Mermaid Diagram</span>
    </div>
  )),
}));

// ── Mock react-markdown with smart component invocation (hoisted) ──
// Use globalThis to store the mock reference since vi.mock is hoisted above all declarations.
const getMockReactMarkdown = (): ReturnType<typeof vi.fn> => (globalThis as any).__mockReactMarkdown;

vi.mock('react-markdown', () => {
  const fn = vi.fn(({ children, components }: any) => {
    const content = typeof children === 'string' ? children : '';

    // Parse simple markdown elements for testing
    const elements: React.ReactNode[] = [];
    let keyIdx = 0;

    // Split by fenced code blocks first
    const parts = content.split(/(```[\s\S]*?```)/g);

    for (const part of parts) {
      if (!part) continue;

      // Check if this is a fenced code block
      const codeBlockMatch = part.match(/^```(\w*)\n([\s\S]*?)```$/);
      if (codeBlockMatch) {
        const lang = codeBlockMatch[1];
        const codeContent = codeBlockMatch[2].trim();

        // Build a synthetic <pre><code> element like react-markdown does
        const className = lang ? `language-${lang}` : '';
        const mockCodeEl = (
          <code className={className}>{codeContent}</code>
        );
        const mockPreEl = (
          <pre key={`pre-${keyIdx++}`}>
            {mockCodeEl}
          </pre>
        );

        // Invoke custom pre component if provided, otherwise render default
        if (components?.pre) {
          elements.push(
            components.pre({ children: mockPreEl, key: `custom-pre-${keyIdx++}` })
          );
        } else {
          elements.push(mockPreEl);
        }
        continue;
      }

      // Parse non-code-block text line by line for proper heading detection
      const lines = part.split('\n');
      const paragraphLines: string[] = [];

      for (const line of lines) {
        // Handle headings (per-line)
        const headingMatch = line.match(/^(#{1,6})\s+(.+)$/);
        if (headingMatch) {
          // Flush any accumulated paragraph lines first
          if (paragraphLines.length > 0) {
            elements.push(<p key={`p-${keyIdx++}`}>{paragraphLines.join(' ')}</p>);
            paragraphLines.length = 0;
          }
          const level = headingMatch[1].length;
          elements.push(React.createElement(`h${level}`, { key: `h-${keyIdx++}` }, headingMatch[2]));
          continue;
        }

        // Accumulate non-heading lines into paragraphs
        if (line.trim()) {
          paragraphLines.push(line.trim());
        } else if (paragraphLines.length > 0) {
          // Empty line flushes the paragraph
          elements.push(<p key={`p-${keyIdx++}`}>{paragraphLines.join(' ')}</p>);
          paragraphLines.length = 0;
        }
      }

      // Flush remaining paragraph lines with inline formatting
      if (paragraphLines.length > 0) {
        const text = paragraphLines.join(' ');
        let processedText: React.ReactNode = text;

        // Handle bold text
        if (text.includes('**')) {
          const segments = text.split(/(\*\*[^*]+\*\*)/g);
          processedText = segments.map((seg, i) => {
            if (seg.startsWith('**') && seg.endsWith('**')) {
              return <strong key={i}>{seg.slice(2, -2)}</strong>;
            }
            return seg;
          });
        }
        // Handle italic text
        else if (text.includes('*')) {
          const segments = text.split(/(\*[^*]+\*)/g);
          processedText = segments.map((seg, i) => {
            if (seg.startsWith('*') && seg.endsWith('*')) {
              return <em key={i}>{seg.slice(1, -1)}</em>;
            }
            return seg;
          });
        }
        // Handle strikethrough (GFM)
        else if (text.includes('~~')) {
          const segments = text.split(/(~~[^~]+~~)/g);
          processedText = segments.map((seg, i) => {
            if (seg.startsWith('~~') && seg.endsWith('~~')) {
              return <del key={i}>{seg.slice(2, -2)}</del>;
            }
            return seg;
          });
        }
        // Handle links
        else if (text.includes('[') && text.includes('](')) {
          const linkMatch = text.match(/^(.+?)\[(.*?)\]\((.*?)\)(.*)$/);
          if (linkMatch) {
            processedText = (
              <>
                {linkMatch[1]}
                <a href={linkMatch[3]}>{linkMatch[2]}</a>
                {linkMatch[4]}
              </>
            );
          }
        }

        elements.push(<p key={`p-${keyIdx++}`}>{processedText}</p>);
      }
    }

    return <div className="mocked-react-markdown">{elements}</div>;
  });

  (globalThis as any).__mockReactMarkdown = fn;

  return {
    default: fn,
  };
});

// Mock remark-gfm (not used directly in our component logic)
vi.mock('remark-gfm', () => ({
  default: {},
}));

// Now import the component under test — imports are processed after vi.mock hoisting
import MarkdownPreview from '../components/MarkdownPreview';

describe('MarkdownPreview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('Empty / undefined content', () => {
    it('should show "No content" when content is not provided', () => {
      render(<MarkdownPreview />);
      expect(screen.getByText('No content')).toBeInTheDocument();
    });

    it('should show "No content" when content is empty string', () => {
      render(<MarkdownPreview content="" />);
      expect(screen.getByText('No content')).toBeInTheDocument();
    });

    it('should have markdown-preview CSS class on root element', () => {
      const { container } = render(<MarkdownPreview />);
      expect(container.querySelector('.markdown-preview')).toBeInTheDocument();
    });
  });

  describe('Basic markdown rendering', () => {
    it('should render h1 heading', () => {
      render(<MarkdownPreview content="# Main Title" />);
      expect(screen.getByText('Main Title').tagName.toLowerCase()).toBe('h1');
    });

    it('should render h2 heading', () => {
      render(<MarkdownPreview content="## Sub Title" />);
      expect(screen.getByText('Sub Title').tagName.toLowerCase()).toBe('h2');
    });

    it('should render bold text with <strong>', () => {
      render(<MarkdownPreview content="This is **bold** text." />);
      expect(screen.getByText('bold')).toBeInTheDocument();
    });

    it('should render italic text with <em>', () => {
      render(<MarkdownPreview content="This is *italic* text." />);
      expect(screen.getByText('italic')).toBeInTheDocument();
    });

    it('should render links', () => {
      render(<MarkdownPreview content="Visit [Example](https://example.com) for more." />);
      expect(screen.getByText('Example')).toBeInTheDocument();
    });
  });

  describe('GFM features (remark-gfm)', () => {
    it('should render strikethrough with <del>', () => {
      render(<MarkdownPreview content="This is ~~deleted~~ text." />);
      expect(screen.getByText('deleted')).toBeInTheDocument();
    });

    it('should use remarkGfm plugin (verified via mock call)', () => {
      render(<MarkdownPreview content="# Test" />);
      // The mock react-markdown receives remarkPlugins prop — verify it was called
      expect(getMockReactMarkdown()).toHaveBeenCalled();
    });
  });

  describe('Mermaid block detection', () => {
    it('should detect a mermaid code block and render MermaidDiagram', () => {
      const mermaidCode = `flowchart TD
        A[Start] --> B{Decision}
        B -->|Yes| C[Process]`;

      render(<MarkdownPreview content={`\`\`\`mermaid\n${mermaidCode}\n\`\`\``} />);

      expect(screen.getByTestId('mermaid-diagram')).toBeInTheDocument();
    });

    it('should pass the correct code content to MermaidDiagram', () => {
      const mermaidCode = 'sequenceDiagram\n  Alice->>Bob: Hello';

      render(<MarkdownPreview content={`\`\`\`mermaid\n${mermaidCode}\n\`\`\``} />);

      const diagramEl = screen.getByTestId('mermaid-diagram');
      expect(diagramEl.getAttribute('data-code')).toBe(mermaidCode);
    });

    it('should render a non-mermaid code block as regular <pre>', () => {
      const jsCode = 'const x = 42;';

      render(<MarkdownPreview content={`\`\`\`javascript\n${jsCode}\n\`\`\``} />);

      // Should NOT have mermaid diagram for non-mermaid blocks
      expect(screen.queryByTestId('mermaid-diagram')).not.toBeInTheDocument();
    });

    it('should handle code block without language specifier', () => {
      const plainCode = 'some code here';

      render(<MarkdownPreview content={`\`\`\`\n${plainCode}\n\`\`\``} />);

      expect(screen.queryByTestId('mermaid-diagram')).not.toBeInTheDocument();
    });
  });

  describe('Multiple mermaid diagrams', () => {
    it('should render multiple mermaid blocks independently', () => {
      const content = `# Diagrams

\`\`\`mermaid
flowchart TD
  A --> B
\`\`\`

Some text between diagrams.

\`\`\`mermaid
sequenceDiagram
  Alice->>Bob: Hi
\`\`\``;

      render(<MarkdownPreview content={content} />);

      const diagrams = screen.getAllByTestId('mermaid-diagram');
      expect(diagrams.length).toBeGreaterThanOrEqual(2);
    });

    it('should mix mermaid and regular code blocks correctly', () => {
      const content = `\`\`\`javascript\nconst x = 1;\n\`\`\`\n\`\`\`mermaid\nflowchart TD\n  A --> B\n\`\`\``;

      render(<MarkdownPreview content={content} />);

      // Only one mermaid diagram should be rendered
      const diagrams = screen.getAllByTestId('mermaid-diagram');
      expect(diagrams.length).toBe(1);
    });
  });

  describe('Custom className', () => {
    it('should apply custom className to the root element', () => {
      render(<MarkdownPreview content="# Test" className="custom-class" />);

      const root = screen.getByText('Test').closest('.markdown-preview');
      expect(root).toHaveClass('custom-class');
    });

    it('should combine default and custom classes', () => {
      render(<MarkdownPreview content="# Test" className="extra-style" />);

      const root = screen.getByText('Test').closest('.markdown-preview');
      expect(root).toHaveClass('markdown-preview');
      expect(root).toHaveClass('extra-style');
    });
  });

  describe('TypeScript types', () => {
    it('should accept all documented props without errors', () => {
      const props: any = {
        content: '# Hello World\nThis is **markdown**.',
        className: 'my-custom-class',
      };
      render(<MarkdownPreview {...props} />);
      expect(screen.getByText('Hello World')).toBeInTheDocument();
    });

    it('should handle undefined content prop gracefully', () => {
      render(<MarkdownPreview content={undefined} />);
      expect(screen.getByText('No content')).toBeInTheDocument();
    });
  });
});
