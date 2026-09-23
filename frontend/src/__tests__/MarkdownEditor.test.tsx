/**
 * Tests for MarkdownEditor component.
 *
 * Monaco Editor is mocked because it requires a real browser environment
 * (Web Workers, iframes) that jsdom cannot provide. The @monaco-editor/react
 * `loader` export is also mocked: the component gates rendering on
 * loader.init() so the wiki4ai-dark theme is registered on the same Monaco
 * instance the Editor loads (WIKI4AI-77).
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import MarkdownEditor from '../components/MarkdownEditor';

// Mock @monaco-editor/react — the real Editor requires Web Workers + iframes
vi.mock('@monaco-editor/react', () => ({
  default: vi.fn(({ language, value, onChange, theme, options, height, onMount }: any) => {
    // readOnly is passed via options.readOnly in Monaco Editor API
    const isReadOnly = options?.readOnly ?? false;

    // Simulate onMount callback being called with a mock editor
    if (onMount) {
      onMount({
        layout: vi.fn(),
        getValue: () => value,
        getPosition: () => ({ lineNumber: 1, column: 1 }),
        getModel: () => ({ getValue: () => value }),
        executeEdits: vi.fn(),
        setPosition: vi.fn(),
        setSelection: vi.fn(),
        focus: vi.fn(),
      });
    }

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
  loader: {
    // Resolves with a fake monaco namespace; the component registers
    // wiki4ai-dark on it before rendering the Editor.
    init: vi.fn(() => Promise.resolve({ editor: { defineTheme: vi.fn() } })),
  },
}));

// Mock monaco-editor direct import (used by MarkdownEditor for Range class)
vi.mock('monaco-editor', () => ({
  Range: vi.fn((startLine, startCol, endLine, endCol) => ({
    startLineNumber: startLine,
    startColumn: startCol,
    endLineNumber: endLine,
    endColumn: endCol,
  })),
  editor: {
    IStandaloneCodeEditor: {},
  },
}));

describe('MarkdownEditor', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('should render without errors', async () => {
    const { container } = render(<MarkdownEditor />);
    expect(container).toBeInTheDocument();
    expect(await screen.findByTestId('monaco-editor')).toBeInTheDocument();
  });

  it('should use markdown as the default language', async () => {
    render(<MarkdownEditor />);
    expect(await screen.findByTestId('editor-language')).toHaveTextContent('markdown');
  });

  it('should render with default empty value', async () => {
    render(<MarkdownEditor />);
    expect(await screen.findByTestId('editor-value')).toHaveTextContent('');
  });

  it('should display the provided value prop', async () => {
    const testContent = '# Hello World\nThis is **markdown** content.';
    render(<MarkdownEditor value={testContent} />);
    // HTML text content normalizes whitespace, so newlines become spaces
    expect(await screen.findByTestId('editor-value')).toHaveTextContent('# Hello World');
    expect(screen.getByTestId('editor-value')).toHaveTextContent('This is **markdown** content.');
  });

  it('should use "wiki4ai-dark" theme', async () => {
    render(<MarkdownEditor />);
    expect(await screen.findByTestId('editor-theme')).toHaveTextContent('wiki4ai-dark');
  });

  it('should be editable by default (readOnly=false)', async () => {
    render(<MarkdownEditor />);
    expect(await screen.findByTestId('editor-read-only')).toHaveTextContent('false');
  });

  it('should support readOnly mode', async () => {
    render(<MarkdownEditor value="some content" readOnly={true} />);
    expect(await screen.findByTestId('editor-read-only')).toHaveTextContent('true');
  });

  it('should call onChange callback when content changes', async () => {
    const handleChange = vi.fn();
    render(<MarkdownEditor value="initial" onChange={handleChange} />);

    // Wait for the Monaco ready gate, then simulate editor change via our mock button
    await screen.findByTestId('monaco-editor');
    const triggerButton = screen.getByTestId('trigger-change');
    triggerButton.click();

    expect(handleChange).toHaveBeenCalledWith('mocked new content');
  });

  it('should not call onChange if no handler is provided', async () => {
    render(<MarkdownEditor value="initial" />);
    // Should not throw even without onChange
    expect(await screen.findByTestId('monaco-editor')).toBeInTheDocument();
  });

  it('should pass a numeric pixel height to Monaco Editor (from ResizeObserver)', async () => {
    render(<MarkdownEditor />);
    // The component uses ResizeObserver to measure container height.
    // In jsdom, clientHeight returns 0, so the default fallback of 600 is used.
    const heightValue = (await screen.findByTestId('editor-height')).textContent;
    expect(heightValue).toMatch(/^\d+$/); // Should be a numeric pixel value
  });

  it('should have markdown-editor CSS class on wrapper', async () => {
    render(<MarkdownEditor />);
    const wrapper = (await screen.findByTestId('monaco-editor')).parentElement;
    expect(wrapper).toHaveClass('markdown-editor');
  });

  it('should have proper TypeScript interface (compile-time check)', async () => {
    // This test verifies the component accepts all documented props without errors
    const props: any = {
      value: 'test',
      onChange: () => {},
      readOnly: false,
    };
    render(<MarkdownEditor {...props} />);
    expect(await screen.findByTestId('monaco-editor')).toBeInTheDocument();
  });

  it('should handle undefined value gracefully', async () => {
    render(<MarkdownEditor value={undefined} />);
    expect(await screen.findByTestId('editor-value')).toHaveTextContent('');
  });

  it('should support all optional props being omitted', async () => {
    render(<MarkdownEditor />);
    // All defaults should be applied without errors
    expect(await screen.findByTestId('monaco-editor')).toBeInTheDocument();
    expect(screen.getByTestId('editor-language')).toHaveTextContent('markdown');
    expect(screen.getByTestId('editor-theme')).toHaveTextContent('wiki4ai-dark');
  });
});
