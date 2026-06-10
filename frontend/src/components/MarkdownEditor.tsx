/**
 * MarkdownEditor — React wrapper around Monaco Editor for professional markdown editing.
 *
 * Features:
 * - Syntax highlighting for markdown
 * - Dark theme styled to match the project's cyan/magenta neon aesthetic
 * - Configurable height, read-only mode, and change callbacks
 */

import React from 'react';
import Editor from '@monaco-editor/react';
import './MarkdownEditor.css';

export interface MarkdownEditorProps {
  /** Current editor content value */
  value?: string;
  /** Callback fired when the editor content changes */
  onChange?: (value: string | undefined) => void;
  /** Editor height — CSS value or number (pixels). Default: "100%" */
  height?: string | number;
  /** Whether the editor is read-only. Default: false */
  readOnly?: boolean;
}

const MarkdownEditor: React.FC<MarkdownEditorProps> = ({
  value = '',
  onChange,
  height = '100%',
  readOnly = false,
}) => {
  const handleEditorChange = (newValue: string | undefined) => {
    if (onChange) {
      onChange(newValue);
    }
  };

  // Always pass a height to Monaco's Editor component.
  // The parent .markdown-editor gets its computed pixel height from CSS flex layout,
  // so height="100%" on Monaco's inner wrapper correctly fills the available space.
  // Without an explicit height, Monaco defaults to auto-height and collapses to ~20px.
  const resolvedHeight = typeof height === 'number' ? `${height}px` : height;

  return (
    <div className="markdown-editor">
      <Editor
        language="markdown"
        value={value}
        onChange={handleEditorChange}
        height={resolvedHeight}
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
