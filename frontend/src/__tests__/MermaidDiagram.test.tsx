/**
 * Tests for MermaidDiagram component.
 * Covers: loading state, successful SVG render, error handling, empty input, multiple diagrams.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import MermaidDiagram from '../components/MermaidDiagram';

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

describe('MermaidDiagram', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

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
});
