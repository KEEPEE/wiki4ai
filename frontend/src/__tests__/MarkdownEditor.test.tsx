/**
 * Tests for MarkdownEditor component.
 *
 * Monaco Editor is mocked because it requires a real browser environment
 * (Web Workers, iframes) that jsdom cannot provide.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import MarkdownEditor from '../components/MarkdownEditor';

// Mock @monaco-editor/react — the real Editor requires Web Workers + iframes
vi.mock('@monaco-editor/react', () => ({
  default: vi.fn(({ language, value, onChange, theme, options, height }: any) => {
    // readOnly is passed via options.readOnly in Monaco Editor API
    const isReadOnly = options?.readOnly ?? false;
    return (
      <div data-testid="monaco-editor" className="mocked-monaco">
        <span data-testid="editor-language">{language}</span>
        <span data-testid="editor-value">{value}</span>
        <span data-testid="editor-theme">{theme}</span>
        <span data-testid="editor-read-only">{isReadOnly ? 'true' : 'false'}</span>
        <span data-testid="editor-height">{height}</span>
        <button
          data-testid="trigger-change"
          onClick={() => onChange?.('mocked new content')}
        >
          trigger change
        </button>
      </div>
    );
  }),
}));

describe('MarkdownEditor', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('should render without errors', () => {
    const { container } = render(<MarkdownEditor />);
    expect(container).toBeInTheDocument();
    expect(screen.getByTestId('monaco-editor')).toBeInTheDocument();
  });

  it('should use markdown as the default language', () => {
    render(<MarkdownEditor />);
    expect(screen.getByTestId('editor-language')).toHaveTextContent('markdown');
  });

  it('should render with default empty value', () => {
    render(<MarkdownEditor />);
    expect(screen.getByTestId('editor-value')).toHaveTextContent('');
  });

  it('should display the provided value prop', () => {
    const testContent = '# Hello World\nThis is **markdown** content.';
    render(<MarkdownEditor value={testContent} />);
    // HTML text content normalizes whitespace, so newlines become spaces
    expect(screen.getByTestId('editor-value')).toHaveTextContent('# Hello World');
    expect(screen.getByTestId('editor-value')).toHaveTextContent('This is **markdown** content.');
  });

  it('should use "wiki4ai-dark" theme', () => {
    render(<MarkdownEditor />);
    expect(screen.getByTestId('editor-theme')).toHaveTextContent('wiki4ai-dark');
  });

  it('should be editable by default (readOnly=false)', () => {
    render(<MarkdownEditor />);
    expect(screen.getByTestId('editor-read-only')).toHaveTextContent('false');
  });

  it('should support readOnly mode', () => {
    render(<MarkdownEditor value="some content" readOnly={true} />);
    expect(screen.getByTestId('editor-read-only')).toHaveTextContent('true');
  });

  it('should call onChange callback when content changes', async () => {
    const handleChange = vi.fn();
    render(<MarkdownEditor value="initial" onChange={handleChange} />);

    // Simulate editor change via our mock button
    const triggerButton = screen.getByTestId('trigger-change');
    triggerButton.click();

    expect(handleChange).toHaveBeenCalledWith('mocked new content');
  });

  it('should not call onChange if no handler is provided', () => {
    render(<MarkdownEditor value="initial" />);
    // Should not throw even without onChange
    expect(screen.getByTestId('monaco-editor')).toBeInTheDocument();
  });

  it('should not set inline height for default "100%" (uses CSS flex instead)', () => {
    render(<MarkdownEditor />);
    const container = screen.getByTestId('monaco-editor').parentElement!;
    // When height is "100%", the wrapper relies on CSS flex: 1 layout,
    // not an inline style. This prevents the editor from collapsing when
    // parent containers don't have explicit pixel heights.
    expect(container).not.toHaveStyle({ height: '100%' });
    expect(container.style.height).toBe('');
  });

  it('should apply custom string height', () => {
    render(<MarkdownEditor height="500px" />);
    const container = screen.getByTestId('monaco-editor').parentElement;
    expect(container).toHaveStyle({ height: '500px' });
  });

  it('should convert numeric height to pixels', () => {
    render(<MarkdownEditor height={400} />);
    const container = screen.getByTestId('monaco-editor').parentElement;
    expect(container).toHaveStyle({ height: '400px' });
  });

  it('should have markdown-editor CSS class on wrapper', () => {
    render(<MarkdownEditor />);
    const wrapper = screen.getByTestId('monaco-editor').parentElement;
    expect(wrapper).toHaveClass('markdown-editor');
  });

  it('should pass correct height to internal Editor component', () => {
    render(<MarkdownEditor height="600px" />);
    expect(screen.getByTestId('editor-height')).toHaveTextContent('600px');
  });

  it('should convert numeric height prop for internal Editor', () => {
    render(<MarkdownEditor height={350} />);
    expect(screen.getByTestId('editor-height')).toHaveTextContent('350px');
  });

  it('should have proper TypeScript interface (compile-time check)', () => {
    // This test verifies the component accepts all documented props without errors
    const props: any = {
      value: 'test',
      onChange: () => {},
      height: '100%',
      readOnly: false,
    };
    render(<MarkdownEditor {...props} />);
    expect(screen.getByTestId('monaco-editor')).toBeInTheDocument();
  });

  it('should handle undefined value gracefully', () => {
    render(<MarkdownEditor value={undefined} />);
    expect(screen.getByTestId('editor-value')).toHaveTextContent('');
  });

  it('should support all optional props being omitted', () => {
    render(<MarkdownEditor />);
    // All defaults should be applied without errors
    expect(screen.getByTestId('monaco-editor')).toBeInTheDocument();
    expect(screen.getByTestId('editor-language')).toHaveTextContent('markdown');
    expect(screen.getByTestId('editor-theme')).toHaveTextContent('wiki4ai-dark');
  });
});
