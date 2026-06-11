/**
 * MarkdownEditor — React wrapper around Monaco Editor for professional markdown editing.
 *
 * Features:
 * - Syntax highlighting for markdown
 * - Dark theme styled to match the project's cyan/magenta neon aesthetic
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
