/**
 * MarkdownEditor — React wrapper around Monaco Editor for professional markdown editing.
 *
 * Features:
 * - Syntax highlighting for markdown
 * - Dark theme styled to match the project's cyan/magenta neon aesthetic
 * - Configurable height, read-only mode, and change callbacks
 * - Uses ResizeObserver for reliable container height measurement
 */

import React, { useRef, useEffect, useState, useCallback } from 'react';
import Editor from '@monaco-editor/react';
import type { editor } from 'monaco-editor';
import './MarkdownEditor.css';

export interface MarkdownEditorProps {
  /** Current editor content value */
  value?: string;
  /** Callback fired when the editor content changes */
  onChange?: (value: string | undefined) => void;
  /** Whether the editor is read-only. Default: false */
  readOnly?: boolean;
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

  const handleEditorMount = useCallback((editor: editor.IStandaloneCodeEditor) => {
    editorRef.current = editor;
    // Force initial layout after mount
    requestAnimationFrame(() => {
      editor.layout();
    });
  }, []);

  return (
    <div className="markdown-editor" ref={containerRef}>
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
