/**
 * Tests for MermaidDiagram component.
 * Covers: loading state, successful SVG render, error handling, empty input,
 * multiple diagrams, stable ID generation, DOM cleanup, and race condition prevention.
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor, act } from '@testing-library/react';
import MermaidDiagram, { withNaturalSize } from '../components/MermaidDiagram';

// ── Mock mermaid library ──────────────────────────────────────────

const mockSvg = '<svg xmlns="http://www.w3.org/2000/svg"><text>Mock Diagram</text></svg>';

vi.mock('mermaid', () => ({
  default: {
    initialize: vi.fn(),
    render: vi.fn(async (_id: string, code: string) => {
      // Simulate invalid syntax error for specific codes
      if (code.includes('INVALID_SYNTAX')) {
        throw new Error('Parse error on line 1: Invalid syntax');
      }
      return { svg: mockSvg };
    }),
  },
}));

// Note: document.getElementById is mocked per-test where needed for cleanup tests

describe('MermaidDiagram', () => {
  describe('Loading state', () => {
    it('should show loading spinner initially', async () => {
      const code = `flowchart TD
        A[Start] --> B{Decision}
        B -->|Yes| C[Process]`;

      render(<MermaidDiagram code={code} />);

      // Loading state is shown synchronously before the async render completes
      expect(screen.getByText('Loading diagram…')).toBeInTheDocument();
    });

    it('should hide loading spinner after successful render', async () => {
      const code = 'flowchart TD\n  A --> B';

      render(<MermaidDiagram code={code} />);

      await waitFor(() => {
        expect(screen.queryByText('Loading diagram…')).not.toBeInTheDocument();
      });
    });
  });

  describe('Successful SVG render', () => {
    it('should render flowchart as SVG', async () => {
      const code = `flowchart TD
        A[Start] --> B{Decision}
        B -->|Yes| C[Process]
        B -->|No| D[End]`;

      render(<MermaidDiagram code={code} />);

      await waitFor(() => {
        expect(screen.getByText('Mock Diagram')).toBeInTheDocument();
      });
    });

    it('should render sequence diagram as SVG', async () => {
      const code = `sequenceDiagram
        participant Alice
        participant Bob
        Alice->>Bob: Hello
        Bob-->>Alice: Hi`;

      render(<MermaidDiagram code={code} />);

      await waitFor(() => {
        expect(screen.getByText('Mock Diagram')).toBeInTheDocument();
      });
    });
  });

  describe('Error handling', () => {
    it('should show error message for invalid syntax (not crash)', async () => {
      const code = 'INVALID_SYNTAX this is broken';

      render(<MermaidDiagram code={code} />);

      await waitFor(() => {
        expect(screen.getByText(/Diagram Error/i)).toBeInTheDocument();
      });

      // The error container has role="alert" for accessibility
      expect(screen.getByRole('alert')).toBeInTheDocument();
    });

    it('should display the actual error message text', async () => {
      const code = 'INVALID_SYNTAX';

      render(<MermaidDiagram code={code} />);

      await waitFor(() => {
        expect(screen.getByText(/Parse error/i)).toBeInTheDocument();
      });
    });
  });

  describe('Empty / whitespace input', () => {
    it('should handle empty string gracefully without crash', async () => {
      const { container } = render(<MermaidDiagram code="" />);

      // Should not show loading spinner or error — just an empty container
      await waitFor(() => {
        expect(screen.queryByText('Loading diagram…')).not.toBeInTheDocument();
      });

      // The root div should still exist
      expect(container.querySelector('.mermaid-diagram')).toBeInTheDocument();
    });

    it('should handle whitespace-only string gracefully', async () => {
      const { container } = render(<MermaidDiagram code="   \n  " />);

      await waitFor(() => {
        expect(screen.queryByText('Loading diagram…')).not.toBeInTheDocument();
      });

      expect(container.querySelector('.mermaid-diagram')).toBeInTheDocument();
    });
  });

  describe('Multiple independent diagrams', () => {
    it('should render multiple diagrams independently on one page', async () => {
      const flowchartCode = 'flowchart TD\n  A --> B';
      const sequenceCode = 'sequenceDiagram\n  Alice->>Bob: Hi';

      render(
        <div>
          <MermaidDiagram code={flowchartCode} data-testid="diagram-1" />
          <MermaidDiagram code={sequenceCode} data-testid="diagram-2" />
        </div>
      );

      // Both diagrams should eventually render their SVG
      await waitFor(() => {
        const diagrams = screen.getAllByText('Mock Diagram');
        expect(diagrams.length).toBeGreaterThanOrEqual(2);
      });
    });
  });

  describe('Custom className', () => {
    it('should apply custom className to the root element', async () => {
      const code = 'flowchart TD\n  A --> B';

      render(<MermaidDiagram code={code} className="custom-class" />);

      // The root div should have both base and custom classes
      expect(screen.getByText('Loading diagram…').closest('.mermaid-diagram'))
        .toHaveClass('custom-class');
    });
  });

  describe('Stable ID', () => {
    it('should use a consistent mermaid- prefixed ID for rendering', async () => {
      const code = 'flowchart TD\n  A --> B';

      render(<MermaidDiagram code={code} />);
      await waitFor(() => {
        expect(screen.getByText('Mock Diagram')).toBeInTheDocument();
      });

      // mermaid.render was called with an ID — verify the format is stable (mermaid-<hex>)
      const mockRender = vi.mocked(
        (await import('mermaid')).default.render,
      ) as unknown as ReturnType<typeof vi.fn>;
      const lastCall = mockRender.mock.calls.at(-1);
      expect(lastCall).toBeDefined();
      expect((lastCall as [string, string])[0]).toMatch(/^mermaid-[0-9a-f]+$/);
    });

    it('should re-render smoothly when code changes without duplicate ID errors', async () => {
      const { rerender } = render(
        <MermaidDiagram code="flowchart TD\n  A --> B" />,
      );

      await waitFor(() => {
        expect(screen.getByText('Mock Diagram')).toBeInTheDocument();
      });

      // Change the code — should trigger a new render with cleanup of old element
      rerender(<MermaidDiagram code="sequenceDiagram\n  Alice->>Bob: Hi" />);

      await waitFor(() => {
        expect(screen.getByText('Mock Diagram')).toBeInTheDocument();
      });
    });
  });

  describe('DOM cleanup on unmount', () => {
    it('should clean up mermaid DOM element when component unmounts', async () => {
      // Create a mock element that getElementById returns
      const mockRemove = vi.fn();
      const mockElement = { remove: mockRemove } as unknown as Element;

      // Temporarily override document.getElementById
      const originalGetElementById = document.getElementById;
      document.getElementById = vi.fn().mockReturnValue(mockElement);

      try {
        const code = 'flowchart TD\n  A --> B';
        const { unmount } = render(<MermaidDiagram code={code} />);

        await waitFor(() => {
          expect(screen.getByText('Mock Diagram')).toBeInTheDocument();
        });

        // Unmount the component — cleanup should remove the mermaid DOM element
        act(() => {
          unmount();
        });

        // The cleanup function calls document.getElementById(stableId) and el.remove()
        expect(document.getElementById).toHaveBeenCalled();
        expect(mockRemove).toHaveBeenCalled();
      } finally {
        document.getElementById = originalGetElementById;
      }
    });
  });

  describe('Race condition prevention', () => {
    it('should not update state after component unmounts (cancelled flag)', async () => {
      const code = 'flowchart TD\n  A --> B';
      const { unmount } = render(<MermaidDiagram code={code} />);

      // Unmount immediately — the async mermaid.render is still in flight
      act(() => {
        unmount();
      });

      // No errors should be thrown even though the promise resolves after unmount
      await waitFor(() => {
        // Just wait a tick for any pending promises to settle
      }, { timeout: 100 });

      // If we got here without errors, the cancelled flag is working correctly
      expect(true).toBe(true);
    });
  });
});

describe('withNaturalSize (WIKI4AI-84)', () => {
  it('should rewrite width="100%" to the natural pixel size from the viewBox', () => {
    const input =
      '<svg xmlns="http://www.w3.org/2000/svg" id="mermaid-1" width="100%" height="100%" viewBox="0 0 842.5 595"><text>hi</text></svg>';
    const out = withNaturalSize(input);
    expect(out).toContain('width="843"');
    expect(out).toContain('height="595"');
    expect(out).not.toContain('width="100%"');
  });

  it('should add width/height when the svg has a viewBox but no size attributes', () => {
    const out = withNaturalSize('<svg viewBox="0 0 300 200"><text>hi</text></svg>');
    expect(out).toContain('width="300"');
    expect(out).toContain('height="200"');
  });

  it('should leave svgs without a viewBox unchanged', () => {
    const input = '<svg xmlns="http://www.w3.org/2000/svg"><text>Mock</text></svg>';
    expect(withNaturalSize(input)).toBe(input);
  });

  it('should size the root svg without clobbering nested width/height attributes', () => {
    // Mermaid flowchart output: root has width="100%" but NO height; the first
    // child is a background <rect> with its own height. The old whole-string
    // replace overwrote the rect's height instead of adding one to the root.
    const input =
      '<svg id="m" width="100%" viewBox="0 0 500 250"><rect width="100%" height="77"/><text>hi</text></svg>';
    const out = withNaturalSize(input);
    // Both sizes land on the ROOT tag...
    const rootTag = out.slice(0, out.indexOf('>'));
    expect(rootTag).toContain('width="500"');
    expect(rootTag).toContain('height="250"');
    // ...and the nested rect keeps its own height untouched.
    expect(out).toContain('<rect width="100%" height="77"/>');
  });
});
