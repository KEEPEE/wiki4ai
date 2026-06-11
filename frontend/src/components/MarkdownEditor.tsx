/**
 * MarkdownEditor — React wrapper around Monaco Editor for professional markdown editing.
 *
 * Features:
 * - Syntax highlighting for markdown
 * - Dark theme styled to match the project's cyan/magenta neon aesthetic
 * - Refined color palette: white line numbers (30%), cyan cursor, subtle selection highlight
 * - Configurable height, read-only mode, and change callbacks
 * - Uses ResizeObserver for reliable container height measurement
 * - MarkdownToolbar with formatting buttons above the editor
 */

import React, { useRef, useEffect, useState, useCallback } from 'react';
import Editor from '@monaco-editor/react';
import type { editor } from 'monaco-editor';
import * as monaco from 'monaco-editor';
import MarkdownToolbar from './MarkdownToolbar';
import './MarkdownEditor.css';

export interface MarkdownEditorProps {
  /** Current editor content value */
  value?: string;
  /** Callback fired when the editor content changes */
  onChange?: (value: string | undefined) => void;
  /** Whether the editor is read-only. Default: false */
  readOnly?: boolean;
}

/**
 * Selection range for placing cursor after insertion.
 * { startOffset, endOffset } are character offsets within the inserted text
 * where the cursor should be positioned (or a selection made).
 */
interface InsertSelection {
  startOffset: number;
  endOffset: number;
}

/**
 * Define the "wiki4ai-dark" Monaco theme with refined colors.
 * Called on editor mount to register the custom theme.
 *
 * Color palette:
 * - Background: #12121f (deep dark matching --dark-2)
 * - Foreground: rgba(255, 255, 255, 0.7) soft white text
 * - Line numbers: rgba(255, 255, 255, 0.3) subtle white (not cyan)
 * - Cursor: #00f0ff bright cyan
 * - Selection: rgba(0, 240, 255, 0.15) gentle cyan highlight
 * - Find widget: cyan-themed background and text
 * - Scrollbar: consistent cyan tones at low opacity
 * - Current line: rgba(0, 240, 255, 0.08) subtle glow
 */
function defineWiki4aiTheme() {
  monaco.editor.defineTheme('wiki4ai-dark', {
    base: 'vs-dark',
    inherit: true,
    rules: [
      { token: '', foreground: 'FFFFFF', background: '12121F' },
    ],
    colors: {
      'editor.background': '#12121f',
      'editor.foreground': '#ffffff',
      'editor.lineNumberForeground': '#666666',
      'editorCursor.foreground': '#00f0ff',
      'editor.selectionBackground': 'rgba(0, 240, 255, 0.2)',
      'editor.findWidget.background': 'rgba(0, 240, 255, 0.1)',
      'editor.findWidget.foreground': '#00f0ff',
      'scrollbarSlider.background': 'rgba(0, 240, 255, 0.12)',
      'scrollbarSlider.hoverBackground': 'rgba(0, 240, 255, 0.25)',
      'editor.lineHighlightBackground': 'rgba(0, 240, 255, 0.08)',
    },
  });
}

const MarkdownEditor: React.FC<MarkdownEditorProps> = ({
  value = '',
  onChange,
  readOnly = false,
}) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const editorRef = useRef<editor.IStandaloneCodeEditor | null>(null);
  const [editorHeight, setEditorHeight] = useState<number>(600);

  // Use ResizeObserver to measure the actual container height in pixels.
  // This is more reliable than CSS percentage heights because Monaco Editor
  // internally creates an iframe that needs explicit pixel dimensions.
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    // Initial measurement
    const updateHeight = () => {
      const newHeight = container.clientHeight;
      if (newHeight > 0) {
        setEditorHeight(newHeight);
      }
    };
    updateHeight();

    // Watch for size changes
    const observer = new ResizeObserver(updateHeight);
    observer.observe(container);

    return () => {
      observer.disconnect();
    };
  }, []);

  // Force Monaco to relayout after height changes
  useEffect(() => {
    if (editorRef.current) {
      editorRef.current.layout();
    }
  }, [editorHeight]);

  const handleEditorChange = (newValue: string | undefined) => {
    if (onChange) {
      onChange(newValue);
    }
  };

  const handleEditorMount = useCallback((ed: editor.IStandaloneCodeEditor) => {
    editorRef.current = ed;

    // Register the custom wiki4ai-dark theme on first mount.
    // Guard against mock environments (tests) where defineTheme may not exist.
    if (typeof monaco.editor.defineTheme === 'function') {
      try {
        defineWiki4aiTheme();
      } catch {
        // Theme already defined or definition failed — non-fatal in test env
      }
    }

    // Force initial layout after mount
    requestAnimationFrame(() => {
      ed.layout();
    });
  }, []);

  /**
   * Insert text at the current cursor position in Monaco editor.
   * Optionally places the cursor (or selection) within the inserted text.
   */
  const handleInsert = useCallback((text: string, selection?: InsertSelection) => {
    const ed = editorRef.current;
    if (!ed) return;

    const model = ed.getModel();
    if (!model) return;

    const position = ed.getPosition();
    if (!position) return;

    // Use the editor's executeEdits API for proper undo/redo support
    const range = new monaco.Range(
      position.lineNumber,
      position.column,
      position.lineNumber,
      position.column,
    );

    ed.executeEdits('markdown-toolbar-insert', [
      {
        range,
        text: text,
      },
    ]);

    // If a selection was specified, place cursor/selection within the inserted text
    if (selection) {
      const startCol = position.column + selection.startOffset;
      const endCol = position.column + selection.endOffset;
      ed.setPosition({ lineNumber: position.lineNumber, column: startCol });
      if (startCol !== endCol) {
        const selRange = new monaco.Range(
          position.lineNumber,
          startCol,
          position.lineNumber,
          endCol,
        );
        ed.setSelection(selRange);
      }
    }

    // Focus the editor after insertion so user can continue typing immediately
    ed.focus();
  }, []);

  return (
    <div className="markdown-editor" ref={containerRef}>
      {/* Formatting toolbar above the Monaco editor */}
      {!readOnly && (
        <MarkdownToolbar onInsert={handleInsert} />
      )}
      <Editor
        language="markdown"
        value={value}
        onChange={handleEditorChange}
        onMount={handleEditorMount}
        height={editorHeight}
        theme="wiki4ai-dark"
        options={{
          minimap: { enabled: false },
          lineNumbers: 'on',
          wordWrap: 'on',
          fontSize: 14,
          scrollBeyondLastLine: false,
          automaticLayout: true,
          tabSize: 2,
          insertSpaces: true,
          readOnly,
        }}
      />
    </div>
  );
};

export default MarkdownEditor;
