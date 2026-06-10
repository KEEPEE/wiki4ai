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

  // When height is "100%" (default), rely on CSS flex layout instead of explicit height.
  // This prevents the editor from collapsing to content size when parent
  // containers don't have explicit pixel heights set.
  const isDefaultHeight = typeof height === 'string' && height === '100%';
  const explicitHeight = isDefaultHeight
    ? undefined
    : (typeof height === 'number' ? `${height}px` : height);

  return (
    <div className="markdown-editor" style={isDefaultHeight ? undefined : { height }}>
      <Editor
        language="markdown"
        value={value}
        onChange={handleEditorChange}
        {...(explicitHeight ? { height: explicitHeight } : {})}
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
